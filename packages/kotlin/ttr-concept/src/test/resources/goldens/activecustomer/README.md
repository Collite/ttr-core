# ActiveCustomer — a phase as a statement filter

Source: `design/04-statement-options.md` §E-0 (`filters: [ phase: ActiveCustomer ]`), with the definition of `design/02-metamodel-options.md` / `03-grounding-options.md` ("purchased in the last 12 months" — a DC-3 window). A phase used as a PhaseFilter: the definition is inlined, rendered on the md layer, and recorded in the provenance.

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
