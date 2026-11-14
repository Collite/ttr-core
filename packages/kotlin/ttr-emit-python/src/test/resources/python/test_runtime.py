# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
from decimal import Decimal
from urllib.parse import urlsplit
import pytest
from release_order_client.client import Client
from release_order_client import runtime as rt
from release_order_client import service, main


SEAM = json.loads(Path(__file__).with_name("server-perform.json").read_text())


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
    def __init__(self, monkeypatch, *, claim=201, states=None, reopen=201, dry=True, held=False):
        self.claim, self.reopen, self.dry = claim, reopen, dry
        self.states = list(states or ["evaluated"])
        self.seen, self.opens, self.sleeps = [], [], []
        self.now = 0
        self.open_retry_after = "7"
        self.status_headers, self.status_elapsed = [], []
        self.status_reads, self.status_limit = 0, None
        self.open_dry = dry
        self.batch_response = None
        self.batch_rows = [{"seq": 1, "group": {"order_number": 4711} if held else None}]
        self.previews = [{"key": {"order_number": 4711}, "action": "release", "arguments": {"order": 4711}, "batchRows": [1]}]
        self.route_rows = [{"order_number": 4711}]
        self.page_size = 1000
        self.request_elapsed = 0
        self.evaluate_response = Response(200, {})
        self.perform_responses = {}
        self.close_error = False
        self.client = Client("http://127.0.0.1", "test-token")
        self.client.opener = self
        monkeypatch.setattr(rt.time, "monotonic", lambda: self.now)
        monkeypatch.setattr(rt.time, "sleep", self.sleep)
        monkeypatch.setitem(rt.CONFIG, "dry_run", False)
        monkeypatch.setitem(rt.CONFIG, "held_actions", ["release"] if held else [])

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
            data = {"runId": "r1"}
            if self.open_dry is not None:
                data["dryRun"] = self.open_dry
            return Response(self.claim if len(self.opens) == 1 else self.reopen, data, {"Retry-After": self.open_retry_after})
        if path.endswith("/evaluate"):
            return self.evaluate_response
        if path.endswith("/routes/release"):
            from urllib.parse import parse_qs
            offset = int(parse_qs(urlsplit(request.full_url).query)["offset"][0])
            return Response(200, {"rows": self.route_rows[offset:offset + self.page_size], "rowCount": len(self.route_rows)})
        if path.endswith("/details/stock"):
            return Response(200, {"rows": [{"available": 1}], "rowCount": 1})
        if path.endswith("/perform"):
            name = path.split("/actions/")[1].split("/")[0]
            if name in self.perform_responses:
                return Response(200, self.perform_responses[name])
            rows = {row["seq"]: row for row in self.batch_rows}
            result = {"performed": [], "skipped": []}
            for entry in self.previews:
                if entry["action"] != name or entry["key"] not in body["keys"]:
                    continue
                held = any(rows[seq].get("group") is not None for seq in entry["batchRows"])
                if held or self.dry:
                    reason = SEAM["dry"]["skipped"][0 if held else 1]["reason"]
                    result["skipped"].append({"key": entry["key"], "reason": reason})
                else:
                    assert SEAM["real"]["performed"]
                    result["performed"].append({"key": entry["key"], "batchRows": entry["batchRows"]})
            return Response(200, result)
        if path.endswith("/outcome"):
            return Response(200, SEAM["outcome"]["response"])
        if path.endswith("/batch"):
            return Response(200, {"rows": self.batch_rows} if self.batch_response is None else self.batch_response)
        if path.endswith("/close"):
            if self.close_error:
                raise rt.ApiError("close failed")
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
    server.dry = server.open_dry = False
    server.client.open({"key": 4711}, "2026-11-02T08:00:00Z", dry_run=False)
    assert server.opens[1]["dryRun"] is False
    assert "asOf" not in server.opens[1]
    assert server.opens[1]["port"]["at"] == "2026-11-02T08:00:00Z"
    server.dry = server.open_dry = True
    server.client.open({"key": 4711}, "now")
    assert server.opens[2]["asOf"] == "now"
    assert "at" not in server.opens[2]["port"]


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
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


