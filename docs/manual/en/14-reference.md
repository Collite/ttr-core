# Reference

A lookup chapter, not a tutorial. It collects the definition kinds, the type system, the property vocabulary, the diagnostic catalog, a grammar cheat-sheet, and one complete worked model.

## Models

A *model* is a perspective on the data, selected with the `model` directive. Only the `db` model carries a **schema** (a SQL namespace such as `dbo`); the others have none.

| Model | Purpose | Has schema? |
|---|---|---|
| `db` | Physical database — tables, columns, keys, indexes, FKs, queries, procedures | yes (`dbo`, …) |
| `er` | Conceptual — entities, attributes, relations | no |
| `binding` | Correspondence between `er` and `db` (`er2db_*`, `md2*`) | no |
| `cnc` | Semantic roles (fact, dimension, …) | no |
| `md` | Multidimensional logical model ([MD model](15-md-model.md)) | no |

A file selects a model with `model <code> [schema <id>]` (the `schema` only for `db`). A `.ttrg` graph names its model with `model: <code>`. There is no separate `query` model — `def query` and `def procedure` are `db`-layer objects (grammar 4.0).

## File kinds

| Extension | Holds | Top-level block |
|---|---|---|
| `.ttrm` | model definitions | `def …` (many) |
| `.ttrg` | one curated diagram ([Graphs](11-graphs.md)) | `graph <id> { … }` |

