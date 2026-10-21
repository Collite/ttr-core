# Transfer — the write frame

Source: `design/09-grounding-and-frames.md` §5 (the acceptance case for assertions). A `Transfer` `assertion.v1` with labelled participants (`from`/`to`), a period and `asserted_as_of` lowers to the `Statement[Transfer]` row payload. Invented names and codes only — never client content (this is a public repo).

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