@pytest.mark.parametrize("reopen", [200, 202])
def test_second_live_handle_fails(monkeypatch, reopen):
    server = Server(monkeypatch, claim=202, states=["abandoned", "open"], reopen=reopen)
    with pytest.raises(rt.ApiError, match="key held by run r1; retry later"):
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


@pytest.mark.parametrize("open_dry", [None, True])
def test_reclaim_resets_status_and_uses_open_policy_on_evaluate_error(monkeypatch, open_dry):
    server = Server(monkeypatch, claim=202, states=["abandoned"])
    server.open_dry = open_dry
    server.evaluate_response = Response(200, {"status": "failed"})
    with pytest.raises(rt.ApiError, match="run evaluation failed"):
        rt.execute(server.client, {"key": 4711}, None)
    assert len(server.opens) == 2
    assert all(body["dryRun"] is True for body in server.opens)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


def test_perform_retains_overlapping_previews_and_batch_then_closes(monkeypatch):
    server = Server(monkeypatch, held=True)
    server.previews.append(dict(server.previews[0], batchRows=[]))
    result = rt.execute(server.client, {"key": 4711}, None)
    assert result["actions"] == [{"key": 4711, "action": "release", "columns": {"order": 4711}}] * 2
    assert result["effects"] == server.batch_rows
    assert set(result) == {"verdicts", "actions", "details", "detail_counts", "effects", "perform_effects"}
    assert [path.rsplit("/", 1)[-1] for path, _, _ in server.seen][-3:] == ["batch", "perform", "close"]
    assert hasattr(server.client.open({"key": 4711}), "actions")


@pytest.mark.parametrize("entry", ["typed", "execute", "service", "cli"])
def test_real_run_is_reachable(monkeypatch, capsys, entry):
    server = Server(monkeypatch, dry=False)
    if entry == "service":
        result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}, "dry_run": False})
        assert result["perform_effects"][0]["performed"]
    elif entry == "cli":
        monkeypatch.setattr(main, "Client", lambda: server.client)
        monkeypatch.setattr(main.sys, "argv", ["client", "--key", "4711", "--as-of", "now"])
        assert main.main() == 0
        assert "asOf" not in server.opens[0]  # `now` needs no replay-only override on a real run.
        assert rt.loads(capsys.readouterr().out)["perform_effects"][0]["performed"]
    elif entry == "typed":
        run = server.client.open({"key": 4711}, dry_run=False)
        assert run.actions.release.perform([{"order_number": 4711}])["performed"]
        assert run.actions.release.outcome([{"key": {"order_number": 4711}, "status": "delivered", "at": "2026-11-11T10:05:00Z"}]) == SEAM["outcome"]["response"]
    else:
        assert rt.execute(server.client, {"key": 4711}, None, dry_run=False)["perform_effects"][0]["performed"]
    assert server.opens[0]["dryRun"] is False


def test_cli_dry_run_reads_evidence(monkeypatch, capsys):
    server = Server(monkeypatch)
    monkeypatch.setitem(rt.CONFIG, "dry_run", True)
    monkeypatch.setattr(main, "Client", lambda: server.client)
    monkeypatch.setattr(main.sys, "argv", ["client", "--key", "4711", "--as-of", "now", "--dry-run"])
    assert main.main() == 0
    assert rt.loads(capsys.readouterr().out)["effects"] == server.batch_rows
    assert server.opens[0]["dryRun"] is True


def test_reported_real_run_is_not_closed_even_when_dry_was_requested(monkeypatch):
    server = Server(monkeypatch, dry=False)
    with pytest.raises(rt.ApiError, match="Server dryRun differs"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=True)
    assert not any(path.endswith(("/evaluate", "/perform", "/close")) for path, _, _ in server.seen)


