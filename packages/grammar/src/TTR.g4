// =============================================================================
// TTR (Tatrman) grammar
//
// @grammar-version: 0.21
// 0.21 (MW-53): lexical indexSecurity; query skipSecurity; parameter
// defaultValue and binds. Typed optional declarations; execution stays host-owned.
// 0.20 (CN-P8): subkind; involves/period on kinds; phase episodes
// (grounds+period, from/until); valued-state projection (per/from).
// 0.18 (HP-P1): md2er_dimension; structural md2er_cubelet measures.
// Every previously-valid file parses unchanged.
//
// Version scheme: X.Y — X is a breaking/major change, Y is additive
// (syntactic sugar, new optional constructs, bug fixes). Bump the marker
// above when changing the grammar; the prebuild script extracts it into
// @tatrman/grammar's exported TTR_GRAMMAR_VERSION. See CHANGELOG.md for
// history. (Renumbered 4.4 → 0.9 on 2026-07-16 so every artifact — grammar
// package, parsers, protos — shares one version: grammar <major.minor> is the
// artifact <major.minor>, the patch digit is code-only. Prior entries below
// keep their original numbers as historical record.)
//
// Changes vs v1 (promoted to 2.0 with v1.1 work):
//   1. New top-level constructs: `package <qualifiedName>`, `import <qualifiedName>[]`,
//      `graph <id> { ... }`.
//   2. New lexer tokens: PACKAGE, IMPORT, GRAPH, OBJECTS, LAYOUT, STAR.
//   3. New parser rules: packageDecl, importDecl, graphBlock, graphProperty,
//      graphSchemaProperty, graphObjectsProperty, graphLayoutProperty, qualifiedName.
//   4. Updated document rule to accept the new constructs.
//   5. Extended idPart to include the new keywords so they remain usable as
//      cross-reference components.
//
// Changes in 2.2 (additive):
//   - New top-level construct: `def drill_map <id> { from, to, args, display?, override? }`.
//     Models "click this row → run that pattern". Consumers (ai-platform's new-Golem)
//     surface drill maps to the FE; modeler treats them like any other def for
//     parse/diagnose purposes.
//   - New lexer tokens: DRILL_MAP, ARGS, DISPLAY, OVERRIDE.
//   - New parser rules: drillMapDef, drillMapProperty, argsProperty, displayProperty,
//     overrideProperty, drillArgEntry.
//   - Extended idPart to include the new keywords.
//
// Changes in 2.3 (additive — Packages & Domains PD2):
//   - New `.ttrd` domain file kind: `domain <id> { description?, tags?,
//     packages: [...], entities: [...] }`. Editor-only — ai-platform's loader
//     does NOT load `.ttrd` (consumed by the agent registry + resolved-packages
//     artifact). File-kind enforcement is semantic, not grammatical (mirrors
//     `.ttrg`).
//   - New lexer tokens: DOMAIN, PACKAGES, ENTITIES.
//   - New parser rules: domainBlock, domainProperty, domainPackagesProperty,
//     domainEntitiesProperty; `document` accepts a `domainBlock`.
//   - Extended idPart to include the new keywords.
//
// Changes in 3.0 (BREAKING — MD Phase 0 legacy renames):
//   A. `schema map` → `schema binding`. New lexer token BINDING; `schemaCode`
//      now alternates DB|ER|BINDING|QUERY|CNC. MAP is removed from `schemaCode`
//      (so `schema map` is no longer a valid directive) but the MAP token is
//      retained in `idPart` (and reserved for the future MD `def map` value-set
//      keyword). The `er2db_*` defs are unchanged — only the schema code they
//      live under is renamed. Migration: `schema map` → `schema binding`.
//   B. `domain` block / `.ttrd` file kind removed. Subject areas are now a plain
//      `def area <id> { description?, tags?, packages: [...], entities: [...] }`
//      definition that lives in ordinary model files and registers a resolvable
//      symbol. New lexer token AREA + parser rules areaDef/areaProperty (reusing
//      PACKAGES/ENTITIES). The DOMAIN token and domainBlock productions are
//      deleted; `domain` is freed for the future MD value-set keyword.
//   C. Model file extension `.ttr` → `.ttrm` (grammar-external; file detection
//      only). `.ttrg` (graph) is unchanged.
//
// Changes in 3.1 (additive — MD model):
//   1. New schema code `md` (`schema md`) for the multidimensional logical model.
//   2. Six new logical `def` kinds: `domain` (DOMAIN re-added — was deleted in
//      3.0), `dimension`, `map` (MAP promoted from idPart-only to a real def
//      kind), `hierarchy`, `measure`, `cubelet`. Four new binding `def` kinds
//      (under `schema binding`): `md2db_cubelet`, `md2db_domain`, `md2db_map`,
//      `md2er_cubelet`.
//   3. New punctuation token DOTDOT (`..`) for range literals (`restrict: {
//      range: 1..12 }`), placed before DOT.
//   4. New body keyword tokens: RESTRICT, MEMBERS, KIND, CALC, KEY, HIERARCHIES,
//      LEVELS, VIA, CLASS, AGGREGATION, VALID_BY, GRAIN, MEASURES, SHAPE,
//      JOURNALING, SOURCE. The `domain:`/`cubelet:`/`map:`/`dimension:` property
//      keywords reuse the existing DOMAIN/CUBELET/MAP/DIMENSION tokens.
//   5. The shared `attribute` body gains MD-only `domain:` and `aggregation:`
//      props (all optional in grammar; per-schema validity is semantic).
//   6. New value rules: rangeLiteral, restrictBlock/restrictClause, membersBlock,
//      calcRef/calcArg, levelList, aggregationValue, shapeValue, journalingValue,
//      measureInlineList. All new keywords added to idPart (cross-refs stay valid).
//   Additive: no existing 3.0 file changes meaning. Schema/file-kind validity of
//   the new defs is semantic, not grammatical (the "parser stays mechanical"
//   invariant). See docs/features/md/grammar-md-changes.md.
//
// Changes in 4.0 (BREAKING — qname redesign; docs/features/qname-redesign):
//   A. `def model <id>` → `def project <id>`. New lexer token PROJECT; the
//      whole-artifact header (identity + version + description + tags) is the
//      project. `objectDefinition` alt `MODEL id modelDef` → `PROJECT id
//      projectDef`; rules `modelDef`/`modelProperty` → `projectDef`/
//      `projectProperty` (body unchanged). One `def project` per repo.
//   B. The type directive `schema <code> [namespace <id>]` becomes
//      `model <code> [schema <id>]`. The freed MODEL token now names the model
//      type; the SCHEMA token now carries the namespace/binding id; the
//      NAMESPACE token is DELETED. Rule `schemaDirective` → `modelDirective`,
//      `schemaCode` → `modelCode`.
//   C. The graph header property `schema <code>` becomes `model <code>`:
//      `graphSchemaProperty` → `graphModelProperty`.
//   D. The freed keywords `schema` and `model` stay in `idPart` so they remain
//      usable as cross-reference / identifier fragments.
//   Note: `modelCode` keeps all alternatives (DB|ER|BINDING|QUERY|CNC|MD) — the
//   parser stays mechanical. The *semantic* ModelCode set drops `query` (a
//   `def query` folds into the `db` model, schema-bound) and `cnc` loses its
//   namespace echo; that folding lives in @modeler/semantics, not the grammar.
//
// Changes in 4.1 (additive — ttr-metadata M0, D-d-α world model):
//   1. New model code `world` (`model world`) for the deployment world model.
//      `modelCode` gains `| WORLD`; `objectDefinition` gains ONE alternative
//      `WORLD id worldDef`. engine/executor/storage/world-schema are NOT
//      top-level def kinds — they exist ONLY nested inside `def world`
//      (grammar-enforced nesting; meaningless outside a world, like graph
//      bodies). Per-model validity of `def world` (world defs only in
//      `model world` files) stays semantic.
//   2. New lexer tokens: WORLD, ENGINE, EXECUTOR, STORAGE, EXTENDS, HOSTS,
//      STAGING. `via:` reuses the existing VIA token (3.1); `type:`/`version:`
//      reuse DATA_TYPE/VERSION.
//   3. New parser rules: worldDef, worldMember, worldProperty, engineDef,
//      executorDef, enginePartProperty, storageDef, storageProperty,
//      extendsProperty, hostsProperty, stagingProperty, viaProperty,
//      worldSchemaDef, worldSchemaField. engine/executor/storage bodies list
//      typed props FIRST then a free-form `propertyEntry` fallback (T6 β
//      manifest data — transported opaque, interpreted by TTR-P Stage 2.2 only;
//      MD5).
//   4. idPart gains WORLD, ENGINE, EXECUTOR, STORAGE, VERSION (the def-noun
//      keywords + `version` stay usable as cross-ref / manifest-key fragments).
//      EXTENDS/HOSTS/STAGING are DELIBERATELY excluded: their properties take a
//      strict value form (id / listOfIds / BOOLEAN_LITERAL) and are guarded by
//      negative fixtures (world-negative/neg-02,03,05) — keeping them out of
//      idPart makes a malformed `staging: "x"` / `hosts: ["x"]` / `extends: "x"`
//      a hard parse error instead of silently falling through to propertyEntry.
//   Additive: no existing 4.0 file changes meaning.
//
// Changes in 4.4 (additive — TTR-M lexicon surface; RG-P4, RS-9..11):
//   1. New model code `lexicon` (`model lexicon`) — the canonical vocabulary
//      form. `modelCode` gains `| LEXICON`.
//   2. Three new lexicon def kinds: `term` / `pattern` / `example`, all sharing
//      one permissive body (`lexiconEntryDef`) with optional `for` (target ref) +
//      `forms` (term surface forms) | `match` (pattern regex) | `text` (example
//      text). Per-kind required-field validity (term needs forms, pattern needs
//      match, example needs text) is SEMANTIC (parser stays mechanical).
//   3. Unit-level locale header: `model lexicon locale <id>` (the `db … schema`
//      precedent slot). `modelDirective` gains an optional `( LOCALE id )?`;
//      locale-only-on-lexicon is enforced in semantics.
//   4. Inline `lexicon { … }` sugar (free-form `object_` body, the semantics-block
//      precedent) attachable to data-bearing carriers — er/db `table`/`column`/
//      `entity`/`attribute` AND md `measure`/`dimension`/`cubelet` (RS-10 makes md
//      kinds legal carriers). Desugars to canonical `term` entries in semantics.
//   5. New lexer tokens: LEXICON, TERM, PATTERN, EXAMPLE, FOR, FORMS, MATCH,
//      LOCALE — all added to idPart (the WORLD precedent: no common word is newly
//      reserved as anything but an id fragment). `text:` reuses TEXT; the inline
//      `terms:` key stays an un-minted bare object key (validated in semantics).
//   Additive: no existing 4.3 file changes meaning. See CHANGELOG.md 4.4 and
//   docs/features/resolution/plan/contracts.md §7.
//
// Changes in 0.10 (additive — TTR-M entry declarations; EN-P1, ttr-p entry stdlib):
//   1. Two new `table` properties (write-behaviour axis, per FO §9 / demand
//      ttrm-md-declarations.md §1/§2): `management: <id>` (data|canon, default
//      data) and `changeSemantics: <mode> [ { <role>: <column>, … } ]`
//      (scd1|scd2|ledger; the optional role map declares valid-from/valid-to for
//      scd2, reversal-link for ledger — md-declared, never name-sniffed). Vocabulary
//      + role-name/type validity are SEMANTIC (parser stays mechanical). Kept OFF
//      the `semantics { }` grounding vocabulary deliberately: that vocab is the
//      ai-platform proto-enum sync key and covers query-grounding, not write
//      mechanics.
//   2. Q-8 writeback-mapping reservation (demand §4): `writeback { … }` — a
//      free-form `object_` body that parses as a structured no-op (the H-1
//      reservation pattern); full rung-v3 semantics ride FO v1.1+.
//   3. New lexer tokens: MANAGEMENT, CHANGE_SEMANTICS, WRITEBACK — all added to
//      idPart (WORLD/SEMANTICS precedent: usable as id fragments / object keys).
//   Additive: no existing 0.9 file changes meaning.
// Changes in 0.10 (additive — MD dot-path S5-B.2 writeback spread strategy):
//   1. New optional `allocation:` property on `md2db_cubelet` bindings — declares
//      the writeback spread strategy (contracts R21, MDS5). `allocationProperty :
//      ALLOCATION propSep? allocationValue`; `allocationValue : id | object_` —
//      `allocation: proportional` (uniform, all spread dims) or `allocation: {
//      time: equal, product: proportional }` (per-dimension). Mirrors
//      `journalingProperty`/`aggregationValue`.
//   2. New lexer token ALLOCATION (also added to `md2erCubeletProperty` as a
//      permissive superset, REJECTED in semantics as a physical prop — the
//      shape/measures/journaling precedent). Added to idPart (stays usable as an
//      id fragment / object key). The strategy VALUE (`equal`/`proportional`)
//      stays an un-minted bare id, validated in semantics.
//   Additive: no existing 0.9 file changes meaning. Spread only became *legal*
//   with a declared strategy — MDS5's "spread emits the declared strategy or
//   fails, never a default" is a semantic/lowering rule, not grammatical. See
//   CHANGELOG.md 0.10 and features/md/dot-path/contracts.md §5 (R21).
//
// Changes in 0.11 (additive — PL-P4.S3 H-1 `security { }` block):
//   1. New DOCUMENT-LEVEL, repeatable `security { … }` block (sibling to
//      `definition`) — declarative access sugar over model objects. `document`
//      gains `(definition | securityBlock)*`; `securityBlock : SECURITY LBRACE
//      (securityStatement (COMMA? securityStatement)* COMMA?)? RBRACE`.
//   2. `securityStatement` is STRUCTURED — exactly the four §11 verbs:
//      `own <object>: <owner>` · `classify <object>: <classification>` ·
//      `grant <privilege> on <object> to <role>` · `mask <object>`. Deliberately
//      NOT the free-form `object_` bag of `semantics`/`lexicon`: an unknown verb
//      and a row-level predicate must be hard PARSE rejects (contracts §11 —
//      "row-level predicates stay Rego-side in v1"), which a permissive body
//      cannot enforce. Object refs (`sales`, `order_line.customer_email`) are
//      plain `id`s (dotted ids parse); resolution is semantic + advisory (H-3).
//   3. New lexer tokens SECURITY, OWN, CLASSIFY, GRANT, MASK, ON — all added to
//      `idPart` (the WORLD precedent: no common word newly reserved as anything
//      but an id fragment; `own`/`grant`/`mask`/`on` stay usable as ids/keys).
//      TO already exists (and is already in idPart).
//   4. Fingerprint-neutral: `security` blocks never enter WorldFingerprint / the
//      T6 semantic hash and never alter emitted plans (H-1 pin 2, contracts §11).
//   Additive: no existing 0.10 file changes meaning. See platform contracts §11
//   and project/platform/.../tasks/tasks-pl-p4-perun.md S3.
//
// Changes in 0.12 (additive — RV-P1.5 match-method attribute; RV-31/RV-32):
//   1. `searchable` becomes the lexicon INCLUSION marker (RV-31 source (3):
//      "include this carrier's content in the lexicon"), so its boolean is now
//      OPTIONAL — bare `searchable` means included. `searchable: true` /
//      `searchable: false` are unchanged.
//   2. New optional match-method attribute on it (RV-32): `searchable method:
//      EXACT | TYPOS(2) | TOKENS`, combinable with the boolean (`searchable:
//      true method: TOKENS`). `matchMethodAttr : METHOD propSep?
//      matchMethodValue`; `matchMethodValue : id ( LPAREN NUMBER_LITERAL
//      RPAREN )?`. The method NAME stays an un-minted bare id and its argument a
//      NUMBER_LITERAL — the ALLOCATION `proportional`/`equal` precedent, so the
//      EXACT/TYPOS/TOKENS vocabulary, the arity rule (only TYPOS takes an
//      argument) and the distance range are all SEMANTIC. The MD dot-path
//      numeric-literal invariant is untouched: no lexer rule changed.
//   3. `fuzzy: <bool>` still PARSES (deliberately — 0.12 is additive); it is
//      deprecated in the semantics layer, which maps `fuzzy: true` to
//      `method: TYPOS(1)` and `fuzzy: false` to `method: EXACT`.
//   4. New lexer token METHOD, added to idPart (the WORLD precedent: `method`
//      stays usable as an id fragment / object key).
//   Additive: no existing 0.11 file changes meaning. See CHANGELOG.md 0.12 and
//   project/kantheon/features/resolving/contracts.md §2.
//
// Changes in 0.13 (additive — NLS-P10 localised descriptions; ⚑GXP-D7):
//   1. `descriptionProperty` accepts the localised map form beside the string
//      form: `descriptionProperty : DESCRIPTION propSep? ( stringLiteralForm |
//      localizedString )`. So `description: { en: "Product", cs: "Produkt" }` is
//      legal everywhere `description: "Product"` already was — every def kind
//      that carries `descriptionProperty`, at every nesting depth.
//   2. No new token and no new production: `localizedString` is the SAME rule
//      `displayLabel` / `label` / `value_labels` already use (the deliberate
//      precedent — one localised shape across the language).
//   3. Which locale a reader serves is NOT a grammar concern. The parsers stay
//      mechanical and expose both forms side by side (`description` = the plain
//      form, `descriptionLocalized` = the map); the selection chain lives in the
//      consumer (Veles: requested locale → plain → `en` → first by language code
//      → ""), see project/server/features/nlp-suite/tasks/tasks-nls-p10.md.
//   Additive: no existing 0.12 file changes meaning. An empty map (`description:
//   {}`) parses to an empty entry set — it is a LINT warning, not a parse error.
//
// Changes in 0.14 (additive — named row schemas; TTR-P action displays):
//   1. New top-level def kind `def schema <id> { description?, tags?, columns: [...] }`:
//      a named, ordered row shape. `objectDefinition` gains ONE alternative
//      `SCHEMA id schemaDef`; `schemaProperty : descriptionProperty | tagsProperty |
//      columnsProperty`. The column list REUSES `columnsProperty` / `columnDefList` /
//      `columnProperty` verbatim, so a column is written exactly like a table column
//      (`def column note { type: text, optional: true }`) — no new sub-production.
//   2. No new token: SCHEMA already exists (`model <code> schema <id>`, the world
//      storage `def schema`). The world-nested `DEF SCHEMA id worldSchemaDef`
//      (storageProperty) is unchanged and stays a separate production — the two never
//      meet (top-level `definition` vs inside `def storage { … }`).
//   3. Consumer: TTR-P (`ttrp-frontend`) brings a schema into scope by `import <pkg>.*`
//      (like entities) and holds `display(<name>)` rows to it (TTRP-DSP-001..004).
//      The kind is tier-neutral and maps to the `db` model by default (the `area` /
//      `query` precedent) — it is not a loadable object.
//   Additive: no existing 0.13 file changes meaning.
//
// Changes in 0.15 (no syntax change — a version cut for the toolchain; VL-P2 review):
//   The grammar is byte-identical to 0.14 below this header: every 0.14 file parses
//   unchanged and every conformance golden is unchanged. The minor moves because the
//   bundle that ships with it changes public JVM signatures (`ttr-metadata`
//   `MetadataRegistry.swap(…, layouts)`, `RegistrySnapshot` / `SourceSnapshot` /
//   `TtrgGraph` gain fields) — a patch would let a 0.14-compiled jar meet them at run
//   time — and the bundle's <major.minor> is this marker (publish.yml enforces it).
//
// Changes in 0.16 (additive — the automation model; AU-core-P0 S0.2, AU D-26/D-35/D-67/D-90/D-91/D-104/D-106/D-107):
//   1. New model code `automation` (`model automation`): `modelCode` gains `| AUTOMATION`.
//      Six new top-level def kinds share ONE permissive body, `autoBody`:
//      `def parameter | action | service | approval | program | recipe <id> { … }`
//      (`objectDefinition` gains six alternatives). `def recipe` is the GENERATED catalog
//      object (the TTR-P compiler writes it; authored files never do) — admitted now so the
//      catalog needs no second grammar window. The typed properties (contracts §2) are
//      validated SEMANTICALLY, not here (the `enginePartProperty` precedent): typed alts
//      first (description, tags, label, type, columns, target, transition, set), then the
//      free-form `autoEntry` fallback.
//   2. `autoValue` — the automation value forms, used ONLY inside automation bodies (the
//      generic `value` is untouched): a duration literal `NUMBER_LITERAL id`
//      (`2 working_days`, unit validated semantically), a tagged string `id string`
//      (`schedule "06:00 Europe/Prague"`, `keycloak_group "hartland-sales"`), a call-shaped
//      value `id { … }` (`on_due: hold { order: order_number }`), `*` (`program: *`),
//      plus the literal / id / object / list forms (`autoObject`, `autoList` recurse
//      through `autoValue`). `autoKey : id | TEXT` (`fingerprints: { text: … }`).
//   3. `transitionProperty` — `transition: order.state -> released { from: [placed, held] }`
//      or `-> {to}` (the state taken from an action column); `setProperty` —
//      `set: order.held_for = department`. New token ARROW `'->'` (only here).
//   4. `def role` (the cnc kind) gains three TYPED automation properties —
//      `resolve: keycloak_group "…"`, `delivery: {…}`, `members: […]` (values are
//      `autoValue`s) — so a typo in any role (`lable:`) is still a parse error (review-114
//      M5: no free-form fallback on `def role`). New tokens RESOLVE, DELIVERY (MEMBERS
//      exists). Per-model validity is semantic.
//   5. World members (nested in `def world`, the 4.1 precedent): `def identity <id>`,
//      `def grant { … }` (UNNAMED — keyed by its who/program/as triple),
//      `def deployment <id>` (all `autoBody`) and `def process <id> { instance: …,
//      steps: [ def step <id> { … } … ] }` (`processBody`, `stepsProperty`).
//      `writable:` on a storage needs no syntax — the storage `propertyEntry` already
//      carries `writable: [order.state, "x*"]`.
//   6. Attributes gain `states: [placed, held, …]` and `transitions: { placed: [held, …], … }`
//      (`statesProperty`, `transitionsProperty`).
//   7. New lexer tokens: AUTOMATION, PARAMETER, ACTION, SERVICE, APPROVAL, PROGRAM, RECIPE,
//      IDENTITY, DEPLOYMENT, PROCESS, STEP, STEPS, TRANSITION, TRANSITIONS, STATES, SET,
//      RESOLVE, DELIVERY, ARROW. Every new noun is added to `idPart` (the WORLD precedent: `action`,
//      `process`, `set`, `program`, … stay usable as names, column names, keys and dotted
//      ref parts); PARAMETERS joins `idPart` too (`def recipe … parameters: [a, b]`).
//      ARROW is not an id.
//   Additive: no existing 0.15 file changes meaning; every conformance golden is unchanged.
//
// Changes in 0.17 (additive — the conceptual model; CN-P1 S1.1, cn contracts §1, CN-D3…D8/D11/D17/D18/
// D20…D25/D28, A-CN-9/13/16/18/19):
//   1. New model code `cn` (`model cn`): `modelCode` gains `| CN`. Four new top-level def kinds share ONE
//      permissive body, `cnBody`: `def kind | event | phase | quality | relation_type <id> { … }`
//      (`objectDefinition` gains five alternatives — KIND is reused from MD).
//   2. `def role` stays ONE production (`ROLE id roleDef`) shared by cnc, automation and cn (⚑CN-P6,
//      CN-D28): `roleProperty` is widened with the cn role's typed properties (`event:`, `grounds:`,
//      inline `lexicon`) and the cn free-form entry. The grammar stays target-neutral, so the WALKER reads
//      the body by the file's `model` directive — and keeps review-114 M5 there: outside `model cn` a cn
//      property on a role is a `ttr/unknown-property` parse error, so a typo (`lable:`) in a cnc or an
//      automation role is still rejected.
//   3. `cnBody`: typed alternatives FIRST for every cn key that is a token — `description`, `label`,
//      `tags`, the stereotype refs `kind|role|quality|event|phase|concept: <ref>` (`cnRefProperty`),
//      `grounds: <ref>`, `involves: [ Item, Warehouse as from ]` (`participant : id (AS id)?` — labelled
//      participants, AM-19), `period: { start, end, end_open } | { at, precision }`, `extent: <id>`,
//      `basis: <id>`, `definition: <block>`, `members: <block>`, `value_space: <ref>`, inline `lexicon` —
//      then the free-form `cnEntry` (`of`, `is_a`, `sources`, `qualities`, `relations`, `hierarchy`,
//      `imprecision`, `valued_state`, `abstract`, …). The free-form KEY is `IDENT | HIERARCHY | VIA`,
//      never a typed key, so a malformed typed property (`definition: OverdueInvoice`) is a parse error,
//      not a silently-accepted free-form entry. Free-form values (`cnValue`) are literals, ids, lists and
//      objects; a nested object key may also be `type` or `precision` (`relations: [ { type: supplies,
//      to: Demand } ]`, `period: { at: sold_at, precision: day }`).
//   4. `definition:` and `members:` are `embeddedBlock`s (⚑CN-P5, CN-D28) — a string, a triple-string or a
//      `"""pl`-tagged block, the `definitionSql` / `sourceText` precedent. TTR.g4 keeps the text and its
//      span; the expression is the TTRP expression grammar's (`TTRP.g4 expr`, `parseExpression` with the
//      EOF anchor of ttr-core#202), parsed by semantics in CN-P2. The session docs' bare spelling
//      (`definition: state = 'held'`) is written `definition: """state = 'held'"""`.
//   5. Ten binding kinds under `model binding`, one token each (the MD2ER_* precedent), sharing ONE body,
//      `cnBindingBody`: `def cn2md_kind | cn2md_role | cn2md_quality | cn2md_event | cn2er_kind |
//      cn2er_role | cn2er_event | cn2er_quality | cn2er_phase | cn2db_concept <id> { … }` — the stereotype
//      ref, `target:` (the existing `targetProperty`), `period:`, then the free-form entry (`hierarchy`,
//      `participants: { label: [ er.relation.… ] }`, `via: [ … ]`, `filter: <block>`). The stereotype ↔
//      target-type table is semantic.
//   6. `concept { … }` (`conceptSugarProperty : CONCEPT propSep? cnBody`) inside `cubelet`, `dimension`,
//      `domain`, `measure`, `entity` and `attribute` (er and md) — the bottom-up sugar (cn contracts §1.4).
//   7. Lexicon entries (`term` / `pattern` / `example`) gain typed `default: <bool>` and
//      `sense_priority: <number>` (the 4.4 rule: new *Property alts, not a free-form fallback); `for:`
//      takes a `cn.<X>` ref like any dotted id.
//   8. New lexer tokens: CN EVENT PHASE QUALITY RELATION_TYPE CONCEPT, the ten CN2MD_* / CN2ER_* /
//      CN2DB_CONCEPT, INVOLVES PERIOD GROUNDS DEFINITION VALUE_SPACE EXTENT BASIS AS, DEFAULT SENSE_PRIORITY.
//      EVERY ONE is in `idPart` (the WORLD precedent: `event`, `period`, `quality`, `default`, `as` stay
//      usable as names, columns, object keys and dotted ref parts; `cn.Item` refs need CN there).
//      Longest match: `definitionSql` stays DEFINITION_SQL, `cnc` stays CNC, `relation_type` beats
//      RELATION, `as_of` / `qualities` / `definitions` stay IDENTs.
//   9. `model cnc` and `roles:` parse unchanged (deprecated by CN-D18 — "no third mechanism";
//      `cn/cnc-deprecated` arrives with CN-P2's semantics; removal is a later major).
//   Additive: no existing 0.16 file changes meaning; every conformance golden 01–74 is byte-unchanged.
// =============================================================================

