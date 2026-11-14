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


def key_object(key: Any) -> dict[str, Any]:
    """Validate preview key objects; Open's port may still carry a scalar."""
    names = CONFIG["key"]
    if isinstance(key, dict):
        if set(key) != set(names):
            raise ValueError("key columns differ")
        result = key
    elif isinstance(key, list):
        if len(key) != len(names):
            raise ValueError("composite key arity")
        result = dict(zip(names, key))
    else:
        if len(names) != 1:
            raise ValueError("composite key arity")
        result = {names[0]: key}
    if any(value is None for value in result.values()):
        raise ValueError("missing key value")
    return result


def response_key(value: Any, *, row: bool = False) -> Any:
    """Server keys are evidence; malformed evidence is never a caller input error."""
    try:
        if not isinstance(value, dict):
            raise ValueError("non-object key")
        return key_of(value) if row else key_object(value)
    except (ValueError, KeyError, TypeError):
        raise ApiError("Server returned a malformed key") from None


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

    def request(self, method: str, name: str, body: dict[str, Any] | None = None, *, deadline: float | None = None, **values: Any) -> tuple[int, dict[str, Any], dict[str, str]]:
        query = values.pop("query", None)
        path = CONFIG["calls"][name]
        for key, value in values.items():
            text = str(value)
            if text in (".", ".."):
                raise ValueError("invalid API identifier")
            path = path.replace("{" + key + "}", quote(text, safe=""))
        if "{" in path or not path.startswith("/") or path.startswith("//") or "://" in path:
            raise ValueError("invalid runApi path")
        remaining = 30 if deadline is None else deadline - time.monotonic()
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

    def pause(self, headers: dict[str, str], deadline: float) -> bool:
        value = next((v for k, v in headers.items() if k.lower() == "retry-after"), "15")
        try:
            seconds = max(0.01, float(value))
        except ValueError:
            seconds = 15
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ApiError("run polling timed out")
        if remaining > 1:
            time.sleep(min(seconds, remaining - 1))
        # Permit one final status read, then refuse another polling iteration.
        return deadline - time.monotonic() <= 1

    def open(self, port: dict[str, Any], as_of: str | None = None, *, dry_run: bool = True, idempotency_key: str | None = None) -> BaseRun:
        if dry_run is not True:
            raise ApiError("real runs arrive with S2.1b")
        kind = CONFIG["port"].replace("on_event", "event")
        # Replay may replace batch `none` with explicit `keys`, as the native dry driver does.
        accepted = {"none", "keys"} if CONFIG["mode"] == "batch" else {kind}
        if len(set(port) - {"at"}) != 1 or not (set(port) - {"at"}) <= accepted:
            raise ValueError("port differs from exported program")
        literal_clock = as_of is not None and as_of != "now"
        if literal_clock:
            instant = datetime.fromisoformat(as_of.replace("Z", "+00:00"))
            if instant.tzinfo is None:
                raise ValueError("as-of requires an offset")
            if kind in ("key", "keys", "event"):
                port = dict(port, at=as_of)
        fps = CONFIG["fingerprints"]
        body = {"deployment": CONFIG["deployment"], "port": port, "dryRun": True, "origin": "driver"}
        event_clock = CONFIG["clock"] == "event" and kind in ("key", "keys", "event")
        if as_of is not None and (not literal_clock or not event_clock or body["dryRun"]):
            body["asOf"] = as_of
        for field, dimension in (("recipeFingerprint", "text"), ("recipeValuesFingerprint", "values"), ("programFingerprint", "program"), ("deploymentFingerprint", "deployment")):
            if dimension in fps:
                body[field] = fps[dimension]
        if idempotency_key is not None:
            body["idempotencyKey"] = idempotency_key
        return self._claim(body)

    def _claim(self, body: dict[str, Any]) -> BaseRun:
        started_at = time.monotonic()
        code, data, headers = self.request("POST", "open", body, recipe=CONFIG["recipe"])
        return self.run_type(self, data["runId"], code, started_at=started_at, headers=headers, open_body=body, dry_run=data.get("dryRun", body["dryRun"]) is True)


