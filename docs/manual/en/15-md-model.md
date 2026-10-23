# The MD (multidimensional) model

The **MD model** describes data the way an analyst thinks about it: *measures*
(the numbers you sum) sliced by *dimensions* (the things you slice by). It is a
**logical** layer — it says what a cube *means*, not which table it lives in —
and a separate **binding** layer ties it to physical `db` tables (or, read-only,
to `er` entities).

MD lives in its own model:

```ttrm
model md
```

and the four binding kinds live under the `binding` model (`model binding`) — the
same model the `er2db_*` bindings use (see [Binding](08-mapping.md)).

> MD is grammar **3.1**, additive over 3.0. Every MD keyword is also a valid
> identifier fragment, so adding MD never changes the meaning of an existing
> model.

## The seven logical objects

| Object | What it is |
|---|---|
| `def domain` | a value set (a measure's type, or a dimension attribute's range) |
| `def dimension` | an axis you slice by, with inline attributes and a `key` |
| `def map` | a function between domains (`N:1` by default) — calc-backed or table-backed |
| `def hierarchy` | an ordered drill path of a dimension's attributes (leaf→root) |
| `def measure` | a number, with an aggregation and an additivity class |
| `def cubelet` | a grain (set of dimension attributes) + the measures defined at it |

### Domains

