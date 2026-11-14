#!/usr/bin/env bash
# MW S3.6 T6 — the generated-file guard fixtures, rebuilt from dialect/demo-cs-links/tree with the installed CLI:
#   ./gradlew :packages:kotlin:ttr-metadata-cli:installDist && tests/conformance/guard/demo-cs-links/regenerate.sh
# Each case is <case>/tree (the inputs) and <case>/model (what `profile apply` wrote from them, headers included).
#   pristine        — as generated: lint is green.
#   stale           — an input changed after generation: xm/generated-stale at each generated file's head.
#   edited          — demo/er.yaml's body changed by hand: xm/generated-edited at its head.
#   orphan-overlay  — the overlay's first key renamed after generation: stale, and xm/overlay-orphan at the key.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../../.." && pwd)"
bin="$repo/packages/kotlin/ttr-metadata-cli/build/install/ttr-metadata/bin/ttr-metadata"
source="$repo/tests/conformance/dialect/demo-cs-links/tree"
for case in pristine stale edited orphan-overlay; do
  rm -rf "${here:?}/$case"
  mkdir -p "$here/$case"
  cp -R "$source" "$here/$case/tree"
  (cd "$here/$case" && "$bin" profile apply --root tree --out model >/dev/null 2>&1)
done
# stale: a dialect file gains a description after generation.
sed -i.bak 's/popis: Zákazník/popis: Zákazník (nově)/' "$here/stale/tree/l1-prodej.yaml" && rm "$here/stale/tree/l1-prodej.yaml.bak"
# edited: the generated body says something its inputs do not.
sed -i.bak 's/Objednávka (overlay)/Objednávka (ručně)/' "$here/edited/model/demo/er.yaml" && rm "$here/edited/model/demo/er.yaml.bak"
# orphan-overlay: the overlay names an entity the tree no longer has.
sed -i.bak 's/demo.er.entity.Objednavka:/demo.er.entity.Zrusena:/' "$here/orphan-overlay/tree/profiles/overlay-inline.yaml" \
  && rm "$here/orphan-overlay/tree/profiles/overlay-inline.yaml.bak"
