# Python recipe exporters

`python_client` is an SPI v2 revision 3 plugin. It emits a standard-library HTTP client,
typed route/part/action methods, CLI, stdin/stdout service, pinned Python/pytest project
and one generated test per route. No SQL or compiler code leaves the platform.

Open baseline: ttr-server `f28120e12f8f25540394018e99b5920fc670c47d`. The action
wire follows draft ttr-server#391 and AU Server contracts §1.2, amended under
✅R254-A/B/E/F (2026-11-12/13). #391 restored held-first ordering at `d0954bbe`;
this repair freezes its action-wire reference at `e13c74d5`.
That baseline has no perform route; this stage tests the
contract fake, with actual-Server acceptance in P1C/#366. Open is
`POST /automation/recipes/{recipe}/runs`; every path comes from `RunApiMeta`.
The generated README explains credentials, ports, clocks and stale-export refusals.

The `python-client` conformance driver runs the generated `service.py` using uv/Python
3.13.9 once per fixture (ordered per-key results/errors) against the configured Server. It forces dryRun and the fixture clock, performs
one perform call per Server-selected action with distinct keys, validates the multiset of per-entry perform results, and compares exact verdict/action/detail multisets and
transition effects. The default contract HTTP fixture is not a Server recording;
P1C owns actual-recording acceptance and S2.2 owns live P2 certification on bp-dsk.

`held_actions` marks approval-required actions whose **native** sink is `transition`,
`writer` or `obligation` (D-142, as Server ApplyPlanner selects it). It is a cross-check,
not the perform result policy: read the batch before perform and require `held` iff
the preview entry's own `batchRows` reference any grouped row. Other entries must be `dry-run`
on a dry run or performed on a wet run. Overlapping routes retain one answer per preview entry,
including repeated keys with different reasons. On owned runs every grouped row needs a marked owner,
and a marked entry with rows must reference a group. A marked key can have no rows; an
unmarked key can share a held row. The process timeout sums each request's
emitted `budget_seconds`, allowing a waited-on claim and one freshly budgeted re-open.
Completed foreign claims get a separate evidence-read window. Polling permits one
final status read in the last second, then times out without a busy loop.
On a real-run error the client leaves the run for Server abandonment at its deadline,
avoiding close applying auto rows. A retry waits for abandonment and reopens once.
Dry-run errors keep a best-effort close.

The complete unit package golden is synthetic. The CLI suite checks the real H-1
(`held_actions: []`) and compiles a required-transition variant (`["release"]`).

`python_offline` remains manifest-only until S2.2.