grammar TTR;

// ----- Top level -----

document
  : packageDecl? importDecl* (modelDirective | graphBlock)? (definition | securityBlock)* EOF
  ;

// ----- `security { }` block (v0.11, PL-P4.S3, H-1) -----
// Document-level, repeatable; declarative access sugar over model objects.
// STRUCTURED (own/classify/grant/mask), not a free-form `object_` bag — so an
// unknown verb and a row-level predicate are hard parse errors (contracts §11).
// Object refs are plain `id`s (dotted ids parse: `order_line.customer_email`);
// reference resolution is semantic + advisory (never a compile block).
securityBlock
  : SECURITY LBRACE (securityStatement (COMMA? securityStatement)* COMMA?)? RBRACE
  ;

securityStatement
  : OWN      id COLON id        // own <object>: <owner-role>
  | CLASSIFY id COLON id        // classify <object>: <classification>
  | GRANT    id ON id TO id     // grant <privilege> on <object> to <role|classification>
  | MASK     id                 // mask <object>
  ;

// ----- `.ttrl` view-state sidecar (v4.3, C1-c-iii) -----
//
// The family-wide `.ttrl` document body: `ttrl <version>` header + `canvas` blocks.
// The TTR-M grammar HOSTS this (it is not a fresh `.g4`, not in TTRP.g4) — a separate
// entry rule the Kotlin ttr-parser dispatches to on the `.ttrl` extension. Canvas keys
// are a TTR-P `program` / container path, or a TTR-M qname — kept generic (`id`).
// Property keys stay generic `id` (skin/mode/nodes/collapsed validated in the parser
// wrapper, not the grammar) so no common English word (`mode`, `nodes`) is reserved as
// a new keyword; only `ttrl` + `canvas` are added (and both fold into `idPart`). The
// `edges:` bendPoints slot is intentionally NOT in the grammar (reserved, not v1).
ttrlDocument
  : TTRL NUMBER_LITERAL ttrlCanvas* EOF
  ;

