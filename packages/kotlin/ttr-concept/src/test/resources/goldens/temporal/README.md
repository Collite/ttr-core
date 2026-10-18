# Temporal — conditions, as-of, three-valued comparison

Source: `design/08b-temporality-261016.md` §3.1 (DC-12). `PostSTEMI` (occurrence basis) · `HasBrokenLeg` (current state) · `HistoryOfLegFracture` (past state), each evaluated at an explicit `as_of`, under `certainly` and `possibly`. The S0.3 spike's §4/§5 SQL is the starting point. Granularity-derived bounds wait on G-ER-1.

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
