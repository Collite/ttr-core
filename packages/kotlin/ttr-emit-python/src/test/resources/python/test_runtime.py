# SPDX-License-Identifier: Apache-2.0
from decimal import Decimal
from urllib.parse import urlsplit
import pytest
from release_order_client.client import Client
from release_order_client import runtime as rt


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
    def __init__(self, monkeypatch, *, claim=201, states=None, reopen=201, dry=True):
        self.claim, self.reopen, self.dry = claim, reopen, dry
        self.states = list(states or ["evaluated"])
        self.seen, self.opens, self.sleeps = [], [], []
        self.now = 0
        self.client = Client("http://127.0.0.1", "test-token")
        self.client.opener = self
        monkeypatch.setattr(rt.time, "monotonic", lambda: self.now)
        monkeypatch.setattr(rt.time, "sleep", self.sleep)
        monkeypatch.setitem(rt.CONFIG, "dry_run", dry)

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds

    def open(self, request, timeout):
        path = urlsplit(request.full_url).path
        body = rt.loads(request.data.decode()) if request.data else None
        self.seen.append((path, body, timeout))
        if path.endswith("/runs"):
            self.opens.append(body)
            return Response(self.claim if len(self.opens) == 1 else self.reopen, {"runId": "r1"}, {"Retry-After": "15"})
        if path.endswith("/evaluate"):
            return Response(200, {})
        if path.endswith("/routes/release"):
            return Response(200, {"rows": [{"order_number": 4711}], "rowCount": 1})
        if path.endswith("/perform"):
            assert body == {"keys": [{"order_number": 4711}]}
            return Response(200, {"performed": [], "skipped": [{"key": {"order_number": Decimal("4711.0")}, "reason": "dry-run" if self.dry else "held"}]})
        if path.endswith("/outcome"):
            return Response(200, {})
        if path.endswith("/batch"):
            return Response(200, {"rows": []})
        if path.endswith("/close"):
            return Response(200, {"status": "closed"})
        state = self.states.pop(0) if len(self.states) > 1 else self.states[0]
        return Response(200, {"status": state, "dryRun": self.dry, "verdict": {"routes": {"release": {"rowCount": 1}}},
                              "actions": [{"key": {"order_number": 4711}, "action": "release", "arguments": {"order": 4711}}]})


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
    server = Server(monkeypatch, dry=False)
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
    assert server.sleeps == [15, 15, 15]
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
    assert server.sleeps == [15, 23, 15]


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