ttrlCanvas
  : CANVAS id LBRACE ttrlProperty* RBRACE
  ;

ttrlProperty
  : id propSep? ttrlPropValue
  ;

ttrlPropValue
  : STRING_LITERAL          // skin: "alteryx-knime"
  | id                      // mode: auto | manual
  | listOfStrings           // collapsed: ["db_prep", …]
  | ttrlNodeMap             // nodes: { "db_prep": { x: 120, y: 80 } }
  | ttrlIntMap              // chains: { "sales": 2 } — recorded SSA chain lengths (orphaning, C1-c-i)
  ;

ttrlNodeMap
  : LBRACE ttrlNodeEntry* RBRACE
  ;

ttrlNodeEntry
  : STRING_LITERAL propSep? LBRACE ( ttrlCoord (COMMA? ttrlCoord)* COMMA? )? RBRACE
  ;

ttrlCoord
  : id propSep? NUMBER_LITERAL
  ;

// Recorded per-name SSA chain lengths: the orphaning discriminator (C1-c-i). String
// key = SSA name group (`sales`) or anonymous-chain anchor; value = chain length at
// write time. Distinct from ttrlNodeMap by its NUMBER (not brace) values.
ttrlIntMap
  : LBRACE ttrlIntEntry* RBRACE
  ;

ttrlIntEntry
  : STRING_LITERAL propSep? NUMBER_LITERAL
  ;

packageDecl
  : PACKAGE qualifiedName
  ;

importDecl
  : IMPORT qualifiedName (DOT STAR)?
  ;

graphBlock
  : GRAPH id LBRACE (graphProperty (COMMA? graphProperty)* COMMA?)? RBRACE
  ;

graphProperty
  : graphModelProperty
  | descriptionProperty
  | tagsProperty
  | graphObjectsProperty
  | graphLayoutProperty
  ;

graphModelProperty    : MODEL propSep? modelCode ;
graphObjectsProperty  : OBJECTS propSep? LBRACK ( id (COMMA id)* )? COMMA? RBRACK ;
graphLayoutProperty   : LAYOUT propSep? object_ ;

qualifiedName
  : id
  ;

modelDirective
  : MODEL modelCode ( SCHEMA id )? ( LOCALE id )?      // 4.4 — `model lexicon locale cs` (locale-only-on-lexicon is semantic)
  ;

// `modelCode` stays mechanical — it still accepts QUERY (a `def query` file
// migrates to `model query` and parses), even though the *semantic* ModelCode
// set folds query→db (D14). Narrowing lives in @modeler/semantics.
modelCode
  : DB | ER | BINDING | QUERY | CNC | MD      // MD (3.1) — multidimensional logical model
  | WORLD                                     // 4.1 — deployment world model (ttr-metadata M0)
  | LEXICON                                   // 4.4 — canonical vocabulary model (RG-P4, RS-9)
  | AUTOMATION                                // 0.16 — the automation model (AU, D-26)
  | CN                                        // 0.17 — the conceptual model (CN-P1, CN-D17)
  ;

definition
  : DEF objectDefinition
  ;

objectDefinition
  : PROJECT        id  projectDef
  | TABLE          id  tableDef
  | VIEW           id  viewDef
  | COLUMN         id  columnDef
  | INDEX          id  indexDef
  | CONSTRAINT     id  constraintDef
  | FK             id  fkDef
  | PROCEDURE      id  procedureDef
  | ENTITY         id  entityDef
  | ATTRIBUTE      id  attributeDef
  | RELATION       id  relationDef
  | ER2DB_ENTITY   id  er2dbEntityDef
  | ER2DB_ATTRIBUTE id er2dbAttributeDef
  | ER2DB_RELATION id  er2dbRelationDef
  | QUERY          id  queryDef
  | ROLE           id  roleDef            // Phase 2.2 — cnc.role.*; 0.16 automation role; 0.17 cn role (one production — the walker reads the body by the file's `model`, ⚑CN-P6)
  | ER2CNC_ROLE    id  er2cncRoleDef      // Phase 2.2 — er2cnc_role.*
  | DRILL_MAP      id  drillMapDef        // v2.2 — drill mapping between two patterns
  | AREA           id  areaDef            // v3.0 — subject area (replaces the .ttrd domain block)
  | SCHEMA         id  schemaDef          // 0.14 — named row schema (TTR-P action displays)
  // ----- v3.1 MD model — logical def kinds (schema md) -----
  | DOMAIN         id  mdDomainDef        // 3.1 — DOMAIN re-added (deleted in 3.0)
  | DIMENSION      id  dimensionDef       // 3.1
  | MAP            id  mdMapDef           // 3.1 — `def map` now a real kind
  | HIERARCHY      id  hierarchyDef       // 3.1
  | MEASURE        id  measureDef         // 3.1
  | CUBELET        id  cubeletDef         // 3.1
  // ----- v3.1 MD model — binding def kinds (schema binding) -----
  | MD2DB_CUBELET  id  md2dbCubeletDef    // 3.1
  | MD2DB_DOMAIN   id  md2dbDomainDef     // 3.1
  | MD2DB_MAP      id  md2dbMapDef        // 3.1
  | MD2ER_DIMENSION id md2erDimensionDef
  | MD2ER_CUBELET  id  md2erCubeletDef    // 3.1 — structural-only
  // ----- v4.1 world model — the ONLY top-level world def kind (ttr-metadata M0) -----
  // engine/executor/storage/world-schema are meaningless outside `def world`, so
  // they are grammar-nested (worldMember / storageProperty), NOT top-level alts.
  // This is a structural fact (like graph bodies), not a per-schema validity rule,
  // so enforcing it here does not violate "parser stays mechanical".
  | WORLD          id  worldDef           // 4.1 — deployment world (D-d-α)
  // ----- v4.4 lexicon model — vocabulary def kinds (model lexicon) -----
  // One shared permissive body; per-kind required-field validity is semantic.
  | TERM           id  lexiconEntryDef    // 4.4 — surface forms → target (RS-9)
  | PATTERN        id  lexiconEntryDef    // 4.4 — regex pattern → target
  | EXAMPLE        id  lexiconEntryDef    // 4.4 — example utterance → target
  // ----- 0.16 automation model — one shared permissive body (`model automation`) -----
  | PARAMETER      id  autoBody           // 0.16 — a recipe parameter (D-13: a value change is a re-approval)
  | ACTION         id  autoBody           // 0.16 — an action: columns, sinks, transition, set (D-65)
  | SERVICE        id  autoBody           // 0.16 — a request/response service (D-69)
  | APPROVAL       id  autoBody           // 0.16 — an approval kind: by, quorum
  | PROGRAM        id  autoBody           // 0.16 — recipe + port + mode (D-26)
  | RECIPE         id  autoBody           // 0.16 — the GENERATED recipe catalog object (contracts §5.3)
  // ----- 0.17 cn model — concept def kinds (`model cn`); one shared permissive body -----
  // `def role` is NOT here: it stays the one ROLE alternative above (⚑CN-P6, CN-D28).
  | SUBKIND        id  cnBody             // 0.20 — rigid specialization; of: is required semantically
  | KIND           id  cnBody             // 0.17 — rigid things; may bind a vocabulary (KIND token reused from MD)
  | EVENT          id  cnBody             // 0.17 — occurrences: labelled `involves`, a `period:`, an `extent:`
  | PHASE          id  cnBody             // 0.17 — anti-rigid: a `definition:` OR a grounding to a stacked entity
  | QUALITY        id  cnBody             // 0.17 — characterisation: grounded OR defined; `value_space:`
  | RELATION_TYPE  id  cnBody             // 0.17 — open-tier relation types (`relations: [ { type: … } ]`)
  // ----- 0.17 cn bindings (`model binding`) — one token per kind (the MD2ER_* precedent) -----
  | CN2MD_KIND     id  cnBindingBody      // 0.17 — kind → md dimension
  | CN2MD_ROLE     id  cnBindingBody      // 0.17 — role → md dimension
  | CN2MD_QUALITY  id  cnBindingBody      // 0.17 — quality → md measure | attribute
  | CN2MD_EVENT    id  cnBindingBody      // 0.17 — event → md cubelet
  | CN2ER_KIND     id  cnBindingBody      // 0.17 — kind → er entity | attribute (value kind); `hierarchy:`
  | CN2ER_ROLE     id  cnBindingBody      // 0.17 — role → own entity, or base + `via:` path
  | CN2ER_EVENT    id  cnBindingBody      // 0.17 — event → entity; `participants:` label → relation path; `period:`
  | CN2ER_QUALITY  id  cnBindingBody      // 0.17 — quality → attribute; `filter:` (a valued state's series)
  | CN2ER_PHASE    id  cnBindingBody      // 0.17 — phase → query-backed (stacked) entity only
  | CN2DB_CONCEPT  id  cnBindingBody      // 0.17 — doc-grade db grounding (advisory)
  ;

// ----- Object bodies -----
// Each *Def is a brace-enclosed list of properties. Comma-optional.

projectDef       : LBRACE (projectProperty       (COMMA? projectProperty)*       COMMA?)? RBRACE ;
tableDef         : LBRACE (tableProperty         (COMMA? tableProperty)*         COMMA?)? RBRACE ;
viewDef          : LBRACE (viewProperty          (COMMA? viewProperty)*          COMMA?)? RBRACE ;
columnDef        : LBRACE (columnProperty        (COMMA? columnProperty)*        COMMA?)? RBRACE ;
indexDef         : LBRACE (indexProperty         (COMMA? indexProperty)*         COMMA?)? RBRACE ;
constraintDef    : LBRACE (constraintProperty    (COMMA? constraintProperty)*    COMMA?)? RBRACE ;
fkDef            : LBRACE (fkProperty            (COMMA? fkProperty)*            COMMA?)? RBRACE ;
procedureDef     : LBRACE (procedureProperty     (COMMA? procedureProperty)*     COMMA?)? RBRACE ;
entityDef        : LBRACE (entityProperty        (COMMA? entityProperty)*        COMMA?)? RBRACE ;
attributeDef     : LBRACE (attributeProperty     (COMMA? attributeProperty)*     COMMA?)? RBRACE ;
relationDef      : LBRACE (relationProperty      (COMMA? relationProperty)*      COMMA?)? RBRACE ;
er2dbEntityDef   : LBRACE (er2dbEntityProperty   (COMMA? er2dbEntityProperty)*   COMMA?)? RBRACE ;
er2dbAttributeDef: LBRACE (er2dbAttributeProperty(COMMA? er2dbAttributeProperty)* COMMA?)? RBRACE ;
er2dbRelationDef : LBRACE (er2dbRelationProperty (COMMA? er2dbRelationProperty)* COMMA?)? RBRACE ;
queryDef         : LBRACE (queryProperty         (COMMA? queryProperty)*         COMMA?)? RBRACE ;
roleDef          : LBRACE (roleProperty          (COMMA? roleProperty)*          COMMA?)? RBRACE ;
er2cncRoleDef    : LBRACE (er2cncRoleProperty    (COMMA? er2cncRoleProperty)*    COMMA?)? RBRACE ;
drillMapDef      : LBRACE (drillMapProperty      (COMMA? drillMapProperty)*      COMMA?)? RBRACE ;
areaDef          : LBRACE (areaProperty          (COMMA? areaProperty)*          COMMA?)? RBRACE ;
schemaDef        : LBRACE (schemaProperty        (COMMA? schemaProperty)*        COMMA?)? RBRACE ;   // 0.14