The two are mutually exclusive per file; mixing them is a `wrong-file-kind` error. An **area** — a reusable model slice ([Areas](10-packages-and-imports.md#areas)) — is a plain `def area` and lives in an ordinary `.ttrm` file, so it needs no file kind of its own. An area only *references* packages and entities; the metadata loader does not load it.

## Definition kinds

| Kind | Model | Key properties |
|---|---|---|
| `project` | (file header) | `version`, `description`, `tags` — the whole-artifact header (was `def model`) |
| `table` | `db` | `primaryKey`, `columns`, `indices`, `constraints`, `description` |
| `view` | `db` | `columns`, `definitionSql`, `description` |
| `column` | `db` | `type`, `optional`, `isKey`, `indexed`, `description` |
| `index` | `db` | `type` (`primary`/`secondary`/`ordered`/`btree`/`fulltext`), `columns` |
| `constraint` | `db` | `type` (`unique`/`not_null`), `columns` |
| `fk` | `db` | `from`, `to` (lists of column paths), `description` |
| `query` | `db` | `language`, `parameters`, `sourceText` |
| `procedure` | `db` | `parameters` (with `direction`), `resultColumns` |
| `entity` | `er` | `nameAttribute`, `codeAttribute`, `attributes`, `roles`, `displayLabel`, `aliases`, `labelPlural`, `binding` |
| `attribute` | `er` | `type`, `isKey`, `optional`, `valueLabels`, `displayLabel`, `binding` |
| `relation` | `er` | `from`, `to`, `cardinality`, `join`, `binding` |
| `er2db_entity` | `binding` | `entity`, `target` (`{ table }`), `whereFilter` |
| `er2db_attribute` | `binding` | `attribute`, `target` (`{ column }`) |
| `er2db_relation` | `binding` | `relation`, `fk` |
| `role` | `cnc` | `label` (localized), `description` |
| `er2cnc_role` | `binding`/`cnc` | `entity`, `role` |
| `drill_map` | `db` | `from`, `to`, `args`, `display`, `override` |
| `schema` | (any — tier-neutral) | `columns` (each a `def column` with `type`, `optional`) — a named row shape (grammar 0.14); TTR-P holds `display(<name>)` rows to it |

Every kind also accepts `description` and `tags`. Many `er`/`db` kinds also accept a `search { … }` block.

## Type system

A `type` is a simple name or a structured object `{ type: <name>, length: <n>, precision: <n> }`.

| TTR type | Closest SQL | Notes |
|---|---|---|
| `int`, `integer` | `INT` | |
| `float`, `double` | `FLOAT` / `DOUBLE` | |
| `decimal`, `number` | `DECIMAL(length, precision)` | use the structured form for scale |
| `bool`, `boolean` | `BIT` / `BOOLEAN` | |
| `text`, `string` | `TEXT` / `NVARCHAR(MAX)` | |
| `char`, `varchar` | `CHAR(n)` / `VARCHAR(n)` | `length` gives `n` |
| `date` | `DATE` | |
| `datetime`, `timestamp` | `DATETIME` / `TIMESTAMP` | |
| `object`, `list` | — | structured/composite values |

```ttr
type: int
type: { type: varchar, length: 40 }
type: { type: decimal, length: 19, precision: 2 }
```

## Common properties

| Property | Applies to | Value |
|---|---|---|
| `description` | all | string or `"""triple-quoted"""` |
| `tags` | all | list of strings |
| `type` | column, attribute, parameter | type value |
| `optional` | column, attribute | boolean (default `false`) |
| `isKey` | column, attribute | boolean |
| `indexed` | column | boolean |
| `primaryKey` | table | list of column-name strings |
| `columns` | table, view | inline `def column` list |
| `indices` / `constraints` | table | inline `def index` / `def constraint` list |
| `from` / `to` | fk, relation | column paths (fk) or entity paths (relation) |
| `cardinality` | relation | `{ from: "...", to: "..." }` |
| `join` | relation | `[{ from: <attr>, to: <attr> }]` |
| `nameAttribute` / `codeAttribute` | entity | attribute name |
| `roles` | entity | list of role names |
| `displayLabel` | entity, attribute | localized string `{ en: "...", cs: "..." }` |
| `valueLabels` | attribute | `{ "1": { en: "..." }, … }` |
| `aliases` | entity | list of strings |
| `target` | er2db_entity/attribute | `{ table: … }` or `{ column: … }` |
| `whereFilter` | er2db_entity | object filter, e.g. `{ active: 1 }` |
| `language` | query | `SQL` / `TRANSFORMATION_DSL` / `DATAFRAME_DSL` / `REL_NODE` |
| `sourceText` | query | string |
| `parameters` | query, procedure | `[{ name, type, label, direction? }]` |
| `direction` | procedure parameter | `IN` / `OUT` / `INOUT` |

Cardinality multiplicities: `1`, `0..1`, `1..*`, `0..*`, `*`, `1..n`, `n`.

## Diagnostic catalog

| Code | Severity | Meaning / fix |
|---|---|---|
| `ttr/unresolved-reference` | Error | A reference points at nothing. Check the path and spelling. |
| `ttr/unimported-reference` | Error | Bare reference to a package you did not `import`. Add the import or use the full path. |
| `ttr/ambiguous-reference` | Error | A bare name two wildcard imports both provide. Qualify it. |
| `ttr/package-declaration-mismatch` | Warning¹ | Declared `package` ≠ folder path (leaf segment). Rename the folder or the declaration. |
| `ttr/package-prefix-divergence` | Warning¹ | A non-leaf segment of the declaration diverges from the folder — orphans the file from path resolution. |
| `ttr/invalid-package-segment` | Warning¹ | A folder segment is not a valid identifier (hyphen, space, leading digit, …) and no `package` declaration overrides it. Rename the folder (use underscores) or add an explicit declaration — no `-`→`_` rewriting happens. |
| `ttr/wrong-file-kind` | Error | A `def`/`graph` in the wrong file kind (see [File kinds](#file-kinds)). Move it. |
| `ttr/unused-import` | Warning | Import nothing uses. Delete it. |
| `ttr/duplicate-import` | Warning | Same import twice. Remove the duplicate. |
| `ttr/wildcard-with-no-matches` | Warning | `import x.*` where `x` has no definitions. |
| `ttr/circular-package-dependency` | Warning | A imports B and B imports A. Break the cycle. |
| `ttr/graph-object-not-found` | Warning | A `.ttrg` lists a qname that no longer resolves. Update the graph. |
| `ttr/area-member-not-found` | Warning | A `def area` `packages:`/`entities:` member resolves to nothing. |
| `ttr/area-empty` | Warning | A `def area` with no members. |
| `ttr/duplicate-area` | Error | Two `def area` definitions declare the same area name. |
| `ttr/area-redundant-member` | Info | An `entities:` entry already covered by a recursive `packages:` member. |
| `ttr/missing-package-declaration` | Info | File is in the default package. Add a `package` line. |

¹ Package-mismatch severities are set by `modeler.toml [packages] layout` — `flexible` (default) reports `package-declaration-mismatch` as a Warning, `strict` as an Error, `off` not at all; `package-prefix-divergence` and `invalid-package-segment` are a Warning under `flexible`/`off` and an Error under `strict` (never silenced). See [Packages, imports, and areas](10-packages-and-imports.md#packages-and-folders).

(Plus per-kind validation, e.g. a `duplicate-binding` when one attribute is bound both inline and in the `binding` model.)

### MD (multidimensional model)

The `md` model and the `binding`-model `md2*` kinds (see [MD model](15-md-model.md)).
**Logical kinds:** `def domain` / `dimension` / `map` / `hierarchy` / `measure` /
`cubelet`. **Binding kinds:** `def md2db_cubelet` / `md2db_domain` / `md2db_map` /
`md2er_cubelet`.

| Code | Severity | Meaning |
|---|---|---|
| `md/unknown-schema-def` | Error | An MD logical def outside `model md`, or a binding def outside `model binding`. |
| `md/unknown-ref` | Error | A `domain`/`map`/`dimension`/`measure`/`hierarchy` reference doesn't resolve. |
| `md/attr-needs-domain` / `md/attr-type-in-md` | Error | An MD attribute is missing `domain:`, or carries the ER-only `type:`. |
| `er/attr-domain-in-er` | Error | An ER attribute carries the MD-only `domain:`. |
| `md/kind-on-scalar` | Error | `kind:` on a continuous (scalar) domain. |
| `md/bad-restrict-value` / `md/unknown-restrict-clause` | Error / Warning | A restrict clause value has the wrong shape / an unrecognised clause name. |
| `md/bound-domain-no-source` / `md/source-on-unbound-domain` | Error | A `kind: bound` domain with no `md2db_domain`, or an `md2db_domain` on a non-bound domain. |
| `md/unknown-calc-map` / `md/bad-calc-args` / `md/calc-type-mismatch` / `md/calc-cardinality-conflict` | Error | Calc-map name / args / `from`-`to` type / explicit `1:1` problems. |
| `md/table-map-no-binding` | Warning | A table-backed map with no `md2db_map`. |
| `md/binding-on-calc-map` / `md/map-columns-incomplete` | Error | `md2db_map` on a calc map / not covering every from/to domain. |
| `md/no-hierarchy-step` / `md/ambiguous-hierarchy-step` / `md/level-not-in-dim` | Error | Hierarchy step inference: no map / >1 map (add `via`) / level not in the dimension. |
| `md/semiadditive-no-validby` / `md/nonadditive-recompute-unsupported` | Error | Additivity consistency (semi-additive latest-valid needs `validBy`; non-additive recompute is v1.1). |
| `md/grain-ref-unknown` / `md/grain-not-leaf` | Error / Warning | A cubelet grain ref doesn't resolve / is coarser than another in the grain. |
| `md/shape-measure-mismatch` / `md/incomplete-journaling` / `md/multisource-grain-mismatch` | Error | Binding shape↔measure form / writeback completeness / multi-source grain agreement. |
| `md/md2er-physical-prop` | Error | An `md2er_cubelet` (structural-only) carries shape/measures/journaling. |

## Grammar cheat-sheet

```
file        : package? import* (model | graph)? definition*
package     : 'package' qualifiedName
import      : 'import' qualifiedName ('.' '*')?
model       : 'model' (db|er|binding|cnc|md|query) ('schema' id)?   // 'schema' for db only
definition  : 'def' kind id '{' property* '}'
graph       : 'graph' id '{' graphProperty* '}'        // .ttrg only; body: model: <code>, objects, layout
// 'area' is one of the def kinds — a plain def in any .ttrm file:
//   def area <id> { (description | tags | packages | entities)* }
areaProp    : ('packages'|'entities') ':' '[' path* ']' | description | tags
// 'schema' (0.14) — a named row shape, columns written exactly like a table's:
//   def schema <id> { (description | tags | columns: [ def column <id> { type: t, optional: true } ... ])* }

property    : key (':' | '=') value
value       : string | number | boolean | null | id | list | object
list        : '[' (value (',' value)*)? ','? ']'
object      : '{' (entry (','? entry)*)? ','? '}'
string      : "..."  |  """ ... """
localized   : '{' (langTag (':'|'=') string)* '}'   // { en: "...", cs: "..." }
path        : id ('.' id)*                           // er.entity.customer.id
```

Commas between properties are optional; trailing commas are allowed; `//` and `/* */` are comments.

## A complete worked model

The full retail example is under [`examples/retail/`](examples/retail/). One self-contained package — `shop.catalog` — shows all three core models working together:

```ttr
// shop/catalog/category.ttrm
package shop.catalog
model er

def entity category {
    description: "A grouping of products, e.g. Beverages or Stationery.",
    nameAttribute: name,
    codeAttribute: code,
    roles: [dimension],
    attributes: [
        def attribute id   { type: int, isKey: true },
        def attribute code { type: text, optional: true },
        def attribute name { type: text, optional: true }
    ]
}
```

```ttr
// shop/catalog/product.ttrm
package shop.catalog
model er

def entity product {
    description: "A sellable item in the catalog.",
    nameAttribute: name,
    codeAttribute: sku,
    roles: [dimension],
    attributes: [
        def attribute id          { type: int, isKey: true },
        def attribute sku         { type: text, optional: true },
        def attribute name        { type: text, optional: true },
        def attribute unit_price  { type: { type: decimal, length: 19, precision: 2 }, optional: true },
        def attribute category_id { type: int, optional: true }
    ]
}

def relation product_category {
    from: er.entity.product,
    to: er.entity.category,
    cardinality: { from: "0..*", to: "1" },
    join: [{ from: er.entity.product.category_id, to: er.entity.category.id }]
}
```

```ttr
// shop/catalog/db.ttrm
package shop.catalog
model db schema dbo

def table CATEGORY {
    primaryKey: ["CATEGORY_ID"],
    columns: [
        def column CATEGORY_ID { type: int, isKey: true },
        def column CODE        { type: text, optional: true, indexed: true },
        def column NAME        { type: text, optional: true }
    ],
    constraints: [ def constraint UQ_CATEGORY_CODE { type: unique, columns: ["CODE"] } ]
}

def table PRODUCT {
    primaryKey: ["PRODUCT_ID"],
    columns: [
        def column PRODUCT_ID  { type: int, isKey: true },
        def column SKU         { type: text, optional: true, indexed: true },
        def column NAME        { type: text, optional: true },
        def column UNIT_PRICE  { type: { type: decimal, length: 19, precision: 2 }, optional: true },
        def column CATEGORY_ID { type: int, optional: true, indexed: true }
    ],
    constraints: [ def constraint UQ_PRODUCT_SKU { type: unique, columns: ["SKU"] } ]
}

def fk fk_product_category {
    from: [db.dbo.PRODUCT.CATEGORY_ID],
    to:   [db.dbo.CATEGORY.CATEGORY_ID]
}
```

```ttr
// shop/catalog/map.ttrm
package shop.catalog
model binding

def er2db_entity category { entity: er.entity.category, target: { table: db.dbo.CATEGORY } }
def er2db_entity product  { entity: er.entity.product,  target: { table: db.dbo.PRODUCT } }

def er2db_attribute category.id   { attribute: er.entity.category.id,   target: { column: db.dbo.CATEGORY.CATEGORY_ID } }
def er2db_attribute category.code { attribute: er.entity.category.code, target: { column: db.dbo.CATEGORY.CODE } }
def er2db_attribute category.name { attribute: er.entity.category.name, target: { column: db.dbo.CATEGORY.NAME } }

def er2db_attribute product.id          { attribute: er.entity.product.id,          target: { column: db.dbo.PRODUCT.PRODUCT_ID } }
def er2db_attribute product.sku         { attribute: er.entity.product.sku,         target: { column: db.dbo.PRODUCT.SKU } }
def er2db_attribute product.name        { attribute: er.entity.product.name,        target: { column: db.dbo.PRODUCT.NAME } }
def er2db_attribute product.unit_price  { attribute: er.entity.product.unit_price,  target: { column: db.dbo.PRODUCT.UNIT_PRICE } }
def er2db_attribute product.category_id { attribute: er.entity.product.category_id, target: { column: db.dbo.PRODUCT.CATEGORY_ID } }

def er2db_relation product_category {
    relation: er.entity.product_category,
    fk: db.dbo.fk_product_category
}
```

The `shop.sales` package adds `customer`, `order`, and `order_line`, plus a cross-package relation and foreign key into `shop.catalog`. See the [`examples/retail/`](examples/retail/) folder for the complete set and the `graphs/sales_er.ttrg` diagram.
