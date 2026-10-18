# Refusals — refusal over guess

Source: `plan/plan.md` CN-P3 S3.7 and contracts §7. The cases: ambiguous statement · ambiguous assertion · missing time · not analytically reachable · not recipe-reachable (hop missing) · retired code · unit mismatch · unknown participant label · definition cycle. Each golden asserts the exact `cn/*` slug.

**Pending convention.** A case whose lowering needs a primitive that does not exist yet ships as
`<case>.pending`, a one-line file naming the demand id it waits on (e.g. `G-ER-2` — see
`project/tatrman/features/conceptual/plan/demands/ttrp-catalog-dc-gaps.md`). The golden runner reports a
`.pending` case as pending, never as passed. When the demand lands, the file is replaced by the real fixture.
No fixture content yet: CN-P3 S3.7 authors these goldens FIRST, before S3.2.