// v3.1 MD model def bodies (all follow the brace/comma-optional pattern).
mdDomainDef      : LBRACE (mdDomainProperty      (COMMA? mdDomainProperty)*      COMMA?)? RBRACE ;
dimensionDef     : LBRACE (dimensionProperty     (COMMA? dimensionProperty)*     COMMA?)? RBRACE ;
mdMapDef         : LBRACE (mdMapProperty         (COMMA? mdMapProperty)*         COMMA?)? RBRACE ;
hierarchyDef     : LBRACE (hierarchyProperty     (COMMA? hierarchyProperty)*     COMMA?)? RBRACE ;
measureDef       : LBRACE (measureProperty       (COMMA? measureProperty)*       COMMA?)? RBRACE ;
cubeletDef       : LBRACE (cubeletProperty       (COMMA? cubeletProperty)*       COMMA?)? RBRACE ;
md2dbCubeletDef  : LBRACE (md2dbCubeletProperty  (COMMA? md2dbCubeletProperty)*  COMMA?)? RBRACE ;
md2dbDomainDef   : LBRACE (md2dbDomainProperty   (COMMA? md2dbDomainProperty)*   COMMA?)? RBRACE ;
md2dbMapDef      : LBRACE (md2dbMapProperty      (COMMA? md2dbMapProperty)*      COMMA?)? RBRACE ;
md2erDimensionDef : LBRACE (md2erDimensionProperty (COMMA? md2erDimensionProperty)* COMMA?)? RBRACE ;
md2erCubeletDef  : LBRACE (md2erCubeletProperty  (COMMA? md2erCubeletProperty)*  COMMA?)? RBRACE ;

// v4.4 lexicon model — one shared body for term/pattern/example (permissive
// superset; per-kind required fields enforced in semantics). Additive-growth
// fields (`pos`, `weight` — RS-10's rejected-β-now) fold in here on parity
// evidence; add them as new *Property alts, not a free-form fallback.
lexiconEntryDef  : LBRACE (lexiconEntryProperty (COMMA? lexiconEntryProperty)* COMMA?)? RBRACE ;
lexiconEntryProperty
  : descriptionProperty | tagsProperty
  | forProperty | formsProperty | matchProperty | textProperty
  | defaultProperty | sensePriorityProperty           // 0.17 — cn default senses (cn contracts §1.5)
  | indexSecurityProperty
  ;
forProperty   : FOR   propSep? id ;               // target ref (er/db/md; 0.17 cn) — resolved in semantics
formsProperty : FORMS propSep? listOfStrings ;    // term surface forms
matchProperty : MATCH propSep? stringLiteralForm ;// pattern regex
textProperty  : TEXT  propSep? stringLiteralForm ;// example utterance (TEXT token reused)
// 0.17 — `default: true` (the default sense of a bare term; ≤1 per form and locale, never a phase —
// cn/duplicate-default-sense, cn/phase-as-default) and `sense_priority: 2` (higher wins). Typed alts per
// the note above (no free-form fallback on a lexicon entry); the rules are semantic.
defaultProperty       : DEFAULT        propSep? BOOLEAN_LITERAL ;
sensePriorityProperty : SENSE_PRIORITY propSep? NUMBER_LITERAL ;
indexSecurityProperty : INDEX_SECURITY propSep? id ;
skipSecurityProperty  : SKIP_SECURITY propSep? BOOLEAN_LITERAL ;
defaultValueProperty  : DEFAULT_VALUE propSep? stringLiteralForm ;
bindsProperty         : BINDS propSep? listOfIds ;

// ----- v4.1 world model (ttr-metadata M0; D-d-α, D-d-i, D-f, T6) -----
worldDef         : LBRACE (worldMember (COMMA? worldMember)* COMMA?)? RBRACE ;
worldMember
  : DEF ENGINE   id engineDef
  | DEF EXECUTOR id executorDef
  | DEF STORAGE  id storageDef
  | DEF IDENTITY   id autoBody                        // 0.16 — a service principal (D-90)
  | DEF GRANT         autoBody                        // 0.16 — UNNAMED; keyed by its who/program/as triple (D-91)
  | DEF DEPLOYMENT id autoBody                        // 0.16 — program|process × target × run_as (D-107)
  | DEF PROCESS    id processBody                     // 0.16 — a stepped process instance (D-39, D-86)
  | worldProperty
  ;
worldProperty    : descriptionProperty | tagsProperty | extendsProperty ;

engineDef        : LBRACE (enginePartProperty (COMMA? enginePartProperty)* COMMA?)? RBRACE ;
executorDef      : LBRACE (enginePartProperty (COMMA? enginePartProperty)* COMMA?)? RBRACE ;
// Shared engine/executor body: typed alts FIRST, then the free-form manifest
// fallback (T6 β data — transported opaque, interpreted by TTR-P Stage 2.2 only).
enginePartProperty
  : descriptionProperty | tagsProperty | typeProperty | versionProperty
  | extendsProperty | propertyEntry
  ;

storageDef       : LBRACE (storageProperty (COMMA? storageProperty)* COMMA?)? RBRACE ;
storageProperty
  : descriptionProperty | tagsProperty | typeProperty | extendsProperty
  | viaProperty | hostsProperty | stagingProperty
  | DEF SCHEMA id worldSchemaDef                      // D-c world home for named schemas
  | propertyEntry
  ;

extendsProperty  : EXTENDS  propSep? id ;             // instance ⊕ type overlay input (resolved in M2)
hostsProperty    : HOSTS    propSep? listOfIds ;      // D-d-i: model packages this storage hosts
stagingProperty  : STAGING  propSep? BOOLEAN_LITERAL ;// D-f: exactly-one checked in semantics/WorldResolver
viaProperty      : VIA      propSep? id ;             // storage reached via engine (VIA token reused from 3.1)

worldSchemaDef   : LBRACE (worldSchemaField (COMMA? worldSchemaField)* COMMA?)? RBRACE ;
worldSchemaField : id propSep? dataType ;             // { customer: string, amount: decimal }

// ----- 0.16 automation model (AU-core-P0 S0.2; contracts §2–§3) -----
// One permissive body for every automation kind and the automation world members. Typed
// alternatives FIRST (the `enginePartProperty` precedent), then the free-form `autoEntry`
// fallback; per-kind required/optional properties, enums and references are validated in
// semantics (ttr-semantics AutomationValidator / its TS twin) — the parser stays mechanical.
autoBody         : LBRACE (autoProperty (COMMA? autoProperty)* COMMA?)? RBRACE ;
autoProperty
  : descriptionProperty | tagsProperty | labelProperty | typeProperty | columnsProperty | targetProperty
  | transitionProperty | setProperty
  | autoEntry
  ;
autoEntry        : autoKey propSep? autoValue ;
autoKey          : id | TEXT ;                      // `fingerprints: { text: … }` (TEXT is a type keyword, not an idPart)
// Alternative order matters for ANTLR's lowest-alt preference: the two-token forms first
// (a duration `2 working_days`, a tagged string, a call-shaped value), then the one-token
// forms. A literal followed by the NEXT comma-less entry's key (`{ value: 0.01 label: … }`)
// is resolved by LL(*) lookahead (the key is followed by `:`), not taken as a duration.
autoValue
  : durationLiteral
  | taggedValue
  | callValue
  | STAR                                            // `program: *` (any)
  | autoObject
  | autoList
  | literal
  | id
  ;
autoObject       : LBRACE (autoEntry (COMMA? autoEntry)* COMMA?)? RBRACE ;
autoList         : LBRACK (autoValue (COMMA autoValue)*)? COMMA? RBRACK ;
durationLiteral  : NUMBER_LITERAL id ;              // 2 working_days · 30 days · 15 minutes (unit is semantic)
taggedValue      : id stringLiteralForm ;           // schedule "06:00 Europe/Prague" · keycloak_group "hartland-sales"
callValue        : id autoObject ;                  // on_due: hold { order: order_number, department: "Sales" }
// `transition: order.state -> released { from: [placed, held] }` / `-> {to}` (D-85: a
// self-transition is implicit and silent — semantic, not here).
transitionProperty : TRANSITION propSep? id ARROW transitionTarget transitionGuard? ;
transitionTarget   : id | LBRACE id RBRACE ;        // a state, or `{column}` — the state named by an action column
transitionGuard    : LBRACE FROM propSep? (listOfIds | id) RBRACE ;
setProperty        : SET propSep? id EQUALS id ;    // set: order.held_for = department

// `def process <id> { instance: …, steps: [ def step <id> { … } … ] }` (D-39, D-86).
processBody      : LBRACE (processProperty (COMMA? processProperty)* COMMA?)? RBRACE ;
processProperty  : descriptionProperty | tagsProperty | stepsProperty | autoEntry ;
stepsProperty    : STEPS propSep? LBRACK (stepInline COMMA?)* RBRACK ;
stepInline       : DEF STEP id autoBody ;

// ----- 0.17 cn model (CN-P1 S1.1; cn contracts §1) -----
// One permissive body for `def kind | event | phase | quality | relation_type`, the `concept { }` sugar
// and (through `roleProperty`) a cn `def role`. Typed alternatives FIRST — every cn key that is a token
// — then the free-form `cnEntry` (the `autoBody` precedent). Which property is legal on which kind,
// required properties, enums and references are SEMANTIC (CN-P2); the parser stays mechanical.
cnBody           : LBRACE (cnProperty (COMMA? cnProperty)* COMMA?)? RBRACE ;
cnProperty
  : descriptionProperty | labelProperty | tagsProperty
  | cnRefProperty                     // event: Order (a role's defining event) · kind: Invoice (the sugar's stereotype)
  | groundsProperty                   // grounds: md.Customer | er.entity.invoice | er.entity.channel_sales.channel
  | involvesProperty                  // involves: [ Item, Warehouse as from, Warehouse as to ]
  | periodProperty                    // period: { start: placed_date, end: delivered_date, end_open: open } | { at: sold_date }
  | extentProperty                    // extent: instantaneous | durative
  | basisProperty                     // basis: occurrence | current_state | past_state
  | definitionProperty                // definition: """state in ('placed', 'held')"""  (an embeddedBlock, ⚑CN-P5)
  | conceptMembersProperty            // members: """descendants(ProductCategory, 'H10-H19')"""  (an embeddedBlock)
  | cnFromProperty | cnUntilProperty | cnPerProperty // 0.20 — bounds / projection refs, validity is semantic
  | valueSpaceProperty                // value_space: ProductCategory
  | lexiconBlockProperty              // lexicon { terms: [ … ] } — the 4.4 inline sugar, a cn.* target
  | cnEntry                           // of · is_a · sources · qualities · relations · hierarchy · imprecision · valued_state · abstract
  ;
// The stereotype-ref keys: a cn role's `event:`, the sugar's `kind:`/`role:`/`quality:`/`event:` and a
// binding's `kind:`/`role:`/`quality:`/`event:`/`phase:`/`concept:` — one rule, the key is the token.
cnRefProperty          : ( KIND | ROLE | QUALITY | EVENT | PHASE | CONCEPT ) propSep? id ;
cnFromProperty         : FROM  propSep? id ;
cnUntilProperty        : UNTIL propSep? id ;
cnPerProperty          : PER   propSep? id ;
groundsProperty        : GROUNDS     propSep? id ;
involvesProperty       : INVOLVES    propSep? LBRACK participant ( COMMA participant )* COMMA? RBRACK ;
participant            : id ( AS id )? ;                      // a concept ref, optionally labelled: `Warehouse as from`
periodProperty         : PERIOD      propSep? cnObject ;
extentProperty         : EXTENT      propSep? id ;
basisProperty          : BASIS       propSep? id ;
// `definition:` / `members:` (and the binding's free-form `filter:`) carry an expression the TTRP
// expression grammar parses (CN-D28 ⚑CN-P5) — the `definitionSql` / `sourceText` precedent: TTR.g4 keeps
// the block text and its span, never the expression. A bare (non-block) value is a parse error.
definitionProperty     : DEFINITION  propSep? embeddedBlock ;
conceptMembersProperty : MEMBERS     propSep? embeddedBlock ;
valueSpaceProperty     : VALUE_SPACE propSep? id ;

