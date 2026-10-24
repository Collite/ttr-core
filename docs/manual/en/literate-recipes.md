# Literate recipes

A literate recipe's Markdown document is its source. `ttrp build` derives canonical TTR-P, a `.ttrd` provenance
map, a recipe bundle, and an explanation README. Commit the document and its derived artifacts together.

```sh
ttrp build releasing-a-placed-order.md
ttrp build releasing-a-placed-order.md --check
ttrp build releasing-a-placed-order.cs.md --lang cs --out generated
```

`--check` derives in memory, compares canonical bytes, semantic `.ttrd` content including `documentSha`, and every derived README's bytes in the requested `--lang`, and exits **3** with
`TTRP-DOC-003` on drift or missing artifacts. It writes no files. Every Markdown edit requires a rebuild, including prose-only edits. Checks compile against the current model, render explanations in memory, and write no artifacts.

Start with a `ttrp preamble` fence containing `uses world` and imports. Without a preamble, these lines are read
from the first skeleton. Put a `ttrp` skeleton before its containers:

````markdown
# Releasing an order

Consider every placed order.

```ttrp preamble
uses world "hartland.worlds.demo"
import hartland.*
import hartland.automation.*
```

```ttrp
recipe: release_order
recipe {
    key: order.order_number
    as of: now
    parameters: [tolerance]
}
```

Read the placed orders.

```sql candidates placed_orders(out orders) target erp
SELECT o.order_number FROM er.entity.order o WHERE o.state = 'placed'
```
````

Fence tags are `ttrp`, `sql`, `ttrb`, `ttrb-cs`, and `python`. A fence's info string supplies the complete
container header; without one, its first body line supplies the header. A `ttrp` fence may instead contain a
complete canonical decision, container, or actions block. The canonical parser validates headers. Fragment
interiors remain verbatim; the Markdown walker and formatter never rewrite them.

Each container belongs to the preceding skeleton and implements the nearest preceding paragraph. The derived
`.ttrd` records container identities, fence indices, Markdown spans, canonical spans and sentence associations.
Containers before a skeleton are `DOC-001`; duplicate skeleton/container names are `DOC-002`; unsupported fence
languages are `DOC-004`.

For several recipes, give **each** skeleton a `recipe: <name>` line. Outputs are `<name>/<name>.ttrp` and
`<name>/<name>.ttrd` beside the document, plus `<name>/README.md` and `<name>.bundle/README.md`. A single unnamed
skeleton derives `<document-basename>.ttrp` and `.ttrd`. Its recipe identifier drops an `.en`/`.cs` language suffix, normalizes punctuation to underscores, and prefixes a leading digit with `_`. `--out` changes the output root. Plain `.ttrp` recipes also
receive the explanation README inside their bundle. READMEs list every route's reason and detail contexts, reads
with catalogue labels, action wiring and dry-run descriptions, parameter values and labels, and the mode verdict.
`--lang en|cs` changes presentation; identifiers and canonical recipe bytes remain unchanged.

Annotations are immutable per document SHA. Readers ignore unknown fields and types; unknown types are skipped with a warning. The producer JSON schema remains strict. The optional `<recipe>.annotations.json` lives beside the source
or derived recipe. Its published [schema](../../features/ttr-p/architecture/schemas/annotation-record.schema.json)
and `org.tatrman:ttrp-doc` types describe proposals and human reviews. A human `Verdict` rejecting a `Link` removes
only that block/sentence association from `.ttrd`; it cannot edit recipe code. A record for another document SHA
is refused. The Platform resolver owns annotation synthesis.

For Hartland and ai-models CI, use the reusable `ttrp-check.yml` workflow with a pinned toolchain version. It checks
all matching Markdown recipes after checking out the consumer repository. Both consumer repositories can use the same job (replace `TOOLCHAIN_TAG` and `PUBLIC_VERSION` with the reviewed release pin):

```yaml
jobs:
  recipes:
    uses: Collite/ttr-core/.github/workflows/ttrp-check.yml@TOOLCHAIN_TAG
    with:
      toolchain-version: PUBLIC_VERSION
      recipes: 'recipes/**/*.md'
```

The workflow installs the pinned public CLI distribution from Maven Central and executes every matched source;
no matched files is an error. Hartland can point `recipes` at its literate automation directory; ai-models can point
it at the corresponding package documents. For an already installed toolchain:

```sh
find recipes -name '*.md' -print0 | while IFS= read -r -d '' file; do
  ttrp build "$file" --check
done
```

The AU fixture documents in `packages/kotlin/ttrp-doc/src/test/resources/docs/` show the complete H-1 and the three
H-2 recipes, with committed derived programs and provenance. The canonical writer fixes member separators while
preserving each block interior; executable statement goldens prove equivalence to the original `17` fixtures.

TTR-B recipe examples:

```text
Keep the latest snapshot per item and warehouse.
Keep only rows where placed_date is within the last 30 days.
Compute days_open as the working days since decided_date.
Decide hold_sales when n_short > 0, because "stock shortfall", showing shortfall.
Decide; a row may take several routes.
Otherwise decide release.
```

A latest-snapshot sentence infers only a unique temporal key attribute in the imported model (including a calendar
surrogate key). If the date is ambiguous, write `Keep the latest snapshot by captured_on per item and warehouse.`
Parameter mentions such as `the price tolerance` resolve through imported parameter labels; `:tolerance` remains
available. A decision's pipeline precedes its route sentences. Fixed Czech `Rozhodni`, `protože`, `s detailem`,
`Rozhoduj výlučně` and `Jinak nic` forms share the same AST; morphology-dependent forms remain with LM/NLS (D-64).
The optional `TtrbRecipeWiring.parse` API reads `Do release with order as order_number.` with explicit routes
supplied by the canonical skeleton. It never invents which verdict routes trigger the action (D-30).
