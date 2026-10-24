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

The in-memory source, graph, scope, JSON and Turtle writers are implemented. XMI,
the CLI and the service route follow in later XM stages.

The XM `Exporter` facade returns either named UTF-8 files, a bundle for callers to archive,
a stable cannot-carry lossiness id, an unknown-snapshot result, or `NotBuilt` for registered
formats awaiting their writer. S1.2 builds `graph.json`, `defs.json`, `dot` and canonical TTR;
S1.3 adds Turtle (`ttl`); XMI follows in S1.4, while canonical YAML waits for YS. Unknown model, format or
profile names are usage errors. Binding families such as `cn2md` can be selected separately.
Package selectors include descendants; package and area selectors form a union. Cross-scope
endpoints in graph output have only `id`, `kind` and `external`.

JSON writers use explicit builder insertion order, compact UTF-8 and one trailing LF.
`defs.json` follows the checked-in grammar property order (guarded by an exact drift test),
then sorts retained free-form and structural AST keys bytewise. Nested definitions carry
`id` so their names survive; lexicon definitions carry `locale`. Embedded language text is
opaque `{block, tag}` data. Reference resolution is shared with the graph, including implied
concepts; scalar identifier enums remain scalars. This is a projection of the retained
parsed AST, without source spans or trivia. The YS fixed-point test is explicitly pending.

Callers read `connections.toml` and pass `ConnectionDescriptor` values containing only
`id`, `database`, `schema` and public `locators`; the library has no TOML runtime dependency.
They also read authored `renames.yaml` with `Rename.read` and supply the previous graph's
node **and binding-edge** ids for `since`. `ExportDelta` compares snapshot version strings
lexically: versions must be chosen so lexical order is version order (for example,
zero-padded sequential ids). Renamed-from ids are excluded from `removed`. The service's
snapshot ring and the CLI's previous-graph reader belong to the callers, as does tar.gz
assembly. No timestamps are introduced by these writers.

`TurtleWriter` emits `cn.ttl` for `cn` or `world`, with profiles `both` (default),
`owl`, and `skos`. Every profile retains the ontology header and asserted `ttr:`
semantics; standard aliases follow the selected profile. Grounding targets remain
IRIs only. Schemes and area membership are retained, and scoped-out concepts keep
their authored identity without exposing their definition. Definitions/members
are opaque text; lexicon forms and examples retain their language tags. See the
[TTR RDF vocabulary](../../../docs/ns/ttr.md) for classes, properties, domain/range
descriptions, aliases and serialization rules. Jena is test-only; runtime
dependency rules ban it.