// The free-form entry. Its KEY is a plain identifier (IDENT), never a keyword, so a malformed typed
// property (`definition: OverdueInvoice` — a bare value where a block is required) is a parse error
// rather than a silently-accepted free-form entry. That also leaves out every OTHER TTR keyword: `from`,
// `to`, `type`, `name`, `key`, `source`, `default`, `cardinality`, … are not free-form keys at the top
// of a cn body. Inside a nested object a key is any `id` (every `idPart` word — `to`, `from`, `name`,
// `default` …, not `cardinality`) or `type` / `precision`. HIERARCHY and VIA are the two pre-0.17 tokens
// admitted as free-form keys (`hierarchy: { closure: … }`, `via: [ … ]`).
cnEntry          : cnKey propSep? cnValue ;
cnKey            : IDENT | HIERARCHY | VIA ;
cnValue          : cnObject | cnList | literal | id ;
cnObject         : LBRACE (cnObjectEntry (COMMA? cnObjectEntry)* COMMA?)? RBRACE ;
// Nested keys are any word, plus the two type keywords cn spells as keys: `relations: [ { type: supplies,
// to: Demand } ]` (cn contracts §1.3) and `period: { at: sold_at, precision: day }` (AM-19).
cnObjectEntry    : ( id | DATA_TYPE | PRECISION ) propSep? cnValue ;
cnList           : LBRACK (cnValue (COMMA cnValue)*)? COMMA? RBRACK ;

// `def cn2md_* | cn2er_* | cn2db_concept <id> { … }` — one permissive body for the ten binding kinds (the
// `md2erCubeletDef` precedent): the stereotype ref (`kind: cn.Item`), `target:`, `period:`, then the
// free-form entry (`hierarchy`, `participants`, `via`, `filter`). The stereotype ↔ target-type table is
// semantic (cn/grounding-target-kind).
cnBindingBody    : LBRACE (cnBindingProperty (COMMA? cnBindingProperty)* COMMA?)? RBRACE ;
cnBindingProperty
  : descriptionProperty | tagsProperty
  | cnRefProperty                     // kind: | role: | quality: | event: | phase: | concept: cn.<X>
  | targetProperty                    // target: md.Product | er.entity.category_code | er.entity.order.value | db.dbo.x
  | cnPerProperty                     // 0.20 — valued-state projection binding index
  | periodProperty                    // cn2er_event — period: { at: er.entity.transfer.shipped_at }
  | cnEntry                           // hierarchy · participants · via · filter
  ;

// `concept { … }` — the bottom-up sugar inside an md or er def (cn contracts §1.4): the def's own concept.
// Desugars (CN-P2) to a concept def + the matching cn2md_*/cn2er_* binding; one per def is semantic.
conceptSugarProperty   : CONCEPT propSep? cnBody ;

// ----- Per-kind valid properties -----

projectProperty          : descriptionProperty | tagsProperty | versionProperty ;

tableProperty            : descriptionProperty | tagsProperty | primaryKeyProperty | columnsProperty | indicesProperty | constraintsProperty | searchBlockProperty | semanticsBlockProperty | lexiconBlockProperty | managementProperty | changeSemanticsProperty | writebackReservationProperty ;

viewProperty             : descriptionProperty | tagsProperty | columnsProperty | definitionSqlProperty | searchBlockProperty ;

columnProperty           : descriptionProperty | tagsProperty | typeProperty | optionalProperty | isKeyProperty | indexedProperty | searchBlockProperty | semanticsBlockProperty | lexiconBlockProperty ;

indexProperty            : descriptionProperty | indexTypeProperty | columnNamesListProperty ;

constraintProperty       : descriptionProperty | constraintTypeProperty | columnNamesListProperty ;

fkProperty               : descriptionProperty | tagsProperty | fromProperty | toProperty ;

procedureProperty        : descriptionProperty | tagsProperty | parametersProperty | resultColumnsProperty ;

entityProperty           : descriptionProperty | tagsProperty | labelPluralProperty | nameAttributeProperty | codeAttributeProperty | aliasesProperty | attributesProperty | rolesProperty | displayLabelProperty | searchBlockProperty | semanticsBlockProperty | lexiconBlockProperty | bindingProperty | conceptSugarProperty ;   // 0.17 — `concept { kind|role|event: … }`

// v3.1: the shared attribute body gains MD-only `domain:` and `aggregation:`
// props. All props are optional in the grammar; per-schema validity (md requires
// `domain:` & forbids `type:`; er the reverse) is enforced in semantics.
// 0.16 — `states:` / `transitions:` on an attribute (D-67); consistency is semantic (ttr/states-*).
attributeProperty        : descriptionProperty | tagsProperty | typeProperty | isKeyProperty | optionalProperty | valueLabelsProperty | displayLabelProperty | searchBlockProperty | semanticsBlockProperty | lexiconBlockProperty | bindingProperty | domainRefProperty | aggregationProperty | statesProperty | transitionsProperty | conceptSugarProperty ;   // 0.17 — `concept { quality: … }` (er: also a value kind)

relationProperty         : descriptionProperty | tagsProperty | fromProperty | toProperty | cardinalityProperty | joinProperty | searchBlockProperty | bindingProperty ;

er2dbEntityProperty      : descriptionProperty | tagsProperty | entityProperty_ | targetProperty | whereFilterProperty ;

er2dbAttributeProperty   : descriptionProperty | tagsProperty | attributeProperty_ | targetProperty ;

er2dbRelationProperty    : descriptionProperty | tagsProperty | relationProperty_ | fkProperty_ ;

queryProperty            : descriptionProperty | tagsProperty | languageProperty | parametersProperty | sourceTextProperty | searchBlockProperty | skipSecurityProperty ;

// 0.16 — `def role` in `model automation` gains three TYPED properties (resolve / delivery /
// members) with `autoValue` values. Per-model validity is semantic.
// 0.17 — ONE production for the cnc, automation and cn role (⚑CN-P6, CN-D28): the cn role's typed
// properties (`event:` — the defining event —, `grounds:`, inline `lexicon`) and the cn free-form entry
// (`of:`, `is_a:`, `qualities:`, `sources:`, `relations:`). The WALKER reads the body by the file's
// `model` directive and keeps review-114 M5 there: outside `model cn` a free-form entry is a parse error,
// so a typo (`lable:`) in a cnc or automation role is still rejected.
roleProperty             : descriptionProperty | tagsProperty | labelProperty | searchBlockProperty | resolveProperty | deliveryProperty | membersProperty
                         | cnRefProperty | groundsProperty | involvesProperty | periodProperty | lexiconBlockProperty | cnEntry ;
resolveProperty          : RESOLVE  propSep? autoValue ;   // resolve: keycloak_group "hartland-sales"
deliveryProperty         : DELIVERY propSep? autoValue ;   // delivery: { email: "…" }
membersProperty          : MEMBERS  propSep? autoValue ;   // members: ["ana", "bo"] (MEMBERS reused from MD)

er2cncRoleProperty       : descriptionProperty | tagsProperty | entityProperty_ | roleProperty_ ;

drillMapProperty         : descriptionProperty | tagsProperty | fromProperty | toProperty | argsProperty | displayProperty | overrideProperty ;

// v3.0 — subject area (replaces the v2.3 `.ttrd` domain block). A plain def that
// lives in ordinary model files; `id` members allow dotted nested-package names.
// Reuses the PACKAGES / ENTITIES tokens.
areaProperty             : descriptionProperty | tagsProperty | areaPackagesProperty | areaEntitiesProperty ;

// 0.14 — named row schema (`def schema`): an ordered column list, written exactly like a
// table's (`columns: [ def column x { type: text, optional: true } ]`). Tier-neutral; the
// TTR-P consumer holds `display(<name>)` rows to it. Column-property validity is semantic.
schemaProperty           : descriptionProperty | tagsProperty | columnsProperty ;
areaPackagesProperty     : PACKAGES propSep? LBRACK ( id (COMMA id)* )? COMMA? RBRACK ;
areaEntitiesProperty     : ENTITIES propSep? LBRACK ( id (COMMA id)* )? COMMA? RBRACK ;

// ----- v3.1 MD model — per-kind valid properties -----
//
// `domain:`/`cubelet:`/`map:`/`dimension:` property keywords reuse the existing
// DOMAIN/CUBELET/MAP/DIMENSION tokens (ANTLR cannot mint two tokens for one
// literal). `attributes`/`measures` reuse ATTRIBUTES/MEASURES. The grammar is a
// permissive superset; value-set / reference / shape validity is semantic.

mdDomainProperty     : descriptionProperty | tagsProperty | typeProperty | kindProperty | restrictProperty | publishProperty | conceptSugarProperty ;   // 0.17 — `concept { kind|role: … }`
dimensionProperty    : descriptionProperty | tagsProperty | keyProperty | attributesProperty | hierarchiesProperty | lexiconBlockProperty | conceptSugarProperty ;   // 0.17 — `concept { kind|role: … }`
mdMapProperty        : descriptionProperty | tagsProperty | fromProperty | toProperty | cardinalityProperty | calcProperty ;
hierarchyProperty    : descriptionProperty | tagsProperty | dimensionRefProperty | levelsProperty ;
measureProperty      : descriptionProperty | tagsProperty | domainRefProperty | classProperty | aggregationProperty | validByProperty | lexiconBlockProperty | conceptSugarProperty ;   // 0.17 — `concept { quality: … }`
cubeletProperty      : descriptionProperty | tagsProperty | grainProperty | measuresProperty | lexiconBlockProperty | conceptSugarProperty ;   // 0.17 — `concept { event: …, involves: [ … ] }`

md2dbCubeletProperty : descriptionProperty | tagsProperty | cubeletRefProperty | targetProperty | shapeProperty | attributesMapProperty | measuresMapProperty | journalingProperty | allocationProperty ;
md2dbDomainProperty  : descriptionProperty | tagsProperty | domainRefProperty | sourceProperty ;
md2dbMapProperty     : descriptionProperty | tagsProperty | mapRefProperty | targetProperty | columnsMapProperty ;
// MD to ER maps attributes and measures structurally. Physical shape, allocation and
// journaling are accepted here as a permissive superset and REJECTED in
// semantics (md/md2er-physical-prop) — the "parser stays mechanical" invariant.
md2erDimensionProperty : descriptionProperty | tagsProperty | dimensionRefProperty | entityProperty_ | attributesMapProperty ;
md2erCubeletProperty : descriptionProperty | tagsProperty | cubeletRefProperty | targetProperty | attributesMapProperty | shapeProperty | measuresMapProperty | journalingProperty | allocationProperty ;

// MD property productions. `kind`/`class` values are bare ids validated in
// semantics; `domain:`/`cubelet:`/`map:`/`dimension:` reuse their def-kind token.
kindProperty         : KIND        propSep? id ;
restrictProperty     : RESTRICT    propSep? restrictBlock ;
publishProperty      : PUBLISH     propSep? MEMBERS ;         // MD dot-path §1.4 — opt into member catalog
keyProperty          : KEY         propSep? id ;
hierarchiesProperty  : HIERARCHIES propSep? listOfIds ;
domainRefProperty    : DOMAIN      propSep? id ;             // `domain: md.Customer` (DOMAIN token reused)
aggregationProperty  : AGGREGATION propSep? aggregationValue ;
calcProperty         : CALC        propSep? calcRef ;
dimensionRefProperty : DIMENSION   propSep? id ;            // `dimension: md.Time` (DIMENSION token reused)
levelsProperty       : LEVELS      propSep? levelList ;
classProperty        : CLASS       propSep? id ;            // additive | semiAdditive | nonAdditive
validByProperty      : VALID_BY    propSep? id ;
grainProperty        : GRAIN       propSep? listOfIds ;     // `[Customer.code, Time.day]` (dotted ids ok)
measuresProperty     : MEASURES    propSep? measuresValue ;
cubeletRefProperty   : CUBELET     propSep? id ;            // `cubelet: md.sales` (CUBELET token reused)
mapRefProperty       : MAP         propSep? id ;            // `map: md.month_to_qtr` (MAP token reused)
shapeProperty        : SHAPE       propSep? shapeValue ;
journalingProperty   : JOURNALING  propSep? journalingValue ;
allocationProperty   : ALLOCATION  propSep? allocationValue ;   // v0.10 — writeback spread strategy (R21)
sourceProperty       : SOURCE      propSep? object_ ;       // `source: { table: …, column: … }`
attributesMapProperty: ATTRIBUTES  propSep? object_ ;       // generic map; shape-checked in semantics
measuresMapProperty  : MEASURES    propSep? object_ ;       // generic map; shape-checked in semantics
columnsMapProperty   : COLUMNS     propSep? object_ ;       // md2db_map: from/to domain → case-table column

// MD value forms.
restrictBlock        : LBRACE (restrictClause (COMMA? restrictClause)* COMMA?)? RBRACE ;
restrictClause       : key propSep? restrictValue ;         // key ∈ {range, members, pattern, length, …} (open)
restrictValue        : rangeLiteral | membersBlock | value ;
rangeLiteral         : NUMBER_LITERAL DOTDOT NUMBER_LITERAL ;
membersBlock         : LBRACE (memberEntry (COMMA? memberEntry)* COMMA?)? RBRACE ;
memberEntry          : stringLiteralForm propSep? localizedString ;   // reuses localizedString

// Calc reference with NAMED parens args (decided 2026-06-25) — NOT the positional
// `functionCall`, which can't express `fiscalYearStartMonth: 4`.
calcRef              : id ( LPAREN ( calcArg (COMMA calcArg)* )? RPAREN )? ;
calcArg              : id propSep? value ;

levelList            : LBRACK ( levelEntry (COMMA levelEntry)* )? COMMA? RBRACK ;
levelEntry           : id (VIA id)? ;                       // `Quarter` | `Quarter via md.month_to_qtr`

aggregationValue     : id | object_ ;                       // `sum` | `{ default: sum, time: latestValid }`

measuresValue        : measureInlineList | listOfIds ;      // inline defs | refs to standalone measures
measureInlineList    : LBRACK ( DEF MEASURE id measureDef COMMA? )* RBRACK ;

