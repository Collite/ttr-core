# Hartland engine recipes

CG-2 pre-flight fixture for AU design `20-recipes-retyped-engine.md` §1–§4,
with candidates, context and decision bodies from `17-recipes-retyped.md` §3–§4.
The four action blocks come from `20`; the recipe bodies retain `17` verbatim
apart from the native comment marker (`#` → `//`), following the existing hero fixtures.

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

The CG-2 list requires these pre-flight checks to fail only on its three new
attributes before implementation starts. The stage list and PR describe the
current gate; this directory contains inputs, not generated reports or bundles.
