# SPDX-License-Identifier: Apache-2.0
"""Generated HTTP run-API support. No model access or executable recipe payloads."""
from __future__ import annotations

import json
import os
import re
import time
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

CONFIG = json.loads(Path(__file__).with_name("config.json").read_text())


def loads(text: str) -> Any:
    return json.loads(text, parse_float=Decimal)


def dumps(value: Any) -> str:
    """Preserve exact decimal JSON numbers across the subprocess and HTTP seams."""
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("non-finite number")
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(k) + ":" + dumps(v) for k, v in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(dumps(v) for v in value) + "]"
    return json.dumps(value, allow_nan=False)


def key_of(row: dict[str, Any]) -> Any:
    values = [row[k] for k in CONFIG["key"]]
    if not values or any(v is None for v in values):
        raise ValueError("route omitted key")
    return values[0] if len(values) == 1 else values


def port_key(key: Any) -> Any:
    if isinstance(key, list):
        if len(key) != len(CONFIG["key"]):
            raise ValueError("composite key arity")
        return dict(zip(CONFIG["key"], key))
    return key


class ApiError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BaseClient:
    run_type: type[BaseRun]

    def __init__(self, server: str | None = None, token: str | None = None) -> None:
        server = (server if server is not None else os.environ.get("TTR_SERVER", "")).rstrip("/")
        url = urlsplit(server)
        if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("TTR_SERVER must be an HTTP(S) origin or run-API base URL")
        base = CONFIG["base_path"].rstrip("/")
        if url.path not in ("", base):
            raise ValueError("TTR_SERVER path differs from runApi")
        self.base = server if url.path else server + base
        self.token = token if token is not None else os.environ.get("TTR_BEARER", "")
        if not re.fullmatch(r"[A-Za-z0-9._~+/\-]+=*", self.token):
            raise ValueError("invalid bearer token")
        self.opener = build_opener(NoRedirect())
        budget = CONFIG["budget"].get("batch" if CONFIG["mode"] == "batch" else "sync", "1800s" if CONFIG["mode"] == "batch" else "60s")
        match = re.fullmatch(r"(\d+)\s*(ms|s|m|h|milliseconds|seconds|minutes|hours)?", budget)
        units = {"ms": 0.001, "s": 1, "m": 60, "h": 3600, "milliseconds": 0.001, "seconds": 1, "minutes": 60, "hours": 3600, None: 1}
        seconds = int(match[1]) * units[match[2]] if match else 1800 if CONFIG["mode"] == "batch" else 60
        self.deadline = time.monotonic() + seconds + 120

    def request(self, method: str, name: str, body: dict[str, Any] | None = None, **values: Any) -> tuple[int, dict[str, Any], dict[str, str]]:
        query = values.pop("query", None)
        path = CONFIG["calls"][name]
        for key, value in values.items():
            text = str(value)
            if text in (".", ".."):
                raise ValueError("invalid API identifier")
            path = path.replace("{" + key + "}", quote(text, safe=""))
        if "{" in path or not path.startswith("/") or path.startswith("//") or "://" in path:
            raise ValueError("invalid runApi path")
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise ApiError("run deadline exceeded")
        url = self.base + path + ("?" + urlencode(query) if query else "")
        request = Request(url, data=None if body is None else dumps(body).encode(), method=method,
                          headers={"Authorization": "Bearer " + self.token, "Accept": "application/json", "Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=min(30, remaining)) as response:
                code = response.status
                data = loads(response.read().decode())
                headers = dict(response.headers)
        except HTTPError as error:
            raise ApiError(f"{method} {name}: HTTP {error.code}") from None
        except (URLError, OSError, ValueError):
            raise ApiError(f"{method} {name}: transport or response error") from None
        if not isinstance(data, dict) or code not in (200, 201, 202):
            raise ApiError(f"{method} {name}: invalid response")
        return code, data, headers

    def pause(self, headers: dict[str, str]) -> None:
        value = next((v for k, v in headers.items() if k.lower() == "retry-after"), "0.25")
        try:
            seconds = max(0.01, float(value))
        except ValueError:
            seconds = 0.25
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise ApiError("run polling timed out")
        time.sleep(min(seconds, remaining, 15))

    def open(self, port: dict[str, Any], as_of: str | None = None, *, dry_run: bool = False, idempotency_key: str | None = None) -> BaseRun:
        kind = CONFIG["port"].replace("on_event", "event")
        # Replay may replace batch `none` with explicit `keys`, as the native dry driver does.
        accepted = {"none", "keys"} if CONFIG["mode"] == "batch" else {kind}
        if len(set(port) - {"at"}) != 1 or not (set(port) - {"at"}) <= accepted:
            raise ValueError("port differs from exported program")
        if as_of is not None:
            instant = datetime.fromisoformat(as_of.replace("Z", "+00:00"))
            if instant.tzinfo is None:
                raise ValueError("as-of requires an offset")
            if kind in ("key", "event"):
                port = dict(port, at=as_of)
        fps = CONFIG["fingerprints"]
        body = {"deployment": CONFIG["deployment"], "port": port, "dryRun": CONFIG["dry_run"] or dry_run, "origin": "driver"}
        if as_of is not None:
            body["asOf"] = as_of
        for field, dimension in (("recipeFingerprint", "text"), ("recipeValuesFingerprint", "values"), ("programFingerprint", "program"), ("deploymentFingerprint", "deployment")):
            if dimension in fps:
                body[field] = fps[dimension]
        if idempotency_key is not None:
            body["idempotencyKey"] = idempotency_key
        code, data, _ = self.request("POST", "open", body, recipe=CONFIG["recipe"])
        return self.run_type(self, data["runId"], code)


class BaseRun:
    def __init__(self, client: BaseClient, run_id: str, claim: int) -> None:
        self.client, self.id, self.claim = client, run_id, claim

    def request(self, method: str, name: str, body: dict[str, Any] | None = None, **values: Any):
        return self.client.request(method, name, body, id=self.id, **values)

    def part(self, name: str) -> dict[str, Any]:
        return self.request("POST", "part", part=name)[1]

    def status(self) -> dict[str, Any]:
        return self.request("GET", "status")[1]

    def evaluate(self) -> dict[str, Any]:
        code, data, headers = self.request("POST", "evaluate", {})
        while code == 202 or data.get("status") in ("open", "running"):
            self.client.pause(headers)
            code, data, headers = self.request("GET", "status")
        if data.get("status") in ("failed", "abandoned"):
            raise ApiError("run evaluation failed")
        return data

    def pages(self, name: str, **values: Any) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for _ in range(10000):
            query = dict(values.pop("query", {}), offset=len(rows), limit=1000)
            _, data, _ = self.request("GET", name, query=query, **values)
            page = data["rows"]
            if not isinstance(page, list) or any(not isinstance(row, dict) or row.get("truncated") is True for row in page):
                raise ApiError("detail evidence is truncated or malformed")
            rows.extend(page)
            if len(rows) == data["rowCount"]:
                return rows
            if not page or len(rows) > data["rowCount"]:
                raise ApiError("paging stopped before rowCount")
            # Keep the detail row selector across pages.
            values["query"] = {k: v for k, v in query.items() if k not in ("offset", "limit")}
        raise ApiError("paging limit exceeded")

    def route(self, name: str) -> list[dict[str, Any]]:
        return self.pages("route", route=name)

    def close(self) -> dict[str, Any]:
        return self.request("POST", "close", {})[1]


class Action:
    def __init__(self, run: BaseRun, name: str) -> None:
        self.run, self.name = run, name

    def perform(self, keys: list[Any] | None = None) -> dict[str, Any]:
        return self.run.request("POST", "perform", {} if keys is None else {"keys": keys}, action=self.name)[1]

    def outcome(self, outcomes: list[dict[str, Any]]) -> dict[str, Any]:
        return self.run.request("POST", "outcome", {"outcomes": outcomes}, action=self.name)[1]


def normalize_arguments(value: Any) -> Any:
    if isinstance(value, dict):
        if "refs" in value:
            refs = value["refs"]
            if not isinstance(refs, list) or any(ref.get("truncated") is True for ref in refs):
                raise ApiError("action detail evidence is truncated")
            return dict(value, refs=[{k: v for k, v in ref.items() if k != "ref"} for ref in refs])
        return {key: normalize_arguments(item) for key, item in value.items()}
    return value


def execute(client: BaseClient, port: dict[str, Any], as_of: str | None, *, dry_run: bool = False, parameters: dict[str, Any] | None = None) -> dict[str, Any]:
    if parameters:
        declared = {p["name"]: p for p in CONFIG["parameters"]}
        for name, value in parameters.items():
            p = declared.get(name)
            if p is None or "value" not in p:
                raise ValueError("replay parameter differs")
            expected = p["value"]
            if p["type"] in ("int", "decimal", "days", "working_days"):
                equal = Decimal(str(expected)) == Decimal(str(value))
            else:
                try:
                    expected = loads(expected)
                except (ValueError, TypeError):
                    pass
                equal = expected == value
            if not equal:
                raise ValueError("replay parameter differs")
    run = client.open(port, as_of, dry_run=dry_run)
    # A live claim belongs to another executor. Observe it; never evaluate or perform its work.
    status = run.status() if run.claim != 201 else None
    while run.claim == 202 and status["status"] in ("open", "running", "evaluated"):
        client.pause({})
        status = run.status()
    if run.claim != 201 and status["status"] not in ("closed", "waiting"):
        raise ApiError("existing run did not complete")
    try:
        if run.claim == 201:
            if CONFIG["granularity"] == "parts":
                for name in CONFIG["parts"]:
                    run.part(name)
            elif CONFIG["granularity"] == "islands":
                for name in CONFIG["islands"]:
                    run.request("POST", "island", island=name)
            run.evaluate()
            status = run.status()
        if dry_run and status.get("dryRun") is not True:
            raise ApiError("Server did not confirm dryRun")
        summaries = status["verdict"]["routes"]
        if set(summaries) - set(CONFIG["routes"]):
            raise ApiError("Server returned an undeclared route")
        verdicts, details, actions, counts = [], [], [], {}
        for route in summaries:
            for offset, row in enumerate(run.route(route)):
                key = key_of(row)
                verdict = {"key": key, "route": route, "verdict_reason": row.get("verdict_reason")}
                if "fills" in row:
                    verdict["fills"] = row["fills"]
                verdicts.append(verdict)
                for context in row.get("detail", {}):
                    rows = run.pages("detail", route=route, context=context, query={"row": offset})
                    details.append({"key": key, "route": route, "context": context, "rows": rows})
                    slot = counts.setdefault(str(key) if not isinstance(key, list) else ":".join(map(str, key)), {})
                    slot[context] = slot.get(context, 0) + len(rows)
        previews = status.get("actions")
        if not isinstance(previews, list):
            raise ApiError("status omitted preview actions")
        for preview in previews:
            name = preview["action"]
            if name not in CONFIG["actions"]:
                raise ApiError("Server returned an undeclared action")
            raw_key = preview["key"]
            key = key_of(raw_key) if isinstance(raw_key, dict) else raw_key
            if run.claim == 201:
                effect = Action(run, name).perform([port_key(key)])
                performed = effect.get("performed", [])
                if effect.get("skipped") or len(performed) != 1 or performed[0].get("key") not in (key, port_key(key)):
                    raise ApiError("perform effects differ from selected actions")
            actions.append({"key": key, "action": name, "columns": normalize_arguments(preview["arguments"])})
        batch = run.request("GET", "batch")[1]
        if run.claim == 201:
            closed = run.close()
            if closed.get("status") not in ("closed", "waiting"):
                raise ApiError("run did not close")
        return {"verdicts": verdicts, "actions": actions, "details": details, "detail_counts": counts, "effects": batch.get("rows", [])}
    except Exception:
        if run.claim == 201:
            try:
                run.close()
            except Exception:
                pass
        raise
