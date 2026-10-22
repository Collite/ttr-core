# ttr-concept

The conceptual-model (`cn`) layer below the determinism frontier. An agent proposes a frame over concept IDs — a
`statement.v1` (read), an `assertion.v1` (write) or a recipe naming a phase — and this module validates, lowers,
explains and renders it, deterministically: `statement_*` lowers to `plan.v1` through the md path, `assertion_*`
lowers to an E-R row payload, and `definitionRender` renders a phase or defined quality on the md layer (a PL
predicate for `ttrp-emit`) or the er layer (SQL over entities in the shared dialect). No LLM, no server call, no I/O
mid-lower: the same model and frame always give the same bytes.

**Status: CN-P3 in progress.** Built: the definition surface and the hygiene report (CN-P2); the frames
(`statement.v1` / `assertion.v1`, `ttr-plan-proto`) frozen and their canonical JSON pinned (CN-P3 S3.1); the validates
(S3.2), `statementLower` (S3.3), `definitionRender` on both layers and `materialize` — `ttr cn materialize` (S3.4). The
assertion lowering and the explanations land at S3.5–S3.6. Published as `org.tatrman:ttr-concept` in the
`grammar/v*` bundle from the first grammar cut after CN-P3 S3.1 (A-CN-1).

**Law order.** This module implements `project/tatrman/features/conceptual/` (outside this repo): `design/design.md`
v2 and the decision log › `plan/contracts.md` (§3 is this module's API) › `plan/architecture.md` › `plan/plan.md` ›
the task lists. Where this code and those documents disagree, the documents win.
