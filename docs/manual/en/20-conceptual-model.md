# Conceptual model (cn)

## Grammar 0.20 — things in time

Grammar 0.20 adds the syntax below. CN-P8 S8.1 provides parsing, formatting and
writer round-trips; the corresponding validation and lowering arrive in S8.2.

`subkind` declares a rigid specialization with an `of:` bearer, beside an
anti-rigid `phase`. Kinds (including subkinds and roles) may have participants and
a lifetime. The four time shapes are an event's instant, a kind's lifetime or
validity, a phase's episodes, and a quality's changing value.

```ttrm
model cn
def subkind WebSale { of: Sale, definition: """channel = 'web'""" }
def event OrderPlaced { involves: [Order], period: { at: placed_date } }
def kind ListPrice {
    involves: [Item, Channel],
    period: { start: valid_from, end: valid_to, end_open: open },
    qualities: [price]
}
def phase OutOfService {
    of: Warehouse, grounds: er.entity.warehouse_outage,
    period: { start: from_date, end: to_date }, qualities: [reason]
}
def phase OpenOrder { of: Order, from: OrderPlaced, until: OrderDelivered }
def quality list_price { of: Item, valued_state: true, per: Channel, from: ListPrice.price }
```

The parser preserves `from`, `until` and `per` as distinct reference properties;
semantic validation distinguishes event bounds from a projected quality. The CN
body accepts incomplete combinations so an editor can retain them for diagnostics.
`extent:` remains accepted during retirement. CN-P8's semantics restrict events
to instants; duration belongs to a kind's lifetime or a phase's episode.

The **conceptual model** names what the business talks about — products, customers, orders, sales, an overdue
invoice — apart from how the `db`, `er` and `md` layers store it. A **concept** is grounded to exactly one place in
those layers, and a defined concept carries the expression that says which rows it means. Agents and recipes speak in
concepts; the bindings tell them where the data is.