shapeValue           : id | object_ ;                       // `wide` | `{ long: { codeColumn: …, valueColumn: … } }`
journalingValue      : id | object_ ;                       // `overwrite` | `diff` | `{ invalidate: { validColumn: … } }`
allocationValue      : id | object_ ;                       // `proportional` | `equal` | `{ time: equal, product: proportional }` (v0.10)

// A query / procedure parameter: { name: <id>, type: <dataType>, label: "...", direction: <id> }.
// `label` here is a plain display string (unlike `roleProperty`'s localised `labelProperty`).
// `direction` (IN | OUT | INOUT) only appears on procedure parameters.
paramProperty            : nameProperty | typeProperty | paramLabelProperty | directionProperty | defaultValueProperty | bindsProperty ;

// ----- Property productions -----

// 0.13 (NLS-P10 / ⚑GXP-D7) — the localised map form rides beside the string
// form, reusing `localizedString` (the `displayLabel` precedent). Parsers keep the
// two forms apart; locale SELECTION is the consumer's, not the grammar's.
descriptionProperty       : DESCRIPTION       propSep? ( stringLiteralForm | localizedString ) ;
tagsProperty              : TAGS              propSep? listOfStrings ;
versionProperty           : VERSION           propSep? STRING_LITERAL ;
primaryKeyProperty        : PRIMARY_KEY       propSep? primaryKeyValue ;
columnsProperty           : COLUMNS           propSep? columnDefList ;
indicesProperty           : INDICES           propSep? indexDefList ;
constraintsProperty       : CONSTRAINTS       propSep? constraintDefList ;
definitionSqlProperty     : DEFINITION_SQL    propSep? embeddedBlock ;
typeProperty              : DATA_TYPE         propSep? dataType ;
optionalProperty          : OPTIONAL          propSep? BOOLEAN_LITERAL ;
isKeyProperty             : IS_KEY            propSep? BOOLEAN_LITERAL ;
// v0.12 (RV-P1.5, RV-31/32) — `searchable` is the INCLUSION marker ("include this
// carrier's content in the lexicon"), so the boolean is now OPTIONAL (bare
// `searchable` = included) and an optional match-method attribute rides it:
// `searchable method: EXACT | TYPOS(2) | TOKENS`. The method NAME is an ordinary
// `id` and its argument a NUMBER_LITERAL — the parser stays mechanical, the
// EXACT/TYPOS/TOKENS vocabulary + the arity rule (only TYPOS takes an argument)
// are validated in semantics (the `allocation:` precedent).
searchableProperty        : SEARCHABLE        ( propSep? BOOLEAN_LITERAL )? matchMethodAttr? ;
matchMethodAttr           : METHOD            propSep? matchMethodValue ;
matchMethodValue          : id ( LPAREN NUMBER_LITERAL RPAREN )? ;
indexedProperty           : INDEXED           propSep? BOOLEAN_LITERAL ;
indexTypeProperty         : DATA_TYPE         propSep? indexTypeValue ;
constraintTypeProperty    : DATA_TYPE         propSep? constraintTypeValue ;
columnNamesListProperty   : COLUMNS           propSep? listOfStrings ;
fromProperty              : FROM              propSep? value ;
toProperty                : TO                propSep? value ;
parametersProperty        : PARAMETERS        propSep? parameterDefList ;
resultColumnsProperty     : RESULT_COLUMNS    propSep? columnDefList ;
labelPluralProperty       : LABEL_PLURAL      propSep? STRING_LITERAL ;
nameAttributeProperty     : NAME_ATTRIBUTE    propSep? id ;
nameProperty              : NAME              propSep? id ;
codeAttributeProperty     : CODE_ATTRIBUTE    propSep? id ;
aliasesProperty           : ALIASES           propSep? listOfStrings ;
attributesProperty        : ATTRIBUTES        propSep? attributeDefList ;
cardinalityProperty       : CARDINALITY       propSep? object_ ;
joinProperty              : JOIN              propSep? list ;
entityProperty_           : ENTITY            propSep? id ;
attributeProperty_        : ATTRIBUTE         propSep? id ;
relationProperty_         : RELATION          propSep? id ;
fkProperty_               : FK                propSep? id ;
targetProperty            : TARGET            propSep? ( object_ | id ) ;
whereFilterProperty       : WHERE_FILTER      propSep? object_ ;
languageProperty          : LANGUAGE          propSep? languageValue ;
sourceTextProperty        : SOURCE_TEXT       propSep? embeddedBlock ;

// Phase 2.2 — localised string blocks: { cs: "...", en: "..." } shape, used for
// role labels, entity / attribute display_label, and value_labels values.
labelProperty             : LABEL             propSep? localizedString ;
paramLabelProperty        : LABEL             propSep? stringLiteralForm ;
directionProperty         : DIRECTION         propSep? id ;
displayLabelProperty      : DISPLAY_LABEL     propSep? localizedString ;
rolesProperty             : ROLES             propSep? listOfIds ;
valueLabelsProperty       : VALUE_LABELS      propSep? valueLabelsBody ;
roleProperty_             : ROLE              propSep? id ;
// 0.16 — attribute lifecycle (D-67): `states: [placed, held]`, `transitions: { placed: [held] }`.
statesProperty            : STATES            propSep? listOfIds ;
transitionsProperty       : TRANSITIONS       propSep? LBRACE ( transitionEntry ( COMMA? transitionEntry )* COMMA? )? RBRACE ;
transitionEntry           : id propSep? listOfIds ;

// Search feature — `search { keywords {...} patterns [...] descriptions {...} examples [...] aliases [...] searchable: true, fuzzy: true }`
searchBlockProperty       : SEARCH            propSep? searchBlock ;
// Grounding Phase 1 (v4.2) — `semantics { kind: …, role: …, … }`. Free-form
// `object_` body (the `attributesMapProperty` precedent above): the parser stays
// mechanical, so vocabulary/shape/cross-ref checks all live in ttr-semantics and
// new roles need no future grammar bump. Attachable on table/column/entity/
// attribute ONLY (see those four *Property rules).
semanticsBlockProperty    : SEMANTICS         propSep? object_ ;
// EN-P1 (0.10) — TTR-M entry declarations (write-behaviour axis), on `table` only.
// Vocabulary (data|canon; scd1|scd2|ledger; role names) + role-column type checks
// are SEMANTIC — the parser stays mechanical.
managementProperty        : MANAGEMENT        propSep? id ;
changeSemanticsProperty   : CHANGE_SEMANTICS  propSep? id changeRoleMap? ;
changeRoleMap             : LBRACE ( changeRoleEntry ( COMMA? changeRoleEntry )* COMMA? )? RBRACE ;
changeRoleEntry           : id propSep? id ;                 // <role-name> : <column-id>
// Q-8 reservation (demand §4) — parses as a structured no-op (H-1 pattern).
writebackReservationProperty : WRITEBACK      propSep? object_ ;
// Lexicon inline sugar (v4.4) — `lexicon { terms: […] }`. Free-form `object_`
// body (the semantics-block precedent): the parser stays mechanical; the
// `terms`/`patterns`/`examples` shape + desugar to canonical `term` entries live
// in ttr-semantics. Attachable to data-bearing carriers (table/column/entity/
// attribute AND measure/dimension/cubelet — see those *Property rules).
lexiconBlockProperty      : LEXICON           propSep? object_ ;
keywordsProperty          : KEYWORDS          propSep? localizedStringList ;
patternsProperty          : PATTERNS          propSep? listOfStrings ;
descriptionsProperty      : DESCRIPTIONS      propSep? localizedStringList ;
examplesProperty          : EXAMPLES          propSep? listOfStrings ;
fuzzyProperty             : FUZZY             propSep? BOOLEAN_LITERAL ;

// v2.2 — drill map properties.
//
// `args` is a map of <target-parameter-name> → <source-column-name-or-literal>;
// keys are bare identifiers (parameter names on the `to` pattern) and values are
// string literals (column names from `from`'s result projection, or literals).
// `display` is a localised string for the user-facing drill chip label.
// `override` (default false) suppresses auto-derived drills with the same target.
argsProperty          : ARGS              propSep? drillArgsMap ;
displayProperty       : DISPLAY           propSep? localizedString ;
overrideProperty      : OVERRIDE          propSep? BOOLEAN_LITERAL ;

drillArgsMap          : LBRACE ( drillArgEntry ( COMMA? drillArgEntry )* COMMA? )? RBRACE ;
drillArgEntry         : id propSep? stringLiteralForm ;

// v2.1 — inline binding (syntactic sugar for def er2db_*).
// v3.0: the property keyword was renamed `mapping:` → `binding:` (Stage AA),
// reusing the BINDING token; the rules below were renamed mapping* → binding*.
bindingProperty       : BINDING propSep? bindingValue ;

bindingValue
    : id
    | bindingBlock
    ;

bindingBlock
    : LBRACE ( bindingBlockProperty ( COMMA? bindingBlockProperty )* COMMA? )? RBRACE
    ;

bindingBlockProperty
    : targetProperty
    | bindingColumnsProperty
    | fkProperty_
    ;

bindingColumnsProperty
    : COLUMNS propSep? bindingColumnMap
    ;

bindingColumnMap
    : LBRACE ( bindingColumnEntry ( COMMA? bindingColumnEntry )* COMMA? )? RBRACE
    ;

bindingColumnEntry
    : id propSep? bindingColumnValue
    ;

bindingColumnValue
    : id
    | LBRACE TARGET propSep? bindingTargetValue RBRACE
    | object_
    ;

bindingTargetValue
    : id
    | object_
    ;

// ----- Inline def lists -----

columnDefList     : LBRACK ( columnInline COMMA? )* RBRACK | columnInline ;
columnInline      : DEF COLUMN id columnDef ;

indexDefList      : LBRACK ( indexInline COMMA? )* RBRACK | indexInline ;
indexInline       : DEF INDEX id indexDef ;

constraintDefList : LBRACK ( constraintInline COMMA? )* RBRACK | constraintInline ;
constraintInline  : DEF CONSTRAINT id constraintDef ;

attributeDefList  : LBRACK ( attributeInline COMMA? )* RBRACK | attributeInline ;
attributeInline   : DEF ATTRIBUTE id attributeDef ;

parameterDefList  : LBRACK ( parameterInline COMMA? )* RBRACK | parameterInline ;
parameterInline   : LBRACE ( paramProperty COMMA? )* RBRACE ;

// ----- Type values -----

dataType
  : typeValue
  | LBRACE dataTypeProperty (COMMA? dataTypeProperty)* COMMA? RBRACE
  ;

dataTypeProperty
  : DATA_TYPE propSep? typeValue
  | LENGTH    propSep? NUMBER_LITERAL
  | PRECISION propSep? NUMBER_LITERAL
  ;

typeValue
  : TEXT | INT | FLOAT | BOOL | DATETIME
  | STRING | BOOLEAN | NUMBER | INTEGER | DOUBLE
  | OBJECT | LIST
  | CHAR | VARCHAR | DECIMAL | DATE | TIMESTAMP
  | id
  ;

indexTypeValue       : PRIMARY | SECONDARY | ORDERED | BTREE | FULLTEXT ;
constraintTypeValue  : UNIQUE | NOT_NULL ;
languageValue        : SQL | TRANSFORMATION_DSL | DATAFRAME_DSL | REL_NODE ;

// ----- Generic value forms -----

propSep : COLON | EQUALS ;

value
  : literal
  | id
  | list
  | object_
  | functionCall
  ;

literal
  : NUMBER_LITERAL
  | stringLiteralForm
  | BOOLEAN_LITERAL
  | NULL_LITERAL
  ;

stringLiteralForm
  : STRING_LITERAL
  | TRIPLE_STRING_LITERAL
  // A plain triple-string whose first line is a bare word + newline (e.g.
  // `"""Ne␊1 = Ano"""` in a description/valueLabels) lexes as TAGGED_BLOCK_LITERAL
  // (that token is declared first and wins). Accept it here and read it as a
  // plain triple-string — the "tag" word is just text. Only `embeddedBlock`
  // (sourceText / definitionSql) actually peels the tag.
  | TAGGED_BLOCK_LITERAL
  ;

// Embedded foreign-language source (embedded-sql DESIGN §2.2). Used by the
// sourceText / definitionSql properties, where a TAGGED_BLOCK_LITERAL has its
// tag peeled into language/dialect. Elsewhere the same token is read as a plain
// triple-string via `stringLiteralForm`.
embeddedBlock
  : TAGGED_BLOCK_LITERAL
  | TRIPLE_STRING_LITERAL
  | STRING_LITERAL
  ;

list
  : LBRACK ( value (COMMA value)* )? COMMA? RBRACK
  ;

listOfStrings
  : LBRACK ( stringLiteralForm ( COMMA stringLiteralForm )* )? COMMA? RBRACK
  ;

// Phase 2.2 — bracketed list of bare ids (used by `roles: [fact, dimension]`).
listOfIds
  : LBRACK ( id ( COMMA id )* )? COMMA? RBRACK
  ;

// `primaryKey` accepts three forms — a quoted-string list (legacy), a bare-id
// list, or a single bare id. Column names are always valid identifiers, so the
// bare forms are the cleaner authoring style (`primaryKey: i_item_sk` /
// `primaryKey: [ss_item_sk, ss_ticket_number]`); the quoted `["i_item_sk"]` form stays valid.
// A list must be all-strings or all-ids (no mixing) — the per-element rules
// keep the two unambiguous.
primaryKeyValue
  : listOfStrings
  | listOfIds
  | id
  ;

