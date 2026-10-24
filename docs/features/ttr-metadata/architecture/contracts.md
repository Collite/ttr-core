# ttr-metadata — Contracts (v1)

> **Status:** designed 2026-07-05. Source of truth for the library API surface, the Designer-server protocol, artifact coordinates, and compatibility rules. Companion: [`architecture.md`](./architecture.md). Changes here require a changelog entry (bottom).
>
> Signatures below are normative in *shape*; exact Kotlin spelling may drift during extraction — any drift that changes a shape needs a changelog entry.

---

## 1. Artifacts & coordinates

| Artifact | Module | Content | Consumers |
|---|---|---|---|
| `org.tatrman:ttr-metadata` | `:packages:kotlin:ttr-metadata` | typed model, storage SPI (+ fs/classpath), reconciler, resolver, graph, search, registry, refresher mechanism, export, **world resolution** | ttrp-frontend (P1.3), ttr-designer-server, Ariadne |
| `org.tatrman:ttr-metadata-git` | `:packages:kotlin:ttr-metadata-git` | `GitArchiveStorage` (jgit) implementing the core `ModelStorage` SPI | Ariadne |

Publishing: tag-driven per `PUBLISHING.md` — new tags **`kotlin-metadata/v<x.y.z>`** (publishes both artifacts together; they version in lockstep). Semver: minor = additive API, major = breaking; ttr-metadata pins the ttr-parser/semantics versions it builds against and re-exports them as `api` deps (consumers get one coherent set). JVM toolchain 21, Kotlin per `gradle/libs.versions.toml` (matches ttr-semantics).

## 2. Library API — core

Package root `org.tatrman.ttr.metadata`. All types immutable; snapshot semantics throughout.

```kotlin
// ── storage SPI (moved verbatim from Ariadne source/) ─────────────────
fun interface ModelSource { fun load(): SourceSnapshot }
interface ModelStorage {
    fun fetchVersion(): String
    fun listFiles(extensions: Set<String>, prefixes: List<String> = emptyList()): List<StorageFile>   // moved surface (v1.1 correction)
    fun read(file: StorageFile): String
}
class LocalFsStorage(root: Path) : ModelStorage          // repo-attached (Designer server, compiler)
class ClasspathStorage(prefix: String) : ModelStorage    // bundled seed models
// GitArchiveStorage lives in ttr-metadata-git

// ── load & lifecycle ──────────────────────────────────────────────────
class MetadataLoader(source: ModelSource) {
    fun load(): LoadResult                               // parse (ttr-parser) → reconcile → resolve → Model
}
data class LoadResult(val model: Model?, val issues: List<LoadIssue>)   // never throws on model errors

class MetadataRegistry {                                  // AtomicReference snapshot + listeners (moved)
    fun read(): RegistrySnapshot?
    fun readOrError(): RegistrySnapshot
    fun swap(snapshot: RegistrySnapshot)
    fun addListener(listener: (RegistrySnapshot) -> Unit)
}
class MetadataRefresher(loader: MetadataLoader, registry: MetadataRegistry) {
    suspend fun tryRefresh(): RefreshOutcome              // mutex-guarded; scheduling = host concern
    suspend fun forceRefresh(): RefreshOutcome
}

// ── typed model (moved from Ariadne model/, package-renamed) ──────────
data class Model(/* schemas, packages, areas, versions */) {
    fun objectByQname(): Map<QualifiedName, ModelObject>
    fun areaByName(name: String): AreaRecord?
}
sealed interface ModelObject                              // DbTable, DbView, DbColumn, DbProcedure,
                                                          // Entity, Attribute, RelationDef, Binding, …
sealed interface SchemaContents { fun objects(): Sequence<ModelObject> }  // DbSchema, ErSchema, CncSchema, WorldSchema(NEW)

// ── query facade (MD2: pulled down from Ariadne's gRPC bodies) ───────
class MetadataQuery(snapshot: RegistrySnapshot) {
    fun listObjects(filter: ObjectFilter, page: PageRequest): Page<ModelObject>
    fun getObject(qname: QualifiedName): ModelObject?
    fun resolve(qname: QualifiedName, expected: ObjectKind): ResolveOutcome   // §3 kind-typed lookup
    fun search(query: SearchQuery): List<SearchHit>       // keyword|regex|substring|all + post-processing
    fun graph(): ModelGraph                               // DEFINES/REFERENCES/MAPS_TO/USES; traverse/topo/cycles
    fun resolveArea(name: String): AreaResolution?
    fun erToDb(erQname: QualifiedName): ErBindingResult   // §3

    // ── grounding surface (grammar 4.2 `semantics { … }`; ai-platform Ariadne / grounding) ──
    fun periodTableFor(pkg: String): ModelObject?         // kind period_table (null ⇒ calendar-aligned)
    fun semanticRole(attrQname: QualifiedName): String?   // resolved attribute/column role, or null
    fun attributesByRole(pkg: String, role: String): List<ModelObject>   // e.g. all event_dates
    fun poiEntities(pkg: String): List<ModelObject>       // kind poi
    fun fxRateTableFor(pkg: String): ModelObject?         // kind fx_rate
}
```

