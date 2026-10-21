# Conceptual model (cn)

The **conceptual model** names what the business talks about — products, customers, orders, sales, an overdue
invoice — apart from how the `db`, `er` and `md` layers store it. A **concept** is grounded to exactly one place in
those layers, and a defined concept carries the expression that says which rows it means. Agents and recipes speak in
concepts; the bindings tell them where the data is.

> **Grammar 0.17.1 — checked.** The editor resolves every cn reference, desugars `grounds:` and the `concept { }`
> sugar into bindings, and reports the model-level `cn/*` diagnostics as you type. `ttr cn hygiene` adds the checks on
> `definition:` / `members:` / `filter:` blocks and prints the [hygiene report](#the-hygiene-report).
> [Diagnostics](#diagnostics) says which check fires where.

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
`involves: [Customer]` becomes `involves: [Buyer as customer]`.

## Definitions are expression blocks

`definition:` and `members:` (and a binding's `filter:`) hold an expression in one of three forms: a string, a
triple-string, or a `"""pl` block. The parser keeps the text exactly as written; the expression itself is TTR-P's
expression language (see the [TTR-P expression reference](../../features/ttr-p/language-design.md#5-the-expression-sublanguage)).
In a definition, `this` is the bearer, an event's participants are reached by their labels, a phase name is a
predicate, `:as_of` is the instant the definition is evaluated at, and `:name` reads a `def parameter`. Durations
and windows are spelled as in TTR-P (`hours(24)`, `within(sold_date, last: days(365))`); the conceptual forms —
`exists … involving … where …`, `count(… involving …)`, `code in descendants(Kind, 'X')` and the temporal words
`at_or_before`, `before`, `contains`, `ended_before`, `overlaps` — are TTR-P expression grammar 0.5.

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
| `cn2md_*`, and an `md2er_cubelet` maps the target | md-grounded | the mapped entity or attribute — through the **hop** |
| `cn2md_*`, and no `md2er_cubelet` maps it | md-grounded-hop-missing | — `md/er-unmapped` (`md/er-ambiguous` when two map it and the bearer's entity does not pick one) |
| `cn2db_concept` | ungrounded | — documentation only |

A defined phase with no grounding takes its bearer's grade and landing. In this release the hop reads explicit
`md2er_cubelet` bindings only — a cubelet, and the md attributes its `attributes:` map names. Every other md grounding
— a dimension, a domain, a measure: `Item` to `md.Product` above — is md-grounded-hop-missing until the hop is
derived; ground such a concept to er when it needs a landing now.

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
  loads the model the way the editor checks it: an error-grade *editor* finding is printed to stderr as a load error
  and fails `--fail-on error`; the editor's warnings and hygiene findings it does not print;
- **consumer** *(a later release)* — the tools that read the model at run time will refuse a statement, an assertion
  or a recipe with it.

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
| `cn/missing-time` | error | consumer | a statement with no time and not `all_time` |
| `cn/materialize-parametric` | error | `ttr cn materialize` *(a later release)* | a definition reading `:name` or `:as_of` cannot become a stacked entity |
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

**Granularity.** An instant's granularity is its event's `period: { precision }`, else its attribute's type (a `date`
is a day, a timestamp a second); `:as_of` is a day. Unknown on either side — no finding.

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
finding; a warning: a phase-as-default row, a retired code or a warning-grade definition finding); 2 — usage. In this
release the command runs from the ttr-core repository: `just cn-hygiene <model-dir> [flags]`.

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
classification `cnc` recorded (fact, dimension, …) is derived from the md bindings and the entities' `semantics { }`
kinds, so a model drops its `cnc` files and `roles:` rather than rewriting them.

## Words that stay names

`event`, `period`, `quality`, `phase`, `concept`, `default`, `as` and the other cn keywords remain ordinary names
everywhere else — a column called `period`, an object key `default:`, a ref part `er.entity.event` all keep working.
