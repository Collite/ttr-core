# SPDX-License-Identifier: Apache-2.0
from decimal import Decimal
from urllib.parse import urlsplit
import pytest
from release_order_client.client import Client
from release_order_client import runtime as rt
from release_order_client import service


class Response:
    def __init__(self, code, data, headers=None):
        self.status, self.data, self.headers = code, data, headers or {}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self):
        return rt.dumps(self.data).encode()


class Server:
    """Drive the generated HTTP transport, including request bodies and response keys."""
    def __init__(self, monkeypatch, *, claim=201, states=None, reopen=201, dry=True, held=False, marked=None):
        self.claim, self.reopen, self.dry = claim, reopen, dry
        self.states = list(states or ["evaluated"])
        self.seen, self.opens, self.sleeps = [], [], []
        self.now = 0
        self.open_retry_after = "7"
        self.status_headers, self.status_elapsed = [], []
        self.status_reads, self.status_limit = 0, None
        self.perform_reason = "held" if held else "dry-run" if dry else None
        self.perform_reasons = None
        self.response_key = None
        self.batch_rows = [dict(seq=1, **({"group": {"order_number": 4711}} if held else {}))]
        self.previews = [{"key": {"order_number": 4711}, "action": "release", "arguments": {"order": 4711}, "batchRows": [1]}]
        self.route_rows = [{"order_number": 4711}]
        self.page_size = 1000
        self.request_elapsed = 0
        self.evaluate_response = Response(200, {})
        self.client = Client("http://127.0.0.1", "test-token")
        self.client.opener = self
        monkeypatch.setattr(rt.time, "monotonic", lambda: self.now)
        monkeypatch.setattr(rt.time, "sleep", self.sleep)
        monkeypatch.setitem(rt.CONFIG, "dry_run", dry)
        monkeypatch.setitem(rt.CONFIG, "held_actions", ["release"] if (held if marked is None else marked) else [])

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def open(self, request, timeout):
        path = urlsplit(request.full_url).path
        body = rt.loads(request.data.decode()) if request.data else None
        self.seen.append((path, body, timeout))
        self.now += self.request_elapsed
        if path.endswith("/runs"):
            self.opens.append(body)
            return Response(self.claim if len(self.opens) == 1 else self.reopen, {"runId": "r1"}, {"Retry-After": self.open_retry_after})
        if path.endswith("/evaluate"):
            return self.evaluate_response
        if path.endswith("/routes/release"):
            from urllib.parse import parse_qs
            offset = int(parse_qs(urlsplit(request.full_url).query)["offset"][0])
            return Response(200, {"rows": self.route_rows[offset:offset + self.page_size], "rowCount": len(self.route_rows)})
        if path.endswith("/details/stock"):
            return Response(200, {"rows": [{"available": 1}], "rowCount": 1})
        if path.endswith("/perform"):
            action = path.split("/actions/")[1].split("/")[0]
            performed, skipped = [], []
            for key in body["keys"]:
                assert isinstance(key, dict) and set(key) == {"order_number"}
                row = {"key": key if self.response_key is None else self.response_key}
                reason = self.perform_reason if self.perform_reasons is None else self.perform_reasons[action][key["order_number"]]
                (performed if reason is None else skipped).append(dict(row, batchRows=[]) if reason is None else dict(row, reason=reason))
            return Response(200, {"performed": performed, "skipped": skipped})
        if path.endswith("/outcome"):
            return Response(200, {})
        if path.endswith("/batch"):
            return Response(200, {"rows": self.batch_rows})
        if path.endswith("/close"):
            return Response(200, {"status": "closed"})
        self.status_reads += 1
        assert self.status_limit is None or self.status_reads <= self.status_limit, "too many status reads"
        state = self.states.pop(0) if len(self.states) > 1 else self.states[0]
        if self.status_elapsed:
            self.now += self.status_elapsed.pop(0)
        headers = self.status_headers.pop(0) if self.status_headers else {}
        return Response(200, {"status": state, "dryRun": self.dry, "verdict": {"routes": {"release": {"rowCount": 1}}},
                              "actions": self.previews}, headers)


def test_exact_decimal_and_bearer_validation():
    value = Decimal("1.00000000000000000001")
    assert rt.loads(rt.dumps({"v": value}))["v"] == value
    with pytest.raises(ValueError, match="invalid bearer token"):
        Client("http://127.0.0.1", "secret\nheader")