@pytest.mark.parametrize("claim", [200, 202])
def test_foreign_closed_claim_returns_dry_batch_evidence(monkeypatch, claim):
    server = Server(monkeypatch, claim=claim, states=["closed"], held=True)
    result = rt.execute(server.client, {"key": 4711}, None)
    assert result["effects"] == server.batch_rows
    assert not any(path.endswith(("/evaluate", "/perform", "/close")) for path, _, _ in server.seen)


def test_dry_batch_evidence_shape_and_preview_references(monkeypatch):
    for group in ("null", "omitted", "empty"):
        server = Server(monkeypatch)
        if group == "omitted":
            assert server.batch_rows[0].pop("group") is None
        elif group == "empty":
            server.batch_rows = []
            server.previews[0]["batchRows"] = []
        result = rt.execute(server.client, {"key": 4711}, None)
        assert result["effects"] == server.batch_rows

    invalid = [
        ({}, [1]),
        ({"rows": None}, [1]),
        ({"rows": {}}, [1]),
        ({"rows": "rows"}, [1]),
        ({"rows": [None]}, [1]),
        ({"rows": [{}]}, [1]),
        ({"rows": [{"seq": True}]}, [1]),
        ({"rows": [{"seq": "1"}]}, [1]),
        ({"rows": [{"seq": 1}, {"seq": 1}]}, [1]),
        ({"rows": [{"seq": 1}]}, None),
        ({"rows": [{"seq": 1}]}, "not-a-list"),
        ({"rows": [{"seq": 1}]}, [True]),
        ({"rows": [{"seq": 1}]}, [999]),
        ({"rows": []}, [1]),
    ]
    for batch, refs in invalid:
        server = Server(monkeypatch)
        server.batch_response = batch
        if refs is None:
            server.previews[0].pop("batchRows")
        else:
            server.previews[0]["batchRows"] = refs
        result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}})
        assert result == {"error": "malformed batch evidence"}, (batch, refs)
        assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


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
    server = Server(monkeypatch, claim=202, states=["running"], reopen=202)
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
    with pytest.raises(rt.ApiError, match="key held by run" if operation == "observe" else "run polling timed out"):
        invoke()
    assert sum(path == "/automation/runs/r1" for path, _, _ in server.seen) == expected_reads
    assert server.sleeps == ([0.5] if operation == "observe" else [])


@pytest.mark.parametrize("fault", ["route-null", "route-missing", "preview-extra", "preview-null"])
def test_malformed_server_route_and_preview_keys_are_api_errors(monkeypatch, fault):
    server = Server(monkeypatch)
    if fault.startswith("route"):
        server.route_rows = [{"order_number": None}] if fault == "route-null" else [{}]
    else:
        server.previews[0]["key"] = {"order_number": 4711, "extra": 1} if fault == "preview-extra" else {"order_number": None}
    result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}})
    assert result == {"error": "Server returned a malformed key"}


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


@pytest.mark.parametrize("dry", [True, False])
def test_regenerated_server_fixture_answers(monkeypatch, dry):
    server = Server(monkeypatch, dry=dry)
    monkeypatch.setitem(rt.CONFIG, "actions", ["hold"])
    monkeypatch.setitem(rt.CONFIG, "held_actions", ["hold"] if dry else [])
    fixture = SEAM["dry" if dry else "real"]
    entries = fixture["skipped"] if dry else fixture["performed"]
    server.previews = [{"action": "hold", "key": item["key"], "arguments": {}, "batchRows": [i + 1]} for i, item in enumerate(entries)]
    server.batch_rows = [{"seq": i + 1, "group": item["key"] if item.get("reason") == "held" else None} for i, item in enumerate(entries)]
    server.perform_responses["hold"] = fixture
    result = rt.execute(server.client, {"key": "4711"}, None, dry_run=dry)
    assert result["perform_effects"] == [{"action": "hold", **fixture}]
    assert SEAM["provenance"]["sha"] == "c2f2f049d4c623ef06bd47d6879427d5ee7e95cf"


