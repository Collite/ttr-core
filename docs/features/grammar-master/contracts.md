# Grammar-master contracts

**Companion to:** [`plan.md`](plan.md), [`architecture.md`](architecture.md),
[`AST-NAMING.md`](AST-NAMING.md).

Defines every public API exposed by the new modeler-owned Kotlin artifacts.
"Public" = anything an ai-platform consumer can `import`. Internal helpers,
the generated ANTLR classes, and `TtrWalker`'s private state are NOT public
and may change between minor versions.

Versioning: see `plan.md` §"Decisions" D6 (no SNAPSHOTs) and
`PUBLISHING.md` (to be authored in P1-4) for the full policy. Summary:
breaking change to any contract below = major bump.

---

## 1. Maven coordinates

| GroupId | ArtifactId | Phase | Depends on |
|---|---|---|---|
| `org.tatrman` | `ttr-parser` | 1 | `org.antlr:antlr4-runtime:4.13.2`, `org.slf4j:slf4j-api:2.0.17` |
| `org.tatrman` | `ttr-writer` | 1 | `org.tatrman:ttr-parser` (api) |
| `org.tatrman` | `ttr-semantics` | 2 | `org.tatrman:ttr-parser` (api) |

Repository: `https://maven.pkg.github.com/Collite/modeler`
(authenticated with a GitHub PAT having `read:packages`).

POM `<scm>`, `<licenses>`, `<developers>` — fill from the modeler repo root
once on first publish.

---

## 2. `org.tatrman:ttr-parser` — public API

### 2.1 Entry point: `TtrLoader`

Package: `org.tatrman.ttrm.parser.loader`

```kotlin
object TtrLoader {
    fun parseString(content: String, fileLabel: String = "<inline>"): ParseResult
    fun parseFile(path: Path): ParseResult
    fun parseFile(path: String): ParseResult            // convenience
    fun parseDirectory(rootPath: Path, recursive: Boolean = true): List<ParseResult>
}
```

Behaviour:
- Syntax errors **never throw**; they accumulate on `ParseResult.errors`.
- `parseDirectory` filters to `*.ttrm` and **excludes** `*.ttrg` (graphical
  artefact files).
- Skips directories named `.modeler`, `node_modules`, `.git` (matches the TS
  `parseDirectory`).
- On any parser error, `ParseResult.definitions` is **empty** — no partial
  trees emitted.

### 2.2 `ParseResult`

```kotlin
data class ParseResult(
    val definitions: List<Definition>,
    val schemaDirective: SchemaDirective?,
    val errors: List<ParseError>,
    val sourceFile: String,
    val warnings: List<ParseWarning> = emptyList(),
    val packageName: String? = null,
    val imports: List<ImportStatement> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()
}
```

`ok` is gated on `errors` only; warnings do not block ingestion.

### 2.3 `ParseError` / `ParseWarning`

```kotlin
data class ParseError(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val code: DiagnosticCode = DiagnosticCode.ParseError,
) {
    override fun toString(): String = "$file:$line:$column: $message"
}

data class ParseWarning(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
    val code: DiagnosticCode,
)
```