A domain is a value set. A **scalar** domain (a measure's value type) is just a
`type`; a **member-set** or **calc** domain adds `kind` and a `restrict`:

```ttrm
def domain Money     { type: decimal }                                  // scalar
def domain Day       { type: date }                                     // a calendar leaf
def domain Month     { type: int, kind: calc, restrict: { range: 1..12 } }
def domain AccountKind {
  type: string,
  kind: bound,                                                          // members come from a table
  restrict: { members: { "A": { en: "Asset" }, "L": { en: "Liability" } } }
}
```

- `kind: calc` — values are *computed* (e.g. month-of-date). Only on discrete
  types; `kind` on a continuous `decimal`/`float` is rejected
  (`md/kind-on-scalar`).
- `kind: bound` — members come from a real table; you **must** supply an
  `md2db_domain` source (`md/bound-domain-no-source`).
- `restrict: { range: 1..12 }` uses the `..` range literal. `members` is only
  valid on a discrete type.

### Dimensions and attributes

A dimension owns its attributes inline and names one `key`:

```ttrm
def dimension Customer {
  key: code,
  attributes: [
    def attribute code { domain: md.CustomerCode, isKey: true },
    def attribute name { domain: md.CustomerName }
  ],
  hierarchies: [geo]
}
```

The `attribute` body is **shared** with ER, but the rules differ by schema: an
MD attribute **must** carry `domain:` and **must not** carry `type:`
(`md/attr-needs-domain`, `md/attr-type-in-md`); an ER attribute is the reverse
(`er/attr-domain-in-er`).

### Maps

A map is a function between domains. A **calc** map references the built-in
catalog; an **absent** `calc:` means the map is **table-backed** (its cases come
from an `md2db_map`):

```ttrm
def map ts_to_day    { from: md.Timestamp, to: md.Day,   calc: truncToDay }   // calc
def map day_to_month { from: md.Day,       to: md.Month, calc: monthOfDate }
def map cc_to_building { from: md.CostCenterCode, to: md.BuildingCode,
                         cardinality: { from: "N", to: "1" } }                // table-backed
```

Calc maps with parameters use **named arguments**:

```ttrm
def map day_to_fy { from: md.Day, to: md.FiscalYear, calc: fiscalYearOfDate(fiscalYearStartMonth: 4) }
```

The catalog (the [Time calc-map catalog](../../features/md/map-catalog.md))
ships truncation (`truncToDay`/`Month`/`Quarter`/`Year`/`Week`), extraction
(`monthOfDate`, `quarterOfDate`, `yearOfDate`, `dayOfMonth`, `weekOfYear`) and
roll-up (`quarterOfMonth`) families. The editor checks the name
(`md/unknown-calc-map`), the args (`md/bad-calc-args`), and that the `from`/`to`
domains satisfy the signature (`md/calc-type-mismatch`). A calc map is implicitly
`N:1`; an explicit `1:1` conflicts (`md/calc-cardinality-conflict`).

### Hierarchies

A hierarchy lists a dimension's attributes **leaf→root**. The connecting map
between consecutive levels is **inferred**; `via` pins it when ambiguous:

```ttrm
def hierarchy calendar {
  dimension: md.Time,
  levels: [day, month via md.day_to_month, quarter via md.month_to_qtr]
}
```

Zero connecting maps → `md/no-hierarchy-step`; more than one and no `via` →
`md/ambiguous-hierarchy-step`; a level not in the dimension → `md/level-not-in-dim`.

### Measures

A measure has a value `domain`, an `aggregation`, and an additivity `class`
(default `additive`):

```ttrm
def measure net     { domain: md.Money, class: additive, aggregation: sum }
def measure balance {
  domain: md.Money,
  class: semiAdditive,
  aggregation: { default: sum, time: latestValid },
  validBy: asOf
}
```

A semi-additive latest-valid override **requires** `validBy`
(`md/semiadditive-no-validby`). A non-additive measure is mark-only in v1.

### Cubelets

A cubelet is a **grain** (a set of `Dimension.attribute`s) plus the measures
defined at it:

```ttrm
def cubelet sales {
  grain: [Customer.code, Product.code, Time.day],
  measures: [net, balance]
}
```

Every grain ref must resolve (`md/grain-ref-unknown`); a grain attribute that is
strictly coarser than another in the same grain is flagged (`md/grain-not-leaf`,
advisory).

## Binding the model to physical tables

The logical model says *what*; the `model binding` defs say *where*.

### `md2db_cubelet` — wide and long

```ttrm
model binding

def md2db_cubelet sales_fact {
  cubelet: md.sales,
  target: db.dbo.SALES_FACT,
  shape: wide,                                  // one column per measure
  attributes: { Customer.code: CUST_CODE, Time.day: TXN_DATE },
  measures: { net: NET_AMT, balance: BAL_AMT },
  journaling: overwrite
}
```

A **long** fact table carries measures as `(code, value)` rows:

```ttrm
def md2db_cubelet drivers_fact {
  cubelet: md.otherDrivers,
  target: db.dbo.DRIVERS,
  shape: { long: { codeColumn: DRIVER_CODE, valueColumn: AMOUNT } },
  attributes: { CostCenter.code: CC_CODE, Time.month: PERIOD },
  measures: { fte: { code: FTE }, m2: { code: M2 } }
}
```

The measure-binding form must match the shape — a `{code}` under `wide` (or a
bare column under `long`) is `md/shape-measure-mismatch`. The bound `attributes`
must cover the cubelet's grain. An attribute can be reached **through a map**:

```ttrm
attributes: {
  Building.code: { via: md.cc_to_building, from: { table: db.dbo.CC_MAP, column: BUILDING } }
}
```

**Journaling** declares writeback: `overwrite`, `diff`, or
`{ invalidate: { validColumn: VALID_TO } }`. When journaling is present, every
measure must be bound (`md/incomplete-journaling`). **Multi-source** is several
`md2db_cubelet` defs for one cubelet; their bound grain must agree
(`md/multisource-grain-mismatch`).

### `md2db_domain` and `md2db_map`

```ttrm
def md2db_domain account_kind_src {
  domain: md.AccountKind,                        // must be kind: bound
  source: { table: db.dbo.ACCOUNTS, column: KIND }
}

def md2db_map cc_building_map {
  map: md.cc_to_building,                         // must be table-backed (no calc)
  target: db.dbo.CC_BUILDING,
  columns: { CostCenterCode: CC_COL, BuildingCode: BLDG_COL }
}
```

`md2db_domain` on a non-`bound` domain is `md/source-on-unbound-domain`;
`md2db_map` on a calc map is `md/binding-on-calc-map`; `columns` must cover every
`from`/`to` domain (`md/map-columns-incomplete`).

### The md → er hop: what you pin, what is derived

`md2er_cubelet` pins a cubelet's ER entity, grain attributes and structural measures:

```ttrm
model binding
def md2er_cubelet sales_er {
  cubelet: md.sales,
  target: er.entity.Sale,
  attributes: { Customer.code: customerCode, Time.day: saleDate },
  measures: { revenue: amount }
}
```

`md2er_dimension` pins a dimension and its attributes. Its attribute map must include the dimension's key:

```ttrm
def md2er_dimension customer_er {
  dimension: md.Customer,
  entity: er.entity.customer,
  attributes: { code: sk, state: address.state }
}
```

A plain value names an attribute of `entity:`. `address.state` traverses the outgoing ER relation
named `address` and selects its target's `state` attribute. Exactly one relation must match;
multiple matching paths are `md/md2er-path-ambiguous`. A missing dimension key is
`md/md2er-dimension-key`; a measure mapping to an unknown target attribute is
`md/md2er-measure-unknown`. On a dimension binding, `md/md2er-reference` checks the dimension, entity and both sides of each attribute map. On a cubelet binding it also requires `cubelet:` and checks that each measure belongs to that cubelet. There is one explicit binding per dimension (`md/duplicate-binding`).

Both bindings are structural. Physical `shape`, `journaling` and `allocation` on a cubelet binding
remain `md/md2er-physical-prop`. A domain is a value type; it has no explicit `md2er_domain` form.

The formatter orders documentation, tags, dimension/cubelet, entity/target, attributes, then measures.
It preserves bindings with comments or refused physical fields verbatim.

### What is derived, and how

The hop joins `md2db_*` and `er2db_*` on the same resolved database table/view and column:

- **R1:** a cubelet lands on the entity bound to its fact table.
- **R2:** a measure collects the ER attributes bound to its columns across every carrying cubelet.
- **R3:** a dimension follows its key's fact FK through ER relations; maps from its key domain and a bound-domain source corroborate the target entity. Disagreeing entities remain ambiguous.
- **R4:** the key lands on the relation's target key. A descriptive attribute follows the MD map to its own physical column; it may live on a different entity. For example, `Customer.state` can land on `customer_address.state`.
- **R5:** a cubelet grain attribute lands on that fact's FK attribute, separately from the dimension's own key.
- **R6:** a bound domain uses its member-source column; otherwise its dimension attributes supply candidate landings.
- **R7:** an entity stacked on an ER query adds its output columns' physical origins. For `sales` over a three-branch sales union, revenue has three fact landings and `sales.ext_sales_price`. A quality whose bearer is `sales` picks the latter (`derived-bearer`). A database view has no inferred lineage.

Explicit mappings win per element. Without one, the bearer restricts quality candidates first, then a unique unrestricted candidate wins. Multiple candidates give `md/er-ambiguous`; none gives `md/er-unmapped`, naming the missing physical binding or relation. `md/er-hop-override` is information when an explicit answer differs from derivation.

The metadata library uses conservative syntactic lineage for bare SELECT attributes, aliases, literals and UNION ALL. Unsupported expressions yield `md/er-lineage-opaque` information. When ttr-concept is present, its optional Calcite service uses the translator's configured parser and supplies column origins and recursively expands stacked bindings. Python retains the structural MD/ER validation; it has no MD derivation model.

`ttr md hop <dir> [--all] [--format text|json] [--fail-on warning]` prints every MD element, landing, rule or diagnostic and the derived warehouse roles. Findings default to concept-grounded elements and use each quality's bearer; `--all` includes ungrounded elements. Exit codes are 0 for success, 1 for a finding at the requested threshold, and 2 for usage or unreadable input. `just md-hop <dir>` invokes it from this repository. In VS Code, use **TTR: md → er hop report** (`modeler/getMdHop`). ER entity hover shows the derived role and its reasons.

Roles are derived from the same mappings: a cubelet's entity is a fact; a dimension's entity is a dimension. Measures plus two many-to-one outgoing relations also identify a fact; the one side from a fact identifies a dimension. An N:N map target is flagged as a bridge and excluded from grain coarsening; other entities stay unclassified. Only deprecated CNC `roles:` claims are checked against this result (`md/role-claim-mismatch`, warning).

---

See the [reference](14-reference.md#md-multidimensional-model) for the full
keyword and `md/*` diagnostic list, and `docs/features/md/contracts.md` for the
authoritative property tables.

Query-backed attributes select output columns by their bound names, independent of attribute declaration order. Identity table/column bindings follow the translator convention. Reports retain package-qualified identities; shared dimensions can derive through cubelets in other visible packages. An explicit dimension pin constrains its derived attributes to reachable entities and reports excluded derived landings as override information.

Inverse ER candidates are limited to the binding document's package, root package and explicit imports. Pin reach follows N:1 or 1:1 cardinality toward the one side, regardless of relation declaration order. Implicit entity/table lookup uses the translator's model DB namespace (`dbo` for reconciled document models); other physical namespaces require explicit entity bindings.