@pytest.mark.parametrize("second_refs", [[], [1]])
@pytest.mark.parametrize("dry", [True, False])
def test_expected_answers_are_per_entry_and_request_keys_are_unique(monkeypatch, second_refs, dry):
    server = Server(monkeypatch, held=True, dry=dry)
    server.previews.append(dict(server.previews[0], batchRows=second_refs))
    result = rt.execute(server.client, {"key": 4711}, None, dry_run=dry)
    effect = result["perform_effects"][0]
    assert [entry["reason"] for entry in effect["skipped"]] == (["held", "held"] if second_refs else ["held", "dry-run"] if dry else ["held"])
    assert len(effect["performed"]) == int(not dry and not second_refs)
    body = next(body for path, body, _ in server.seen if path.endswith("/perform"))
    assert body == {"keys": [{"order_number": 4711}]}


@pytest.mark.parametrize("reason", ["performed", "failed", "not-applied"])
def test_real_legal_results_are_reported_and_close_normally(monkeypatch, reason):
    server = Server(monkeypatch, dry=False)
    effect = {"performed": [], "skipped": []}
    effect["performed" if reason == "performed" else "skipped"] = [{"key": {"order_number": 4711}, **({"batchRows": [1]} if reason == "performed" else {"reason": reason})}]
    server.perform_responses["release"] = effect
    result = rt.execute(server.client, {"key": 4711}, None, dry_run=False)
    assert result["perform_effects"] == [{"action": "release", **effect}]
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


@pytest.mark.parametrize("fault", ["omitted", "extra", "reason", "malformed", "key"])
def test_perform_multiset_rejects_changed_answers(monkeypatch, fault):
    server = Server(monkeypatch)
    skipped = [{"key": {"order_number": 4711}, "reason": "dry-run"}]
    effect = {"performed": [], "skipped": skipped}
    if fault == "omitted":
        skipped.clear()
    elif fault == "extra":
        skipped.append(dict(skipped[0]))
    elif fault == "reason":
        skipped[0]["reason"] = "held"
    elif fault == "key":
        skipped[0]["key"] = 4711
    else:
        effect["skipped"] = None
    server.perform_responses["release"] = effect
    with pytest.raises(rt.ApiError, match="perform effects differ|malformed key"):
        rt.execute(server.client, {"key": 4711}, None)


@pytest.mark.parametrize("dry,first_performs,closes", [(False, True, 1), (False, False, 0), (True, False, 1)])
def test_error_close_depends_on_performed_any(monkeypatch, dry, first_performs, closes):
    server = Server(monkeypatch, dry=dry)
    server.previews.append(dict(server.previews[0], action="notify"))
    if not first_performs and not dry:
        server.perform_responses["release"] = {"performed": [], "skipped": [{"key": {"order_number": 4711}, "reason": "failed"}]}
    server.perform_responses["notify"] = {"performed": [], "skipped": []}
    server.close_error = True
    with pytest.raises(rt.ApiError, match="^perform effects differ$"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=dry)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == closes


@pytest.mark.parametrize("dry", [False, True])
def test_bad_grouping_before_perform_leaves_real_run_open(monkeypatch, dry):
    server = Server(monkeypatch, dry=dry, held=True)
    monkeypatch.setitem(rt.CONFIG, "held_actions", [])
    with pytest.raises(rt.ApiError, match="perform effects differ"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=dry)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == int(dry)
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


@pytest.mark.parametrize("echo", [True, False, None])
@pytest.mark.parametrize("requested", [True, False])
def test_open_policy_mismatch_is_refused_before_execution(monkeypatch, echo, requested):
    server = Server(monkeypatch, dry=not requested)
    server.open_dry = echo
    if echo == requested:
        server.client.open({"key": 4711}, dry_run=requested)
    else:
        with pytest.raises(rt.ApiError, match="Server dryRun differs"):
            server.client.open({"key": 4711}, dry_run=requested)
    assert not any(path.endswith(("/evaluate", "/perform")) for path, _, _ in server.seen)
    stored = server.dry if echo is None else echo
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == int(stored is True and stored != requested)
    assert len(server.opens) == 1
    assert sum(path == "/automation/runs/r1" for path, _, _ in server.seen) == int(echo is None)