def test_open_pins_fingerprints_and_clock_policy(monkeypatch):
    server = Server(monkeypatch)
    server.client.open({"key": 4711}, "2026-11-02T08:00:00Z", dry_run=True)
    body = server.opens[0]
    assert body["recipeFingerprint"] == "text-fp"
    assert body["programFingerprint"] == "program-fp"
    assert body["dryRun"] is True
    assert body["asOf"] == body["port"]["at"] == "2026-11-02T08:00:00Z"
    monkeypatch.setitem(rt.CONFIG, "dry_run", False)
    server.client.open({"key": 4711}, "2026-11-02T08:00:00Z")
    assert "asOf" not in server.opens[1]
    assert server.opens[1]["port"]["at"] == "2026-11-02T08:00:00Z"
    server.client.open({"key": 4711}, "now")
    assert server.opens[2]["asOf"] == "now"
    assert "at" not in server.opens[2]["port"]


def test_dry_perform_and_wet_held_effect_are_reported(monkeypatch):
    server = Server(monkeypatch, dry=False, held=True)
    result = rt.execute(server.client, {"key": 4711}, None)
    assert result["perform_effects"] == [{"action": "release", "performed": [], "skipped": [{"key": {"order_number": 4711}, "reason": "held"}]}]
    assert result["actions"][0]["key"] == 4711


def test_action_and_outcome_always_use_object_keys(monkeypatch):
    server = Server(monkeypatch)
    run = server.client.open({"key": 4711})
    run.actions.release.perform([4711])
    run.actions.release.outcome([{"key": 4711, "status": "delivered"}])
    assert server.seen[-1][1] == {"outcomes": [{"key": {"order_number": 4711}, "status": "delivered"}]}
    monkeypatch.setitem(rt.CONFIG, "key", ["order_number", "line"])
    assert rt.key_object([4711, 2]) == {"order_number": 4711, "line": 2}
    with pytest.raises(ValueError, match="arity"):
        rt.key_object([4711])
    with pytest.raises(ValueError, match="arity"):
        rt.key_object(4711)


def test_numeric_key_comparison_is_exact():
    assert rt.canonical_key({"order_number": 4711.0}) == rt.canonical_key({"order_number": Decimal("4711")})
    assert rt.canonical_key({"order_number": 4711}) == rt.canonical_key({"order_number": Decimal("4711.0")})
    assert rt.canonical_key({"order_number": Decimal("1.00000000000000000001")}) != rt.canonical_key({"order_number": Decimal("1.00000000000000000002")})


def test_emitted_budget_and_per_run_deadline(monkeypatch):
    server = Server(monkeypatch)
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 600)
    first = server.client.open({"key": 4711})
    assert first.deadline == 720
    server.now = 200
    first.status()  # A ten-minute budget survives the old three-minute fallback.
    assert server.seen[-1][2] == 30
    server.now = 1000
    with pytest.raises(rt.ApiError, match="deadline"):
        first.status()
    second = server.client.open({"key": 4712})
    assert second.deadline == 1720
    second.status()
    assert first.deadline == 720


@pytest.mark.parametrize("claim", [200, 202])
def test_existing_claims_poll_without_executing_foreign_work(monkeypatch, claim):
    server = Server(monkeypatch, claim=claim, states=["open", "running", "evaluated", "closed"])
    rt.execute(server.client, {"key": 4711}, None, dry_run=True)
    assert server.sleeps == [7, 7, 7]
    assert not any(path.endswith(("/evaluate", "/perform", "/close")) for path, _, _ in server.seen)


@pytest.mark.parametrize("state", ["failed", "abandoned"])
def test_dropped_handle_reopens_once_with_identical_body(monkeypatch, state):
    server = Server(monkeypatch, claim=202, states=[state, "evaluated"])
    rt.execute(server.client, {"key": 4711}, None, dry_run=True)
    assert len(server.opens) == 2
    assert server.opens[0] == server.opens[1]
    assert sum(path.endswith("/perform") for path, _, _ in server.seen) == 1