class BaseRun:
    def __init__(self, client: BaseClient, run_id: str, claim: int, *, headers: dict[str, str] | None = None, open_body: dict[str, Any] | None = None,
                 started_at: float | None = None, dry_run: bool = True) -> None:
        self.client, self.id, self.claim = client, run_id, claim
        self.renew_window(started_at=started_at)
        self.dry_run = dry_run
        self.headers = headers or {}
        self.open_body = open_body

    def renew_window(self, *, started_at: float | None = None) -> None:
        self.deadline = (time.monotonic() if started_at is None else started_at) + CONFIG["budget_seconds"] + 120
        self._last_poll = False

    def request(self, method: str, name: str, body: dict[str, Any] | None = None, **values: Any):
        response = self.client.request(method, name, body, deadline=self.deadline, id=self.id, **values)
        self.headers = response[2]
        return response

    def pause(self, headers: dict[str, str] | None = None) -> None:
        if self._last_poll:
            raise ApiError("run polling timed out")
        self._last_poll = self.client.pause(self.headers if headers is None else headers, self.deadline)

    def part(self, name: str) -> dict[str, Any]:
        return self.request("POST", "part", part=name)[1]

    def status(self) -> dict[str, Any]:
        return self.request("GET", "status")[1]

    def evaluate(self) -> dict[str, Any]:
        code, data, headers = self.request("POST", "evaluate", {})
        while code == 202 or data.get("status") in ("open", "running"):
            self.pause(headers)
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


def normalize_arguments(value: Any) -> Any:
    if not isinstance(value, dict):
        raise ApiError("malformed action arguments")
    result = dict(value)
    for column, item in value.items():
        if not isinstance(item, dict) or not isinstance(item.get("refs"), list):
            continue
        refs = []
        for ref in item["refs"]:
            if not isinstance(ref, dict) or not isinstance(ref.get("context"), str) or not isinstance(ref.get("ref"), str):
                raise ApiError("malformed action detail reference")
            if ref.get("truncated") is True:
                raise ApiError("action detail evidence is truncated")
            refs.append({key: v for key, v in ref.items() if key != "ref"})
        result[column] = dict(item, refs=refs)
    return result


def execute(client: BaseClient, port: dict[str, Any], as_of: str | None, *, dry_run: bool = True, parameters: dict[str, Any] | None = None) -> dict[str, Any]:
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
    # Observe another executor's claim; reclaim a dropped handle only once.
    status = None
    if run.claim != 201:
        open_headers = run.headers
        status = run.status()
        while status["status"] in ("open", "running", "evaluated"):
            run.pause(run.headers if any(key.lower() == "retry-after" for key in run.headers) else open_headers)
            status = run.status()
        if status["status"] in ("failed", "abandoned"):
            run = client._claim(run.open_body)
            status = None
            if run.claim != 201:
                raise ApiError("second live handle")
        elif status["status"] not in ("closed", "waiting"):
            raise ApiError("existing run did not complete")
        else:
            # Observing completion and collecting its pages are separate bounded windows.
            run.renew_window()
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
        if status.get("dryRun") is not True:
            raise ApiError("Server did not confirm dryRun")
        summaries = status["verdict"]["routes"]
        if set(summaries) - set(CONFIG["routes"]):
            raise ApiError("Server returned an undeclared route")
        verdicts, details, actions, counts = [], [], [], {}
        for route in summaries:
            for offset, row in enumerate(run.route(route)):
                key = response_key(row, row=True)
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
            if not isinstance(preview, dict):
                raise ApiError("malformed preview action")
            name = preview["action"]
            if name not in CONFIG["actions"]:
                raise ApiError("Server returned an undeclared action")
            obj = response_key(preview.get("key"))
            actions.append({"key": key_of(obj), "action": name, "columns": normalize_arguments(preview["arguments"])})
        batch = run.request("GET", "batch")[1]
        if run.claim == 201:
            closed = run.close()
            if closed.get("status") not in ("closed", "waiting"):
                raise ApiError("run did not close")
        return {"verdicts": verdicts, "actions": actions, "details": details, "detail_counts": counts, "effects": batch.get("rows", [])}
    except Exception:
        is_dry_run = status.get("dryRun") is True if status is not None else run.dry_run
        if run.claim == 201 and is_dry_run:
            try:
                run.close()
            except Exception:
                pass
        raise
