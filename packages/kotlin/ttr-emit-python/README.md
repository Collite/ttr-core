# Python recipe exporters

`python_client` is an SPI v2 revision 3 plugin. S2.1 emits a dry-evidence client:
typed route/part methods, CLI, stdin/stdout service, pinned Python/pytest project
and one generated test per route. No SQL or compiler code leaves the platform.

Every Open sends `dryRun: true`. The CLI requires `--dry-run`; the typed API defaults
to `dry_run=True` and rejects `dry_run=False` with "real runs arrive with S2.1b".
The client opens, evaluates, reads complete routes/details/action previews/batch,
returns that evidence and closes its owned dry run. Action execution is S2.1b;
its code and call paths are absent from this package.

Open baseline: ttr-server `f28120e12f8f25540394018e99b5920fc670c47d`.
Open is `POST /automation/recipes/{recipe}/runs`; paths come from `RunApiMeta`.
The generated README explains credentials, ports, clocks and stale-export refusals.
The default HTTP fixture is a contract fake; P1C/#366 owns actual-Server recording
acceptance and S2.2 owns live P2 certification on bp-dsk.

The `python-client` conformance driver runs the generated `service.py` using uv/Python
3.13.9 once per fixture (ordered per-key results/errors) against the configured Server.
It forces dryRun and the fixture clock, compares exact verdict/action/detail multisets
and projected transition effects from the dry batch, and never executes actions.
The process timeout sums each request's emitted `budget_seconds`, allowing a waited-on
claim and one freshly budgeted re-open. Completed foreign claims get a separate
evidence-read window. Polling honors Retry-After and permits one final status read
in the last second. Errors on owned dry runs receive a best-effort close; after a
re-claim the status is reset and unread status falls back to the Open response/body.

The complete unit package golden is synthetic. The CLI suite also emits the real H-1
and a required-transition variant, both as dry-evidence clients.
`python_offline` remains manifest-only until S2.2.
