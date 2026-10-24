# ttr-metadata

The Kotlin model loader, reconciler, typed model graph and metadata query library.

## export/ — projections (XM)

There are two export sources. `GraphDotExporter` reads the typed `ModelGraph` for
diagrams. External metadata projections read `DefinitionSet.from(load.files,
load.model, grammar, snapshot)`: every parsed definition, including MD, CN,
lexicon and binding definitions. `LoadResult.files` retains the loader's
`LoadedFile` objects; no runtime dependency was added for projections.

`DefinitionSet.entries` is sorted by UTF-8 qname then kind. Identity is kinded
and package-independent: `db.<schema>.<table>[.<column>]`,
`er.entity.<entity>[.<attribute>]`, `er.relation.<relation>`,
`md.<kind>.<name>` (dimension attributes append `.<attribute>`),
`cn.<stereotype>.<name>`, `<binding_kind>.<name>` and `area.<name>`.
DB and ER entity/attribute identities agree with `QualifiedName.dotted()`. Legacy typed ER relations inherit their file namespace; external relations always
use the kinded relation spelling. The original AST,
package, relative file and schema remain available for tier-0 writers.

`DefinitionGraph.build(set)` derives bodiless nodes and edges, harvests concept
sugar, keeps binding identities on edges, and attaches every lexicon term form
by locale. Concept IRIs may override `urn:ttr:<world>:<qname>`. Expression text
stays in the definition set; graph nodes and binding edges link to it through
`hasDefinition` and `definitionRef`. Missing references become minimal
`unresolved` stubs. Structural package and physical-schema endpoints are minimal
external nodes too.

`ExportKinds` records the XM C-5 decisions. Node kinds are schema, table, view,
column, foreign_key (grammar `fk`), entity, attribute, relation, domain, dimension,
map, hierarchy, measure, cubelet, kind, role, phase, quality, event,
relation_type and area. Bindings become edges; lexicon terms become synonyms.
World plumbing (storage, engine, executor, project, worldSchema, world), query,
index, constraint, procedure, process, automation and all six automation
definition kinds, deprecated CNC roles/er2cnc_role, and drill_map are excluded.
`KindListSpec` compares explicit decisions with the generated grammar property
map; regenerate it with `pnpm --filter @tatrman/grammar prebuild` before tests.
Adding a grammar kind requires an explicit export decision.
The full contract is XM `plan/contracts.md` C-2–C-6 in the project planning repo.

`ExportScope(packages, areas).apply(graph, set)` selects nodes before projection.
Areas select their packages and explicitly named entities (including attributes).
Selectors form a union; null is unrestricted and an empty selector selects
nothing. Edges touching either selected endpoint remain, and every other endpoint
becomes a stub carrying only `id`, `kind` and `external`. `scope.header` supplies
the deterministic C-4 header value.

S1.1 provides the in-memory source, graph and scope. JSON, Turtle, XMI writers,
the CLI and the service route follow in later XM stages.
