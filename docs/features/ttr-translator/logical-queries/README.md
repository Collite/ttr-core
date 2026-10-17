# LQ — Logical queries (pointer)

Saved queries written over the E-R model (`def query` in `model er`, the Golem's SQL-over-entities
dialect) and entities stacked on them, in both the ttr and the legacy world.

The effort is authored in the project repo:
`~/Dev/collite-gh/project/tatrman/features/ttr-translator/logical-queries/` — `STATUS.md`, `design.md`
(rulings LQ-1…7, inventory, confirmation tests C-1..C-3, gated v2), then `plan/` after planning.

Code homes: `packages/grammar`, `packages/kotlin/{ttr-parser,ttr-metadata,ttr-translator}`, `docs/manual/en`;
`tatrman-server` metadata service (`QueryParseWorker`); ai-platform `infra/metadata` (legacy carrier).
