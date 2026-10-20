# Changelog

All notable changes to `ttr-parser` (Python) are documented here. While
`< 1.0.0`, minor bumps may introduce breaking changes (see each entry).

## 0.17.0 — unreleased

- **Grammar 0.17 (additive) — the conceptual model (CN-P1).** `model cn`: `ConceptDef` (`kind =
  "concept"`, `stereotype` = `kind | role | event | phase | quality | relation_type`) and the ten cn
  bindings as `ConceptBindingDef` (`kind = "concept_binding"`, `binding_kind` = the def keyword), both
  carrying a `ConceptBody` — every property in authored order as a `ConceptProperty` (`key` as written,
  `source`): `ConceptDescription`, `ConceptLabel`, `ConceptTags`, `ConceptRef`, `ConceptGrounds`,
  `ConceptValueSpace`, `ConceptInvolves` (`ConceptParticipant`s, `label` for `X as y`), `ConceptPeriod`,
  `ConceptExtent`, `ConceptBasis`, `ConceptDefinition` / `ConceptMembers` / `ConceptFilter` (an
  `EmbeddedBlock` with `line_offsets` and `location()`, so an expression diagnostic lands in the file),
  `ConceptLexicon`, `ConceptTarget` and the free-form `ConceptEntry`. A `def role` in a `model cn` file is a
  concept role (⚑CN-P6); a cnc / automation role property on it is `ttr/unknown-property` (review-114 M5).
  `EntityDef` / `AttributeDef` gain `concept: ConceptSugar | None`; `TermDef` / `PatternDef` /
  `ExampleDef` gain `default` and `sense_priority`. New codes `ttr/duplicate-property` and
  `ttr/expression-block-required`; `EXPRESSION_TAG_REGISTRY` (`pl`) beside the SQL `TAG_REGISTRY`.
- **The md hosts of the sugar:** `MdDomainDef`, `DimensionDef`, `MeasureDef` and `CubeletDef` now parse
  (identity, `description`, `tags` and `concept` only — the md model itself is not ported, so a
  dimension's inline attributes and a cubelet's inline measures, and the `concept { }` on them, still
  drop). `model md` now reads as `md` (it read as `""`).
- One `concept { }` per host: a second is `ttr/duplicate-property` and the first is read. A string-form
  `EmbeddedBlock` carries `escapes` (the `text` index of every escaped character) and `location()` steps
  over them, so a position in an expression with `\"` still lands on the right file character.
- Conformance fixtures 75–79 byte-identical to the TS golden (parse and semantics); 01–74 unchanged.

## 0.16.0 — unreleased

- **Grammar 0.16 (additive) — the automation model.** `model automation` with six def kinds on one
  body: `AutomationDef` (subclasses `AutomationParameterDef` … `AutomationRecipeDef`, `kind` =
  the def keyword) carrying an `AutoBody` (typed fields + ordered `entries: tuple[AutoEntry, ...]`
  of `AutoValue`s: `AutoStr`, `AutoNum`, `AutoBool`, `AutoNull`, `AutoId`, `AutoDuration`,
  `AutoTagged`, `AutoCall`, `AutoStar`, `AutoObj`, `AutoList`). `RoleDef.entries`; `WorldDef`
  gains `identities`, `grants`, `deployments` (`WorldAutoMember`) and `processes` (`ProcessDef`);
  `AttributeDef` gains `states` and `transitions`. `model_for_kind` maps the automation kinds to
  `automation` and the world members to `world`. Conformance fixtures 71–74 byte-identical to TS.

## 0.14.0 — unreleased

- **Grammar 0.14 (additive) — named row schemas (`def schema`).** A new top-level def kind
  `def schema <name> { description?, tags?, columns: [...] }` parses to `SchemaDef`
  (`kind = "schema"`, `columns: tuple[ColumnDef, ...]`); each column is written exactly like a
  table column (`type`, `optional`, …). Tier-neutral — `model_for_kind("schema")` is `db`. The
  conformance dump gains the `schema` kind (fixture 69), byte-identical to the TS golden.

## 0.13.0 — 2026-08-13

- **Grammar 0.13 (additive) — localised `description:` (NLS-P10, ⚑GXP-D7).** The
  walker accepts `description: { en: "…", cs: "…" }` everywhere it accepted a
  string. Every `Definition` gains `description_localized: LocalizedStringValue |
  None`, declared on the `Definition` base
  right after `description`, so a positional construction that passed `tags` fourth
  must switch to keywords (the walker and the shipped code already use keywords
  throughout). `description` keeps the plain form and exactly one of the pair is ever
  set. The walker does not fold a
  map to one locale (that is a reader's job). The conformance dump gains a
  present-only `descriptionLocalized` key, byte-identical to the TS golden.

## 0.1.0

Initial release — parser, walker and reference resolver for the TTR modeling
language (`@grammar-version 2.2`).

- **Parser / walker.** `parse_string` / `parse_file` / `parse_directory` produce
  the typed, frozen AST of the full grammar surface, with source locations on
  every node and lossless triple-string dedent.
- **Semantics.** Symbol table, the six-step reference resolver (lexical →
  same-package → named-import → wildcard-import → stock auto-import →
  fully-qualified), the stock CNC vocabulary, the portable validator subset, and
  the `Project` / `load_project` entry point — shipped together in the one
  package (D8).
- **Pure-Python wheel.** The ANTLR parser is generated at build time and the
  stock CNC vocab is bundled, so the installed wheel needs **no JVM**.
- **Conformance.** The AST and resolution output are pinned byte-for-byte to the
  reference TypeScript/Kotlin implementations.
