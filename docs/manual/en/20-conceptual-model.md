# Conceptual model (cn)

The **conceptual model** names what the business talks about — products, customers, orders, sales, an overdue
invoice — apart from how the `db`, `er` and `md` layers store it. A **concept** is grounded to exactly one place in
those layers, and a defined concept carries the expression that says which rows it means. Agents and recipes speak in
concepts; the bindings tell them where the data is.

> **Grammar 0.17 — reference only.** This page documents the syntax. The cn semantics — which property is legal on
> which kind, the `cn/*` diagnostics, the desugaring of the `concept { }` sugar and of `grounds:`, reference
> checking — arrive with the next release. Until then a cn file parses and is carried through, but nothing checks it.

| where | what |
|---|---|
| a `model cn` file | the concepts: `def kind` · `def role` · `def event` · `def quality` · `def phase` · `def relation_type` |
| a `model binding` file | the groundings: `def cn2md_*` · `def cn2er_*` · `def cn2db_concept` |
| inside an md or er def | the `concept { … }` sugar — the def's own concept |
| a `model lexicon` file | terms and examples whose `for:` is a concept (`for: cn.Customer`) |

A concept is referred to as `cn.<Name>` from other models (`kind: cn.Item`, `for: cn.Customer`), and by its bare
name inside a `model cn` file.

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
| `imprecision` | `certainly` · `possibly` | how a definition reads an imprecise time |
| `basis` | `occurrence` · `current_state` · `past_state` | what a condition phase is evaluated on |
| `relations` | `[ { type: <relation_type>, to: <concept> } ]` | open-tier relations |
| `lexicon` | `{ terms: […] }` | the inline lexicon shorthand, targeting the concept |

Any other `key: value` parses as a free-form property (a literal, a name, a list or an object). The keys above that
the grammar types — `definition`, `members`, `grounds`, `involves`, `period`, `extent`, `basis`, `value_space`,
`event` — must carry their typed value: `definition: OverdueInvoice` is a parse error, not a free-form property.

## Definitions are expression blocks

`definition:` and `members:` (and a binding's `filter:`) hold an expression in one of three forms: a string, a
triple-string, or a `"""pl` block. The parser keeps the text exactly as written; the expression itself is TTR-P's
expression language (see the [TTR-P expression reference](../../features/ttr-p/language-design.md#5-the-expression-sublanguage)).
In a definition, `this` is the bearer, an event's participants are reached by their labels, a phase name is a
predicate, and `as_of` is the instant the definition is evaluated at.

```ttrm
model cn

def quality open_amount { of: Invoicing, definition: "amount - paid" }

def quality transfer_delay {
    of: StockTransfer,
    definition: """pl
        this.period.at - this.from.period.at
    """
}

def phase PendingOrder   { of: Order, definition: """state in ('placed', 'held')""" }
def phase OverdueInvoice { of: Invoicing, definition: """invoice_status = 'open' and period.end < as_of""" }
def phase DebtorCustomer {
    of: Customer,
    definition: """exists Invoicing involving this as customer where OverdueInvoice""",
    imprecision: certainly
}
def phase PlacedOrder    { of: Order, grounds: er.entity.placed_order }
```

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
    qualities: [shipped_at, transfer_delay]
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
| `measure` | a quality |
| `entity` | a kind, a role or an event |
| er `attribute` | a quality, or a value kind |

```ttrm
model md

def cubelet storeReturns {
    grain: [Customer.code, Product.code],
    concept { event: Return, involves: [Customer, Item, ReturnReason] }
}
def measure returnAmount { domain: md.Money, aggregation: sum, concept { quality: return_amount } }
```

```ttrm
model er

def entity invoice {
    attributes: [
        def attribute invoice_id { type: int, isKey: true },
        def attribute status { type: text, concept { quality: invoice_status } }
    ],
    concept { kind: Invoice }
}
def entity customer { concept { role: Customer, event: Order } }
```

## Lexicon entries for concepts

A `term` or `example` can target a concept. `default: true` makes it the reading of a bare form when several
concepts share it (never on a phase); `sense_priority:` ranks the rest, higher first.

```ttrm
model lexicon locale en

def term customer { for: cn.Customer, forms: ["customer", "customers", "client"], default: true }
def term debtor   { for: cn.DebtorCustomer, forms: ["debtor"], sense_priority: 2 }
def example web_revenue { for: cn.WebSale, text: "revenue on the web last month", default: true }
```

## The open tier

Relations between concepts that no consumer computes with — they inform, they never lower to a query:

```ttrm
model cn

def relation_type supplies { description: "A supplier supplies a distribution center.", label: { en: "supplies" } }
def kind Supplier { is_a: Party, relations: [ { type: supplies, to: Warehouse } ] }
```

## `cnc` is deprecated

`model cnc`, `def role` in it, and an entity's `roles:` still parse in grammar 0.17; a deprecation warning arrives
with the cn semantics, and a later major release removes them. There is **nothing to migrate to**: the
classification `cnc` recorded (fact, dimension, …) is derived from the md bindings and the entities' `semantics { }`
kinds, so a model drops its `cnc` files and `roles:` rather than rewriting them.

## Words that stay names

`event`, `period`, `quality`, `phase`, `concept`, `default`, `as` and the other cn keywords remain ordinary names
everywhere else — a column called `period`, an object key `default:`, a ref part `er.entity.event` all keep working.
