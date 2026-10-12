# Tatrman layout — shared test vectors (format 1)

**This folder is published.** It is the contract every implementation of the Tatrman layout format is
tested against — Tatrman's own (the Studio Viewer's TypeScript writer and reader, the JVM
`TtrgLayoutWriter` / `TtrgGraphReader` / `LayoutResolver`) and any third party's. A third party may copy
it (pin the commit it was copied from) and assert the same equalities below. Change a file here only
together with every implementation; the tests read these files directly, never a copy.

The format itself — the document, both serializations, the resolution rule — is described on the docs
page [Layout files](https://docs.tatrman.org/model/layout-files/); the YAML form has a JSON Schema,
[`../layout.schema.json`](../layout.schema.json).

## What is in a case

Each sub-folder is one case:

| file | what it is | what an implementation asserts |
|---|---|---|
| `document.json` | the layout document, canonical: `name`, `model`, `objects` (sorted), `positions` (keys sorted, integer coordinates); `skin` / `collapsed` only when present | — the reference value |
| `layout.yaml` | the YAML serialization | `writeYaml(document) == layout.yaml` **byte for byte**; `readYaml(layout.yaml) == document`; the file validates against `layout.schema.json` |
| `layout.ttrg` | the TTR serialization — one `.ttrg` graph block, positions in its in-file `layout:` block | `writeTtrg(document) == layout.ttrg` byte for byte; `readTtrg(layout.ttrg) == document` on `name`, `model`, `objects`, `positions` (a `.ttrg` does not carry `skin` or `collapsed`) |
| `model.json` | the model index the case resolves against: `cards` (entities, tables, views) and `edges` (relations, foreign keys), each `{ fullQname, qname, kind, name, pkg, schema }` | — input to resolution |
| `expect.json` | the resolution of `document.objects` against `model.json` for `document.model`: `resolved` (ref → full qname), `missing`, `ambiguous` (`{ ref, candidates }`), `ignored` — keys and lists in the canonical (alphabetical) order | `resolve(document.objects, model, document.model) == expect.json` |
| `input.ttrg` + `input.ttrl` | (`sidecar-wins` only) a `.ttrg` and its `.ttrl` sidecar whose positions differ | reading the pair gives `document` — the sidecar's positions and skin win |

## The cases

| case | what it pins |
|---|---|
| `minimal` | three cards, no positions: no `positions:` key in YAML, no `layout:` block in TTR |
| `positions` | positions, including a negative coordinate |
| `diacritics` | Czech letters in qnames — written raw (UTF-8, never `\u` escapes) and bare in TTR (the grammar's identifiers admit U+00C0–U+024F); alphabetical order — `příjmové_pásmo` before `prodejna`, which code-point order would swap |
| `bare-names` | the four reference forms, one per resolution step: full qname · `er.entity.store` · `entity.store_sales` · `item`; a position keyed by a bare name |
| `ambiguous` | a name two packages define: the bare form and the `qname` form are ambiguous (both candidates listed, never guessed); the full qname still resolves |
| `missing` | a ref the model does not have, and one that is not an identifier at all (`"Customer Address"` — a label, not a ref): both kept, both reported missing; the latter quoted in YAML and in TTR |
| `relation-ignored` | relations listed in `objects` (hand-authored, sample style): ignored, never missing — one known to the model's `edges`, one recognised by its qname's `relation` segment |
| `sidecar-wins` | a `.ttrg` with in-file positions plus a `.ttrl` sidecar with others: the sidecar wins and contributes the skin |
| `db-tables` | a `db` layout: tables and a view by each form, a foreign key ignored, a bare name that is both an `er` entity and a `db` view resolving to the view; a hyphenated skin |
| `skin-collapsed` | the optional YAML keys after `positions`, in order |
| `empty` | no cards: `objects: []` in both serializations |

## The canonical form, in one place

- `objects` de-duplicated and sorted **alphabetically**: compare the strings' *keys* code point by code
  point — the key is the string in Unicode NFD with the nonspacing marks (general category `Mn`) removed,
  lower-cased (`hartland.er.entity.příjmové_pásmo` → `hartland.er.entity.prijmove_pasmo`) — and break a tie
  by comparing the strings themselves code point by code point. No locale is involved: `a < č < d < z`,
  `Item < item < item_class`, `čb < cz`. `positions` keys sorted the same way; `collapsed` sorted and
  de-duplicated.
- Coordinates are integers: a reader accepts any number, a writer rounds to the nearest integer with
  halves toward +∞ (`floor(x + 0.5)`: `319.5 → 320`, `-319.5 → -319`).
- LF line endings, a trailing newline, no trailing spaces.
- **YAML:** the first line is `# Tatrman layout — https://docs.tatrman.org/model/layout-files/ (format 1)`
  (a generated file may add `# …` lines right after it); then `layout`, `name`, `model`, `objects`,
  `positions`, `skin`, `collapsed`, in that order, empty optional keys omitted, an empty card list written
  `objects: []`. Lists in block style with two-space indent (`  - ref`), positions in flow style
  (`  ref: { x: 320, y: 180 }`). A string is written plain when it matches
  `^[A-Za-z_\u00C0-\u024F][A-Za-z0-9_.\u00C0-\u024F-]*$` and is not, lowercased, one of `true false yes
  no on off y n null`; otherwise double-quoted, with `\` and `"` escaped by a backslash.
- **TTR:** `graph <name> {`, then two-space indented `model: <model>,`, `objects: [ … ]` one ref per line
  (four spaces, comma-separated; `objects: []` when empty) and — only when there are positions —
  `layout: { nodes: { … } }` with one `ref: { x: N, y: N }` per line (six spaces, comma-separated); `}`.
  A ref is written bare when it is a TTR identifier (dot-separated parts, each matching
  `[A-Za-z_\u00C0-\u024F][A-Za-z0-9_\u00C0-\u024F]*` and not one of the grammar keywords its `idPart` rule
  does not admit — `text`, `date`, `true`, `null`, `description`, …), otherwise as a string literal with
  `\` and `"` escaped. A `.ttrg` never carries `skin`, `collapsed`, a viewport or edges.

The model indexes use the names of Hartland, Collite's public demo model (`hartland.er.entity.item`,
`hartland.db.dbo.item`; the Czech names are Hartland's Czech labels). `ambiguous` splits it into two
packages, `hartland.store` and `hartland.web`, to have a name defined twice.
