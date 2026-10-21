# Changelog — TTR Modeler (VS Code)

All notable changes to the `collite.ttr-modeler-vsc` extension. Versions track
the grammar/IDE-support line owned by the `tatrman` repo.

## Unreleased

Grammar 0.17.1 — the cn semantics in the editor.

- **Checked.** cn references resolve (bare in a cn slot, or with the `cn.` head, among concepts only), `grounds:` and
  `concept { }` desugar to bindings, and the model-level `cn/*` diagnostics show as you type (lint category `cn`;
  the hygiene ones off under `recommended`). Goto, find-references and rename work across concept defs, bindings,
  the sugar and lexicon `for:` — a rename keeps an unlabelled participant's label, written out (`Buyer as customer`).
- **Hover on a concept** shows its stereotype, label, grounding and grade (where it lands on the er layer: directly,
  through the md → er hop, inherited from its bearer, or the `md/er-unmapped` reason).
- **"TTR: Concept hygiene report"** — a new command: the conceptual model's hygiene report (grades, groundings, the
  hop, open tier, dangling groundings, redundant terms, phase-as-default) as a markdown preview. Definition blocks are
  checked by `ttr cn hygiene`, not in the editor. Term forms and open-tier entries are escaped, so `*vendor*` previews
  as written.
- **Rename on cn, corrected.** A `concept { }` block that names a concept a cn def declares is a reference to it:
  goto, hover, find-references and rename reach it, and a rename no longer detaches its grounding. A participant label
  renames on its own — the written label (`Store as seller`), or ` as <new>` after a default one, plus the
  `participants:` keys that read it — never the participant; F2 on a label no longer renames the event. A label a
  definition block reads (`this.seller`, `as customer`) is not renamed: the editor says which definition, since it
  does not rewrite definitions. A `concept { }`
  block's declaring name renames its concept, and a concept rename follows `import <pkg>.<Name>`. The conflict check
  stays in the target's namespace: concepts against the package's concepts, labels against the event's labels,
  elements never against cn symbols (`cn.Customer` and `md.Customer` never meet). Hover, goto and rename resolve
  through named imports, and a named concept import is no longer reported unused.
- A `concept { }` on a measure written inline in a cubelet's `measures:` is reported (`cn-grounding-target-kind`): it
  grounds nothing — declare the measure with `def measure`. A `concept { }` block restating its def's `period:` or
  `lexicon { }` in another key order is no longer a `cn-duplicate-grounding`.

Grammar 0.17 — the conceptual model (`model cn`).

- **cn highlighting.** `cn` (and the `cn.` ref head), the concept def nouns (`kind`, `role`, `event`,
  `quality`, `phase`, `relation_type`), the ten `cn2md_*` / `cn2er_*` / `cn2db_concept` binding kinds, the cn
  keys (`involves`, `period`, `grounds`, `definition`, `value_space`, `extent`, `basis`, `concept`) and the
  lexicon `default` / `sense_priority` color as keywords; `as` in `Warehouse as from` as an operator word.
- **Expression blocks.** After `definition:`, `members:` or a binding's `filter:`, a `"""pl` block, an
  untagged triple-string or a string highlights as an embedded `pl` expression (operators, words, literals,
  comments) instead of a plain string; an apostrophe in a comment or an unclosed `/*` can no longer colour
  the rest of the file.
- **Formatting `model cn` / `model binding` files** lays them out canonically (see the grammar CHANGELOG
  0.17); md files, `drill_map` and the world's `engine` / `executor` / `storage` are no longer emptied by
  format-document, and `model lexicon locale <id>` keeps its locale.

## 0.9.9 — 2026-07-17

Syntax-highlighting fixes for the TextMate grammar (no parser/LSP change).

- **`semantics { … }` is highlighted again.** The `semantics` block keyword
  (and the rest of the grammar 4.1–4.4 keyword family — `world`, `lexicon`,
  `term`, `pattern`, `example`, `engine`, `executor`, `storage`, `extends`,
  `hosts`, `staging`, `for`, `forms`, `match`, `locale`) rendered white because
  they were missing from the scope map. They now color as keywords, consistent
  with sibling entity-body fields like `aliases`/`attributes`/`search`.
- **Primitive types and literals now colored.** `text`/`int`/`float`/`bool`/…
  render as types and `true`/`false`/`null` (plus the index-type, constraint-type
  and query-language enums) render as constants — previously these were emitted
  into the scope map but never wired into the grammar output, so they showed as
  plain text.
- **`locale` recolored** to match its directive sibling `schema` (it's the
  `model … locale <id>` header, not a body property).

> Provenance: this listing was previously published from the frozen `modeler`
> fork (through 0.9.8). From 0.9.9 it is published from `tatrman`, the canonical
> owner of the grammar + IDE support; the version series continues unbroken.
