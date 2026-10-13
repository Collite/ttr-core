<!-- SPDX-License-Identifier: Apache-2.0 -->
# Layout files

*Reference. The layout document — one named picture of a model: which cards it shows and where they
sit — in its two file forms, how its references find the model's objects, and the published schema and
test vectors any other implementation is held to.*

A **layout** is a schema (`er` or `db`), the **cards** it shows — entities for `er`, tables and views for
`db` — and, optionally, their **positions**. It is written either as a YAML file or as a TTR `.ttrg`
graph file; the two say the same thing in the same words, and every reader accepts both.

You rarely write one by hand: the Studio Viewer draws it — pick the cards, drag them, **Save as…** — and
opens one back ([Layouts in the Studio Viewer](../operate/studio-viewer.md#layouts-pick-place-save)).

## The document

| field | required | what it is |
|---|---|---|
| `name` | yes | an identifier — ASCII letters, digits and `_`, not starting with a digit. A writer also uses it as the file name (`<name>.yaml`, `<name>.ttrg`). |
| `model` | yes | `er` or `db` (`md` and `cnc` are admitted, not yet used). |
| `objects` | yes | the cards shown, as references. A writer lists **full qnames** — the package, then the object's qname: `hartland.er.entity.item`, `hartland.db.dbo.item`. May span packages. |
| `positions` | no | `{ <reference>: { x, y } }` for cards placed by hand. Every key must also be in `objects`. Empty or absent means the viewer lays the cards out itself. |
| `skin` | no | the notation (`er.crow`, `db.table-classic`). YAML only. |
| `collapsed` | no | reserved; carried, not acted on. YAML only. |

A layout lists **cards, not relations.** A relation (or a foreign key) is drawn whenever both of its ends
are shown, so it is never written into `objects`; one that a hand-written file lists anyway is
*ignored* — accepted, reported, never an error. Nothing else is stored: whether the layout is manual
(it has positions), the edges, and which references are missing are worked out when it is read. The
viewport — pan and zoom — is never part of a layout.

## YAML

```yaml
# Tatrman layout — https://docs.tatrman.org/model/layout-files/ (format 1)
layout: 1
name: sales_overview
model: er
objects:
  - hartland.er.entity.item
  - hartland.er.entity.store
  - hartland.er.entity.store_sales
positions:
  hartland.er.entity.item: { x: 320, y: 180 }
  hartland.er.entity.store: { x: 580, y: 180 }
  hartland.er.entity.store_sales: { x: 320, y: 400 }
```

- `layout: 1` is the **format version** and is required. A reader that meets a higher version refuses the
  whole file — *layout format 2 is newer than this reader* — rather than read part of it.
- An **unknown key is an error**, named with its line, anywhere in the file — a misspelt `positons:` is
  reported, not skipped.
- A reader accepts any YAML of this shape: block or flow style, quoted or bare keys, any key order,
  comments anywhere, `.yaml` or `.yml`.
- Hand-written files may use the shorter reference forms below — this is a complete layout:

```yaml
layout: 1
name: returns
model: er
objects: [item, store, store_returns]
```

## TTR

```
graph sales_overview {
  model: er,
  objects: [
    hartland.er.entity.item,
    hartland.er.entity.store,
    hartland.er.entity.store_sales
  ],
  layout: {
    nodes: {
      hartland.er.entity.item: { x: 320, y: 180 },
      hartland.er.entity.store: { x: 580, y: 180 },
      hartland.er.entity.store_sales: { x: 320, y: 400 }
    }
  }
}
```

- A writer produces **one `.ttrg`**: the `graph` block, with the in-file `layout:` block only when there
  are positions. It never writes `skin` or `collapsed` (the graph block cannot carry them), a viewport, or
  edges.
- A reader also accepts the positions in a **`.ttrl` sidecar** next to the `.ttrg` — `<name>.ttrl` with a
  `canvas <name> { … }` block, or the `canvas main { … }` block the Studio writes when it has no canvas named
  after the graph. When both carry positions, **the sidecar wins**; it also supplies the skin and `collapsed`. A
  sidecar is view state the Studio rewrites as you work, so a position for a card the graph no longer lists, or
  nodes under `mode: auto`, are dropped with a warning instead of failing the file; so is a property a layout
  does not use (`viewport`, …). Node keys and `collapsed` items may be bare or quoted.
- References are written bare, as TTR identifiers (Czech letters included: `hartland.er.entity.zákazník`).
  The TTR grammar admits nothing else in `objects` or as a `nodes` key, so a layout holding a reference
  that is not an identifier has **no TTR form**: a label typed by hand (`"Customer Address"`), or the name
  of a table that is a TTR keyword (`hartland.db.dbo.date` — `date`, `type`, `text`, … are keywords).
  Save as… then offers YAML only and names the references; remove them, or keep the YAML file. Layout
  readers still accept a quoted reference in a hand-written `.ttrg`, but the TTR language server reports it
  as a syntax error.
- Hand-written graph files that also carry `description`, `tags`, or relations in `objects` load fine; the
  first two are reported as not part of a layout, the relations are ignored.

## How references resolve

A reference in `objects` (or a `positions` key) is looked up among the cards of the layout's `model`, in
four steps. The **first step that matches decides**; a step that matches more than one card makes the
reference **ambiguous**, and it is reported with its candidates — never guessed.

| step | form | example | matched against |
|---|---|---|---|
| 1 | `<package>.<qname>` | `hartland.er.entity.store` | the exact full qname |
| 2 | `<qname>` | `er.entity.store`, `db.dbo.store` | the object's qname, in any package |
| 3 | `<kind>.<name>` | `entity.store`, `table.store`, `view.channel_sales` | kind and name, in any package |
| 4 | `<name>` | `store` | the name of a card of this layout's schema, in any package |

- **Missing** — no step matches. The reference stays in the file and is reported, never dropped: the model
  may have renamed or removed the object, and that is for a person to decide.
- **Ambiguous** — the deciding step matched cards in two or more packages. Write a longer form.
- **Ignored** — no card matches, but the reference names a relation or a foreign key (by the same four
  forms, or by a qname whose kind segment is `relation`, `fk` or `foreign_key`).

A bare `item` in an `er` layout is the entity `item`; in a `db` layout it is the table `item` — the step
looks only at the layout's own schema. Writers always write step 1, so a saved file never depends on the
others.

## The canonical form

Writers produce one exact text for one document, so saving an unchanged layout is never a diff in git:
`objects` sorted alphabetically (letters with diacritics beside their base letter, case ignored; the same
order on every machine, no locale involved) and without duplicates, `positions` keys sorted,
coordinates rounded to whole numbers, LF line endings and a final newline, the YAML keys in the order
shown above. The precise rules — down to when a YAML string is quoted — are written out with the test
vectors.

## Schema and test vectors

- **JSON Schema** (draft 2020-12) for the YAML form:
  [`layout.schema.json`](layout.schema.json) — its `$id` is this site's copy; the source is
  `packages/package-sdk/schemas/layout.schema.json` in [Collite/ttr-core](https://github.com/Collite/ttr-core).
- **Test vectors**: `packages/package-sdk/schemas/layout-vectors/` in the same repository. Each case holds a
  document (`document.json`), its YAML and TTR files, a model index and the expected resolution, and its
  `README.md` states exactly what an implementation asserts: the writers reproduce both files **byte for
  byte**, the readers give the document back, and resolution gives the expected result. Tatrman's own
  readers and writers — the viewer's and the JVM libraries' — are tested against these files; another
  implementation can copy the folder at a pinned commit and run the same checks.