@pytest.mark.parametrize("reopen", [200, 202])
def test_stranded_claim_reopens_once_then_surfaces_retryable_error(monkeypatch, reopen):
    server = Server(monkeypatch, claim=202, reopen=reopen, states=["evaluated"])
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 60)
    server.open_retry_after = "9999"
    monkeypatch.setitem(rt.CONFIG, "abandonment_seconds", 1920)  # Longer legacy clock cannot extend our wait.
    result = service.run({rt.__package__: server.client}, {"port": {"key": 4711}})
    assert result == {"error": "key held by run r1; retry later", "retryable": True, "runId": "r1", "retryAfter": 1920}
    assert server.now == 180  # Our 60 + 120 window, never the Server's 1800 + 120 clock.
    assert len(server.opens) == 2
    assert server.opens[0] == server.opens[1]
    assert not any(path.endswith(("/evaluate", "/perform", "/close")) for path, _, _ in server.seen)


def test_stranded_claim_reopens_fresh_and_resets_status_and_deadline(monkeypatch):
    server = Server(monkeypatch, claim=202, states=["running", "evaluated"])
    monkeypatch.setitem(rt.CONFIG, "budget_seconds", 60)
    server.status_elapsed = [170]
    server.open_retry_after = "30"
    result = rt.execute(server.client, {"key": 4711}, None)
    assert result["perform_effects"]
    assert len(server.opens) == 2
    assert next(timeout for path, _, timeout in server.seen if path.endswith("/evaluate")) == 30
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


def test_foreign_evidence_does_not_apply_held_cross_check(monkeypatch):
    server = Server(monkeypatch, claim=200, states=["closed"], held=True)
    server.batch_rows[0]["group"] = None
    assert rt.execute(server.client, {"key": 4711}, None)["perform_effects"] == []
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


def test_a_performed_entry_is_tracked_before_answer_verification(monkeypatch):
    server = Server(monkeypatch, dry=False)
    server.perform_responses["release"] = {"performed": [{"key": {"order_number": 4711}, "batchRows": [1]}], "skipped": None}
    with pytest.raises(rt.ApiError, match="perform effects differ"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=False)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


@pytest.mark.parametrize("performed", [[], None, {}])
def test_malformed_performed_list_without_an_entry_does_not_close_real_run(monkeypatch, performed):
    server = Server(monkeypatch, dry=False)
    server.perform_responses["release"] = {"performed": performed, "skipped": []}
    with pytest.raises(rt.ApiError, match="perform effects differ"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=False)
    assert not any(path.endswith("/close") for path, _, _ in server.seen)


def test_last_open_can_return_a_completed_foreign_claim(monkeypatch):
    server = Server(monkeypatch, claim=202, reopen=200, states=["abandoned", "closed"])
    result = rt.execute(server.client, {"key": 4711}, None)
    assert result["perform_effects"] == []
    assert len(server.opens) == 2
    assert not any(path.endswith(("/evaluate", "/perform", "/close")) for path, _, _ in server.seen)


@pytest.mark.parametrize("policy", [0, "true", None])
def test_open_does_not_accept_a_missing_or_non_boolean_policy(monkeypatch, policy):
    server = Server(monkeypatch)
    server.open_dry = None
    old_status = server.open
    def status(request, timeout):
        response = old_status(request, timeout)
        if urlsplit(request.full_url).path == "/automation/runs/r1":
            response.data["dryRun"] = policy
        return response
    server.client.opener = type("Transport", (), {"open": staticmethod(status)})()
    with pytest.raises(rt.ApiError, match="Server dryRun differs"):
        server.client.open({"key": 4711})
    assert not any(path.endswith(("/evaluate", "/perform", "/close")) for path, _, _ in server.seen)