Position convention: `line` and `column` are **both 1-indexed for human
display** (matches ai-platform's existing `ParseError.toString()` shape;
`column` = ANTLR's `charPositionInLine + 1`). Distinct from `SourceLocation`
on AST nodes — see §2.4.

### 2.4 `SourceLocation` (ANTLR-style, modeler superset — D4)

Package: `org.tatrman.ttrm.parser.model`

```kotlin
data class SourceLocation(
    val file: String,
    val line: Int,           // 1-indexed (matches ANTLR token.line)
    val column: Int,         // 0-indexed (matches ANTLR token.charPositionInLine)
    val endLine: Int,        // 1-indexed
    val endColumn: Int,      // 0-indexed; one past the last character
    val offsetStart: Int,    // 0-indexed byte offset, inclusive
    val offsetEnd: Int,      // 0-indexed byte offset, exclusive
) {
    override fun toString(): String = "$file:$line:$column"   // preserves ai-platform's old format

    companion object {
        val UNKNOWN = SourceLocation("<unknown>", -1, -1, -1, -1, -1, -1)
    }
}
```

**Invariant for multi-token spans:** `endColumn = stopToken.column + stopTokenLength`
— NOT `startColumn + spanLength`. Mirrors `CLAUDE.md`'s
`makeSourceLocation` note for `walker.ts`.

LSP-style consumers subtract 1 from `line` / `endLine` to produce LSP
positions.

### 2.5 `Definition` hierarchy

Package: `org.tatrman.ttrm.parser.model`

```kotlin
sealed interface Definition {
    val name: String
    val source: SourceLocation
    val description: String?
    // Grammar 0.13 (NLS-P10) — the localised `description: { en: …, cs: … }` form.
    // Exactly one of the pair is populated; the walker never folds a map to one
    // locale (locale selection is the reader's job — Veles' D7 chain).
    val descriptionLocalized: LocalizedStringValue
    val tags: List<String>
}
```

Concrete subtypes (every TTR v2.2 `def <kind>` shape — full field lists in the
existing ai-platform `Definition.kt` plus the v2.0.0/v2.2 corrections below):

| Subtype | Notes |
|---|---|
| `ModelDef` | `version: String?` |
| `TableDef` | `primaryKey`, `columns`, `indices`, `constraints`, `search` (top-level `search { }` is grammar-legal on tables — `tableProperty | … | searchBlockProperty`) |
| `ViewDef` | `columns`, `definitionSql`, `search` (grammar-legal top-level block) |
| `ColumnDef` | **v2.0.0 fix:** drop top-level `searchable` (it lives inside `search: SearchHintsValue`). Keep `type`, `optional`, `isKey`, and `indexed` — `indexed` stays a top-level column field, matching the canonical TS `ColumnDef` and the grammar's column-level `indexedProperty` (it is NOT part of `SearchHintsValue`). |
| `IndexDef` | `indexType`, `columns` |
| `ConstraintDef` | `constraintType`, `columns` |
| `FkDef` | `from`, `to` |
| `ProcedureDef` | `parameters`, `resultColumns` |
| `EntityDef` | `labelPlural`, `nameAttribute`, `codeAttribute`, `aliases`, `attributes`, `roles`, `displayLabel`, `search`, `mapping` |
| `AttributeDef` | **v2.0.0 fix:** drop top-level `searchable`. Keep `type`, `isKey`, `optional`, `displayLabel`, `valueLabels`, `search`, `mapping` |
| `RelationDef` | `from`, `to`, `cardinality`, `join`, `search` (grammar-legal top-level block), `mapping` |
| `Er2DbEntityDef` | `entity`, `target: TargetValue?`, `whereFilter` |
| `Er2DbAttributeDef` | `attribute`, `target: TargetValue?` |
| `Er2DbRelationDef` | `relation`, `fk` |
| `QueryDef` | `language`, `parameters`, `sourceText`, `search` |
| `RoleDef` | `label`, `search` |
| `Er2CncRoleDef` | `entity`, `role` |
| `DrillMapDef` | `from`, `to`, `args`, `display`, `overrideAuto` (v2.2 grammar) |
| `SchemaDef` | **grammar 0.14:** `columns: List<ColumnDef>` — a named row schema (`def schema <name> { description?, tags?, columns: [...] }`), each column a full `ColumnDef` (`type`, `optional`, …). Tier-neutral (`modelForKind` → `db`, the `area` precedent). Conformance kind `schema`; `properties.columns` dumped exactly like a view's. `ttr-metadata` surfaces it as `Model.rowSchemas` (keyed `<pkg>.<name>`, a `RowSchemaRecord` — not a `ModelObject`); TTR-P holds `display(<name>)` rows to it. |

Full field signatures live in `AST-NAMING.md` — see that doc for the TS↔Kotlin
type/field rename map.

### 2.6 `PropertyValue`

```kotlin
sealed interface PropertyValue {
    val source: SourceLocation                          // NEW vs ai-platform — D4

    data class StringValue(val raw: String, override val source: SourceLocation) : PropertyValue
    data class TripleStringValue(val raw: String, override val source: SourceLocation) : PropertyValue
    data class NumberValue(val raw: Double, override val source: SourceLocation) : PropertyValue
    data class BoolValue(val raw: Boolean, override val source: SourceLocation) : PropertyValue
    data class NullValue(override val source: SourceLocation) : PropertyValue
    data class IdValue(
        val ref: Reference,
        val parts: List<String>,                        // NEW vs ai-platform — matches TS IdValue.parts
        override val source: SourceLocation,
    ) : PropertyValue
    data class ListValue(val items: List<PropertyValue>, override val source: SourceLocation) : PropertyValue
    data class ObjectValue(val entries: Map<String, PropertyValue>, override val source: SourceLocation) : PropertyValue
    data class FunctionCall(val name: String, val args: List<PropertyValue>, override val source: SourceLocation) : PropertyValue
}
```

**Breaking vs ai-platform's current shape:** every variant gains a `source`
field; `IdValue` gains `parts`. Migration on the ai-platform side: any
positional `PropertyValue.StringValue(raw)` call site becomes
`PropertyValue.StringValue(raw, source)` — surfaces as compile error,
mechanical fix.

### 2.7 Other model types

```kotlin
// Carries the reference token's own span (matches the TS `Reference`
// `{ path, parts, source }`), so diagnostics/navigation built from a collected
// reference point at the reference, not its enclosing def. The single-arg
// constructor is a convenience for non-parser construction (derives `parts`,
// uses SourceLocation.UNKNOWN).
data class Reference(val path: String, val parts: List<String>, val source: SourceLocation) {
    constructor(path: String) : this(path, path.split("."), SourceLocation.UNKNOWN)
    override fun toString(): String = path
}

data class SchemaDirective(val schemaCode: String, val namespace: String?, val source: SourceLocation)
data class ImportStatement(val target: String, val wildcard: Boolean, val source: SourceLocation)
data class PackageDeclaration(val name: String, val source: SourceLocation)        // NEW vs ai-platform

data class LocalizedStringValue(val byLanguage: Map<String, String> = emptyMap())
data class LocalizedStringListValue(val byLanguage: Map<String, List<String>> = emptyMap())

data class SearchHintsValue(
    val keywords: LocalizedStringListValue = LocalizedStringListValue(),
    val patterns: List<String> = emptyList(),
    val descriptions: LocalizedStringListValue = LocalizedStringListValue(),
    val examples: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val searchable: Boolean = false,
    val fuzzy: Boolean = false,                 // grammar 0.12: DEPRECATED, see `method`
    // grammar 0.12 (RV-P1.5, RV-32) — the match method on `searchable`, appended
    // so positional construction stays source-compatible. `fuzzyAuthored`
    // distinguishes an authored `fuzzy: false` (→ EXACT) from an absent one
    // (→ the RV-32 default TYPOS(1)), which widening `fuzzy` to `Boolean?` would
    // have expressed at the cost of every consumer reading it as a Boolean.
    val method: MatchMethodValue? = null,
    val fuzzyAuthored: Boolean = false,
)

data class MatchMethodValue(val name: String, val argument: Double? = null)  // 0.12

data class DataType(val name: String, val length: Int? = null, val precision: Int? = null)

// v2.1 mappings — sealed (unchanged from ai-platform)
sealed interface MappingProperty { val source: SourceLocation }
data class MappingPropertyBareId(val id: Reference, override val source: SourceLocation) : MappingProperty
data class MappingPropertyBlock(
    val target: TargetValue? = null,
    val columns: List<MappingColumnEntry> = emptyList(),
    val fk: Reference? = null,
    override val source: SourceLocation,
) : MappingProperty

sealed interface TargetValue { val source: SourceLocation }
data class TargetObjectValue(val obj: PropertyValue.ObjectValue, override val source: SourceLocation) : TargetValue
data class TargetReferenceValue(val ref: Reference, override val source: SourceLocation) : TargetValue

data class MappingColumnEntry(val name: String, val value: MappingColumnValue, val source: SourceLocation)
sealed interface MappingColumnValue { val source: SourceLocation }
data class MappingColumnBareId(val id: Reference, override val source: SourceLocation) : MappingColumnValue
data class MappingColumnObject(val obj: PropertyValue.ObjectValue, override val source: SourceLocation) : MappingColumnValue

// v1.1 graph block (planned per v1.1 design)
data class GraphBlock(
    val name: String,
    val schema: Reference?,
    val objects: List<Reference> = emptyList(),
    val layout: List<GraphLayoutEntry> = emptyList(),
    val source: SourceLocation,
)
data class GraphLayoutEntry(val nodeId: String, val x: Double, val y: Double, val source: SourceLocation)
```

### 2.8 Diagnostics

```kotlin
package org.tatrman.ttrm.parser.diagnostics

enum class DiagnosticCode(val id: String) {
    ParseError("ttr/parse-error"),
    ParseRecoveryInfo("ttr/parse-recovery-info"),
    UnknownProperty("ttr/unknown-property"),
    UnresolvedReference("ttr/unresolved-reference"),
    DuplicateDefinition("ttr/duplicate-definition"),
    RequiredPropertyMissing("ttr/required-property-missing"),
    InvalidType("ttr/invalid-type"),
    EntityAttributeNotFound("ttr/entity-attribute-not-found"),
    PrimaryKeyColumnNotFound("ttr/primary-key-column-not-found"),
    WrongFileKind("ttr/wrong-file-kind"),
    UnimportedReference("ttr/unimported-reference"),
    UnusedImport("ttr/unused-import"),
    WildcardWithNoMatches("ttr/wildcard-with-no-matches"),
    DuplicateImport("ttr/duplicate-import"),
    CircularPackageDependency("ttr/circular-package-dependency"),
    PackageDeclarationMismatch("ttr/package-declaration-mismatch"),
    MissingPackageDeclaration("ttr/missing-package-declaration"),
    AmbiguousReference("ttr/ambiguous-reference"),
    GraphObjectNotFound("ttr/graph-object-not-found"),
    GraphLayoutStaleNode("ttr/graph-layout-stale-node"),
    GraphObjectsEmpty("ttr/graph-objects-empty"),
    GraphNameMismatch("ttr/graph-name-mismatch"),
    FileOrdering("ttr/file-ordering"),
    FuzzyWithoutSearchable("ttr/fuzzy-without-searchable"),
    DuplicateSearchProperty("ttr/duplicate-search-property"),
    // grammar 0.12 (RV-P1.5) — the match-method attribute. All three ride the
    // portable conformance subset, so TS/Kotlin/Python must emit them alike.
    SearchFuzzyDeprecated("ttr/search-fuzzy-deprecated"),
    UnknownMatchMethod("ttr/unknown-match-method"),
    InvalidMatchMethodArgument("ttr/invalid-match-method-argument"),
    DuplicateMapping("ttr/duplicate-mapping"),
    ;
    override fun toString(): String = id
}

enum class DiagnosticSeverity { Error, Warning, Information, Hint }
```

The full enum lives in `ttr-parser` even though some codes are only fired
by `ttr-semantics` (Phase 2). Rationale: codes are a contract shared by all
modeler artifacts; consumers should depend on one canonical set.

### 2.9 Triple-string dedent

Public function (kept in `org.tatrman.ttrm.parser.walker`):

```kotlin
object Dedent {
    fun dedent(raw: String): String
}
```

Semantics: Python `textwrap.dedent` —
1. Drop the leading newline immediately after `"""`.
2. Compute the longest common whitespace prefix across all non-blank lines.
3. Strip that prefix; normalise blank lines to empty.

Conformance: matches CPython's reference cases (see `DedentSpec`).

---

## 3. `org.tatrman:ttr-writer` — public API

Package: `org.tatrman.ttrm.writer`

```kotlin
object TtrRenderer {
    fun render(definitions: List<Definition>, schemaDirective: SchemaDirective? = null): String
    fun render(result: ParseResult): String                  // convenience
}
```

Guarantees:
- Round-trip property: `parseString(render(r))` produces a `ParseResult`
  whose `definitions` are structurally equal to `r.definitions` (modulo
  `SourceLocation` which is regenerated). Tested by a round-trip Kotest
  spec.
- Stable, deterministic property ordering so output is diff-stable. **Note
  (Phase 1):** the implemented renderer uses a fixed hand-coded order per block
  (not strict alphabetical) — deterministic, hence diff-stable. Strict
  alphabetical-within-block is a possible future tightening (see
  `tasks/phase-1/05-ttr-writer.md` deviations).
- Triple-string output for any `description` or content containing a
  newline; single-line strings stay single-line.

---

## 4. `org.tatrman:ttr-semantics` — public API (Phase 2)

Package: `org.tatrman.ttrm.semantics`

> **Implementation note (Phase 2, 2026-06).** The signatures below were the
> pre-implementation design sketch. The shipped API mirrors the canonical TS
> layer (`packages/semantics/src/`) **faithfully**, because the conformance
> harness (§5.1) enforces TS↔Kotlin behavioural parity and the binding
> instruction is "mirrors `resolver.ts` exactly". The differences that matter to
> consumers:
>
> - **`Resolver`** exposes `resolveReference(ref: Resolver.Ref, ctx:
>   ResolutionContext)` and `resolveBareId(name, scope: LexicalScope)`. The
>   six-step algorithm needs the referrer's `schemaCode` / `namespace` /
>   `enclosingQname` / `imports` / `packageName` (carried on `ResolutionContext`)
>   — the sketch's `resolve(reference, currentPackage, imports, lexicalScope)`
>   could not reproduce per-def-kind qname prefixes or the doubled
>   `cnc.cnc.role.*` stock shape. Result is the sealed `ResolutionResult`
>   (`Resolved(symbol, viaStep)` / `Unresolved(reason, tried, candidates)`).
> - **`SymbolTable`** is the project-level table (TS `ProjectSymbolTable`):
>   `upsertDocument` / `get` / `getAll` / `all` / `getByPackage` / `getBySuffix`
>   / `duplicates`, plus the contract aliases `lookup` / `findUnderPackage` /
>   `findByLastSegment`. `SymbolEntry` carries the full TS shape (qname, kind,
>   name, packageName, schemaCode, mappingSource, …).
> - **`Validator`** runs on a `SemanticDocument` and ships the portable subset
>   (see §5.1 rule 3).
> - **`StockLoader.load()`** returns the parsed `List<Definition>`; stock is
>   resolved by upserting it into the `SymbolTable` under a `stock://` URI (not a
>   separate `stockQnames` constructor arg). `stockQnames()` is still provided.
>
> `Qname`, `PackageInference`, and `PackageGraph` match the sketch.

### 4.1 `Qname`

```kotlin
@JvmInline value class Qname(val value: String) {
    val segments: List<String> get() = value.split(".")
    val last: String get() = segments.last()
    val parent: Qname? get() = if (segments.size > 1) Qname(segments.dropLast(1).joinToString(".")) else null
    override fun toString(): String = value
}
```

### 4.2 `SymbolTable`

```kotlin
class SymbolTable {
    fun add(qname: Qname, def: Definition, sourceFile: String)
    fun lookup(qname: Qname): SymbolEntry?
    fun findByLastSegment(last: String): List<SymbolEntry>     // for wildcard matching
    fun findUnderPackage(pkg: Qname): List<SymbolEntry>        // for same-package resolution
}

data class SymbolEntry(val qname: Qname, val def: Definition, val sourceFile: String)
```

### 4.3 `Resolver` — 6-step chain

```kotlin
class Resolver(
    private val symbolTable: SymbolTable,
    private val stockQnames: Set<Qname>,   // populated from StockLoader
) {
    fun resolve(
        reference: String,
        currentPackage: Qname?,
        imports: List<ImportStatement>,
        lexicalScope: Map<String, Qname> = emptyMap(),
    ): ResolutionResult
}

sealed interface ResolutionResult {
    data class Resolved(val qname: Qname, val step: ResolutionStep) : ResolutionResult
    data class Unresolved(val code: DiagnosticCode, val message: String) : ResolutionResult
    data class Ambiguous(val candidates: List<Qname>) : ResolutionResult
}

enum class ResolutionStep { Lexical, SamePackage, NamedImport, WildcardImport, AutoImport, FullyQualified }
```

Algorithm: lexical scope → same-package siblings → named imports (full-suffix
match) → wildcard imports (non-recursive, exactly one extra segment) →
`cnc.*` auto-imports → fully-qualified name. Mirrors
`packages/semantics/src/resolver.ts` exactly.

### 4.4 `PackageInference`

```kotlin
object PackageInference {
    fun inferPackage(filePath: Path, projectRoot: Path): Qname
}
```

Rule: file at `<root>/foo/bar/baz.ttrm` → package `foo.bar`. Empty path → empty
package (the default-package case).

### 4.5 `PackageGraph`

```kotlin
class PackageGraph {
    fun addEdge(from: Qname, to: Qname)
    fun detectCycles(): List<List<Qname>>
}
```

### 4.6 `Validator`

```kotlin
class Validator {
    fun validate(definitions: List<Definition>, symbolTable: SymbolTable): List<ValidationDiagnostic>
}

data class ValidationDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val source: SourceLocation,
    val message: String,
)
```

Covers per-kind validators ported from `packages/semantics/src/validator.ts`:
cardinality strings, target shapes, type aliases, search-block sub-property
checks, drill_map arg validation.

### 4.7 `StockLoader`

```kotlin
object StockLoader {
    fun load(): List<Definition>                  // parses bundled cnc-stock-roles.ttrm
    fun stockQnames(): Set<Qname>                 // doubled cnc.cnc.role.<name> form (as stored)
}
```

`stockQnames()` returns the **doubled** `cnc.cnc.role.<name>` qnames — the form a
`SymbolTable` stores stock under (the transitional `isStockCnc` shape) and the
form `Resolver` resolves to. Each returned qname `get()`s a stored stock symbol.

Resource path: `/builtin/cnc-stock-roles.ttrm` inside the jar.
Stock content is the canonical source — ai-platform's
`BuiltinStockSource` becomes an adapter that wraps these in
`SourceSnapshot` shape.

---

## 5. Conformance harness — JSON dump schema

Both the TS and Kotlin parsers emit one JSON file per fixture into a temp
directory. The dump schema is independent of the TS/Kotlin AST type names so
the diff is naming-agnostic.

```json
{
  "schemaDirective": { "code": "db", "namespace": "dbo" } | null,
  "package": "cnc.role" | null,
  "imports": [
    { "target": "er.entity", "wildcard": true }
  ],
  "definitions": [
    {
      "kind": "table",
      "name": "QSUBJEKT",
      "description": "...",
      "descriptionLocalized": { "cs": "...", "en": "..." },
      "tags": ["audit"],
      "properties": {
        "primaryKey": ["IDSUBJEKT"],
        "columns": [ { "kind": "column", "name": "IDSUBJEKT", "properties": { "type": { "name": "int" }, "isKey": true } } ]
      }
    }
  ]
}
```

Normalization rules (applied identically by both runtimes):

0. **`descriptionLocalized` is present-only** (grammar 0.13) — emitted on the
   definition envelope only when the author wrote the localised map form, so a
   plain-string model dumps byte-identically to what 0.12 produced.
1. **No `SourceLocation` fields** anywhere — the harness compares structure,
   not positions.
2. **Object keys sorted alphabetically.**
3. **Kind discriminator** = lowercased TTR keyword (`table`, `column`,
   `er2db_entity`, `drill_map`, etc.) — sidesteps the
   `Er2dbEntityDef` / `Er2DbEntityDef` rename.
4. **Property names** = the TTR surface name (e.g. `primaryKey`, `valueLabels`)
   — not the Kotlin field name. Rename map maintained in
   [`AST-NAMING.md`](AST-NAMING.md).
5. `LocalizedStringValue.byLanguage` and `LocalizedStringListValue.byLanguage`
   serialise as plain objects.
6. Numbers as JSON numbers; booleans as JSON booleans; null as JSON null.

Diff tool: byte-equal comparison of the two JSON files per fixture, after
normalisation. Any difference fails the build.

#### 5.0.1 Known divergence — a property written TWICE on one definition

**Undefined behaviour, deliberately unpinned.** If the same property appears more
than once in one definition body (`description: "a" description: "b"`), the three
walkers disagree: **TS is last-wins** (a `for` loop that assigns on every match),
**Kotlin is first-wins** (`props.firstNotNullOfOrNull { … }`), **Python is
first-wins** (an `is None` guard) — with `world` the exception, aligned to
last-wins in all three at NLS-P10.

This is **not** description-specific: the same split applies to `tags`, `type`,
`version` and every other single-valued property, and it predates the conformance
harness. No fixture exercises it, so the harness cannot see it, and it is recorded
here rather than fixed because the fix is a ~30-site mechanical edit in two walkers
for input no model in any estate contains and no rule declares legal.

⚑ Whoever makes duplicate properties *meaningful* — a diagnostic, a merge rule, or
an explicit last-wins ruling — owns aligning all three walkers and adding the
fixture that pins it. Until then: do not rely on either behaviour, and do not "fix"
one target in isolation.

### 5.1 Semantics dump (Phase 2)

A second, parallel dump exercises the `ttr-semantics` layer. For every fixture,
both runtimes load the stock CNC vocab, build the symbol table, resolve every
reference and run the portable validator subset, then emit a normalised
`{ diagnostics, resolved }` object that must be byte-identical across TS and
Kotlin.

```json
{
  "diagnostics": [ "ttr/unresolved-reference" ],
  "resolved": [ "fact => cnc.cnc.role.fact" ]
}
```

Normalization rules:

1. **`resolved`** — one sorted `"<refPath> => <resolvedQname>"` string per
   reference that resolves (via `collectAllReferences` + `Resolver`). No source
   positions.
2. **`diagnostics`** — sorted diagnostic-**code** strings (`DiagnosticCode.id`).
   Severity is consumer policy and positions are implementation-specific (the
   Kotlin `Reference` value class carries no per-ref source), so neither is
   compared.
3. **Validator subset** — both sides run exactly `validateDocument` +
   `validateReferences` + `validateProject` + `validateImports`. The TS-only
   validators (file-ordering, `.ttrg`-graph, package-declaration,
   duplicate-search-property) are excluded because the Kotlin `ParseResult`
   lacks the structured inputs they need.
4. **Stock always loaded** — both sides upsert the bundled `cnc-roles` vocab
   under a `stock://` URI before resolving, so `cnc.*` auto-imports resolve.

Outputs: TS → `tests/conformance/out-ts-sem/`, Kotlin → the `ttr-semantics`
module's `build/conformance/kt-sem/`. Scripts: `pnpm --filter @modeler/conformance
dump-sem` and `diff-sem`; the Kotlin side is `SemanticsConformanceSpec`.

**Multi-document scenarios.** A single `.ttrm` file under `fixtures/` exercises
single-document resolution only — same-package siblings, named-import, and
wildcard-import *successes* are cross-file by nature. To cover them, a fixture may
instead be a **subdirectory** of `fixtures/` (e.g. `32-same-package/`,
`33-named-import/`, `34-wildcard-import/`) bundling several `.ttrm` files that are
loaded into **one** project symbol table before resolving; the combined
`{ diagnostics, resolved }` dump is written to `<dir>.json`. Both runtimes
discover these directories (`dumpSemDocs` / `SemanticsConformanceDump.dumpDocs`)
and the diff is byte-identical exactly as for single files. Each scenario includes
a same-named **decoy** def in a different package so the targeted resolution step
is load-bearing: without it, the fully-qualified fallback is ambiguous and the
reference goes unresolved. The parser dump (§5) ignores subdirectories — multi-doc
adds nothing to per-file AST structure.

---

## 6. GitHub Actions workflow contract

### 6.1 `publish.yml`

Triggers: tags matching `kotlin/v*`, `kotlin-parser/v*`,
`kotlin-semantics/v*`. Tag → modules table:

| Tag | Modules published |
|---|---|
| `kotlin/v<x.y.z>` | `:packages:kotlin:ttr-parser`, `:packages:kotlin:ttr-writer` (+ `:packages:kotlin:ttr-semantics` once Phase 2 lands) |
| `kotlin-parser/v<x.y.z>` | `:packages:kotlin:ttr-parser` only |
| `kotlin-semantics/v<x.y.z>` | `:packages:kotlin:ttr-semantics` only |

Workflow body (skeleton — full version lives in P1-7 deliverable):

```yaml
on:
  push:
    tags: ['kotlin/v*', 'kotlin-parser/v*', 'kotlin-semantics/v*']
permissions:
  contents: read
  packages: write
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '21' }
      - uses: gradle/actions/setup-gradle@v3
      - name: Resolve version
        id: ver
        run: |
          TAG="${{ github.ref_name }}"
          echo "version=${TAG##*/v}" >> "$GITHUB_OUTPUT"
          # ...tag → module list mapping...
      - run: ./gradlew --no-configuration-cache -Pversion=${{ steps.ver.outputs.version }} ${{ steps.ver.outputs.modules }}
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### 6.2 `conformance.yml`

