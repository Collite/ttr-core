# TTR grammar changelog

Versioning follows `X.Y`:

- **X** — breaking change. New required syntax, removed/renamed constructs, or
  changes that cause previously-valid `.ttr` files to fail to parse.
- **Y** — additive change. New optional constructs, syntactic sugar, parser
  bug fixes, or any change that keeps previously-valid files parsing.

The canonical version lives in the `// @grammar-version:` marker at the top of
`src/TTR.g4`. The prebuild script extracts it into
`src/generated/version.ts`, re-exported from `@tatrman/grammar` as
`TTR_GRAMMAR_VERSION`.

## 0.14 — 2026-10-10

**Additive (named row schemas — TTR-P action displays).** Every previously-valid `0.13`
file still parses unchanged; no token is added and nothing is newly reserved.

1. **New top-level def kind `def schema <name> { … }`**: a named, ordered row shape.
   `objectDefinition` gains one alternative `SCHEMA id schemaDef`;
   `schemaProperty : descriptionProperty | tagsProperty | columnsProperty`.
2. **No new sub-production.** The column list REUSES `columnsProperty` /
   `columnDefList` / `columnProperty`, so a column is written exactly like a table
   column — `def column note { type: text, optional: true }` (an optional column is
   `optional: true`, as everywhere in TTR-M).
3. **The world-storage `def schema` is unchanged** — a separate production inside
   `def storage { … }`; the two never meet.
4. **Tier-neutral**: the kind maps to the `db` model by default (the `area` /
   `query` precedent) and is legal beside any `model` directive. It is not a
   loadable object. `ttr-metadata` surfaces it as `Model.rowSchemas`
   (`RowSchemaRecord`, keyed `<package>.<name>`); a re-declaration of one qualified
   name is `ttr/duplicate-schema`.
5. **Consumer: TTR-P.** A program brings a schema into scope with `import <pkg>.*`
   (like entities) and holds `display(<name>)` rows to it (`TTRP-DSP-001..004`).
6. **All three targets** (TS / Kotlin / Python) parse it to `SchemaDef` (`kind:
   "schema"`, `columns`); the conformance dump gains the `schema` kind (fixture 69),
   every pre-existing golden is byte-unchanged. `ttr-writer` renders it; the TS
   formatter prints it; the property map advertises `schema: description, tags,
   columns`.

```ttrm
package shop.actions

def schema notify {
    description: "Send a notification to a recipient",
    columns: [
        def column recipient { type: text },
        def column order_id  { type: int },
        def column amount    { type: decimal, optional: true },
    ]
}
```

Consumers re-cut at `0.14.0` per the unified version policy.

## 0.13 — 2026-08-13

**Additive (NLS-P10 — localised `description:`; ⚑GXP-D7).** Every previously-valid
`0.12` file still parses unchanged, no token is added and nothing is newly reserved.

1. **`description:` accepts the localised map form** beside the string form:
   `descriptionProperty : DESCRIPTION propSep? ( stringLiteralForm | localizedString )`.
   `description: { en: "Product", cs: "Produkt" }` is now legal everywhere
   `description: "Product"` already was — every def kind carrying
   `descriptionProperty`, at every nesting depth (table/column, entity/attribute,
   query, role, the md family, the world family, lexicon entries, …).
2. **No new production.** `localizedString` is the rule `displayLabel` / `label` /
   `value_labels` already use — one localised shape across the language.
3. **Two carriers in every AST, never one.** The plain form keeps feeding
   `description`; the map feeds a new sibling (`descriptionLocalized` in TS/Kotlin,
   `description_localized` in Python). Exactly one of the pair is populated, so every
   existing consumer of `description` is byte-unchanged, and the writer round-trips
   the form the author wrote.
4. **Locale selection is a READER's job.** The parsers do not fold a map to one
   locale. Veles resolves `meta.v1.ObjectDescriptor.description` through the D7
   chain — requested locale → plain form → `en` → first entry by language code → `""`
   — and the wire is unchanged (`description` stays a single `string`).
