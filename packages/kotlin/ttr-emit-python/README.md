# Python recipe exporters

`python_client` is an SPI v2 revision 3 plugin. It emits a standard-library HTTP client,
typed route/part/action methods, CLI, stdin/stdout service, pinned Python/pytest project
and one generated test per route. No SQL or compiler code leaves the platform.

Open baseline: ttr-server `f28120e12f8f25540394018e99b5920fc670c47d`. The action
wire follows draft ttr-server#391 and AU Server contracts §1.2, amended under
✅R254-A/B/D (2026-11-12). That baseline has no perform route; this stage tests the
contract fake, with actual-Server acceptance in P1C/#366. Open is
`POST /automation/recipes/{recipe}/runs`; every path comes from `RunApiMeta`.
The generated README explains credentials, ports, clocks and stale-export refusals.

The `python-client` conformance driver runs the generated `service.py` using uv/Python
3.13.9 once per fixture (ordered per-key results/errors) against the configured Server. It forces dryRun and the fixture clock, performs
one perform call per Server-selected action, validates dry-run skips per action, and compares exact verdict/action/detail multisets and
transition effects. The default contract HTTP fixture is not a Server recording;
P1C owns actual-recording acceptance and S2.2 owns live P2 certification on bp-dsk.

`held_actions` marks approval-required actions whose **native** sink is `transition`,
`writer` or `obligation` (D-142, as Server ApplyPlanner selects it). Their dry-run
perform results must all be `held`; other actions must all be `dry-run`. On wet runs,
only marked actions may report holds. The process timeout sums each request's
emitted `budget_seconds`, allowing a waited-on claim and one freshly budgeted re-open.

`python_offline` remains manifest-only until S2.2.