Triggers: PR + push to `main`, paths-filtered to
`packages/grammar/**`, `packages/parser/**`, `packages/kotlin/**`,
`tests/conformance/**`. Two parallel jobs (Node + JVM) emit dump files into
a shared artifact; a third job downloads and diffs. Green = no walker drift.

### 6.3 `ci.yml` (existing — updated)

Add a Gradle test job alongside the existing pnpm jobs. Must remain green for
PR merges.

---

## 7. Backwards compatibility & deprecation policy

- **Phase 1 publishing baseline:** `org.tatrman:ttr-parser:0.1.0` and
  `org.tatrman:ttr-writer:0.1.0`. While version `<1.0.0`, minor bumps may
  contain breaking changes — documented in `CHANGELOG.md`.
- **Phase 1 → Phase 2 transition:** adding `ttr-semantics` is non-breaking.
- **Post-1.0 contract:** semver strict.
  - Patch: bugfix only; no API surface change.
  - Minor: additive — new defs, new properties, new diagnostic codes.
  - Major: removing/renaming any public type, removing a `DiagnosticCode`,
    or changing the JSON dump schema.

The conformance JSON dump schema (§5) is part of the public contract — once
1.0.0, schema changes require a major bump.

## 8. Reserved grammar surfaces — Platform (PL) batch (PL-P0.S1)

Two TTR-M grammar surfaces are **reserved** (not yet implemented) by the PL-P0 S1
amendment batch; full spec text, owners, and target phases are filed in
[`pl-grammar-work-items.md`](pl-grammar-work-items.md) (which also files the two
TTR-P surfaces, `params`/`on-failure`). Both are **additive** (minor bump,
`@grammar-version` `0.9 → 0.10` when the first lands) and are recorded here so the
TTR-M contract carries the reservation:

- **K `extends`-platform-world** (world composition) — `def world … { extends: "<platform-world-qname>" }`; platform world authoritative, project may add/extend, contradiction = compile error (`TTRP-LCK-004`); fingerprint-fed and order-insensitive. **Implements PL-P1.S4.**
- **`security` block** (H-1) — `own`/`classify`/`grant read`/`mask`; deny-overrides; **fingerprint-neutral** (excluded from the world/T6 semantic fingerprint, never alters emitted plans); one-way deterministic MIT generator → Rego. **Implements PL-P4.S3.**
