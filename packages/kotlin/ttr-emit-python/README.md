# Python recipe exporters

`python_client` emits a typed HTTP client, CLI, stdin/stdout service, pinned Python/pytest
project and one generated test per route. The Server executes the recipe; the package
contains no SQL or compiler code. Real runs are available in S2.1b.

The typed API defaults to `dry_run=True`; pass `dry_run=False` for a real run.
The CLI uses `--dry-run` for previews; omit it for a real run. `Run.parts.<name>()`,
`Run.routes.<route>()`, `Run.actions.<action>.perform(keys)`, `outcome(items)` and
`Run.close()` use paths from `RunApiMeta`. Perform keys are objects even for one column.
`execute` reads batch evidence before perform, compares answers as a multiset per preview
entry, and returns `perform_effects` including legal failed/not-applied reasons.
An Open policy mismatch refuses immediately: read `dryRun` from its 201 body, or GET status
before any parts/evaluate. Outcomes after seal refuse with HTTP 409.

Wire: ttr-server contracts §1 and “Amendment 2026-11-14 — the perform seam”.
Fixture baseline: `c2f2f049d4c623ef06bd47d6879427d5ee7e95cf`; source inputs/assertions in
`services/automation/src/componentTest/kotlin/org/tatrman/automation/writer/ApplyComponentSpec.kt`:
R254-E dry held/unheld, S2.3 T3 perform selection/idempotency and outcome/sealed refusal.
Regenerate with `python3 scripts/au/regenerate-perform-fixtures.py ../tatrman-server`.
The checked fixtures include source hashes. The fakes adapt these answer templates to
synthetic route/overlap cases from the amendment. This is not a live HTTP recording;
P1C/#366 owns recording acceptance and S2.2 owns live P2 certification on bp-dsk.

The replay driver runs the generated service using uv/Python 3.13.9 once per fixture,
with ordered per-key results/errors, fixture clocks and dryRun by default. It compares
exact verdict/action/detail multisets and projected transition effects from the batch.
Its timeout sums each request budget, including one freshly budgeted re-open. Completed
foreign claims receive a separate evidence-read window and never perform or close here.
Polling honors Retry-After and permits one final status read. The service exposes a
stranded key as `retryable: true`, `runId` and `retryAfter`. Python is 3.13.9, pytest 8.3.5.

`POST /automation/runs/{id}/actions/{a}/perform { keys: [ { keyColumn: value, … } ], group? }` — keys are always objects, one call per action. Answer `200 { performed: [{ key, batchRows }], skipped: [{ key, reason }] }`; `performed[].key` and `skipped[].key` are the same objects as sent. **Hold is decided per preview entry (⚑R254-J, 2026-11-14):** the request sends each key once; the answer carries one `(key, reason)` per **preview entry** of that action (one key can have two entries — non-exclusive routes × wirings); an entry is `held` iff any of its own `batchRows` carries an approval `group` (`GET …/batch` rows, `batchRows` on the status preview action, are the wire for it). An entry sharing a deduplicated grouped row with a held entry is held too. The export's `held_actions` list is a cross-check only.

Dry run: `performed: []`; every held key `skipped {reason: "held"}` (held precedes dry-run); every other selected key `skipped {reason: "dry-run"}`. Nothing is applied; the client closes a dry run after its evidence is read.

Real run: an unheld key answers `performed`, or `skipped {reason: "failed"}` (its row failed in Apply) or `skipped {reason: "not-applied"}` (still staged). These are **legal results for the caller**, reported with their reason — not client errors, and the run continues and closes normally. Perform applies ahead of close and is idempotent per key; close never resends a performed row.

A client that rejects evidence or fails mid-run does **not** close a real run while nothing has been performed on it (the run stays for inspection; the Server's abandonment ends it). Once at least one perform returned a `performed` entry, the client closes the run (best effort) before re-raising, so the batch stays consistent with what was applied.

An Open that answers a foreign claim (`200` non-terminal or `202 + Location`) is polled inside the client's own window (its budget + 2 min). When the window ends the client POSTs the same Open **once** more; a `201` is a fresh claim, a `202` or a non-terminal `200` raises a distinct retryable error that carries the run id ("key held by run <id>; retry later") for the event system to redeliver. A stranded real run blocks its key until the Server abandons it.

Abandonment clock: today `Ledger.abandonExpiredRuns` compares every run against `COALESCE(budget_batch, budget_sync, default)` and Open always stores `budget_batch`, so an on-event run is abandoned after `budget_batch` + 2 min (~32 min by default). **Contract:** a run whose program `mode` is on-event abandons by **`budget_sync` + 2 min**; a batch run by `budget_batch` + 2 min. The client polls inside its own budget + 2 min. The merged fixture baseline `c2f2f049` includes the mode-aware follow-up. Its `retryAfter` uses the mode's budget + 2 min: 180 seconds for a default on-event run, 1920 seconds for a default batch run.

The complete unit package golden is synthetic. CLI tests emit real H-1 and its
required-transition variant. `python_offline` remains manifest-only until S2.2.
