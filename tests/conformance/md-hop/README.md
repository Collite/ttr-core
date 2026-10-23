# MD → ER conformance

The single source fixture is `packages/kotlin/ttr-metadata/src/test/resources/md-hop/`.
It uses invented names, three fact entities and cubelets, a stacked `sales_union`, FK and join relations,
map-mediated descriptive attributes, a database view (without invented lineage), an unmapped cubelet, and an N:N bridge.
There are no authored `md2er` bindings or `md2db_domain` sources in the base model.

`expected.json` and `expected.md` are the **whole-model** (`--all`) report, pinned byte-for-byte by
Kotlin `MdHopSpec`, TS `md-hop.test.ts`, the metadata CLI and the LSP integration test.
`landings.tsv` in the fixture is the independent landing table. The default finding scope has no warnings:
CN's revenue quality has the `sales` bearer, so it lands on `sales.ext_sales_price` even though the whole-model
measure row is ambiguous across four ER attributes.

The conflict and explicit-override variants are loaded separately, never together with the base by directory loading.
Their CN hygiene rows are pinned in `tests/conformance/cn-hygiene/expected-derived.json`;
`expected-derived-editor.md` pins the default case. Existing CN hygiene baselines remain unchanged.

Kotlin's optional Calcite QueryLineage service and its syntactic default agree on all fixture columns.
TS implements syntactic lineage; Python has no MD derivation model and retains structural validation only.
The syntactic providers treat `SELECT *` and duplicate output names as opaque; Calcite can derive them.
This conservative difference is intentional. Unsupported query projections are reported as `md/er-lineage-opaque` information.

Review-129 regressions share `review-129.json` beside the source fixture. They pin reordered/renamed/missing
query outputs, malformed SQL, via-map targets (including an unrelated map to the same domain), implicit bindings,
nested inline column targets, single-column FKs, cross-package conformed dimensions, explicit dimension pins,
invalid phase groundings, trailing semicolons and translator syntax in filters. Metadata tests use the syntactic
provider; concept tests load the optional Calcite provider from ttr-concept and read the same cases.
The provider uses the translator framework and plans each query once; a separate fresh-JVM check guards UTF-8
initialization before Czech string literals. Translator sources and dependencies stay on their separate release line.
