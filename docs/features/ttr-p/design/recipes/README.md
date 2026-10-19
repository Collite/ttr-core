# Moved → `collite-gh/project/kantheon/features/automation-agents/`

The **recipe calculus** — TTR-P's `recipe { }` block (role containers, the decision's `switch`, the
action wiring), TTR-M's `model automation` objects, the world's identities / grants / deployments /
processes, and `states:` on attributes — was designed and is planned in the `project` repo, as the
ttr-core half of the AU (automation agents) effort. Per the post-2026-07-15 convention (this repo's
`CLAUDE.md` banner, `project/README.md`) nothing about it is authored here:

- Design + decisions (D-n): `project/kantheon/features/automation-agents/design/` — the control room,
  `06` (the calculus), `17` (the hero recipes the toolchain's fixtures come from).
- The ttr-core plan, contracts, task lists and STATUS:
  `project/kantheon/features/automation-agents/implementation/ttr-core/`.

The code lives in the usual places: `packages/grammar/src/TTRP.g4` (the recipe block, TTR-P 0.4),
`packages/grammar/src/TTR.g4` (`model automation`, grammar 0.16), `packages/kotlin/ttrp-frontend`
(`ast/Recipe.kt`; the shared fixtures under `src/test/resources/recipes/`).
