# Channel — `revenue where WebSale` / `revenue by Channel`

Source: `design/11-recipes-over-concepts.md` §8 (A-H-7). The per-channel phase selects the carrying cubelet (`webSales`). The value kind `Channel` groups by its attribute (`channel_sales`).

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