// Phase 2.2 — `{ cs: "...", en: "...", de: "..." }` block. Keys are bare
// language tags (BCP-47); values are string literals. Empty block is valid.
localizedString
  : LBRACE ( localizedEntry ( COMMA? localizedEntry )* COMMA? )? RBRACE
  ;

localizedEntry
  : id propSep? stringLiteralForm
  ;

// Phase 2.2 — `value_labels { "1": { cs: "Aktivní", en: "Active" }, "2": { ... } }`
valueLabelsBody
  : LBRACE ( valueLabelEntry ( COMMA? valueLabelEntry )* COMMA? )? RBRACE
  ;

valueLabelEntry
  : stringLiteralForm propSep? valueLabelValue
  ;

// v4.4 S2 (A4-β, RS-12) — a value label is EITHER the legacy localized label
// (`{ cs: "Aktivní", en: "Active" }`) OR the widened per-value form (`{ label: {
// cs: "Aktivní" }, aliases: ["živý"] }`). ONE permissive object rule keeps the
// parser mechanical: each field's value is a string (legacy locale label), a
// `localizedString` (the `label:` map), or a `listOfStrings` (`aliases:`). The
// walker distinguishes the widened form by the `label`/`aliases` keys; per-key
// vocabulary validity is semantic. First-token disambiguation: `"`→string,
// `{`→localizedString, `[`→listOfStrings.
valueLabelValue
  : LBRACE ( valueLabelField ( COMMA? valueLabelField )* COMMA? )? RBRACE
  ;

valueLabelField
  : id propSep? ( localizedString | listOfStrings | stringLiteralForm )
  ;

// Search feature — `{ cs: ["...", "..."], en: ["..."] }` block. Mirrors localizedString
// but each language's value is a list of strings instead of a single string. Empty
// block is valid (a parse-time warning is emitted by the loader's validator).
localizedStringList
  : LBRACE ( localizedStringListEntry ( COMMA? localizedStringListEntry )* COMMA? )? RBRACE
  ;

localizedStringListEntry
  : id propSep? listOfStrings
  ;

// Search feature — body of a `search { ... }` block.
searchBlock
  : LBRACE ( searchSubProperty ( COMMA? searchSubProperty )* COMMA? )? RBRACE
  ;

searchSubProperty
  : keywordsProperty
  | patternsProperty
  | descriptionsProperty
  | examplesProperty
  | aliasesProperty
  | searchableProperty
  | fuzzyProperty
  ;

object_
  : LBRACE propertyList? RBRACE
  ;

propertyList
  : propertyEntry (COMMA? propertyEntry)* COMMA?
  ;

propertyEntry
  : key propSep? value
  ;

key
  : id
  ;

functionCall
  : id LPAREN ( value (COMMA value)* )? RPAREN
  ;

// ----- Identifiers -----
//
// Cross-references (dotted ids) may contain schema-code or object-kind keywords
// as positional components — e.g. `er.entity.objednavka`, `cnc.role.fact`,
// `db.dbo.customers`. ANTLR otherwise tokenises those as their respective
// keyword tokens; we accept them as IDENT-equivalents here so dotted refs work.
//
// `idPart` is the union; `id` is one or more dotted parts.

id : idPart ( DOT idPart )* ;

idPart
  : IDENT
  | INDEX_SECURITY | SKIP_SECURITY | DEFAULT_VALUE | BINDS // 0.21 — soft keywords
  | SUBKIND | PER | UNTIL                                // 0.20 — soft keywords
  | DB | ER | BINDING | MAP | QUERY | CNC                 // schema codes (MAP retained as id fragment only)
  | ROLE | ER2CNC_ROLE                                   // Phase 2.2 kinds
  | TABLE | VIEW | COLUMN | INDEX | CONSTRAINT
  | FK | PROCEDURE | ENTITY | ATTRIBUTE | RELATION
  | ER2DB_ENTITY | ER2DB_ATTRIBUTE | ER2DB_RELATION
  | MODEL | SCHEMA | PROJECT                              // v4.0 freed keywords — usable as id fragments
  | NAME | LABEL | DIRECTION                              // common as identifiers / object keys (e.g. `def column name`)
  | FROM | TO                                            // allowed as object property keys (e.g. cardinality, join pairs)
  | PACKAGE | IMPORT | GRAPH                              // v1.1 new top-level keywords
  | OBJECTS | LAYOUT                                      // v1.1 graph body keywords
  | TTRL | CANVAS                                         // v4.3 `.ttrl` sidecar (keep usable as id fragments)
  // (MAPPING token removed in v3.0 — inline `mapping:` renamed to `binding:`)
  | DRILL_MAP | ARGS | DISPLAY | OVERRIDE          // v2.2
  | PACKAGES | ENTITIES                             // v2.3 (now area body keywords)
  | AREA                                            // v3.0 subject area
  | MD                                              // v3.1 schema code
  | DOMAIN | DIMENSION | HIERARCHY | MEASURE | CUBELET          // v3.1 def kinds (MAP already present)
  | MD2DB_CUBELET | MD2DB_DOMAIN | MD2DB_MAP | MD2ER_CUBELET | MD2ER_DIMENSION    // v3.1 binding kinds
  | RESTRICT | MEMBERS | KIND | CALC | KEY | HIERARCHIES        // v3.1 body keywords
  | PUBLISH                                                     // MD dot-path §1.4 (cross-ref safe)
  | LEVELS | VIA | CLASS | AGGREGATION | VALID_BY | GRAIN
  | MEASURES | SHAPE | JOURNALING | SOURCE
  | ALLOCATION                                                  // v0.10 — md2db_cubelet spread strategy (cross-ref safe)
  | WORLD | ENGINE | EXECUTOR | STORAGE                         // v4.1 world def nouns (cross-ref safe)
  | VERSION                                                     // v4.1 world manifests may carry `version` as a free-form key
  | SEMANTICS                                                   // v4.2 — keeps `semantics` usable as an identifier (WORLD precedent)
  | LEXICON | TERM | PATTERN | EXAMPLE | FOR | FORMS | MATCH | LOCALE  // v4.4 — lexicon nouns/keywords stay usable as id fragments / object keys
  | PATTERNS | EXAMPLES | ALIASES                               // v4.4 — inline `lexicon { patterns, examples }` + `valueLabels { … aliases: [ … ] }` object keys (search/naming sub-props reusable as ids)
  | MANAGEMENT | CHANGE_SEMANTICS | WRITEBACK                   // 0.10 — entry-declaration keywords stay usable as id fragments / object keys (EN-P1)
  | SECURITY | OWN | CLASSIFY | GRANT | MASK | ON               // v0.11 — security-block verbs/keywords stay usable as id fragments / object keys (WORLD precedent; TO already present above)
  | METHOD                                                      // v0.12 — `method` stays usable as an id fragment / object key (RV-P1.5; also makes `matchMethodValue`'s EXACT/TYPOS/TOKENS plain ids)
  | AUTOMATION | PARAMETER | ACTION | SERVICE | APPROVAL | PROGRAM | RECIPE    // 0.16 — automation def nouns (WORLD precedent: usable as names / columns / keys)
  | IDENTITY | DEPLOYMENT | PROCESS | STEP | STEPS                            // 0.16 — automation world members
  | TRANSITION | TRANSITIONS | STATES | SET                                   // 0.16 — automation / lifecycle property keywords
  | RESOLVE | DELIVERY                                                       // 0.16 — automation role property keywords
  | PARAMETERS                                                  // 0.16 — `def recipe … parameters: [a, b]` through the free-form entry
  | CN | EVENT | PHASE | QUALITY | RELATION_TYPE | CONCEPT                   // 0.17 — cn model code, def nouns, the sugar keyword (`cn.Item` refs need CN here)
  | CN2MD_KIND | CN2MD_ROLE | CN2MD_QUALITY | CN2MD_EVENT                    // 0.17 — cn binding kinds (the MD2ER_* precedent)
  | CN2ER_KIND | CN2ER_ROLE | CN2ER_EVENT | CN2ER_QUALITY | CN2ER_PHASE | CN2DB_CONCEPT
  | INVOLVES | PERIOD | GROUNDS | DEFINITION | VALUE_SPACE | EXTENT | BASIS   // 0.17 — cn property keywords
  | AS                                                                       // 0.17 — `involves: [ Warehouse as from ]`; stays a name
  | DEFAULT | SENSE_PRIORITY                                                 // 0.17 — lexicon entry keywords
  // NOTE: EXTENDS/HOSTS/STAGING are intentionally NOT in idPart — see the 4.1
  // header note. Their strict-value properties are negative-fixture guarded, so
  // keeping them out makes a malformed value a hard parse error.
  ;

// =============================================================================
// Lexer
// =============================================================================

DEF        : 'def' ;
SCHEMA     : 'schema' ;     // v4.0 — now the namespace/binding id in `model <code> schema <id>`
PROJECT    : 'project' ;    // v4.0 — `def project` (was `def model`)
// NAMESPACE token deleted in v4.0 — the namespace id now follows `schema`.

PACKAGE    : 'package' ;    // v1.1
IMPORT     : 'import' ;     // v1.1
GRAPH      : 'graph' ;      // v1.1
OBJECTS    : 'objects' ;    // v1.1 graph body
LAYOUT     : 'layout' ;     // v1.1 graph body
TTRL       : 'ttrl' ;       // v4.3 — `.ttrl` view-state sidecar header
CANVAS     : 'canvas' ;     // v4.3 — `.ttrl` canvas block
// MAPPING token removed in v3.0 — inline `mapping:` renamed to `binding:` (uses BINDING)
// DOMAIN token removed in v3.0 — `.ttrd` domain block replaced by `def area`;
// `domain` is freed for the future MD value-set keyword.
PACKAGES   : 'packages' ;   // area body (note: distinct from PACKAGE)
ENTITIES   : 'entities' ;   // area body
AREA       : 'area' ;       // v3.0 — subject area def (replaces the domain block)

DB      : 'db' ;
ER      : 'er' ;
BINDING : 'binding' ;                  // v3.0 — cross-model mapping schema (was `map`)
MAP     : 'map' ;                      // retained for idPart / future MD `def map`; no longer a schemaCode
CNC     : 'cnc' ;                      // Phase 2.2 — conceptual schema
MD      : 'md' ;                       // v3.1 — multidimensional logical schema

MODEL            : 'model' ;
TABLE            : 'table' ;
VIEW             : 'view' ;
COLUMN           : 'column' ;
INDEX            : 'index' ;
CONSTRAINT       : 'constraint' ;
FK               : 'fk' ;
PROCEDURE        : 'procedure' ;
ENTITY           : 'entity' ;
ATTRIBUTE        : 'attribute' ;
RELATION         : 'relation' ;
ER2DB_ENTITY     : 'er2db_entity' ;
ER2DB_ATTRIBUTE  : 'er2db_attribute' ;
ER2DB_RELATION   : 'er2db_relation' ;
QUERY            : 'query' ;
ROLE             : 'role' ;            // Phase 2.2
ER2CNC_ROLE      : 'er2cnc_role' ;     // Phase 2.2
DRILL_MAP        : 'drill_map' ;       // v2.2
ARGS             : 'args' ;            // v2.2 — drill map: target param → source col/literal
DISPLAY          : 'display' ;         // v2.2 — drill map: localised chip label
OVERRIDE         : 'override' ;        // v2.2 — drill map: suppress auto-derived drill with same target

// v3.1 MD model — logical def kinds. DOMAIN re-added (deleted in 3.0); MAP
// already tokenised above and now promoted to a def kind in objectDefinition.
DOMAIN           : 'domain' ;          // v3.1 — MD value-set (also the `domain:` prop keyword)
DIMENSION        : 'dimension' ;       // v3.1 (also the `dimension:` prop keyword)
HIERARCHY        : 'hierarchy' ;       // v3.1
MEASURE          : 'measure' ;         // v3.1
CUBELET          : 'cubelet' ;         // v3.1 (also the `cubelet:` prop keyword)
// v3.1 MD model — binding def kinds (declared before IDENT; keyword wins ties).
MD2DB_CUBELET    : 'md2db_cubelet' ;   // v3.1
MD2DB_DOMAIN     : 'md2db_domain' ;    // v3.1
MD2DB_MAP        : 'md2db_map' ;       // v3.1
MD2ER_DIMENSION  : 'md2er_dimension' ; // 0.18
MD2ER_CUBELET    : 'md2er_cubelet' ;   // v3.1
// v3.1 MD model — body property keywords (enum/string VALUES stay un-minted ids,
// validated in semantics, e.g. `kind: calc`, `journaling: overwrite`).
RESTRICT         : 'restrict' ;        // v3.1
MEMBERS          : 'members' ;         // v3.1
PUBLISH          : 'publish' ;         // MD dot-path arc (contracts §1.4) — `publish: members`
KIND             : 'kind' ;            // v3.1
CALC             : 'calc' ;            // v3.1
KEY              : 'key' ;             // v3.1
HIERARCHIES      : 'hierarchies' ;     // v3.1 (distinct from HIERARCHY; longest-match)
LEVELS           : 'levels' ;          // v3.1
VIA              : 'via' ;             // v3.1
CLASS            : 'class' ;           // v3.1
AGGREGATION      : 'aggregation' ;     // v3.1
VALID_BY         : 'validBy' ;         // v3.1
GRAIN            : 'grain' ;           // v3.1
MEASURES         : 'measures' ;        // v3.1 (distinct from MEASURE; longest-match)
SHAPE            : 'shape' ;           // v3.1
JOURNALING       : 'journaling' ;      // v3.1
ALLOCATION       : 'allocation' ;      // v0.10 — md2db_cubelet writeback spread strategy (R21)
SOURCE           : 'source' ;          // v3.1 (distinct from SOURCE_TEXT 'sourceText'; longest-match)
INDEX_SECURITY : 'indexSecurity' ;
SKIP_SECURITY : 'skipSecurity' ;
DEFAULT_VALUE : 'defaultValue' ;
BINDS : 'binds' ;

