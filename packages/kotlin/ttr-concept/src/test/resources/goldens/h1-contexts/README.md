# H-1 contexts — the rendered islands vs AU's hand-written ones

Source: `design/11-recipes-over-concepts.md` §3 (A-H-2), against AU `17-recipes-retyped.md` §3. `ShortLine` / `MispricedLine` / `DebtorCustomer` rendered on er, compared with `shortfall` / `price_differences` / `overdue` as **translated plans** (N4-style). The S0.3 spike measured `overdue` and `shortfall` (context mode) byte-equal; `price_differences` is row-equivalent only (memo ⚑S03-5).

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