@pytest.mark.parametrize("reopen", [200, 202])
def test_second_live_handle_fails(monkeypatch, reopen):
    server = Server(monkeypatch, claim=202, states=["abandoned"], reopen=reopen)
    with pytest.raises(rt.ApiError, match="second live handle"):
        rt.execute(server.client, {"key": 4711}, None)
    assert len(server.opens) == 2
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


def test_pause_defaults_to_fifteen_seconds_and_uses_latest_header(monkeypatch):
    server = Server(monkeypatch)
    run = server.client.open({"key": 4711})
    run.pause()
    run.pause({"retry-after": "23"})
    run.pause({})
    assert server.sleeps == [7, 23, 15]


def test_status_retry_after_overrides_open_header(monkeypatch):
    server = Server(monkeypatch, claim=202, states=["open", "running", "closed"])
    server.status_headers = [{}, {"retry-after": "11"}]
    rt.execute(server.client, {"key": 4711}, None)
    assert server.sleeps == [7, 11]


def test_poll_leaves_time_for_final_closed_status(monkeypatch):
    server = Server(monkeypatch, claim=202, states=["open", "closed"])
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 60)
    server.status_elapsed = [160]  # 20 seconds remain on the first claim.
    server.open_retry_after = "30"
    rt.execute(server.client, {"key": 4711}, None)
    assert server.sleeps == [19]
    assert server.now == 179
    assert sum(path == "/automation/runs/r1" for path, _, _ in server.seen) == 2


def test_reopened_run_gets_its_own_full_budget(monkeypatch):
    server = Server(monkeypatch, claim=202, states=["abandoned", "evaluated"])
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 60)
    server.status_elapsed = [170]
    rt.execute(server.client, {"key": 4711}, None)
    assert len(server.opens) == 2
    assert next(timeout for path, _, timeout in server.seen if path.endswith("/evaluate")) == 30


@pytest.mark.parametrize("dry,marked,grouped,empty,reason,accepted", [
    (True, True, True, False, "held", True),
    (True, True, True, False, "dry-run", False),
    (True, False, False, False, "held", False),
    (False, True, True, False, "held", True),
    (False, True, True, False, None, False),
    (False, False, False, False, None, True),
    (False, False, False, False, "held", False),
    (True, True, False, True, "dry-run", True),
    (False, True, False, True, None, True),
    (True, True, False, False, "dry-run", False),
])
def test_perform_policy_uses_each_keys_actual_rows(monkeypatch, dry, marked, grouped, empty, reason, accepted):
    server = Server(monkeypatch, dry=dry, held=grouped, marked=marked)
    if empty:
        server.previews[0]["batchRows"] = []
        server.batch_rows = []
    server.perform_reason = reason
    if accepted:
        rt.execute(server.client, {"key": 4711}, None)
    else:
        with pytest.raises(rt.ApiError, match="perform effects differ"):
            rt.execute(server.client, {"key": 4711}, None)


def test_marked_empty_key_and_unmarked_shared_row_both_replay(monkeypatch):
    server = Server(monkeypatch, held=True)
    server.previews += [
        {"action": "release", "key": {"order_number": 4712}, "arguments": {"order": 4712}, "batchRows": []},
        {"action": "notify", "key": {"order_number": 4711}, "arguments": {"order": 4711}, "batchRows": [1]},
    ]
    server.perform_reasons = {"release": {4711: "held", 4712: "dry-run"}, "notify": {4711: "held"}}
    result = rt.execute(server.client, {"key": 4711}, None)
    assert [r["reason"] for effect in result["perform_effects"] for r in effect["skipped"]] == ["held", "dry-run", "held"]
    paths = [path for path, _, _ in server.seen]
    assert sum(path.endswith("/batch") for path in paths) == 1
    assert next(i for i, path in enumerate(paths) if path.endswith("/batch")) < next(i for i, path in enumerate(paths) if path.endswith("/perform"))


def test_grouped_row_requires_a_marked_owner(monkeypatch):
    server = Server(monkeypatch, held=True, marked=False)
    with pytest.raises(rt.ApiError, match="perform effects differ"):
        rt.execute(server.client, {"key": 4711}, None)
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


@pytest.mark.parametrize("refs", [None, [999], "not-a-list"])
def test_preview_batch_rows_must_reference_the_batch(monkeypatch, refs):
    server = Server(monkeypatch)
    if refs is None:
        server.previews[0].pop("batchRows")
    else:
        server.previews[0]["batchRows"] = refs
    with pytest.raises(rt.ApiError, match="perform effects differ"):
        rt.execute(server.client, {"key": 4711}, None)
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


