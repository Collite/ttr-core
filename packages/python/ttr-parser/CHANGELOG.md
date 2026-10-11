# Changelog

All notable changes to `ttr-parser` (Python) are documented here. While
`< 1.0.0`, minor bumps may introduce breaking changes (see each entry).

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
