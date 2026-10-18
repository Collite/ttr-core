# E-0 — one question, both languages, one statement

Source: `design/04-statement-options.md` §E-0 (A-H-1). The Czech and English forms of the same question resolve to ONE `statement.v1`, which lowers to the md dot-path and then to a `plan.v1`.

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