def test_late_foreign_completion_gets_a_fresh_evidence_window(monkeypatch):
    server = Server(monkeypatch, claim=202, states=["open", "closed"])
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 60)
    server.request_elapsed = 0.5
    server.status_elapsed = [159]
    server.open_retry_after = "30"
    server.page_size = 1
    server.route_rows = [{"order_number": k, "detail": {"stock": "/unused"}} for k in (4711, 4712)]
    result = rt.execute(server.client, {"key": 4711}, None)
    assert len(result["verdicts"]) == len(result["details"]) == 2
    assert server.now > 180  # Reads outlive the claim's original deadline.
    assert all(timeout == 30 for path, _, timeout in server.seen if "/routes/" in path or path.endswith("/batch"))


@pytest.mark.parametrize("operation", ["observe", "evaluate"])
def test_last_half_second_allows_one_status_read_then_times_out(monkeypatch, operation):
    server = Server(monkeypatch, claim=202, states=["running"])
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 60)
    if operation == "observe":
        server.status_elapsed = [179.5]
        invoke = lambda: rt.execute(server.client, {"key": 4711}, None)
        expected_reads = 2  # Initial observation plus one final read.
    else:
        run = server.client.open({"key": 4711})
        server.now = 179.5
        server.evaluate_response = Response(202, {"status": "running"})
        invoke = run.evaluate
        expected_reads = 1
    server.status_limit = expected_reads
    with pytest.raises(rt.ApiError, match="run polling timed out"):
        invoke()
    assert sum(path == "/automation/runs/r1" for path, _, _ in server.seen) == expected_reads
    assert not server.sleeps  # No zero-length sleeps or repeated status GETs.


@pytest.mark.parametrize("fault", ["route-null", "route-missing", "preview-extra", "preview-null"])
def test_malformed_server_route_and_preview_keys_are_api_errors(monkeypatch, fault):
    server = Server(monkeypatch)
    if fault.startswith("route"):
        server.route_rows = [{"order_number": None}] if fault == "route-null" else [{}]
    else:
        server.previews[0]["key"] = {"order_number": 4711, "extra": 1} if fault == "preview-extra" else {"order_number": None}
    result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}})
    assert result == {"error": "Server returned a malformed key"}


@pytest.mark.parametrize("key", [{"order_number": 4711, "extra": 1}, {}])
def test_malformed_response_key_is_a_perform_api_error(monkeypatch, key):
    server = Server(monkeypatch)
    server.response_key = key
    result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}})
    assert result == {"error": "perform effects differ from selected actions"}


@pytest.mark.parametrize("error", [RuntimeError, NotImplementedError, RecursionError, rt.ApiError])
def test_only_api_errors_expose_their_message(monkeypatch, error):
    server = Server(monkeypatch)
    def fail(*args, **kwargs):
        raise error("secret 4711" if error is not rt.ApiError else "POST open: HTTP 400")
    monkeypatch.setattr(rt, "execute", fail)
    result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}})
    assert result == {"error": "POST open: HTTP 400" if error is rt.ApiError else "generated service failed"}


def test_argument_reference_normalization_matches_kotlin_columns():
    ref = {"context": "stock", "ref": "/run/r1", "rowCount": 1}
    vectors = [
        ({"stock": {"refs": [ref]}}, {"stock": {"refs": [{"context": "stock", "rowCount": 1}]}}),
        ({"refs": [ref]}, {"refs": [ref]}),
        ({"nested": {"stock": {"refs": [ref]}}}, {"nested": {"stock": {"refs": [ref]}}}),
        ({"stock": {"refs": "value"}}, {"stock": {"refs": "value"}}),
    ]
    for value, expected in vectors:
        assert rt.normalize_arguments(value) == expected


@pytest.mark.parametrize("ref", [None, "ref", 1, [], {}, {"context": [], "ref": "url"}, {"context": "stock", "ref": 1}, {"context": "stock", "ref": "url", "truncated": True}])
def test_malformed_or_truncated_ref_is_api_error(ref):
    with pytest.raises(rt.ApiError, match="malformed|truncated"):
        rt.normalize_arguments({"stock": {"refs": [ref]}})