def test_a_performed_entry_with_a_mismatched_key_still_requires_cleanup(monkeypatch):
    server = Server(monkeypatch, dry=False)
    server.perform_responses["release"] = {"performed": [{"key": {"order_number": 4712}, "batchRows": [1]}], "skipped": []}
    with pytest.raises(rt.ApiError, match="perform effects differ"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=False)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


@pytest.mark.parametrize("entry", ["typed", "service", "cli"])
def test_dry_only_deployment_refuses_real_run_before_any_request(monkeypatch, capsys, entry):
    server = Server(monkeypatch)
    monkeypatch.setitem(rt.CONFIG, "dry_run", True)
    if entry == "typed":
        with pytest.raises(ValueError, match="deployment is dry-run only; use --dry-run"):
            server.client.open({"key": 4711}, dry_run=False)
    elif entry == "service":
        assert service.run({rt.__package__: server.client}, {"port": {"key": 4711}, "dry_run": False}) == {
            "error": "invalid input or replay parameter"}
    else:
        monkeypatch.setattr(main, "Client", lambda: server.client)
        monkeypatch.setattr(main.sys, "argv", ["client", "--key", "4711", "--as-of", "now"])
        assert main.main() == 2
        assert capsys.readouterr().err.strip() == "invalid input or configuration"
    assert server.seen == []


@pytest.mark.parametrize("reclaim", [False, True])
@pytest.mark.parametrize("fallback", [False, True])
@pytest.mark.parametrize("close_error", [False, True])
def test_unexpected_dry_claim_is_closed_before_refusal(monkeypatch, reclaim, fallback, close_error):
    server = Server(monkeypatch, claim=202 if reclaim else 201, dry=True)
    server.open_dry = None if fallback else True
    server.close_error = close_error
    server.status_elapsed = [rt.CONFIG["budget_seconds"] + 120] if reclaim else []
    with pytest.raises(rt.ApiError, match="^Server dryRun differs from requested value$"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=False)
    assert len(server.opens) == 1 + int(reclaim)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1
    assert not any(path.endswith(("/evaluate", "/perform")) for path, _, _ in server.seen)


@pytest.mark.parametrize("opened_dry", [False, True])
def test_error_cleanup_uses_open_policy_when_status_disagrees(monkeypatch, opened_dry):
    server = Server(monkeypatch, dry=not opened_dry)
    server.open_dry = opened_dry
    with pytest.raises(rt.ApiError, match="^Server dryRun differs from requested value$"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=opened_dry)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == int(opened_dry)
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


@pytest.mark.parametrize("key", [{"order_no": 1}, 4711])
def test_perform_rejects_caller_key_as_input_before_request(monkeypatch, key):
    server = Server(monkeypatch)
    action = server.client.open({"key": 4711}).actions.release
    with pytest.raises(ValueError, match="key columns differ"):
        action.perform([{"order_number": 4711}, key])
    assert not any(path.endswith("/perform") for path, _, _ in server.seen)


@pytest.mark.parametrize("entry", [None, {}, {"key": 4711}, {"key": {"order_no": 1}}])
def test_any_returned_performed_entry_requires_real_error_cleanup(monkeypatch, entry):
    server = Server(monkeypatch, dry=False)
    server.perform_responses["release"] = {"performed": [entry], "skipped": []}
    with pytest.raises(rt.ApiError, match="perform effects differ|Server returned a malformed key"):
        rt.execute(server.client, {"key": 4711}, None, dry_run=False)
    assert sum(path.endswith("/close") for path, _, _ in server.seen) == 1


def test_help_explains_real_run_clock_literal_requirement(monkeypatch, capsys):
    monkeypatch.setattr(main.sys, "argv", ["client", "--help"])
    with pytest.raises(SystemExit) as result:
        main.main()
    assert result.value.code == 0
    assert "A real run of a run-clock program takes `now`; a literal instant needs a debug deployment." in " ".join(capsys.readouterr().out.split())