Grounding-model fields (grammar 4.2, ttr-semantics `SEMANTICS_VOCABULARY_VERSION`): `Entity.semanticsKind` / `DbTable.semanticsKind` (`String?`, from `ResolvedEntitySemantics.kind`) and `Attribute.semantics` / `DbColumn.semantics` (`ResolvedAttributeSemantics?`). Populated by the source loader from a document-local `SemanticsAnalyzer.analyzeSemantics` pass; only diagnostics-free elements are populated (degrade, don't fail), and each `TTR-SEM-2xx` diagnostic surfaces as a `LoadIssue` of the new `Category.SEMANTICS_INVALID`.

`ResolveOutcome` and every error surface are **structured, id-free** (MD5): the library reports *what* (`NotFound`, `KindMismatch(expected, found)`, `Ambiguous(candidates)`, `BindingMissing(erQname, searchedPackages)`), consumers mint their own diagnostic ids (`TTRP-RES-003` etc.).

## 3. Library API — world resolution (new)

```kotlin
class WorldResolver(snapshot: RegistrySnapshot) {
    fun listWorlds(): List<QualifiedName>
    fun resolve(worldQname: QualifiedName): WorldResolution   // Ok(ResolvedWorld) | structured failure
}
data class ResolvedWorld(
    val qname: QualifiedName,
    val engines: List<ResolvedEngine>,        // instance ⊕ extends-type overlay applied
    val executors: List<ResolvedExecutor>,
    val storages: List<ResolvedStorage>,      // incl. hosts: [pkg] mapping, schema defs (D-c world tier)
    val staging: ResolvedStorage?,            // exactly-one validated; >1 → StagingConflict failure (D-f)
    val fingerprint: String,                  // "sha256:<semantic-hash>" — §5
)
data class ErBindingResult(                   // E-d support: provenance data, consumer renders it
    val dbQname: QualifiedName?,
    val chain: List<BindingStep>,             // each: erQname, dbQname, definitionLocation
)
```

Failure shapes: `WorldNotFound`, `NotAWorld(foundKind)`, `StagingConflict(storages)`, `HostsUnknownPackage(pkg)`, `ExtendsUnresolved(typeRef)`. Selection precedence (`uses world` pin > `[ttrp] world` > error) is the **caller's** contract (TTR-P contracts §2) — `WorldResolver` takes the already-chosen qname.

**Capability manifests:** engine/executor manifest *content* (T6 β entries) rides on `ResolvedEngine`/`ResolvedExecutor` as data (`manifest: JsonObject`-shaped, format owned by TTR-P Stage 2.2). ttr-metadata transports and overlays it; it never interprets it (MD5).

## 4. Designer-server protocol — `ttrm/*`

Transport: WebSocket, JSON-RPC 2.0, one message per text frame, endpoint `ws://127.0.0.1:<port>/ttrm` (S24: loopback bind, no auth; default port **7270**, `--port` to override). Read-only in v1 — no method mutates the repo.

| Method | Params → Result | Notes |
|---|---|---|
| `ttrm/getModelIndex` | `{}` → `{packages[], schemas[], areas[], counts, modelVersion}` | the browse tree |
| `ttrm/getModelGraph` | `{scope?: {package? schema? qnames[]?}, edgeTypes?}` → `{nodes[], edges[]}` | node = `{qname, kind, label, schema, pkg}`; edge = `{from, to, type}` (DEFINES\|REFERENCES\|MAPS_TO\|USES); shaped after the payload the Designer already renders (MD6) |
| `ttrm/getObject` | `{qname}` → `{object, sourceLocation, references[]}` | full typed detail incl. er↔db bindings |
| `ttrm/search` | `{query, algorithm?: keyword\|regex\|substring\|all, limit?}` → `{hits[]}` | |
| `ttrm/getWorld` | `{qname?}` → `ResolvedWorld` JSON (or `{worlds[]}` when no qname) | serves TTR-P P5 `ttrp/getWorld` needs |
| `ttrm/getStatus` | `{}` → `{modelVersion, loadedAt, issues[], repoRoot}` | |
| `ttrm/refresh` | `{force?}` → `{outcome, modelVersion}` | re-load from disk |
| `ttrm/modelChanged` | **notification →client** `{modelVersion}` | fired via registry listener on file-watch reload |

Errors: JSON-RPC error objects `{code, message, data:{kind, …structured fields…}}`; codes: `-32000` model-not-loaded, `-32001` not-found, `-32002` bad-scope. Protocol version handshake: `ttrm/getStatus` carries `protocolVersion: 1`; additive changes bump nothing, breaking changes bump it (and this doc).

**Future mounts on the same host (informative):** TTR-P WS-LSP at `/lsp` (P5.1, mounts into this server per MD8) · TTR-M editing + `.ttrl` (C1-f, post-v1). `ttrm/*` is reserved for TTR-M model serving; `ttrp/*` stays with the TTR-P LSP.

## 5. Semantic world fingerprint

One implementation, exported for the TTR-P bundle (`manifest.json.world.fingerprint`, TTR-P contracts §5) and conform harness. Canonicalization: resolved (post-overlay) world → canonical JSON (sorted keys, qname-sorted arrays, defaults elided, source locations and doc-comments excluded) → sha256. Property tests pin: insensitive to declaration order/whitespace/comments; sensitive to any semantic field (engine version, hosts, staging flag, manifest entries). Spelled `sha256:<hex>`.

## 6. Frontend data-source contract (packages/designer)

```ts
interface ModelDataSource {                       // implemented by: WorkerLspDataSource (existing path),
  getModelIndex(): Promise<ModelIndex>            //                 WsDesignerServerDataSource (new)
  getModelGraph(scope?: GraphScope): Promise<ModelGraphPayload>
  getObject(qname: string): Promise<ObjectDetail>
  search(q: SearchParams): Promise<SearchHit[]>
  onModelChanged(cb: (v: string) => void): Disposable
  readonly capabilities: { edit: boolean }        // WS source: edit=false → designer hides edit affordances
}
```

Selection: explicit configuration (URL param `?server=ws://127.0.0.1:7270` or config file) — never sniffed (P2).

## 7. Kantheon adoption contract

- Ariadne consumes `org.tatrman:ttr-metadata` + `:ttr-metadata-git` from GitHub Packages; pins in `gradle/libs.versions.toml`.
- **Frozen through the swap (MD7):** the `org.tatrman.ariadne.v1` proto, all 14 RPC behaviors, ports 7260/7261/7262, `METADATA_GIT_*` env contract, ariadne-mcp.
- Deleted from Ariadne after the swap: `model/ source/ reconcile/ resolve/ graph/ search/ registry/ export/`(minus routes)`, refresh/MetadataRefresher` — anything re-appearing there is drift (guard: review rule in the kantheon arc doc).
- Stays in Ariadne: `grpc/`, `PageTokenCodec`, proto conversions, `parse/` (QueryParseWorker + query-translator), `RefreshScheduler`, Application/Ktor/OTel/k8s, `MetadataExportRoutes`.
- Compatibility gate: Ariadne's remaining Kotest suite green against the published artifact = the adoption DONE bar; the moved core specs (24: 23 core + 1 git; 14 stay) live in tatrman from M1 on.

## 8. Fixture sharing with TTR-P

The world/model fixture project designed in TTR-P `tasks-p1-s1.3-resolution.md` (erp db/er/binding models + `acme.worlds` world doc + WLD/RES/SCH negative roster) gets its **single home in ttr-metadata** (`packages/kotlin/ttr-metadata/src/testFixtures/resources/fixtures/` — the `java-test-fixtures` source set, required for cross-project consumption; v1.1 correction); ttrp-frontend's Stage 1.3 consumes it via `testFixtures(project(":packages:kotlin:ttr-metadata"))` instead of duplicating. TTR-P task list s1.3 gets a pre-flight amendment (plan §6).

---

## Changelog

- **v1.5 · 2026-07-06 (grounding Phase 1, grammar 4.2, feature semantics-block T5)** — new grounding surface on the typed model + query facade. Four resolved-semantics fields: `Entity.semanticsKind` / `DbTable.semanticsKind` (`String?`) and `Attribute.semantics` / `DbColumn.semantics` (`ResolvedAttributeSemantics?` from `ttr-semantics`), populated by the loader from a document-local `SemanticsAnalyzer.analyzeSemantics` pass (diagnostics-free elements only — degrade, don't fail). Five `MetadataQuery` accessors: `periodTableFor`, `semanticRole`, `attributesByRole`, `poiEntities`, `fxRateTableFor` (the grounding lookups ai-platform's Ariadne / grounding services call). New `LoadIssue.Category.SEMANTICS_INVALID` categorizes the `TTR-SEM-2xx` diagnostics (id-free). Vocabulary syncs with `ttr-semantics` `SEMANTICS_VOCABULARY_VERSION = 1` and ai-platform's closed proto enums. Cross-ref: grammar [`CHANGELOG.md`](../../../packages/grammar/CHANGELOG.md) 4.2 entry. Additive — no version bump to parser/semantics pins (project deps, lockstep tag `kotlin/v*`).
- **v1.4 · 2026-07-05 (M3.2)** — frontend data-source adapter (`packages/designer`).
  (a) **§4 `getModelGraph` DTO ≠ the renderable canvas `ModelGraph`.** §4's node/edge
  shape (`{qname,kind,label,schema,pkg}` / `{from,to,type}`) is a *dependency-graph*
  payload; the Designer canvas renders the richer `ModelGraph` (rows[], `fk|relation`
  edges, cardinalities — `packages/lsp/src/model-graph.ts`). §4's "shaped after the
  payload the Designer already renders (MD6)" does **not** hold for the canvas payload.
  v1 resolution: WS mode renders `ttrm/getModelGraph` nodes as **row-less boxes**
  (`src/cy/ttrm-adapter.ts`) and enriches on-demand via `ttrm/getObject`; whether §4
  grows renderable fields (rows, cardinalities) is deferred to the C1-f arc. (b) **§6
  is the READ contract only** — graph-list / layout / edit ops stay on `LspClient`,
  reachable only when `capabilities.edit` (WS source: `edit=false`; worker source:
  `edit=true`). That flag — not the transport — is the read-only gate. (c) **New UI:**
  the Designer had no standalone search today (`modeler/listSymbols` fed the edit-only
  AddObjectPicker); a read-only `SearchBox` was added to meet the DONE bar's "search".
  (d) **Fixture ↔ server reconciliation:** the M3.2 task's illustrative index/search
  fixtures used object arrays (`packages:[{name}]`, `schemas:[{code,name,pkg}]`,
  hits with `kind,label`); the **implemented M3.1 server** emits `packages: string[]`,
  `schemas: string[]`, and search hits `{qname,score,matchedField}` (§v1.3(c)). The
  frontend types (`src/data/ttrm-types.ts`) and canned fixtures were aligned to the
  **server** (the implemented truth), not the illustration. (e) Backend selection is
  explicit `?server=ws://127.0.0.1:7270` (P2, never sniffed; loopback-only per S24);
  WS mode is a dedicated read-only view (`WsModeApp`) that imports zero edit machinery
  — a stronger guarantee than runtime capability-gating of a shared component.
- **v1.3 · 2026-07-05 (M3.1)** — Designer-server protocol pins from the host
  implementation (`packages/kotlin/ttr-designer-server`): (a) **`getStatus` answers
  pre-snapshot** — it is the handshake, not a data method, so it returns
  `{protocolVersion: 1, modelVersion: null, loadedAt: null, repoRoot, issues}` when
  no model is loaded rather than `-32000` (§4 didn't pin this corner). (b) **Batch
  (JSON array) is explicitly unsupported in v1 → `-32600`**; additive to support
  later without a protocolVersion bump. (c) Per-element DTO field pins (beyond §4's
  table, coordinate with M3.2 T3.2.2): graph node `{qname,kind,label,schema,pkg}`
  where `qname` is the package-qualified dotted string
  (`<package>.<schemaToken>.<namespace>.<name>`, empty slots dropped) and is the id
  used by `getObject`/`getWorld` lookups; graph edge `{from,to,type}` (`type` ∈
  `DEFINES|REFERENCES|MAPS_TO|USES`, filterable via `getModelGraph.edgeTypes`);
  search hit `{qname,score,matchedField}`; index `{packages,schemas,areas,counts,modelVersion}`
  (packages derived from source-file paths under `models/`). (d) Error `data.kind`
  carries the structured failure name verbatim from the library (e.g.
  `WorldNotFound`, `NotAWorld`, `StagingConflict`, `HostsUnknownPackage`,
  `ExtendsUnresolved` for `getWorld`) — the library stays id-free; the host mints
  the JSON-RPC shape (MD5). (e) `ttr-designer-server` is **not published** (internal
  tooling; PUBLISHING.md). Deviations recorded: `edgeTypes`/scope-`package`
  filtering implemented, but `getObject.references` is currently `[]` (er↔db binding
  projection deferred to M3.2 alongside the richer canvas-DTO resolution); `search`
  does not reject unknown `algorithm` with `-32602` (the library ignores it) — noted,
  not silently fixed.
- **v1.2 · 2026-07-05 (M2)** — implementation-driven shape confirmations (see
  `../implementation/v1/notes-api-review.md`): §8 fixture home is
  **`src/testFixtures/resources/fixtures/`** (the `test` source set is not
  cross-project consumable; only `testFixtures` is) — consumers wire
  `testFixtures(project(":packages:kotlin:ttr-metadata"))`. §3 `manifest` is
  transported as `Map<String, PropertyValue>` (the parser value model), not a JSON
  tree — TTR-P Stage 2.2 owns the JSON projection/interpretation (MD5); the 1:1
  JSON mapping is shown by `WorldFingerprint.canonValue`. §3 overlay merge rule
  (shipped, reviewable): instance wins, type fills, **lists/manifest replaced
  wholesale** (not element-merged); **dotted `extends` resolves in-model else
  `ExtendsUnresolved`, bare ids pass through on `extendsRef`** (RM6). §5 fingerprint
  implemented (`WorldFingerprint`): canonical JSON (sorted keys, qname-sorted arrays,
  defaults elided, locations/comments excluded) → sha256; world qname excluded from
  the hash (F-f-ii, reviewable). §2 `LoadResult.issues` is the finalized sealed
  `LoadIssue` taxonomy (id-free enum categories, MD5); `MetadataRefresher` keeps the
  moved Ariadne surface (RM9). SchemaCode gains library-only `WORLD` (proto frozen).
- **v1.1 · 2026-07-05** — task-cutting corrections: §2 `ModelStorage.listFiles(extensions, prefixes)` (actual moved surface); §7 moved-spec count 24 (was ≈19); §8 fixture home `src/testFixtures/resources/` under `java-test-fixtures`. Known queued entries (land with their stages): §2 registry/refresher exact signatures (RM9), §4 getModelGraph canvas-DTO note + getStatus handshake + per-element field pins (RM8), §3 `extendsRef` pass-through rule (RM6).
- **v1 · 2026-07-05** — initial design (MD1–MD8).