> **Grammar 0.17.1 — checked.** The editor resolves every cn reference, desugars `grounds:` and the `concept { }`
> sugar into bindings, and reports the model-level `cn/*` diagnostics as you type. `ttr cn hygiene` adds the checks on
> `definition:` / `members:` / `filter:` blocks and prints the [hygiene report](#the-hygiene-report).
> [Diagnostics](#diagnostics) says which check fires where.
>
> **Toolchain 0.17.5 — used.** `org.tatrman:ttr-concept` lowers what agents say in concepts to data operations:
> [statements, assertions and rendering](#statements-assertions-rendering), and `ttr cn materialize`.
>
> **0.18.1–0.18.3 — served.** The compiled lexicon carries the concepts, and a Tatrman server reads them when it
> matches a question and answers frames over its doors ([Concepts at run time](#concepts-at-run-time)). Candidates
> become concepts through a steward's pull request ([The promotion loop](#the-promotion-loop)).

| where | what |
|---|---|
| a `model cn` file | the concepts: `def kind` · `def role` · `def event` · `def quality` · `def phase` · `def relation_type` |
| a `model binding` file | the groundings: `def cn2md_*` · `def cn2er_*` · `def cn2db_concept` |
| inside an md or er def | the `concept { … }` sugar — the def's own concept |
| a `model lexicon` file | terms and examples whose `for:` is a concept (`for: cn.Customer`) |

A concept is referred to as `cn.<Name>` from other models (`kind: cn.Item`, `for: cn.Customer`), and by its bare
name inside a `model cn` file — see [Names and references](#names-and-references).

## Concept kinds

| kind | what it is | usually carries |
|---|---|---|
| `kind` | a rigid thing — it is what it is for its whole life (a product, a product category, a channel) | `grounds`, `hierarchy`, `qualities`; a sub-kind `is_a` + `members` |
| `role` | a kind in a part it plays, defined by an event (a party is a customer *of an order*) | `event` (required), `of`, `grounds` |
| `event` | an occurrence with participants and a time (a sale, an order, a stock transfer) | `involves`, `period`, `extent`, `qualities` |
| `quality` | a characteristic of a bearer (revenue of a sale, category of an item) | `of` (required); `grounds` or `definition`; `value_space`, `valued_state` |
| `phase` | a passing condition of a bearer (a pending order, an overdue invoice) | `of` (required); `definition` or `grounds` (to a query-backed entity), never both; `imprecision`, `basis`, `abstract` |
| `relation_type` | an open-tier relation between concepts | `description`, `label` |

```ttrm
package hartland.concepts
model cn

def kind Item {
    description: "A product the stores, the web shop and the marketplace sell.",
    label: { en: "Product", cs: "Produkt" },
    grounds: md.Product,
    qualities: [category, brand],
    lexicon { terms: ["product", "item", "SKU"] }
}

def kind ProductCategory {
    grounds: er.entity.category_code,
    hierarchy: { closure: er.entity.category_closure, ancestor: ancestor_code, descendant: descendant_code, ranges: node }
}

def kind HomeCategory {
    is_a: ProductCategory,
    members: """descendants(ProductCategory, 'H10-H19')"""
}

def kind Channel { grounds: er.entity.channel_sales.channel }

def role Customer { event: Order, of: Party, grounds: md.Customer }

def quality revenue  { of: Sale, label: { en: "Revenue" }, grounds: md.revenue }
def quality category { of: Item, value_space: ProductCategory, grounds: er.entity.item.category }
```

A `def role` in a `model cn` file is a concept role. In a `model cnc` or `model automation` file it is the role of
that model, and the cn properties are an error there.

## Properties

| key | value | meaning |
|---|---|---|
| `description` · `label` · `tags` | as on every def | `label` is a locale map |
| `sources` | `["doc://…"]` | compact provenance |
| `is_a` | a concept, or a list | the super-concept(s) |
| `abstract` | `true` | a concept only its sub-concepts instantiate |
| `of` | a concept | the bearer of a quality or a phase; a role's base kind |
| `event` | an event | a role's defining event |
| `grounds` | `md.<X>` · `er.entity.<e>` · `er.entity.<e>.<a>` | shorthand for the concept's one binding |
| `hierarchy` | `{ closure, ancestor, descendant, ranges: node \| lexical }` | how a vocabulary kind's codes nest (a property of its grounding) |
| `involves` | `[ Customer, Warehouse as from ]` | an event's participants, each optionally labelled |
| `period` | `{ start, end, end_open: open \| unknown }` · `{ at, precision }` | an event's time, through its qualities |
| `extent` | `instantaneous` · `durative` | an event's temporal extent |
| `qualities` | `[ … ]` | the qualities a concept carries |
| `value_space` | a kind | the kind a quality's values come from |
| `valued_state` | `true` | a quality whose value changes over time |
| `definition` | an expression block | what a defined quality or phase means |
| `members` | an expression block | which members a sub-kind of a vocabulary kind restricts to |
| `imprecision` | `certainly` · `possibly` | how a definition reads an imprecise time (default `certainly`) |
| `basis` | `occurrence` · `current_state` · `past_state` | what a condition phase is evaluated on |
| `relations` | `[ { type: <relation_type>, to: <concept> } ]` | open-tier relations |
| `lexicon` | `{ terms: […] }` | the inline lexicon shorthand, targeting the concept |

Any other `key: value` whose key is a plain name parses as a free-form property (a literal, a name, a list or an
object). The key cannot be a TTR keyword: `from:`, `to:`, `type:`, `name:`, `key:`, `source:`, `default:` and the like
are parse errors at the top of a concept body (inside a nested object such as a `relations:` entry, `type:` and `to:`
are fine). The keys above that the grammar types — `definition`, `members`, `grounds`, `involves`, `period`,
`extent`, `basis`, `value_space`, `event` — must carry their typed value: `definition: OverdueInvoice` is a parse
error, not a free-form property.

## Names and references

A reference to a concept is written with the `cn.` head (`for: cn.Customer`, `kind: cn.Item`), or bare in a slot that
can only name a concept (`of: Party`, `role: Customer`). Either way it resolves **among concepts only** — the concept
`Customer` and the md dimension `md.Customer` never meet — in the usual order: package-qualified, the same package, a
named import, the wildcard imports, then a name unique in the project.

| where | slot | resolves as |
|---|---|---|
| a concept | `is_a` · `of` · `event` · `involves` · `value_space` · `relations[].type` / `.to` | a concept |
| a concept | `qualities:` items · `period:` `start` / `end` / `at` | a quality of the bearer — a name no `def quality` declares is an implicit quality, not an error |
| a concept, a binding | `grounds` · `target` · `hierarchy.closure` · `via` · `participants:` paths · `period:` | an md / er / db element, as written |
| a binding | the concept slot (`kind:` `role:` `quality:` `event:` `phase:` `concept:`) | a concept |
| a binding | `participants:` keys | the event's participant labels |
| a `concept { }` block | its other slots | as on a concept def |
| a lexicon entry | `for: cn.X` | a concept |

An element reference written without its package — `grounds: er.entity.store` — names the referring file's own
package's `store` when another package declares one too; written `md.revenue`, it names an md element even when a
lexicon term or an er element shares the name.

**Participant labels.** `Warehouse as fulfilling` is labelled `fulfilling`; a participant written bare is labelled by
its concept's name, lower-cased (`Customer` → `customer`). A binding's `participants:` keys and a definition's
`this.<label>` read the label. Renaming a concept in the editor keeps such a label by writing it out:
`involves: [Customer]` becomes `involves: [Buyer as customer]`. Renaming a label edits it and the `participants:`
keys; the editor refuses it while a definition block reads the label (`this.seller`, `as customer`), since it does
not rewrite definitions — edit those first.

## Definitions are expression blocks

`definition:` and `members:` (and a binding's `filter:`) hold an expression in one of three forms: a string, a
triple-string, or a `"""pl` block. The parser keeps the text exactly as written; the expression itself is TTR-P's
expression language (see the [TTR-P expression reference](../../features/ttr-p/language-design.md#5-the-expression-sublanguage)).
In a definition, `this` is the bearer, an event's participants are reached by their labels, a phase name is a
predicate, `:as_of` is the instant the definition is evaluated at, and `:name` reads a `def parameter`. Durations
and windows are spelled as in TTR-P (`hours(24)`, `within(sold_date, last: days(365))`); the conceptual forms —
`exists … involving … where …`, `count(… involving …)`, `code in descendants(Kind, 'X')` and the temporal words
`at_or_before`, `before`, `contains`, `ended_before`, `overlaps` — are TTR-P expression grammar 0.5. They belong to
definitions: a TTR-P program refuses them wherever they stand (`TTRP-CN-001`) — except a temporal word followed by
`:`, which a program reads as a route or a key, so `x before :as_of` there is a syntax error (`TTRP-PRS-001`, with a
hint) rather than a cn form. The words stay usable as names in TTR-P, except `this`, which always reads as the bearer —
a column named `this` is refused too.

```ttrm
model cn

def quality open_amount { of: Invoicing, definition: "amount - paid" }

def quality delivery_time {
    of: Order,
    definition: """pl
        this.period.end - this.period.start
    """
}

def phase PendingOrder   { of: Order, definition: """state in ('placed', 'held')""" }
def phase OverdueInvoice { of: Invoicing, definition: """invoice_status = 'open' and period.end < :as_of""" }
def phase DebtorCustomer {
    of: Customer,
    definition: """exists Invoicing involving this as customer where OverdueInvoice""",
    imprecision: certainly
}
def phase PlacedOrder    { of: Order, grounds: er.entity.placed_order }
```

### The spellings

A definition is TTR-P, so where TTR-P already spells a thing, that is the spelling:

| write | not | |
|---|---|---|
| `:as_of` | `as_of` | the instant the definition is evaluated at; a bare `as_of` stays an ordinary name |
| `:tolerance` | `parameter(tolerance)` | reads `def parameter tolerance` |
| `hours(24)` · `days(365)` | `24h` | durations are calls |
| `within(sold_date, last: days(365))` · `within(x, from: a, to: b)` | `last(…)` · `within … of …` | TTR-P's windows |
| `<>` | `!=` | TTR-P's inequality |
| `e.period contains :as_of` | `covers` | an episode containing an instant |

### Names in a definition

- `this` is the bearer. A bare name reads the innermost subject first — the concept an `exists` or an aggregate ranges
  over — then the bearer; a variable (`exists Invoicing i …`) or a ranged concept's name names its row.
- On a subject: `period` (with `.start`, `.end`, `.at`), a participant label (`this.from`), a quality (listed in its
  `qualities:`, or a `def quality` of it — up `of:` and `is_a`), or a phase of it, which reads as a predicate.
- Each `involving` participant is one the ranged concept involves — by label (`as customer`) or, unlabelled, its one
  participant of that concept.
- `descendants(<Kind>, '<code>')` needs a kind grounded with a `hierarchy:`.
- A binding's `filter:` reads its target entity's attributes.

The editor shows a definition as text; `ttr cn hygiene` parses and checks it (syntax, names, cycles, reachability,
the `basis:` shape, instant comparisons — see [Diagnostics](#diagnostics)).

## Events: participants and periods

A participant is a concept, labelled with `as` when the event needs to tell two of the same apart. The period is
written through the event's qualities: `{ start, end }` for a durative event — with `end_open:` saying how an absent
end reads (`open`: still running; `unknown`) — or `{ at }` for an instantaneous one.

```ttrm
model cn

def event Order {
    extent: durative,
    involves: [Customer, Warehouse as fulfilling],
    period: { start: placed_date, end: delivered_date, end_open: open },
    qualities: [order_value, state, placed_date, delivered_date]
}

def event StockTransfer {
    extent: instantaneous,
    involves: [Item, Warehouse as from, Warehouse as to],
    period: { at: shipped_at, precision: day },
    qualities: [shipped_at]
}
```

## Time

Seven words carry time through the model and the frames:

| word | means | in cn |
|---|---|---|
| **instant** | a point on the time line, at a granularity (minute · day · month · year) | an instantaneous event's `period: { at, precision }`; `:as_of` |
| **interval** | the time from a begin instant to an end, closed-open `[begin, end)`; an absent end is either still running or unknown | a durative event's `period: { start, end, end_open: open \| unknown }` |
| **duration** | a length of time with no position | `days(365)`, `hours(24)` |
| **occurrence** | one happening of an event, with its time | a row of the event's grounding |
| **state** | something that holds of a subject at instants | a phase; a quality with `valued_state: true` |
| **episode** | the longest interval a state holds, from the occurrence that starts it to the one that ends it (either may be unknown) | a durative event whose period is the episode |
| **as-of** | the instant a definition is evaluated at, or a claim is made at | `:as_of` in a definition; a statement's `as_of`; an assertion's `asserted_as_of` |

A **condition** is a phase whose definition reads occurrences or episodes at `:as_of`; its `basis:` says which
comparison makes it hold (see [the `basis:` shapes](#diagnostics)). `occurrence` holds forever once the occurrence
happened (*post-infarction*), `current_state` while an episode contains the day (*has a broken leg*), `past_state`
once an episode has ended (*history of a fracture*). No construct is added for conditions: the basis is visible in
the definition, and the hygiene report checks that the definition has the comparison its `basis:` names.

```ttrm
model cn

def phase OverdueInvoice {
    of: Invoicing,
    definition: """invoice_status = 'open' and period.end < :as_of"""
}
def phase LateReturnLine {
    of: ReturnLine,
    definition: """this.request.order.period.end < :as_of - :return_window"""
}
```

An order not delivered yet has no end (`end_open: open`), so `LateReturnLine` never calls it late: an open end is
later than every day.

## Bindings

Every concept is grounded **once** — to the md layer (what analysts aggregate) or to the er layer (the base). The
binding names the concept by its stereotype key and the element by `target:`.

| binding | concept | target |
|---|---|---|
| `cn2md_kind` · `cn2md_role` | `kind:` · `role:` | an md dimension |
| `cn2md_quality` | `quality:` | an md measure, or a dimension attribute |
| `cn2md_event` | `event:` | an md cubelet |
| `cn2er_kind` | `kind:` | an entity, or an attribute (a value kind); `hierarchy:` |
| `cn2er_role` | `role:` | its own entity, or a base entity plus a `via:` relation path |
| `cn2er_event` | `event:` | an entity; `participants:` (label → relation path) and `period:` |
| `cn2er_quality` | `quality:` | an attribute; `filter:` selects a valued state's series |
| `cn2er_phase` | `phase:` | a query-backed (stacked) entity |
| `cn2db_concept` | `concept:` | a db object — documentation only, nothing reads it |

```ttrm
package hartland.concepts
model binding

def cn2md_kind    item_g     { kind: cn.Item, target: md.Product }
def cn2md_quality revenue_g  { quality: cn.revenue, target: md.revenue }
def cn2md_event   return_g   { event: cn.Return, target: md.storeReturns }

def cn2er_kind channel_g { kind: cn.Channel, target: er.entity.channel_sales.channel }
def cn2er_role orderer_g { role: cn.Orderer, target: er.entity.party, via: [er.relation.rel_order_customer] }
def cn2er_event transfer_g {
    event: cn.StockTransfer,
    target: er.entity.stock_transfer,
    period: { at: er.entity.stock_transfer.shipped_at },
    participants: {
        item: [er.relation.rel_transfer_item],
        from: [er.relation.rel_transfer_from],
        to: [er.relation.rel_transfer_to]
    }
}
def cn2er_quality stock_g {
    quality: cn.stock_level,
    target: er.entity.inventory.quantity_on_hand,
    filter: """warehouse = 1"""
}
def cn2er_phase placed_g { phase: cn.PlacedOrder, target: er.entity.placed_order }
```

Attributes are referred to as `er.entity.<entity>.<attribute>`.

## The `concept { }` sugar

An md or er def can declare its own concept in place — the binding is implied by where the sugar stands:

| host | concept |
|---|---|
| `cubelet` | an event |
| `dimension` · `domain` | a kind or a role |
| `measure` · md `attribute` | a quality |
| `entity` | a kind, a role or an event |
| er `attribute` | a quality, or a value kind |

A measure written inline in a cubelet's `measures:` is not a host: its `concept { }` grounds nothing and is
`cn/grounding-target-kind` — declare the measure with `def measure` and list it.

```ttrm
model md

def cubelet storeReturns {
    grain: [Customer.code, Product.code],
    concept { event: Return, involves: [Customer, Item, ReturnReason] }
}
def measure returnAmount { domain: md.Money, aggregation: sum, concept { quality: return_amount, of: Return } }
```

```ttrm
model er

def entity invoice {
    attributes: [
        def attribute invoice_id { type: int, isKey: true },
        def attribute status { type: text, concept { quality: invoice_status, of: Invoice } }
    ],
    concept { kind: Invoice }
}
def entity customer {
    attributes: [ def attribute customer_id { type: int, isKey: true } ],
    concept { role: Customer, event: Order }
}
```

## One grounding per concept

`grounds:` and a `concept { }` block are shorthand for a binding: `def kind Item { grounds: md.Product }` stands for a
`cn2md_kind` binding of `Item` to `md.Product`. The binding kind follows from the concept's stereotype and the
target's layer; a phase grounds only to a query-backed entity. A concept has **one** grounding: a second binding,
`grounds:` or `concept { }` block for it is `cn/duplicate-grounding` at each later one, and the first in document order
is the one read. A `concept { }` block naming a concept a cn def declares may add what the def leaves unset, never
restate it differently.

Where a concept lands on the er layer is its **grade**:

| grounding | grade | lands on |
|---|---|---|
| none | ungrounded | — |
| `cn2er_*` | er-grounded | its target |
| `cn2md_*`, and the hop resolves the target | md-grounded | the mapped entity or attribute — through the **hop** |
| `cn2md_*`, and the hop cannot resolve it | md-grounded-hop-missing | — `md/er-unmapped` (`md/er-ambiguous` when two map it and the bearer's entity does not pick one) |
| `cn2db_concept` | ungrounded | — documentation only |

A defined phase with no grounding takes its bearer's grade and landing. The hop first reads explicit
`md2er_cubelet` and `md2er_dimension` bindings, then derives through shared MD/ER physical bindings and relations.
A quality first restricts derived candidates to its bearer's entity, including stacked-query column origins.
`ttr md hop <dir>` reports the same landings and derived roles for the whole MD model.
See [the hop bindings and derivation](15-md-model.md#the-md--er-hop-what-you-pin-what-is-derived).

### The hop in practice

The Hartland demo model grounds its analytic concepts to md and authors **no `md2er` binding**: every md-grounded
concept lands on the er layer through a derived hop, the md element's physical binding read back through the er
layer's. Its hygiene summary:

```
83 concepts — 65 er-grounded · 11 md-grounded · 7 ungrounded
```

and no `md-grounded-hop-missing` row. Two of the derived landings:

| concept | grounding | lands on |
|---|---|---|
| `cn.revenue` (a quality of `Sale`) | `md.measure.revenue` | `er.entity.sales.ext_sales_price` |
| `cn.return_amount` (a quality of `Return`) | `md.measure.returnAmount` | `er.entity.channel_returns.return_amt` |

Each md measure is bound to columns of several physical tables (one per channel), and more than one er attribute
maps those columns. The quality's bearer settles it: `Sale` lands on `er.entity.sales`, so `revenue` takes the
attribute of `sales`, and `Return` lands on the stacked `channel_returns`, so `return_amount` takes the attribute of
that. A concept whose bearer does not pick one is `md/er-ambiguous`, and pinning it with an `md2er_*` binding is the
fix. A phase with a definition and no grounding inherits its bearer's landing: `cn.DebtorCustomer` lands on
`er.entity.customer` because `cn.Customer` does.

## Lexicon entries for concepts

A `term` or `example` can target a concept. `default: true` makes it the reading of a bare form when several
concepts share it (never on a phase); `sense_priority:` ranks the rest, higher first.

```ttrm
model lexicon locale en

def term customer { for: cn.Customer, forms: ["customer", "customers", "client"], default: true }
def term debtor   { for: cn.DebtorCustomer, forms: ["debtor"], sense_priority: 2 }
def example web_revenue { for: cn.WebSale, text: "revenue on the web last month", default: true }
```

One default per bare form and locale (`cn/duplicate-default-sense`), and never a phase's when the form also names a
concept up its `of:` chain (`cn/phase-as-default`).

## The open tier

Relations between concepts that no consumer computes with — they inform, they never lower to a query:

```ttrm
model cn

def relation_type supplies { description: "A supplier supplies a distribution center.", label: { en: "supplies" } }
def kind Supplier { is_a: Party, relations: [ { type: supplies, to: Warehouse } ] }
```

## Diagnostics

Each `cn/*` diagnostic fires in one of three places:

- **editor** — the language server and `modeler-lint`, as you type (lint category `cn` in `.ttrlint.toml`; the hygiene
  ones are off under the `recommended` preset and `info` under `strict`);
- **`ttr cn hygiene`** — the checks on definition blocks, and the [hygiene report](#the-hygiene-report). It also
  prints the model's load errors to stderr and fails `--fail-on error` on them: the error-grade model checks of the
  Kotlin toolchain — the editor's error rows but the lexicon pair (`cn/duplicate-default-sense` is the editor's
  alone) — and loading errors such as a file whose `package` does not match its directory. Warnings and hygiene
  findings it does not print;
- **consumer** — `ttr-concept` (toolchain 0.17.5) refuses a statement, an assertion or a recipe with it: its validates
  and lowers, which the tools that read the model at run time call (see
  [Statements, assertions, rendering](#statements-assertions-rendering)).

| slug | grade | fires in | meaning |
|---|---|---|---|
| `cn/duplicate-grounding` | error | editor | a second grounding of one concept, or a `concept { }` block restating a cn def differently |
| `cn/grounding-target-kind` | error | editor | the target is not what the stereotype grounds to on that layer (a kind to a measure, a phase to md, …), or a `concept { }` on a cubelet's inline measure |
| `concept-bearer-missing` | error | editor | a quality or a phase that names no bearer — `of:` is required (`ttr/required-property-missing` in the Kotlin and Python toolchains) |
| `cn/role-no-event` | error | editor | a role names no defining event |
| `cn/phase-double-definition` | error | editor | a phase with both `definition:` and a grounding |
| `cn/hierarchy-shape` | error | editor | a `hierarchy:` closure entity lacks its two named attributes or a key |
| `cn/participant-path-broken` | error | editor | a `participants:` relation path does not chain from the event's entity to the participant's |
| `cn/involves-unlabelled-duplicate` | error | editor | the same concept twice in `involves:` without labels |
| `cn/unknown-relation-type` | error | editor | an open-tier edge's `type:` names no `def relation_type` |
| `cn/duplicate-default-sense` | error | editor | two defaults for one bare form and locale |
| `cn/antirigid-supertype` | warning | editor | a kind `is_a` a role or a phase |
| `cn/phase-as-default` | warning | editor · report | a phase as the default sense of a form its bearer's term also has |
| `cn/cnc-deprecated` | warning | editor | `model cnc` or an entity's `roles:` |
| `cn/db-grounding` | advisory | editor | a `cn2db_concept` grounding — no consumer reaches it |
| `cn/ungrounded` | hygiene | editor · report | a concept with no grounding (the report shows its grade) |
| `cn/phase-no-definition` | hygiene | editor | a phase with neither `definition:` nor a grounding |
| `cn/event-no-period` | hygiene | editor | an event with no `period:` |
| `cn/definition-syntax` | error | `ttr cn hygiene` | a definition block that does not parse; the message carries the `TTRP-*` code, the location is in the `.ttrm` |
| `cn/definition-unknown-name` | error | `ttr cn hygiene` | a name the definition's scope lacks — a concept, quality, label, variable, `:name` parameter, function or `filter:` attribute |
| `cn/descendants-no-hierarchy` | error | `ttr cn hygiene` | `descendants(<Kind>, …)` over a kind grounded with no `hierarchy:` |
| `cn/definition-cycle` | error | `ttr cn hygiene` · consumer | definitions that inline each other |
| `cn/basis-mismatch` | error | `ttr cn hygiene` · consumer | a condition whose definition has no comparison of its `basis:` shape (below) |
| `cn/definition-unresolved` | hygiene | `ttr cn hygiene` | a definition reading an ungrounded concept |
| `cn/definition-unreachable(md)` · `(er)` | hygiene · refusal | `ttr cn hygiene` · consumer | a concept the definition reads has no grounding on that layer |
| `cn/imprecise-comparison` | advisory | `ttr cn hygiene` | instants of different granularity compared under `imprecision: certainly` |
| `cn/retired-code` | warning | report | a textual code no longer valid in the current vocabulary (once vocabulary versions register) |
| `cn/not-analytically-reachable` | error | consumer | an aggregating statement slot on a concept with no md grounding |
| `cn/not-recipe-reachable` | error | consumer | a recipe's concept with no landing on the er layer |
| `cn/not-assertable` | error | consumer | an asserted event with no landing on the er layer |
| `cn/unit-mismatch` | error | consumer | an asserted value's unit differs from the attribute's |
| `cn/ambiguous-statement` · `cn/ambiguous-assertion` | error | consumer | a statement or assertion with unresolved candidates |
| `cn/missing-time` | error | consumer | a statement with no time and not `all_time`, or an assertion with no period where its event declares one |
| `cn/unsupported-ask` | error | consumer | a statement asking for a set (`ASK_SET`, reserved) or asking nothing |
| `cn/unknown-participant-label` | error | consumer | an asserted participant label the event's `involves:` does not declare |
| `cn/slot-mismatch` | error | consumer | a concept the slot does not take (a phase as a measure, …), or an asserted value its attribute's type does not take, or NULL where it is not `optional` |
| `cn/malformed-frame` | error | consumer | a frame field out of its shape: a time the vocabulary lacks, a filter with no form, an end before the start, one attribute asserted with two values, … |
| `cn/missing-as-of` | error | consumer | a state claim (the event bears a current- or past-state phase) asserted without `asserted_as_of` |
| `cn/snapshot-mismatch` | warning | consumer | the frame was built on another snapshot of the model |
| `cn/lowered-on-er` | advisory | consumer | md carries no single read of the statement; it was read on E-R (the message says why md did not) |
| `cn/explain-locale-fallback` | warning | consumer | an explanation asked for in a locale it does not speak (it speaks en and cs) — the text is English |
| `cn/materialize-parametric` | error | `ttr cn materialize` | a definition reading `:name` or `:as_of` cannot become a stacked entity |
| `cn/no-concept` | — | consumer | no concept or element matched a mention |

The definition checks read a definition with every phase and defined quality it names inlined, transitively. A
definition that does not parse or resolve reports that only.

**The `basis:` shapes.** A condition matches its `basis:` when one of its comparisons — its own or an inlined one's —
has that shape (`<`, `<=`, `before` and `at_or_before` count alike; `:as_of >` mirrors them):

| `basis:` | holds as of *t* when … | the comparison |
|---|---|---|
| `occurrence` | an occurrence happened at or before *t* | `e.period.start` / `e.period.at` / `e.period` *before* `:as_of` |
| `current_state` | an episode contains *t* | `e.period contains :as_of` |
| `past_state` | an episode ended before *t* | `e.period ended_before :as_of`, `e.period.end` *before* `:as_of` |

A window counts as the comparisons it stands for: `within(x, last: d)` is `x >= :as_of - d and x <= :as_of`, so
`within(o.period.start, last: days(365))` matches `occurrence`; `within(x, from: a, to: b)` is `x >= a and x <= b`.

**Granularity.** An instant's granularity is its event's `period: { precision }`, else its attribute's type (a `date`
is a day, a timestamp a second); `:as_of` is a day. Unknown on either side — no finding.

## Statements, assertions, rendering

An agent never writes SQL against the model. It proposes a **frame** over concepts — a `statement.v1` to read, an
`assertion.v1` to record — or a recipe names a phase, and `ttr-concept` turns that into a data operation through the
bindings. The same model and the same frame always give the same bytes: no model call, no server call, no clock read
when the frame names its day.

| frame | validate | lower | explain |
|---|---|---|---|
| statement (read) | `statementValidate` | `statementLower` → a `plan.v1` through the md path, else SQL over entities (the er lane) | `statementExplain` |
| assertion (write) | `assertionValidate` | `assertionLower` → the E-R row payload | `assertionExplain` |
| a phase, by name | — | `definitionRender` → md predicate or er SQL; `materialize` → model text | — |

A frame that cannot be served is refused with its findings (`cn/missing-time`, `cn/ambiguous-statement`,
`cn/not-analytically-reachable`, `cn/not-assertable`, `cn/slot-mismatch`, …, see [Diagnostics](#diagnostics)) and
nothing else: no plan, no rows, no text.

### Statements

A statement names measures, filters, grouping, a time and optionally an `as_of`, all as concepts. Each slot goes
through its `cn2md` grounding; a phase filter's definition is inlined (and phases inside it, transitively); `:name`
parameters take the world's values, `:as_of` the statement's day. "Net revenue for Kaufland in January 2026":

```json
{ "ask": "ASK_VALUE", "measures": [{ "qname": "cn.revenue" }],
  "filters": [{ "member": { "concept": { "qname": "cn.Customer" },
                            "member": { "label": "Kaufland", "resolvedValue": "Kaufland" } } }],
  "time": { "periodCode": "2026-01" }, "locale": "en" }
```

lowers through the md dot-path `sales[customer.name: "Kaufland", time.month: 1, time.year: 2026].net @ sum` to a
plan, with its provenance beside it: which binding each slot went through, the definitions inlined, the parameters
bound, the as-of, the model snapshot.

**The er lane.** md reads a statement only when one cubelet carries it. The Hartland demo keeps one cubelet per sales
channel and no channel dimension, so "revenue on the web in January 2026" has no single md read: three cubelets carry
revenue, and none of them knows the channel. Such a statement is read on E-R instead, over the grain event's landing —
`Sale` lands on the stacked `sales` entity:

```sql
SELECT SUM(sales.ext_sales_price) AS revenue
FROM sales
JOIN date_dim ON date_dim.sk = sales.sold_date
WHERE sales.channel = 'web' AND date_dim.cal_date >= DATE '2026-01-01' AND date_dim.cal_date < DATE '2026-02-01'
```

- **md first.** E-R reads a statement only when md cannot place it: no carrier, several, or a slot md cannot reach. The
  result then carries SQL over entities (`erSql`) instead of a plan, and the advisory `cn/lowered-on-er` says why md did
  not.
- **E-R places; it does not re-decide.** A question md refuses on principle stays refused on E-R, for md's reason — one
  finding, E-R's reason folded into it:
  - a **non-additive** measure that declares no aggregation, and a **semi-additive** one (`aggregation: { default: sum,
    Time: latestValid }`) — neither is a plain aggregate;
  - a filter on a measure the statement does not read — md's HAVING on another measure; on E-R it would filter rows,
    which is another question;
  - a **negated phase whose definition joins** another entity (G-MD-1).
- **Measures** aggregate by their md measure's aggregation: a declared default (`sum`, `avg`, `min`, `max`, `count`),
  else `sum` for an additive measure. A quality with no md measure has no aggregation to take.
- **Phase filters** inline their definition on the event's row, while the rows stay the event's: a phase that joins a
  relation from its many side (each sale's shipments, say) is refused — the sum would count a sale once per shipment. A
  negated phase is read `NOT COALESCE((…), FALSE)`: two-valued, so a row where the phase is NULL (no channel) counts as
  "not web", and `revenue where WebSale` plus `revenue where not WebSale` is the total.
- **Members and group-bys of a participant are keys.** A member filter compares the key the event holds
  (`sales.item = 1017`) — the member's `resolved_value` *is* that key (as an assertion writes it); a value the key's type
  cannot take is `cn/slot-mismatch`. A group-by on a participant returns its keys, not its names: whoever shows the
  result (the door, Golem) labels them. A narrower concept than the participant (a kind `is_a: Customer` with a
  `members:` restriction) is refused — its restriction is not read on E-R.
- **Quality filters** read the event's columns; an instant is its interval — `due_on = 2026-01` is `due_on >= DATE
  '2026-01-01' AND due_on < DATE '2026-02-01'`, and a day on a timestamp column is that day's range.
- **Time** reads the event's period instant on its day: the column itself when it is a date, else the calendar a
  date-key relation reaches, at its `semantics { role: event_date }` column — the statement's time and a phase's window
  (`within(period.at, last: days(30))`) alike, through one join. The bounds are written as literals, and so is every
  placeholder a phase reads (`:as_of` the evaluation day, a `def parameter`'s value); a `latest(…, by:)` is capped at
  the evaluation day.
- `statementValidate` predicts it: a slot only md cannot reach is no error when E-R reads the statement, and E-R's own
  refusal (a slot mismatch) shows there too.
- Refused on E-R too: a `compare`, an `ANY` / `NOT` group, an expression filter, measures of different events, a window
  in working days. A statement neither lane reads is refused with both lanes' findings, md's first; every finding names
  the frame slot it is about (`groupBy[0]`, `filters[1].member.member`, `time`).

### Assertions

An assertion records an event — its participants by label, its period, its qualities — and lowers to **one row, the
event's**, for a writer to store. cn itself never writes.

```json
{ "rows": [{ "entity": "transfer",
             "values": { "patient_id": "1001", "from_encounter": "501", "to_encounter": "502",
                         "at": "2026-03-14T09:30", "transfer_kind": "step-down" } }],
  "assertedAsOf": "2026-03-14" }
```

- A participant becomes the foreign-key value its labelled relation writes on the event's row; it must carry a
  resolved member. A participant two relations away (the patient of the encounter the transfer starts from) is an
  **obligation**: the writer checks that the row the path reaches holds that member.
- Instants are written as the frame writes them (`2026-03`, `2026-03-14T09:30`); an imprecise period's stored upper
  bound is the next granule (`2026-04`). An absent end is NULL, and whether it is open or unknown travels beside the
  row.
- A value that does not fit its attribute's type, or one attribute given two values, refuses the assertion.

### Explanations

The sentence a frame says, in English or Czech, built from the frame (never from its lowering), for the inspector to
show before anything runs:

```
Net revenue · customer Kaufland · January 2026.
Čistý obrat · zákazník Kaufland · leden 2026.
Net revenue · large sale: net revenue > large_sale (= 10000) · January 2026.
Transfer · patient: Patient P-1001 · from: Encounter E-501 · to: Encounter E-502 · at 2026-03-14 09:30 · kind of transfer: step-down · asserted as of 2026-03-14 · source: manual:steward
```

A concept reads as its label in the locale, else its lexicon term in the locale, else its English label, else its
name in words (`LargeSale` → `large sale`); a parameter as `name (= value)`. A phase filter says its definition; a
construct the explanation cannot put into words is quoted from the source, never guessed. A refused frame is still
explained and ends `— refused: <slug> (<subject>)`. Another locale reads English, with `cn/explain-locale-fallback`.

### Rendering a definition

`definitionRender` renders a phase, a defined quality or a member-restricted kind on the **md** layer (a predicate
over the carrying cubelet's attributes, for TTR-P) or the **er** layer (SQL over entities). `PREDICATE` mode selects
the bearer's key:

```sql
-- cn.PendingOrder: state in ('placed', 'held')
SELECT "order".order_number
FROM "order"
WHERE "order".state IN ('placed', 'held')
```

`CONTEXT` mode joins the bearer to a candidate phase and projects named columns, the shape of a recipe's island. A
definition that reads the evaluation day reads it as a placeholder, and the rendering lists what it reads and how a
derived bound is computed:

```sql
-- cn.RecentBuyer: exists Order o involving this where within(o.period.start, last: days(365))
SELECT customer.sk
FROM customer
WHERE EXISTS (
  SELECT 1
  FROM "order" o
  WHERE o.customer = customer.sk AND o.placed_date >= {window_lo} AND o.placed_date <= {as_of}
)
-- reads: window_lo (= as_of - days(365)), as_of
```

The layout is fixed (one clause per line, a subquery indented two spaces), so a rendering diffs cleanly. A concept the
definition mentions that has no grounding on the layer refuses it (`cn/definition-unreachable(er)` · `(md)`).

### `ttr cn materialize`

A phase that many readers share can be stored as a logical query instead of re-rendered each time:

```
ttr cn materialize <model-dir> <cn.Phase> [--dialect postgresql] [--out <dir>]
```

It writes the phase's er rendering as a `def query <phase>_q` and an entity stacked on it, in the phase's package;
printed, or written under `--out` as `<phase>.ttrm`. You then replace the phase's `definition:` by a `cn2er_phase`
grounding on that entity. A definition that reads a `:name` parameter or `:as_of` is refused
(`cn/materialize-parametric`): a logical query takes no parameters. Exit 0 — written; 1 — refused (the finding on
stderr); 2 — usage, or a model that cannot be read. From the ttr-core repository: `just cn-materialize <model-dir>
<cn.Phase> [flags]`.

```ttrm
model er

def query placed_order_q {
    sourceText: """sql
SELECT "order".order_number
FROM "order"
WHERE "order".state = 'placed'
"""
}
def entity placed_order {
    binding: { target: { query: er.query.placed_order_q } },
    attributes: [ def attribute order_number { type: int, isKey: true } ]
}
```

## Concepts at run time

The compiled lexicon (`ttr-lexicon build`, archive `ttr-lexicon-compiled/v6` from grammar 0.18.1) carries the
concepts. Each concept gets one entry, with its stereotype, its landing and how it lands, and every er and md element a
concept is grounded on gets a `liftsTo` list. A Tatrman server reads it two ways:

- **Matching a question.** A concept's lexicon terms are matched like an element's. A word that matches an element
  also *lifts* to the concepts grounded on it, so *customer* reads as `cn.Customer` from the concept's own term and
  from the customer entity's word alike, as one reading. A concept is resolved as its er landing. `default: true`
  decides a bare form two concepts share: *customer* is `Customer`, not `DebtorCustomer`.
- **The doors.** The server answers frames over MCP: `statement.validate` · `statement.lower` ·
  `statement.explain`, the three `assertion.*` tools, `concept.search` (a span → the concepts it may mean) and
  `definition.render`. They are the functions of [Statements, assertions, rendering](#statements-assertions-rendering),
  over the deployment's model. A deployment serves them only when the model it mounts has the content id the lexicon
  was compiled from (`modelSnapshotHash`), so a door never answers from a model the vocabulary does not describe. A
  deployment that mounts no model answers them `CN_MODEL_UNAVAILABLE`; `concept.search` reads the vocabulary alone
  and answers either way.

The content id is `sha256` over every `.ttr` / `.ttrm` file under the estate's `model/`, in path order, each as its
relative path and its bytes. A deployment mounts the `model/` tree itself, or a snapshot archive of it
(`snapshot.json` plus `docs/<path>`). The Hartland demo ships its archive beside the lexicon, in one ConfigMap, so
one update moves both.

## The promotion loop

Concepts start as **candidates**. A producer that meets a word or a phrase no concept covers submits one: a recipe
sentence the authoring surface could not express (`au:synthesizer`), a span a document processor read (`kleio`), a
steward's own (`manual:<steward>`), or a deployment's evidence pipeline (`<deployment>:evidence`, and
`<deployment>:span-catalogue` for terms only). An importer never submits. The candidate is a `cn-candidate.v1`
document (`ttr-concept`'s `schemas/cn-candidate.v1.schema.json`) that proposes one thing: a concept, a term, or an
open-tier relation. It also records where it came from and why it matters. A recipe that says *customers who pay
badly* finds no concept for the phrase, though the model has one for the idea; the candidate proposes the words:

```json
{ "id": "6f1c…", "status": "proposed",
  "proposal": { "term": { "target_qname": "cn.DebtorCustomer", "locale": "en",
                          "forms": ["customers who pay badly", "bad payers"] } },
  "provenance": { "producer": "au:synthesizer", "source_ref": "recipe://hartland/collections#s2",
                  "confidence": { "value": 0.7, "producer": "au:synthesizer", "method": "no-concept" },
                  "evidence": { "examples": [ { "kind": "recipe_sentence", "ref": "recipe://hartland/collections#s2",
                                                "text": "customers who pay badly" } ] },
                  "created_at": "2026-10-25T09:00:00+02:00" } }
```

A concept proposal carries `stereo`, `name`, `of` / `event` / `involves` as its stereotype takes them, and either a
`definition_text` or a `suggested_grounding`.

A **steward** rejects it or promotes it. Promotion writes model text, never a model object: the def, the term or the
relation appended to the right file of the package. A def carries `sources: ["<source_ref>"]`; a term, which takes no
`sources:`, gets a `// sources:` comment line above it. The patched package
must load cleanly before anything leaves: the strict load report and the definition checks, and a new error refuses
the promotion. The change goes out as a pull request whose body carries, for a phase, its er rendering. Nothing is
live until a person merges it. The first model load that carries the def marks the candidate `promoted`, with
`canon_ref` = `<commit>:cn.<Name>`. The store is the metadata service's (off unless a database and a token issuer are
configured; every call carries a verified bearer, and the caller is the token):

| call | who |
|---|---|
| `POST /v1/cn/candidates` — submit; the same proposal twice is one candidate | a producer's principal |
| `GET /v1/cn/candidates?status=…&sort=newest\|evidence` — the queue | stewards, producers |
| `POST /v1/cn/candidates/{id}/promote` · `…/reject` | the `cn-steward` role |
| `POST /v1/vocabularies/{id}/version` — register a code-system version | the `cn-importer` role |

A promotion refuses rather than guesses: a name already taken in the package's cn name space, a reference the package
does not define, a second default sense for one form, a term whose concept is still a candidate.

## The hygiene report

Nothing in the conceptual model gates on completeness; the hygiene report shows where it stands. Per concept: its
grade and how it lands (direct, through the hop, inherited), its grounding and the hop column, core vs open (a concept
is core; its free-form keys and `relations:` edges are the open tier, listed), the refs in its groundings that no
longer resolve, the **redundant term duplicates** (a form a concept's term shares with a `def term` on an element the
concept is grounded to — one of the two can go), the phase-as-default rows and the retired codes — then the
definition-level findings.

```
ttr cn hygiene <model-dir> [--format text|json] [--fail-on error|warning]
```

`--format json` prints the same as data. Exit 0 — the report printed and nothing at or above `--fail-on` (without it,
always 0); 1 — a finding at or above it (an error: a load error, a dangling grounding ref or an error-grade definition
finding; a warning: a phase-as-default row, a retired code or a warning-grade definition finding); 2 — usage, or a
model that cannot be read. In this release the command runs from the ttr-core repository:
`just cn-hygiene <model-dir> [flags]`.

```
# Concept hygiene

8 concepts — 5 er-grounded · 1 md-grounded · 1 md-grounded-hop-missing · 1 ungrounded

| Concept | Stereotype | Grade | Via | Grounding | Hop | Lands on | Open tier |
|---|---|---|---|---|---|---|---|
| cn.FrequentShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.Sale | event | md-grounded | md-hop | md.cubelet.sales | md.cubelet.sales → er.entity.sale | er.entity.sale | — |
| cn.Store | kind | md-grounded-hop-missing | md-hop | md.dimension.Store | md.dimension.Store → md/er-unmapped | — | — |
| cn.Supplier | kind | ungrounded | — | — | — | — | supplies → cn.Store |
…

## Redundant term duplicates

- cn.Shopper — "shopper" (en): term `shopper`, and term `shopper_entity` on er.entity.shopper
…
## Definitions

- cn.LoyalShopper — cn/definition-cycle (error) at hartland/hygiene/c-concepts.ttrm:11: the definitions inline each
  other: LoyalShopper → VipShopper → LoyalShopper (AM-26)
```

In the editor, the command **TTR: Concept hygiene report** previews the same report without the definitions section,
which the editor does not check.

## `cnc` is deprecated

`model cnc`, `def role` in it, and an entity's `roles:` still parse in grammar 0.17; `cn/cnc-deprecated` warns on
the `model cnc` line of each such file and on each `roles:` (never on the stock vocabulary), and a later major release
removes them. There is **nothing to migrate to**: the
classification `cnc` recorded is derived — fact, dimension and bridge from the md bindings (`ttr md hop`, grammar
0.18.2); `master` / `transaction` / `structural` are planned as entity kinds in `semantics { }` — so a model drops its
`cnc` files and `roles:` rather than rewriting them. [CNC roles](09-cnc-roles.md) has the table.

## Words that stay names

`event`, `period`, `quality`, `phase`, `concept`, `default`, `as` and the other cn keywords remain ordinary names
everywhere else — a column called `period`, an object key `default:`, a ref part `er.entity.event` all keep working.
