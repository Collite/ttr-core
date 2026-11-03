# Hartland engine recipes

CG-2 pre-flight fixture for AU design `20-recipes-retyped-engine.md` §1–§4,
with candidates, context and decision bodies from Core’s shared hero recipes
in the parent directory (including the F1/F2 respellings).
The four action blocks come from `20`; the bodies above `actions {` are copied
verbatim from the shared hero recipes, per the planner’s 2026-11-03 correction.

The automation definitions and the world automation members come from `20` §1–§2.
Native TTR-M notation follows the existing Core fixtures: `package` then `model`,
world members nested in `def world demo`, underscore identifiers for hyphenated
keys/enums (`keycloak_group`, `on_event`, `process_engine`) and unquoted executor
base identifiers. The world engine/storage declarations and ER, DB and binding
models are copied from `ttrp-graph/src/test/resources/fixtures/recipes/model`.
No recipe expressions, model column references or action semantics are repaired here.

Run each recipe with the published predecessor CLI in this directory:

```sh
ttrp check release_order.ttrp
ttrp check decide_return.ttrp
ttrp check monitor_inbound.ttrp
ttrp check settle.ttrp
```

The published predecessor accepts the attributes silently. Before implementation,
each check is expected to fail only on the missing `process_engine` executor base.
CG-2 adds that manifest and carries all four engine attributes into the bundle.