// v4.1 world model (ttr-metadata M0). Def-kind nouns + typed-property keywords.
// Declared before IDENT so the keyword wins ties.
WORLD            : 'world' ;           // v4.1 — model code + `def world`
ENGINE           : 'engine' ;          // v4.1 — nested `def engine`
EXECUTOR         : 'executor' ;        // v4.1 — nested `def executor`
STORAGE          : 'storage' ;         // v4.1 — nested `def storage`
EXTENDS          : 'extends' ;         // v4.1 — instance ⊕ type overlay ref
HOSTS            : 'hosts' ;           // v4.1 — storage `hosts: [pkg]`
STAGING          : 'staging' ;         // v4.1 — storage `staging: true`

// 0.16 automation model (AU-core-P0 S0.2). Def-kind nouns, world members, property keywords.
// Declared before IDENT so the keyword wins ties; every one is also in `idPart`.
AUTOMATION       : 'automation' ;      // 0.16 — model code `model automation`
PARAMETER        : 'parameter' ;       // 0.16 — `def parameter` (distinct from PARAMETERS; longest-match)
ACTION           : 'action' ;          // 0.16 — `def action`; also the column type `action`
SERVICE          : 'service' ;         // 0.16 — `def service`
APPROVAL         : 'approval' ;        // 0.16 — `def approval`
PROGRAM          : 'program' ;         // 0.16 — `def program`
RECIPE           : 'recipe' ;          // 0.16 — `def recipe` (generated catalog)
IDENTITY         : 'identity' ;        // 0.16 — world `def identity`
DEPLOYMENT       : 'deployment' ;      // 0.16 — world `def deployment`
PROCESS          : 'process' ;         // 0.16 — world `def process`
STEPS            : 'steps' ;           // 0.16 — process `steps: [ … ]` (distinct from STEP; longest-match)
STEP             : 'step' ;            // 0.16 — process `def step`
TRANSITIONS      : 'transitions' ;     // 0.16 — attribute `transitions: { … }`
TRANSITION       : 'transition' ;      // 0.16 — action `transition: a.b -> s`
STATES           : 'states' ;          // 0.16 — attribute `states: [ … ]`
SET              : 'set' ;             // 0.16 — action `set: a.b = c`
RESOLVE          : 'resolve' ;         // 0.16 — automation role `resolve: keycloak_group "…"`
DELIVERY         : 'delivery' ;        // 0.16 — automation role `delivery: { … }`

// 0.17 cn model (CN-P1 S1.1; cn contracts §1). Model code, def-kind nouns, binding kinds, property
// keywords. Declared before IDENT so the keyword wins ties; every one is also in `idPart` (`event`,
// `period`, `quality`, `default`, `as` … stay usable as names, keys and dotted ref parts). Longest match:
// `definitionSql` stays DEFINITION_SQL, `cnc` stays CNC, `relation_type` beats RELATION, and `as_of`,
// `qualities`, `definitions`, `periods`, `events` stay IDENTs.
CN               : 'cn' ;              // 0.17 — model code `model cn`; the `cn.` ref head
EVENT            : 'event' ;           // 0.17 — `def event`; also a role's / binding's `event:` key
SUBKIND          : 'subkind' ;         // 0.20
PER              : 'per' ;             // 0.20
UNTIL            : 'until' ;           // 0.20
PHASE            : 'phase' ;           // 0.17 — `def phase`; also a binding's `phase:` key
QUALITY          : 'quality' ;         // 0.17 — `def quality`; also a binding's `quality:` key
RELATION_TYPE    : 'relation_type' ;   // 0.17 — `def relation_type` (open tier)
CONCEPT          : 'concept' ;         // 0.17 — `concept { … }` sugar; cn2db_concept's `concept:` key
CN2MD_KIND       : 'cn2md_kind' ;      // 0.17
CN2MD_ROLE       : 'cn2md_role' ;      // 0.17
CN2MD_QUALITY    : 'cn2md_quality' ;   // 0.17
CN2MD_EVENT      : 'cn2md_event' ;     // 0.17
CN2ER_KIND       : 'cn2er_kind' ;      // 0.17
CN2ER_ROLE       : 'cn2er_role' ;      // 0.17
CN2ER_EVENT      : 'cn2er_event' ;     // 0.17
CN2ER_QUALITY    : 'cn2er_quality' ;   // 0.17
CN2ER_PHASE      : 'cn2er_phase' ;     // 0.17
CN2DB_CONCEPT    : 'cn2db_concept' ;   // 0.17
INVOLVES         : 'involves' ;        // 0.17 — `involves: [ … ]`
PERIOD           : 'period' ;          // 0.17 — `period: { … }`
GROUNDS          : 'grounds' ;         // 0.17 — `grounds: <ref>`
DEFINITION       : 'definition' ;      // 0.17 — `definition: """…"""` (distinct from DEFINITION_SQL; longest-match)
VALUE_SPACE      : 'value_space' ;     // 0.17 — `value_space: <kind>`
EXTENT           : 'extent' ;          // 0.17 — `extent: instantaneous | durative`
BASIS            : 'basis' ;           // 0.17 — `basis: occurrence | current_state | past_state`
AS               : 'as' ;              // 0.17 — `involves: [ Warehouse as from ]` (TTR.g4's own; TTRSql has another)
DEFAULT          : 'default' ;         // 0.17 — lexicon `default: true`
SENSE_PRIORITY   : 'sense_priority' ;  // 0.17 — lexicon `sense_priority: 2`

MANAGEMENT       : 'management' ;       // 0.10 — table `management: data|canon` (EN-P1)
CHANGE_SEMANTICS : 'changeSemantics' ;  // 0.10 — table `changeSemantics: scd1|scd2|ledger { … }`
WRITEBACK        : 'writeback' ;        // 0.10 — Q-8 `writeback { … }` reservation (no-op)

DESCRIPTION       : 'description' ;
TAGS              : 'tags' ;
VERSION           : 'version' ;
PRIMARY_KEY       : 'primaryKey' ;
COLUMNS           : 'columns' ;
INDICES           : 'indices' ;
CONSTRAINTS       : 'constraints' ;
ATTRIBUTES        : 'attributes' ;
PARAMETERS        : 'parameters' ;
RESULT_COLUMNS    : 'resultColumns' ;
DEFINITION_SQL    : 'definitionSql' ;
DATA_TYPE         : 'type' ;
OPTIONAL          : 'optional' ;
IS_KEY            : 'isKey' ;
SEARCHABLE        : 'searchable' ;
INDEXED           : 'indexed' ;
LABEL_PLURAL      : 'labelPlural' ;
NAME_ATTRIBUTE    : 'nameAttribute' ;
CODE_ATTRIBUTE    : 'codeAttribute' ;
ALIASES           : 'aliases' ;
CARDINALITY       : 'cardinality' ;
JOIN              : 'join' ;
TARGET            : 'target' ;
WHERE_FILTER      : 'whereFilter' ;
LANGUAGE          : 'language' ;
SOURCE_TEXT       : 'sourceText' ;
LENGTH            : 'length' ;
PRECISION         : 'precision' ;

// Phase 2.2 — localised text + role properties.
LABEL             : 'label' ;
NAME              : 'name' ;
DIRECTION         : 'direction' ;
DISPLAY_LABEL     : 'displayLabel' ;
VALUE_LABELS      : 'valueLabels' ;
ROLES             : 'roles' ;

// Search feature — `search { keywords {...} patterns [...] descriptions {...} examples [...] aliases [...] searchable: true, fuzzy: true }`.
// `aliases` reuses the existing ALIASES token. `description` (single) and `descriptions` (list) are
// distinct lexemes — ANTLR longest-match handles disambiguation.
SEARCH            : 'search' ;
// Grounding Phase 1 (grammar 4.2) — free-form `semantics { … }` block. The body
// is a plain `object_`; all vocabulary/shape checks live in ttr-semantics.
SEMANTICS         : 'semantics' ;
KEYWORDS          : 'keywords' ;
PATTERNS          : 'patterns' ;
DESCRIPTIONS      : 'descriptions' ;
EXAMPLES          : 'examples' ;
FUZZY             : 'fuzzy' ;
// v0.12 (RV-P1.5, RV-32) — the match-method attribute on `searchable`. One new
// token; the method NAMES (EXACT / TYPOS / TOKENS) stay un-minted bare ids
// validated in semantics (the ALLOCATION `proportional`/`equal` precedent), and
// `TYPOS(2)`'s argument reuses NUMBER_LITERAL — no lexer rule is touched.
METHOD            : 'method' ;

// v4.4 lexicon surface (RG-P4). Def-kind nouns + entry keywords + the model
// code + the locale header keyword. Declared before IDENT so the keyword wins;
// PATTERN/EXAMPLE are distinct lexemes from PATTERNS/EXAMPLES (longest-match).
// `text:` reuses TEXT; the inline `terms:` key stays an un-minted bare id.
LEXICON           : 'lexicon' ;   // model code + inline `lexicon { … }` block
TERM              : 'term' ;      // def kind
PATTERN           : 'pattern' ;   // def kind (distinct from PATTERNS)
EXAMPLE           : 'example' ;   // def kind (distinct from EXAMPLES)
FOR               : 'for' ;       // lexicon entry target keyword
FORMS             : 'forms' ;     // term surface forms
MATCH             : 'match' ;     // pattern regex
LOCALE            : 'locale' ;    // unit-level locale header (`model lexicon locale cs`)

FROM : 'from' ;
TO   : 'to' ;

// v0.11 security block (PL-P4.S3, H-1). Verb + `on` keywords for the structured
// `security { own/classify/grant/mask … }` block. Declared before IDENT so the
// keyword wins; all added to `idPart` (WORLD precedent) so `own`/`grant`/`mask`/
// `classify`/`on`/`security` remain usable as ordinary id fragments / object keys.
SECURITY : 'security' ;
OWN      : 'own' ;
CLASSIFY : 'classify' ;
GRANT    : 'grant' ;
MASK     : 'mask' ;
ON       : 'on' ;

TEXT      : 'text' ;
INT       : 'int' ;
FLOAT     : 'float' ;
BOOL      : 'bool' ;
DATETIME  : 'datetime' ;
STRING    : 'string' ;
BOOLEAN   : 'boolean' ;
NUMBER    : 'number' ;
INTEGER   : 'integer' ;
DOUBLE    : 'double' ;
OBJECT    : 'object' ;
LIST      : 'list' ;
CHAR      : 'char' ;
VARCHAR   : 'varchar' ;
DECIMAL   : 'decimal' ;
DATE      : 'date' ;
TIMESTAMP : 'timestamp' ;

PRIMARY   : 'primary' ;
SECONDARY : 'secondary' ;
ORDERED   : 'ordered' ;
BTREE     : 'btree' ;
FULLTEXT  : 'fulltext' ;
UNIQUE    : 'unique' ;
NOT_NULL  : 'notNull' ;

SQL                : 'SQL' ;
TRANSFORMATION_DSL : 'TRANSFORMATION_DSL' ;
DATAFRAME_DSL      : 'DATAFRAME_DSL' ;
REL_NODE           : 'REL_NODE' ;

// Punctuation / operators
ARROW  : '->' ;      // 0.16 — `transition: a.b -> s` (automation bodies only)
EQUALS : '=' ;       // property separator
COLON  : ':' ;
COMMA  : ',' ;
LBRACE : '{' ;
RBRACE : '}' ;
LBRACK : '[' ;
RBRACK : ']' ;
LPAREN : '(' ;
RPAREN : ')' ;
DOTDOT : '..' ;   // v3.1 — range literal (`1..12`); MUST precede DOT (longest-match)
DOT    : '.' ;
STAR   : '*' ;    // v1.1 wildcard for `import x.y.*`

// Literals
NULL_LITERAL          : 'null' ;
BOOLEAN_LITERAL       : 'true' | 'false' ;
NUMBER_LITERAL        : '-'? [0-9]+ ( '.' [0-9]+ )? ( [eE] [+-]? [0-9]+ )? ;
// Tagged embedded-language block: """<tag>\n ... """ (embedded-sql DESIGN §2.1).
// MUST be declared before TRIPLE_STRING_LITERAL: both can match the same span,
// and ANTLR breaks equal-length ties by declaration order, so the tagged form
// wins when a tag + newline is present on the opener line.
TAGGED_BLOCK_LITERAL  : '"""' [a-zA-Z] [a-zA-Z0-9-]* [ \t]* '\r'? '\n' .*? '"""' ;
TRIPLE_STRING_LITERAL : '"""' .*? '"""' ;
STRING_LITERAL        : '"' ( ~["\\\r\n] | '\\' . )* '"' ;

// Identifiers (declared after keywords so keywords win)
// Includes Czech/Latin Extended letters (á, č, ď, é, ě, í, ľ, ň, ó, ř, š, ť, ú, ů, ý, ž, etc.)
IDENT : [a-zA-Z\u00C0-\u024F_][a-zA-Z0-9_\u00C0-\u024F]* ;

// Comments route to the hidden channel so the CST/trivia layer can read them
// (parse-equivalent: the parser still ignores hidden-channel tokens). WS stays
// skipped — the formatter re-derives whitespace (design §10.1).
LINE_COMMENT  : '//' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
WS            : [ \t\r\n]+   -> skip ;
