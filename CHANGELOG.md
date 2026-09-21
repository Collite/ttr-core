# Changelog

All notable changes to the published `org.tatrman:*` Kotlin artifacts are
recorded here. While versions are `< 1.0.0`, minor bumps may contain breaking
changes (see [`PUBLISHING.md`](PUBLISHING.md) → Semver discipline).

## Unreleased

- **`ttr-translator`** ⚑ **T-SQL string→datetime operand coercion (df-test 2026-09-21) — `DATEDIFF(day,
  '19000101', …)` / `DATEADD(day, 7, '2026-09-14')` validate.** The LLM lane's canonical "previous calendar
  week" idiom (`DATEADD(day, -7, DATEADD(day, (DATEDIFF(day, '19000101', CAST(GETDATE() AS date)) / 7) * 7,
  '19000101'))`) died at `ParseToRelNode` with `Cannot apply 'DATEDIFF' to arguments of type 'DATEDIFF(<INTERVAL
  DAY>, <CHAR(8)>, <DATE>)'`: our `DATEDIFF` (TF-P1.S1) and Calcite's `DATEADD` declare their operands as the
  `DATETIME` family, and Calcite's `TypeCoercionImpl` coerces a character operand only into `DATE`/`TIME`/
  `TIMESTAMP` — for `DATETIME` it inserted no cast at all, so an ISO literal failed just the same. New
  `framework/TsqlTypeCoercion` (bound through `SqlValidator.Config.withTypeCoercionFactory`): a character
  operand expected as `DATETIME` is cast to `TIMESTAMP` (what SQL Server's implicit conversion yields too),
  and T-SQL's unseparated `'YYYYMMDD'` literal is rewritten to `'YYYY-MM-DD'` first — Calcite's date parser
  reads only the dashed form, and the decode-side constant fold turns the cast into a typed TIMESTAMP
  literal (`'1900-01-01 00:00:00'` on the MSSQL unparse). A `datetime` column operand still gets no implicit
  cast; an integer anchor (`DATEDIFF(wk, 0, …)`) is still rejected; binary comparisons are untouched.
  `TsqlTypeCoercionSpec` runs the service path (parse → wire → decode → unparse).

- **`ttr-translator`** ⚑ **regression fix (MJ follow-up, df-test 2026-09-21) — `JoinerPhysical` sees the
  `TableScan` under MAP_TO_PHYSICAL's alias-at-boundary `Project` again.** 0.11.1 narrowed a join side's
  scan search to `Join`/`Filter` (so a derived table's hidden column could not become a bare-name ref that
  dies at decode) — but MAP_TO_PHYSICAL itself wraps a **query-backed** entity's body in a `Project`
  (`id_obchodního_kanálu := IDCENSKUP`), and every `*__filter` entity on the DF estate is query-backed.
  The FK fallback that had conditioned `FROM prodej JOIN obchodní_kanál` since v1.0 silently stopped and the
  join ran as a Cartesian product. A `Project` is now descended as a **name map** on the scan (`ScanRef`):
  only its bare column-ref expressions expose a name, the FK column is mapped through every projection up
  to the join, and an FK whose column no projection exposes is dropped from the candidates (a
  `JOIN_NO_RELATION` warning, never a decode-time `field not found`). `JoinerPhysicalSpec` (nested alias
  projects, unexposed column, computed expression) and `ModelJoinSpec` on `DfpJoinModel.handleQueryBacked()`
  — the df-test shape: query-backed entity, FKs derived from the relations, and no relations served — pin it.

- **`ttr-translator`** ⚑ **model joins (MJ, → `translator/v0.12.0`) — bare and chained `JOIN`s in the ER
  lane are conditioned from the model's declared relations.** `FROM kumulovaný_prodej JOIN dodací_místo
  JOIN zákazník JOIN produkt` — the way the LLM lane writes it — used to die at Calcite validation with
  `INNER, LEFT, RIGHT, FULL, or ASOF join requires a condition`; it now translates with the three `ON`s the
  E-R model implies (`kp ↔ dm`, `dm ↔ z`, `kp ↔ p`) and zero warnings. Two carriers on one policy:
  - **`codec/sql/ModelJoinRewriter`** (new, pre-validation `SqlShuttle`, ER catalog only): every
    `INNER`/`LEFT`/`RIGHT`/`FULL` join without `ON`/`USING` (not `NATURAL`) gets
    `<alias>.<attr> = <alias>.<attr>` from the model — join type preserved, SQL aliases used, `AND` over
    every pair of a composite relation. Each join in a chain is decided against the **whole** entity set
    of its other side, so `dodací_místo → zákazník` is found for the third entity, not `kumulovaný_prodej
    → zákazník`. Anything unresolvable gets `ON TRUE` and falls through to the wire carrier, which reports
    it. Explicit `ON`/`USING`, `NATURAL`, the comma list and `CROSS JOIN` are untouched. `explain` gains
    the stage `model_joins` (the rewritten statement, or `(no bare joins)`).
  - **`joiner/JoinPolicy`** (new) — the single decision: candidates `(l, r, rel)` over both sides; exactly
    one → resolved; none → `NoRelation`; two or more → `AmbiguousRelations` (no proximity tie-break); an
    entity occurring twice across the join (a self-join, or two aliases a candidate names) → ambiguous with
    `repeated` — a repeated alias that already has its `ON` does not block the *next* join (contracts §2
    C-1, review-097 R1); all pairs oriented to the sides. Path inference through bridge entities is deliberately *not* built (gated v2).
  - **`JoinerLogical` / `JoinerPhysical`** on the same rule: whole-side matching (the comma-form chain
    `FROM a, b, c` now resolves too), `and(eq, eq)` for composite relations / multi-column FKs, and a
    **key-name collision guard** — a bare `$L`/`$R` ref resolves to the *first* holder of a name, so a join
    whose key exists on two entities of one side stays unconditioned with the new
    `JoinerWarning.KeyNameCollision` instead of silently mis-joining. ⚠ **Behaviour changes:** (1) a side's
    entity/table set descends `Join` and `Filter` only — a `Project`/`Aggregate`/`Subquery` under a join side
    is no longer looked into (it could produce a bare-name ref to a column a derived table does not expose
    and fail at decode); such a join stays unconditioned and is reported as `JOIN_NO_RELATION` naming
    "a derived table" for that side (review-097 R2) — never a silent Cartesian product. (2) `JoinerLogical` used only the FIRST
    pair of a composite relation. (3) `JoinerPhysical` skipped multi-column FKs (`NoRelation`); they are now
    ANDed.
  - **Warnings ride on the result:** `ParseResult.Success.warnings` / `TranslateResult.Success.warnings`
    (`List<JoinerWarning>`, defaulted — source-compatible), reconciled to **one verdict per join** across
    the two stages (`JoinerWarnings.merge`: a join the physical carrier conditioned drops the logical
    carrier's stale warning; a join both warned on keeps the entity-level one). New
    **`joiner/JoinerMessages`** renders `code` / `severity` / `text`: `JOIN_NO_RELATION`,
    `JOIN_AMBIGUOUS_RELATIONS`, `JOIN_RELATION_WITHOUT_PAIRS` (INFO), `JOIN_KEY_NAME_COLLISION` — the names
    Golem already demotes on; the KDoc names `join_unresolved_cartesian` / `join_ambiguous_multiple_relations`
    / `join_relation_without_pairs` are gone. ⚠ `JoinerWarning` gained `KeyNameCollision` (exhaustive `when`s
    need a branch); `NoRelation` / `AmbiguousRelations` carry `leftEntities` / `rightEntities` (`sideA` /
    `sideB` are their first members); `AmbiguousRelations.repeated`. `JoinerLogical.RelationMatcher` and
    `findFirstScanPublic` are removed. `ParseResult.{Success,Failure}.modelJoinsSql` (debug, defaulted).
  Fixture `DfpJoinModel` (test-fixtures) + `ModelJoinSpec` (H1–H12 of the MJ plan), `ModelJoinRewriterSpec`,
  `JoinPolicySpec`, `JoinerMessagesSpec`, and the extended `JoinerLogicalSpec` / `JoinerPhysicalSpec` pin it;
  every pre-MJ golden (incl. the comma-form plan bytes) is unchanged. Design and decisions:
  `project/tatrman/features/ttr-translator/model-joins/`.

- **`ttr-translator`** ⚑ **behaviour fix — joins over renamed keys, and `datetime_value` read
  and written as ISO-8601.** Three defects that meet on the first entity join to reach execution
  on a model whose join keys are renamed between the ER and DB layers:
  - **`JoinerPhysical` is alias-aware.** MAP_TO_PHYSICAL aliases each renamed column back to its
    attribute name (DF-T05, alias-at-boundary), so above the scan only the alias exists — but the
    FK-based join condition named the physical columns and failed at unparse with
    `field [d_date_sk] not found; input fields are: [sk, …]`. Each operand now resolves through its
    own side's scan: the alias when that scan aliases the column, the column name otherwise. Models
    whose attribute names equal their column names produce the same plans as before.
  - **`JoinerLogical` no longer throws on a relation with no join pairs** (one bound only to its FK,
    with no `join:` list): it leaves the join for `JoinerPhysical` to condition from that FK and
    records **`JoinerWarning.RelationWithoutJoinPairs`** (wire code `join_relation_without_pairs`).
    ⚠ New member of a sealed interface — an exhaustive `when` over `JoinerWarning` needs a branch.
  - **`Literal.datetime_value` honours plan.proto's ISO-8601 in both directions.** Encode wrote
    Calcite's `value2` — epoch milliseconds for a TIMESTAMP (`"1735689600000"`); decode built a
    CHARACTER literal, so a date bound was compared to a date column as a string. A TIMESTAMP now
    encodes as an ISO instant (`2025-01-01T00:00:00Z`), a DATE as `2025-01-01`, a TIME as `12:30:00`;
    decode builds a typed TIMESTAMP / DATE / TIME literal (an offset is normalised to UTC) and rejects
    a value that is not ISO-8601 instead of degrading it to text. ⚠ Known gap, unchanged: a
    `datetime_value` in a **VALUES row** still decodes as text — `RelBuilder.values` routes cells
    through `RelBuilder.literal(Object)`, which does not accept Calcite's temporal types.
  `JoinerPhysicalSpec`, `JoinerLogicalSpec`, `DatetimeLiteralSpec` and `RenamedKeyJoinSpec` (the
  two-pass TransDSL → REL_NODE → SQL pipeline, asserting the unparsed join and bounds) pin them.

- **`ttr-translator`** ⚑ **behaviour fix — `EXTRACT` lowers to `DATEPART` on SQL Server.**
  `EXTRACT(<unit> FROM <datetime>)` reached the engine verbatim (Calcite's stock MSSQL
  dialect has no unparse rule for it) and died with error 195 `'EXTRACT' is not a
  recognized built-in function name`. Every date-part route lands on that node — the
  validator rewrites `YEAR(x)`/`MONTH(x)` to `EXTRACT`, the MD dot-path viaCalc lowering
  emits it, a free-SQL planner writes it as the portable form — so year/month grouping on
  any MSSQL estate failed. `MssqlSqlDialectWithFloatCast` now renders
  `DATEPART(<part>, <datetime>)` for both unit shapes (the validator's `TimeUnitRange`
  flag and the wire's `TimeUnit` flag); `DOY`/`DOW` map to `DAYOFYEAR`/`WEEKDAY`; a unit
  SQL Server cannot express (EPOCH, DECADE, …) fails at translate time with a clear
  message. Postgres/DuckDB are untouched. `ExtractLoweringSpec` pins it.

- **MH T1 + T3-data — the collision report, and the E-R reach in the archive
  (mention homonymy).** One word claimed by two refs (hartland's `prodejna`: the store
  *dimension*'s label and the Stores-*channel* alias pinned to the sales fact) is now
  reported at authoring time and described in the artifact.
  - **`ttr-lexicon`**: `TermNormalizer.fold` — a **second** normalization beside
    `normalize`, stripping combining marks. It is the resolver's *anchor index* key, so
    it is the only key that answers "would these two declarations meet at runtime?";
    `normalize` keeps diacritics and stays the merge key. `Reach(factRef, mandatory)` and
    `TargetFacts.reachedFrom` are new, and the archive label moves to
    **`ttr-lexicon-compiled/v3`**. Both fields are defaulted, so a v2 archive decodes
    unchanged and a v3 archive read by an MS-era reader simply ignores the field;
    `contentHash` is untouched (entry table only).
  - **`ttr-lexicon-compile`**: build warning **`RG-LEXC-004`** — a DECLARED row folds
    onto another target's row. Never fatal (a declared homonym can be deliberate) and
    never archive content. `targets[ref].reachedFrom` is derived from `def relation`:
    every fact whose `to:` is this ref, with `mandatory = cardinality.to`'s lower bound
    ≥ 1. Members and attributes carry none; a relation to a ref outside the model is
    skipped silently.
  - **`ttr-metadata`** ⚑ **behaviour fix**: a relation's authored `cardinality:` now
    reaches `Relation.cardinality`. It was hardcoded to `Cardinality(0, -1, 0, -1)` in
    the file loader, so every relation on every loaded model claimed both sides were
    optional. Nothing in this repo read the field, which is why it went unnoticed;
    `reachedFrom.mandatory` is its first consumer. Bounds parse as `1` · `0..1` ·
    `1..*` · `0..*` · `*` · `N`, with `-1` for unbounded (the previous default), and
    anything unrecognised degrades to `0..*`.
  - **`@tatrman/lint`** (TS twin): project-scoped rule
    `lexicon-form-collides-with-name` / `ttr/lexicon-form-collides-with-name`, warning
    by default, suppressed per term with the existing
    `// ttr-disable-next-line`. One fold, three implementations, one parity table
    (`packages/semantics/src/lexicon/fold-parity.json`). `lintDocument` no longer calls
    a directive naming a *project*-scoped rule unused — only `lintProject` can know.
  Detail: `project/server/features/mention-homonymy/` (design → contracts §1–§4).

- **Grammar `0.13` (additive) — localised `description:` (NLS-P10, ⚑GXP-D7).**
  `description:` accepts the localised map form (`{ en: "…", cs: "…" }`) everywhere it
  accepted a string, reusing the `localizedString` rule `displayLabel` already uses. No
  new token, no wire change: `meta.v1.ObjectDescriptor.description` stays a single
  `string` and Veles selects the locale server-side (D7 chain: requested → plain → `en`
  → first by language code → `""`). Every AST/model layer gains a sibling carrier
  beside the existing one — parser `Definition.descriptionLocalized`, metadata
  `ModelObject.descriptionLocalized` — and **every reader of `description` is
  unaffected**: the two forms are mutually exclusive and nothing folds a map.
  ⚑ **Construct these types by keyword, not by position.** The new parameter is
  declared immediately after `description` (Kotlin `Definition`/`ModelObject`
  implementors, `AreaRecord`, `ModelDescriptor`, `EngineDef`/`ExecutorDef`/
  `StorageDef`; Python's `description_localized` on the `Definition` base), so a
  positional call that passed `tags` fourth no longer compiles. The break is loud —
  `LocalizedStringValue` is not a `List<String>` — never silent, and every in-repo
  positional call site was migrated with the change. `ttr-writer` round-trips
  whichever form the author wrote. Two new lint
  codes (`ttr/localized-description-empty`,
  `ttr/localized-description-missing-locale`) cover the maps that serve nobody. The
  conformance dump gains a present-only `descriptionLocalized`. Consumers re-cut at
  `0.13.0` per the unified version policy; detail in
  [`packages/grammar/CHANGELOG.md`](packages/grammar/CHANGELOG.md).

- **Grammar `0.12` (additive) — the `searchable method:` match-method attribute
  (RV-P1.5, RV-31/RV-32).** `searchable` is the lexicon inclusion marker, so its
  boolean is now optional, and an optional `method: EXACT | TYPOS(n) | TOKENS`
  rides it. `fuzzy` still parses but is **deprecated**: the semantics layer maps
  it (`true` → `TYPOS(1)`, `false` → `EXACT`) and emits
  `ttr/search-fuzzy-deprecated`; `ttr/unknown-match-method` and
  `ttr/invalid-match-method-argument` validate the new attribute. All three codes
  are emitted alike by the TS, Kotlin and Python targets. `SearchHintsValue`
  gains `method` + `fuzzyAuthored` (**appended**, so positional construction stays
  source-compatible), and the conformance dump schema gains a `search.method`
  string. Consumers re-cut at `0.12.0` per the unified version policy; migration
  table in [`packages/grammar/CHANGELOG.md`](packages/grammar/CHANGELOG.md).
  One behaviour delta: a bare `searchable: true` with no `fuzzy` used to match
  exactly and now takes the RV-32 default `TYPOS(1)`.
- **Patch `0.8.1` (qname-redesign fix).** `classifyReference` / the resolver's
  cross-schema fall-back now strips the db schema handle when it follows the
  package + model (`pkg.db.dbo.Table.Col`), not only in the model-less
  `dbo.Name` form — so a fully-qualified cross-package db column/table reference
  resolves again under the v4.0 uniform keys. Mirrored in Kotlin + Python.
- **BREAKING — grammar 4.0 (qname redesign), published as `0.8.0`.** Address
  keywords are renamed so each names one concept: `def model <id>` →
  `def project <id>`; the file directive `schema <code> [namespace <id>]` →
  `model <code> [schema <id>]`; and `graph … schema <code>` → `graph … model
  <code>`. Canonical keys become uniform and package-first —
  `<package>.<model>.<schema?>.<kind>.<name>` — with the schema slot **db-only**
  (D6), `query`/`drillMap` folded into the `db` model (D14), and stock cnc
  de-doubled to `cnc.role.<name>` (D15). New `DiagnosticCode`s
  `ttr/schema-name-collision`, `ttr/unknown-package-schema`,
  `ttr/schema-on-logical-model`, `ttr/require-qualified-refs`. New public
  semantics API `buildCanonicalKey` / `modelForKind` / `namespaceForKind` /
  `kindOf` / `MODEL_CODES`. The conformance dump schema (§5) changes (every
  symbol key gains its kind segment), so this is a major grammar bump shipped as
  a breaking minor pre-1.0. Migrate content with `modeler migrate-qnames`. See
  `docs/features/qname-redesign/`.
- **Grammar 3.1 (additive — MD multidimensional model).** New `md` schema code
  and six logical `def` kinds — `domain` (re-added), `dimension`, `map` (now a
  real kind), `hierarchy`, `measure`, `cubelet` — plus four binding kinds under
  `schema binding`: `md2db_cubelet`, `md2db_domain`, `md2db_map`,
  `md2er_cubelet`. New `DOTDOT` (`..`) range token and MD body keywords; the
  shared `attribute` body gains optional `domain:`/`aggregation:` (per-schema
  validity is semantic). **Every new keyword is in `idPart`**, so no existing 3.0
  file changes meaning — this is additive. New data-only package
  `@tatrman/md-catalog` ships the v1 Time calc-map catalog (`MD_CALC_CATALOG`,
  `MD_CATALOG_VERSION` — the cross-repo sync key). Editor semantics: the full
  `md/*` diagnostic set (resolution, per-kind validators, calc-catalog
  type-checks, leaf/grain + hierarchy inference, cubelet + binding completeness)
  and LSP hover/definition/completion for MD symbols. See `docs/features/md/` and
  `docs/manual/en/15-md-model.md`. Publishing 3.1 (Maven/PyPI) and Kotlin/Python
  conformance are gated on the 3.0 release shipping first.
- **BREAKING — grammar 3.0 (MD Phase 0 legacy renames).** `schema map` →
  `schema binding`; inline `mapping:` → `binding:` (diagnostic
  `ttr/duplicate-mapping` → `ttr/duplicate-binding`); the `.ttrd` `domain` block
  is removed in favour of a plain `def area` definition; model files use the
  `.ttrm` extension. Consumers must bump the published `ttr-parser`
  (Maven/PyPI) and migrate their models (`modeler phase0`). See
  [`packages/grammar/CHANGELOG.md`](packages/grammar/CHANGELOG.md) → 3.0 for the
  full list and migration steps.
- **Fix — plain triple-strings that look like a tagged block no longer break
  parsing.** A `"""…"""` whose first line is a bare ASCII word + newline (e.g.
  `cs: """Ne␊1 = Ano"""` in a `description`/`valueLabels`) lexes as
  `TAGGED_BLOCK_LITERAL` (that token wins over `TRIPLE_STRING_LITERAL`). The
  0.5.0 grammar only accepted that token under `sourceText`/`definitionSql`, so
  it became a **parse error everywhere else** — and because the Kotlin parser
  empties a file's definitions on any error, downstream cross-package references
  then failed too. `stringLiteralForm` now also accepts `TAGGED_BLOCK_LITERAL`
  and reads it as a plain triple-string (tag word kept as text); only
  `embeddedBlock` peels the tag. Fixes ai-platform's `ModelTtrLoadSpec`
  (49 reconcile errors → 0). Regression fixture `53-tagged-like-plain-string`.

- **Grammar — `primaryKey` accepts bare column ids.** In addition to the legacy
  quoted-string list (`primaryKey: ["IDSTRED"]`), a table's `primaryKey` now
  accepts bare identifiers — a single id (`primaryKey: IDSTRED`) or a bare-id
  list (`primaryKey: [ID, KOD]`). All forms collapse to the same column-name
  `List<String>` in the AST, so this is **additive and backward-compatible** for
  `org.tatrman:ttr-parser` (no consumer changes required). The TS formatter
  re-emits the bare form; the Kotlin `ttr-writer` still emits the quoted form
  (both are valid and round-trip). `TTR.g4` change → not yet synced to the
  ai-platform vendored copy.

## 0.5.0 — unreleased

Phase 1 of the embedded-SQL feature (`docs/features/embedded-sql/`): the parser
now recognises a **tagged triple-string carrier** (`"""<tag>␊…"""`) for the
`sourceText` / `definitionSql` properties and exposes the embedded foreign
language structurally. Minor (additive) per `PUBLISHING.md`, but **source-breaking
for exhaustive `when` consumers** (see below).

- **`org.tatrman:ttr-parser:0.5.0`** — adds the top-level
  `PropertyValue.TaggedBlockValue` variant (`tag`, `language`, `dialect`,
  `value`, `tagSource`, `valueSource`, `indentWidth`, `source`) and the
  `LanguageKind` typealias. The walker tag-peels `"""<tag>␊…"""`, dedents
  (shared `Dedent` contract), strips one trailing newline, and resolves the tag
  via a `TAG_REGISTRY` (mirrors the TS table) into `language`/`dialect`.
  `QueryDef` / `ViewDef` gain `sourceTextBlock` / `definitionSqlBlock` carrying
  the structured value; the existing `sourceText` / `definitionSql: String?`
  are retained (= the extracted text), so **text-only consumers are unaffected**.
  Three new `DiagnosticCode`s (`UnknownLanguageTag`, `LanguageTagMismatch`,
  `DeprecatedLanguageProperty`); `language:` on `query` is now inferred from the
  tag and **soft-deprecated**. **Breaking:** any exhaustive `when (v:
  PropertyValue)` must add a `TaggedBlockValue` branch (the compiler flags it).
- **`org.tatrman:ttr-writer:0.5.0`** — `TtrRenderer` renders `TaggedBlockValue`
  back to `"""<tag>␊<value>␊"""` (round-trip guarantee against the parser,
  modulo `SourceLocation`); renders queries/views from the structured block when
  present. Untagged triple-strings render unchanged.
- `ttr-semantics` re-cut at `0.5.0` for the `kotlin/v0.5.0` bundle tag (no
  behavioural change; the embedded SQL semantics land in Phase 3).

Conformance: 11 new fixtures (`tests/conformance/fixtures/41–51`) lock the
value-extraction contract (DESIGN §4 golden cases C1–C11) byte-for-byte across
the TS and Kotlin parsers; a tagged `sourceText`/`definitionSql` now serialises
to `{ kind, tag, language, dialect, value }` in both dumpers.

## 0.4.0 — unreleased

The next published bundle. Minor (additive) per `PUBLISHING.md`.

- **`org.tatrman:ttr-writer:0.4.0`** — `TtrRenderer` now renders the v2.1 inline
  `mapping:` property on entity / attribute / relation defs (additive — the
  parser model + walker already populated these fields; only rendering was
  missing). Both surface forms emit: bare-id (`mapping: IDSKUPZBOZI`,
  `mapping: db.dbo.fk_artikl_produkt`) and block (`mapping: { target: …,
  columns: { … } }` on entities, `{ target: … }` on attributes, `{ fk: … }` on
  relations). Each `columns:` entry is a short bare-id (`attr: COL`) when the
  target is a plain column, else the object form. Round-trip is a fixed point on
  `samples/2.1/er.ttr`. The standalone `def er2db_*` renderers are unchanged.
  Unblocks the ai-platform legacy-YAML→TTR converter's inline-mapping output.
- **`org.tatrman:ttr-semantics:0.4.0`** — first published bundle to carry the
  **kind-derived schema/namespace defaults** (the `0.3.0 — unreleased` entry
  below). That work postdates the tagged `kotlin/v0.3.0` (which stops at
  `SymbolEntry.namespace`), so it first reaches consumers in `0.4.0`. ai-platform
  bumps `tatrman-modeler` to `0.4.0` to consume both of the above.
- `ttr-parser` re-cut at `0.4.0` for the `kotlin/v0.4.0` bundle tag (no
  behavioural change).

## 0.3.0 — unreleased

Delivers the modeler-side half of the resolver-consolidation follow-up (the
deferred part of grammar-master Phase 2.8): exposes the file namespace on every
symbol so ai-platform's downstream proto adapter can build its
`QualifiedName` triple without re-parsing the qname string.

- **`org.tatrman:ttr-semantics:0.3.0`** — `SymbolEntry` gains a `namespace:
  String` field, populated in `DocumentSymbols` from the file's declared
  namespace (`""` when none is declared — **not** the `nsOrKind` qname
  fallback). Purely additive: the resolver algorithm, qname construction, and
  stock handling are unchanged, so both conformance harnesses are unaffected.
- `ttr-parser` / `ttr-writer` re-cut at `0.3.0` for the `kotlin/v0.3.0` bundle
  tag (no behavioural change).

Also in `ttr-semantics:0.3.0`: **schema/namespace are now optional with defaults
derived from the object kind** (namespace already fell back to the kind; the
schema now does too). When a file has **no `schema` directive**, each
definition's qname uses the default schema for its kind — `entity`/`attribute`/
`relation` → `er`, the `db`-family → `db`, `er2db_*` → `map`, `role`/`er2cnc_role`
→ `cnc`, `query`/`drill_map` → `query` (`defaultSchemaForKind`). An explicit
`schema` directive still wins for the whole file. **No grammar change — no
grammar-version bump** (both `packageDecl` and `schemaDirective` were already
optional in `TTR.g4`; only schema-less resolution changed). TS and Kotlin emit
identical qname + diagnostic sets, locked in by new schema-less conformance
fixtures (`tests/conformance/fixtures/35–40`).

Consuming `0.3.0`, ai-platform completed the **resolver consolidation**: its
hand-maintained `ReferenceResolver`/`SymbolTable` are deleted and
`ReferenceResolutionPass` now resolves through a thin adapter over
`org.tatrman.ttr.semantics.{SymbolTable, Resolver}` (proven equivalent by a
differential parity harness). This closes the deferred half of grammar-master
Phase 2.8 — a future grammar/version bump now reaches ai-platform as an
`org.tatrman:*` version-ref change with no hand-written semantics edits
(rehearsed). See [`docs/grammar-master/resolver-consolidation/`](docs/grammar-master/resolver-consolidation/).

## 0.2.1 — 2026-06-09

Reconciles the bundled stock CNC vocabulary (`StockLoader` /
`builtin/cnc-stock-roles.ttr`) with ai-platform's canonical content ahead of the
ai-platform stock-source switch: each `def role` now carries a localized
`label { cs, en }` and ai-platform's descriptions (tags dropped). Names are
unchanged, so resolution and the conformance harness are unaffected. This makes
the published artifact a true single source of truth for stock roles — including
their display labels — so ai-platform's `BuiltinStockSource` can delegate to
`StockLoader.load()` without losing data.

## 0.2.0 — 2026-06-09

Phase 2 of grammar-master. Adds the semantics artifact; replaces ai-platform's
hand-rolled resolver / symbol-table / stock-loader equivalent.

- **`org.tatrman:ttr-semantics:0.2.0`** — symbol table, six-step reference
  resolver, package inference + dependency graph (Tarjan cycle detection),
  per-kind + cross-reference `Validator`, and the bundled stock CNC vocabulary
  (`StockLoader`, `builtin/cnc-stock-roles.ttr`). Faithful Kotlin port of
  `packages/semantics/src/`. Depends on `org.tatrman:ttr-parser` (api).
- `ttr-parser` / `ttr-writer` re-cut at `0.2.0` for the `kotlin/v*` bundle tag
  (no behavioural change since `0.1.0`).

Conformance: a second harness (`SemanticsConformanceSpec` / `dump-sem` /
`diff-sem`) verifies the Kotlin resolver + validator against the TypeScript
semantics layer byte-for-byte (resolved-qname + diagnostic-code sets) across
the shared fixtures.

## 0.1.0 — 2026-06-03

Phase 1 of grammar-master. First published release of the modeler-owned Kotlin
parser stack, generated from the canonical `packages/grammar/src/TTR.g4` (v2.2).

- **`org.tatrman:ttr-parser:0.1.0`** — ANTLR-generated parser + typed AST
  (`TtrLoader`, `ParseResult`, the `Definition` hierarchy, `PropertyValue`,
  `SourceLocation`, `DiagnosticCode`, `Dedent`). Depends only on
  `org.antlr:antlr4-runtime` + `org.slf4j:slf4j-api`.
- **`org.tatrman:ttr-writer:0.1.0`** — deterministic AST → TTR-source renderer
  (`TtrRenderer`) with a round-trip guarantee against the parser. Depends on
  `org.tatrman:ttr-parser`.

Conformance: the Kotlin parser is verified byte-for-byte against the TypeScript
parser across the shared fixture set (`conformance.yml`).