5. **An empty map (`description: {}`) parses** to an empty entry set; it is a lint
   warning (`ttr/localized-description-empty`), not a parse error, because the parser
   stays mechanical and the D7 chain already degrades it to `""`. A second lint rule
   (`ttr/localized-description-missing-locale`) fires when a map omits the locale the
   unit header declares (`model … locale cs`).
6. **Conformance dump schema** gains a present-only `descriptionLocalized` object on
   the definition envelope; a plain-string model dumps byte-identically to `0.12`.

Consumers re-cut at `0.13.0` per the unified version policy.

## 0.12 — 2026-08-04

**Additive (RV-P1.5 — the `searchable method:` match-method attribute; RV-31/RV-32).**
Every previously-valid `0.11` file still parses, and nothing is newly reserved: the
boolean on `searchable` only becomes *optional*, `method:` is a new optional
attribute, `fuzzy` still parses, and `method` joins `idPart` as an id fragment.
**One previously-valid form does change meaning** — a bare `searchable: true`, see
the behaviour delta below. Resolving effort: `project/kantheon/features/resolving/`.

1. **`searchable` is the lexicon INCLUSION marker** (RV-31 source (3): "include
   this carrier's content in the lexicon"), so its boolean is now optional —
   bare `searchable` means included. `searchable: true` / `searchable: false`
   are unchanged.
2. **New optional match-method attribute** (RV-32): `searchable method: EXACT |
   TYPOS(2) | TOKENS`, combinable with the boolean (`searchable: true method:
   TOKENS`). `matchMethodAttr : METHOD propSep? matchMethodValue`;
   `matchMethodValue : id ( LPAREN NUMBER_LITERAL RPAREN )?`. The method NAME
   stays an un-minted bare id and its argument a `NUMBER_LITERAL` — the
   `allocation: proportional` precedent, so the vocabulary, the arity rule (only
   `TYPOS` takes an argument) and the distance range are all SEMANTIC. **No lexer
   rule changed**, so the MD dot-path numeric-literal invariant is untouched.
3. **New lexer token `METHOD`**, added to `idPart`.
4. **`fuzzy` is DEPRECATED, not removed.** It still parses; the semantics layer
   maps it (`fuzzy: true` → `method: TYPOS(1)`, `fuzzy: false` → `method: EXACT`)
   and emits `ttr/search-fuzzy-deprecated` with the migration hint. Two further
   codes validate the new attribute: `ttr/unknown-match-method` and
   `ttr/invalid-match-method-argument`. All three are mirrored across the TS,
   Kotlin and Python targets and ride the portable conformance subset.

### Migrating a model from `fuzzy` to `method`

| 0.11 | 0.12 |
|---|---|
| `search { searchable: true, fuzzy: true }` | `search { searchable method: TYPOS(1) }` |
| `search { searchable: true, fuzzy: false }` | `search { searchable method: EXACT }` |
| `search { searchable: true }` | `search { searchable }` — **behaviour delta, see below** |

**The one behaviour delta.** Under 0.11 a bare `searchable: true` with no `fuzzy`
matched exactly; under RV-32 an included carrier that declares no method takes
the **default `TYPOS(1)`**. Author `searchable method: EXACT` where exact
matching was the intent. Everything an author wrote *explicitly* — including
`fuzzy: false` — keeps its meaning.

**What the migration does NOT change.** An authored non-`EXACT` method means the
same thing to the metadata model as `fuzzy: true` did: `SearchHints.fuzzy` is the
derived "indexed for fuzzy matching" flag, and `ttr-metadata` folds `TYPOS(n)` and
`TOKENS` into it (`Source.kt`'s `toSearchHints`). So a carrier migrated from
`fuzzy: true` to `searchable method: TYPOS(1)` keeps its place in
`ListObjects(fuzzy_only=true)` and in lex-matcher's data-value index. The RV-32
*default* is deliberately not folded in — a bare `searchable` stays out of that
index exactly as it did under 0.11, which is what keeps the delta above latent
until a consumer opts into it.

**`TYPOS(n)` accepts `n` in `1..3`**, the same range `ttr-lexicon` accepts
(`RG-LEX-002`); anything else is `ttr/invalid-match-method-argument` and falls
back to `TYPOS(1)`.

`method` is not a reserved word: `def entity method { … }` still parses.

## 0.11 — 2026-07-23

**Additive (PL-P4.S3 — the H-1 `security { }` block).** No previously-valid `0.10`
file changes meaning; `security` is a new optional document-level block and its
verb keywords all join `idPart` (nothing common is newly reserved as anything but
an id fragment). Platform contracts §11.

1. **New document-level, repeatable `security { … }` block** (sibling to
   `definition`) — declarative access sugar over model objects. `document` gains
   `(definition | securityBlock)*`; `securityBlock : SECURITY LBRACE
   (securityStatement (COMMA? securityStatement)* COMMA?)? RBRACE`.
2. **`securityStatement` is STRUCTURED** — exactly the four §11 verbs:
   `own <object>: <owner>` · `classify <object>: <classification>` ·
   `grant <privilege> on <object> to <role>` · `mask <object>`. Deliberately NOT
   the free-form `object_` bag of `semantics`/`lexicon`: an unknown verb and a
   row-level predicate must be hard parse errors (contracts §11 — "row-level
   predicates stay Rego-side in v1"), which a permissive body cannot enforce.
   Object refs are plain `id`s (dotted ids parse: `order_line.customer_email`);
   reference resolution is semantic + **advisory** (never a compile block, H-3).
3. **New lexer tokens** `SECURITY`, `OWN`, `CLASSIFY`, `GRANT`, `MASK`, `ON` — all
   added to `idPart` (the WORLD precedent; `TO` already present). So `own`/`grant`/
   `mask`/`classify`/`on`/`security` stay usable as ordinary id fragments / keys.
4. **Fingerprint-neutral**: `security` blocks never enter `WorldFingerprint` / the
   T6 semantic hash and never alter emitted plans (H-1 pin 2). Consumed one-way by
   `ttr-security-gen` → OPA/Rego.

## 0.10 — 2026-07-20

**Additive (MD dot-path S5-B.2 — writeback spread strategy).** No previously-valid
`0.9` file changes meaning; `allocation` is a new optional property and `ALLOCATION`
is added to `idPart` (nothing common is newly reserved as anything but an id fragment).

1. **New optional `allocation:` property on `md2db_cubelet`** — declares the writeback
   *spread* strategy (contracts R21, MDS5). `allocationProperty : ALLOCATION propSep?
   allocationValue`; `allocationValue : id | object_` — `allocation: proportional`
   (uniform, applies to every spread dimension) or `allocation: { time: equal,
   product: proportional }` (per-dimension). Mirrors `journalingProperty`/
   `aggregationValue`.
2. **New lexer token** `ALLOCATION`, added to `idPart`. Also accepted on
   `md2er_cubelet` as a permissive parse superset and **rejected in semantics** as a
   physical property (the `shape`/`measures`/`journaling` precedent — "parser stays
   mechanical").
3. The strategy **value** (`equal`/`proportional`) stays an un-minted bare id,
   validated in semantics. Spread only became *legal* with a declared strategy;
   MDS5's "spread emits the declared strategy or fails, never a default" is a
   semantic/lowering rule, not grammatical.
4. **Retroactively recorded (review-071 T-P3): `publish: members` on `def domain`.**
   The `publishProperty : PUBLISH propSep? MEMBERS` opt-in (MD dot-path §1.4, `def
   domain X { publish: members }`) and the `PUBLISH` lexer token were added during the
   S0 dot-path work but never appeared in this changelog (the `0.9` "renumber only"
   entry predates them). Additive — `PUBLISH` joins `idPart`, so nothing common is
   newly reserved. No behaviour change; this entry only closes the documentation gap.
   The separate **TTR-P grammar** (`src/TTRP.g4`) additions from the same arc — `INT` +
   parser-composed `floatLiteral`, `mdPath`, `DOTDOT`, and the `:=`/`+=`/`-=`
   `cubeletStmt` — are versioned by the TTR-P integer-cut process (docs/grammar-master,
   S6), not this `X.Y` line; its `@grammar-version` marker stays `0.1` until that cut.

## 0.9 — 2026-07-16 (renumber only, no grammar change)

Version scheme **renumbered `4.4 → 0.9`** so grammar and artifacts share one line:
grammar `<major.minor>` is the artifact (`ttr-parser`/protos/`@collite/ttr-grammar`)
`<major.minor>`; the patch digit is code-only. No grammar construct changed — `0.9`
is byte-identical to what was tagged `4.4`. Earlier entries keep their original
numbers as historical record.

## 4.4 — 2026-07-13

**Additive (TTR-M lexicon surface — RG-P4, RS-9..11).** The canonical vocabulary
form. No previously-valid 4.3 `.ttr`/`.ttrm`/`.ttrg` file changes meaning — every
new keyword is also added to `idPart`, so nothing common is newly reserved as
anything but an id fragment.

1. **New model code** `lexicon` (`model lexicon`) — `modelCode` gains `| LEXICON`.
2. **Three new def kinds** `term` / `pattern` / `example`, sharing one permissive
   `lexiconEntryDef` body with optional `for` (target ref er/db/md) + `forms` (term
   surface forms) | `match` (pattern regex) | `text` (example text). Per-kind
   required-field validity is SEMANTIC (`@tatrman/semantics`), not grammatical —
   the "parser stays mechanical" invariant.
3. **Unit-level locale header** `model lexicon locale <id>` — `modelDirective`
   gains an optional `( LOCALE id )?` (the `db … schema` precedent slot).
   Locale-only-on-lexicon is enforced in semantics.
4. **Inline `lexicon { … }` sugar** (free-form `object_` body, the semantics-block
   precedent) attachable to data-bearing carriers: er/db `table`/`column`/`entity`/
   `attribute` AND md `measure`/`dimension`/`cubelet` (RS-10 makes md kinds legal
   carriers). Desugars to canonical `term` entries in semantics.
5. **New lexer tokens** `LEXICON`, `TERM`, `PATTERN` (distinct from `PATTERNS`),
   `EXAMPLE` (distinct from `EXAMPLES`), `FOR`, `FORMS`, `MATCH`, `LOCALE` — all
   added to `idPart`. `text:` reuses `TEXT`; the inline `terms:` key stays an
   un-minted bare object key (validated in semantics). See
   `docs/features/resolution/plan/contracts.md` §7.
6. **(S2 amendment)** `PATTERNS`, `EXAMPLES` and `ALIASES` (search/naming
   sub-property keywords) added to `idPart` so inline `lexicon { patterns: […],
   examples: […] }` and the widened `valueLabels { … aliases: [ … ] }` keys parse
   (a free-form `object_` key must be an `idPart`). Additive — the `search {}`
   block keeps using them as typed sub-properties.
7. **(S2, A4-β / RS-12)** `valueLabels` per-value alias widening. `valueLabelEntry`
   now takes a permissive `valueLabelValue : LBRACE ( valueLabelField … )? RBRACE`
   where `valueLabelField : id propSep? ( localizedString | listOfStrings |
   stringLiteralForm )`. This accepts BOTH the legacy `{ cs: "Aktivní", en:
   "Active" }` label AND the widened `{ label: { cs: "Aktivní" }, aliases: ["živý"]
   }` form — the walker recognises the widened shape by a `label:` field, else
   reads the flat locale fields (parser mechanical; walker interprets). Additive:
   legacy `valueLabels` files walk + dump byte-unchanged (aliases present-only).

## 4.3 — 2026-07-07

**Additive (`.ttrl` view-state sidecar — TTR-P Phase 5.2, C1-c-iii).** The
family-wide `.ttrl` sidecar body is now hosted by the TTR-M grammar (not a fresh
`.g4`, not in `TTRP.g4`), as a **separate entry rule** the Kotlin `ttr-parser`
dispatches to on the `.ttrl` extension. No previously-valid 4.2 `.ttr`/`.ttrm`/
`.ttrg` file changes meaning — the new rules are unreachable from `document`.

1. **Two new lexer tokens** `TTRL` (`'ttrl'`), `CANVAS` (`'canvas'`), both added
   to `idPart` so they stay usable as cross-ref / identifier fragments (the
   `WORLD`/`GRAPH` precedent — no common word is newly reserved).
2. **New parser rules** `ttrlDocument : TTRL NUMBER_LITERAL ttrlCanvas* EOF`;
   `ttrlCanvas : CANVAS id LBRACE ttrlProperty* RBRACE`; `ttrlProperty` keys stay
   generic `id` (skin/mode/nodes/collapsed validated in the parser wrapper, not the
   grammar). `nodes` uses a string-keyed `ttrlNodeMap` of `{ x, y }` coords;
   `collapsed` reuses `listOfStrings`. The `edges:` bendPoints slot is intentionally
   NOT in the grammar (reserved, not v1 — C1-c-iii).
3. Promotes the v1.1 in-file `layout` block concept to a standalone document (the
   TTR-M `.ttrl` migration off the v1.1 layout block is a separate post-v1 arc).

## 4.2 — 2026-07-06

**Additive (`semantics { … }` block — grounding Phase 1).** New free-form
`semantics` block property enabling deterministic grounding (time / geography /
money semantic roles) in ai-platform. No previously-valid 4.1 file changes
meaning. See `docs/features/semantics-block/README.md`.

1. **New lexer token `SEMANTICS`** (`'semantics'`), added beside `SEARCH`.
2. **New parser rule `semanticsBlockProperty : SEMANTICS propSep? object_ ;`** —
   attachable on exactly four kinds: `table`, `column`, `entity`, `attribute`.
   NOT on view/relation/query/role. The body is a free-form `object_`
   (`attributesMapProperty` precedent); ALL shape/vocabulary checking lives in
   semantics ("parser stays mechanical"), so new roles need no future grammar
   bump.
3. **`idPart`** gains `SEMANTICS` (4.1 `WORLD` precedent) — keeps files using
   `semantics` as an identifier (e.g. `def attribute semantics { … }`) parsing;
   4.2 stays honestly additive.
4. **Fixtures:** `tests/conformance/fixtures/59-semantics.ttrm` (golden roster —
   entity + attribute + table + column attachments, every role once);
   parser-reject roster under `semantics-negative/`.
5. **Downstream surface:** the validated result reaches consumers via
   `ttr-metadata`'s typed model (`Entity`/`DbTable` `.semanticsKind`,
   `Attribute`/`DbColumn` `.semantics`) and the five `MetadataQuery` grounding
   accessors — see `docs/ttr-metadata/architecture/contracts.md` v1.5.

## 4.1 — 2026-07-05

**Additive (world model — ttr-metadata M0, D-d-α).** New deployment-world model
code; no previously-valid 4.0 file changes meaning.

1. **New model code `world`** (`model world`). `modelCode` gains `| WORLD`.
2. **New top-level def kind `def world <id> { … }`** — the ONLY world def kind.
   `engine`/`executor`/`storage`/world-`schema` exist ONLY nested inside a world
   (grammar-enforced nesting; meaningless outside a world). Per-model validity of
   `def world` itself (world defs only in `model world` files) is semantic.
3. **New lexer tokens:** `WORLD`, `ENGINE`, `EXECUTOR`, `STORAGE`, `EXTENDS`,
   `HOSTS`, `STAGING`. `via:` reuses the 3.1 `VIA` token; `type:`/`version:` reuse
   `DATA_TYPE`/`VERSION`.
4. **New parser rules:** `worldDef`, `worldMember`, `worldProperty`, `engineDef`,
   `executorDef`, `enginePartProperty`, `storageDef`, `storageProperty`,
   `extendsProperty`, `hostsProperty`, `stagingProperty`, `viaProperty`,
   `worldSchemaDef`, `worldSchemaField`. engine/executor/storage bodies list typed
   props first, then a free-form `propertyEntry` fallback (T6 β manifest data —
   transported opaque, interpreted by TTR-P Stage 2.2 only; MD5).
5. **`idPart`** gains `WORLD`, `ENGINE`, `EXECUTOR`, `STORAGE`, `VERSION`.
   `EXTENDS`/`HOSTS`/`STAGING` are deliberately excluded so a malformed
   `staging: "x"` / `hosts: ["x"]` / `extends: "x"` is a hard parse error rather
   than falling through to `propertyEntry` (negative fixtures
   `world-negative/neg-02,03,05`).
6. **Fixtures:** `tests/conformance/fixtures/57-world.ttrm` (golden roster),
   `58-world-extends.ttrm` (overlay input); parser-reject roster under
   `packages/kotlin/ttr-parser/src/test/resources/world-negative/`.

## 4.0 — 2026-06-27

**BREAKING (qname redesign).** Renames the three keywords that name a TTR address
so each word means exactly one concept. Previously valid 3.x files must be
migrated (`@tatrman/migrate`). See `docs/features/qname-redesign/`.

1. **`def model <id>` → `def project <id>`.** New `PROJECT` lexer token; the
   whole-artifact header (identity + `version` + `description` + `tags`) is the
   *project* (one per repo). Rules `modelDef`/`modelProperty` → `projectDef`/
   `projectProperty`; `objectDefinition` alt `MODEL id …` → `PROJECT id …`.
2. **Type directive `schema <code> [namespace <id>]` → `model <code> [schema <id>]`.**
   The freed `MODEL` token now names the model type; the `SCHEMA` token now
   carries the namespace/binding id; the `NAMESPACE` token is **deleted**. Rule
   `schemaDirective` → `modelDirective`, `schemaCode` → `modelCode`.
3. **Graph header property `schema <code>` → `model <code>`** (`graphSchemaProperty`
   → `graphModelProperty`).
4. The freed keywords `schema`, `model`, `project` stay in `idPart` so they remain
   usable as cross-reference / identifier fragments.

`modelCode` keeps all alternatives (`DB | ER | BINDING | QUERY | CNC | MD`) — the
parser stays mechanical. The semantic `ModelCode` set drops `query` (a `def query`
folds into the `db` model, schema-bound) and `cnc` loses its namespace echo; that
folding lives in `@tatrman/semantics`, not the grammar.

## 3.0 — 2026-06-24

**BREAKING (MD Phase 0 legacy renames).** Frees the `map` / `mapping` / `domain`
vocabulary for the upcoming MD model and disambiguates model files. Previously
valid 2.x files must be migrated (the `modeler phase0` CLI automates the steps).

1. **`schema map` → `schema binding`.** New `BINDING` lexer token; `schemaCode`
   now alternates `DB | ER | BINDING | QUERY | CNC`. `MAP` is removed from
   `schemaCode` (so `schema map` no longer parses) but the `MAP` token is
   retained in `idPart` and reserved for the future MD `def map` value-set
   keyword. The `er2db_*` defs are unchanged — only the schema code they live
   under is renamed (qnames move `…map.er2db…` → `…binding.er2db…`).
2. **Inline `mapping:` → `binding:`.** The v2.1 inline mapping property on
   `def entity` / `def attribute` / `def relation` is renamed, reusing the
   `BINDING` token; the `MAPPING` token is removed. The diagnostic code is
   renamed `ttr/duplicate-mapping` → `ttr/duplicate-binding`.
3. **`domain` block / `.ttrd` file kind removed → `def area`.** The top-level
   `domain <id> { … }` block and the `.ttrd` file kind are deleted. Subject
   areas are now a plain `def area <id> { description?, tags?, packages: [...],
   entities: [...] }` definition that lives in ordinary model files, coexists
   with other defs, and registers a resolvable symbol. New `AREA` token; the
   `DOMAIN` token and `domainBlock`/`domainProperty` productions are removed
   (`PACKAGES` / `ENTITIES` tokens retained for the area body). `domain` is
   freed for the future MD value-set keyword. The grouping is renamed end-to-end:
   lint codes `ttr/domain-*` → `ttr/area-*` (`area-empty`, `area-member-not-found`,
   `duplicate-area`, `area-redundant-member`) and the resolved-packages artifact
   key `domains` → `areas`.
4. **Model file extension `.ttr` → `.ttrm`** ("Tatrman Model"). Grammar-external
   (file detection only); `.ttrg` (graph) is unchanged.

Migration: `modeler phase0 <project-root>` renames `*.ttr` → `*.ttrm`, rewrites
`schema map` → `schema binding` and inline `mapping:` → `binding:`, and converts
`.ttrd` `domain { … }` blocks to `def area { … }` in `.ttrm`.

## 2.3 — 2026-06-20

Additive: the `.ttrd` **domain** file kind (Packages & Domains PD2). Editor-only
— ai-platform's loader does NOT load `.ttrd` (consumed by the agent registry +
resolved-packages artifact). Backward compatible — every 2.2 file parses unchanged.

- Added `DOMAIN`, `PACKAGES`, `ENTITIES` lexer tokens; extended `idPart` to keep
  them usable as identifier components.
- Added rules: `domainBlock`, `domainProperty`, `domainPackagesProperty`,
  `domainEntitiesProperty`; `document` now accepts a `domainBlock` alongside
  `graphBlock`.
- File-kind (`.ttrd` ⇔ exactly one domain block) is enforced by semantics, not
  grammar (mirrors `.ttrg`).

## 2.2 — 2026-06 (additive)

Additive: `def drill_map` ("click this row → run that pattern"). Backward
compatible — every 2.0/2.1 file parses unchanged.

- Added `DRILL_MAP`, `ARGS`, `DISPLAY`, `OVERRIDE` lexer tokens; extended `idPart`.
- Added rules: `drillMapDef`, `drillMapProperty`, `argsProperty`,
  `displayProperty`, `overrideProperty`, `drillArgEntry`.

## 2.1 — 2026-05-27

Additive: inline mapping shorthand for er2db_* on entity/attribute/relation.
Backward compatible — every 2.0 file parses unchanged.

- Added `mapping:` property on `def entity`, `def attribute`, and `def relation`.
- Added `MAPPING` lexer token; extended `idPart` to keep it usable as an identifier component.
- Added rules: `mappingProperty`, `mappingValue`, `mappingBlock`,
  `mappingBlockProperty`, `mappingColumnsProperty`, `mappingColumnMap`,
  `mappingColumnEntry`, `mappingColumnValue`.
- Relaxed `targetProperty` to accept a bare id (`target: <ref>`) in addition to
  the existing object form (`target: { column: <ref> }`).
- Reuses existing `COLUMNS` and `FK` tokens; no new keywords beyond `mapping`.

## 2.0 — 2026-05-27

Major version bump promoting the v1.1 "packages" work to a stable grammar
version. Breaking because the document rule now accepts new top-level
constructs and several new reserved keywords were added.

- Added `package <qualifiedName>` top-level declaration.
- Added `import <qualifiedName>[.*]` top-level declaration.
- Added `graph <id> { ... }` block with `schema`, `objects`, and `layout`
  properties (replaces the v1 `.modeler/layout.ttrl` sidecar).
- New lexer tokens: `PACKAGE`, `IMPORT`, `GRAPH`, `OBJECTS`, `LAYOUT`, `STAR`.
- New parser rules: `packageDecl`, `importDecl`, `graphBlock`,
  `graphProperty`, `graphSchemaProperty`, `graphObjectsProperty`,
  `graphLayoutProperty`, `qualifiedName`.
- `idPart` extended so the new keywords remain usable inside cross-reference
  components.

## 1.x

Pre-versioning baseline. See git history under `packages/grammar/src/TTR.g4`.
