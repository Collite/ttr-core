# Conceptual model (cn)

The **conceptual model** names what the business talks about — products, customers, orders, sales, an overdue
invoice — apart from how the `db`, `er` and `md` layers store it. A **concept** is grounded to exactly one place in
those layers, and a defined concept carries the expression that says which rows it means. Agents and recipes speak in
concepts; the bindings tell them where the data is.

> **Grammar 0.17.2 — checked.** The editor resolves every cn reference, desugars `grounds:` and the `concept { }`
> sugar into bindings, and reports the model-level `cn/*` diagnostics as you type. `ttr cn hygiene` adds the checks on
> `definition:` / `members:` / `filter:` blocks and prints the [hygiene report](#the-hygiene-report).
> [Diagnostics](#diagnostics) says which check fires where.
>
> **Toolchain 0.17.6 — used.** `org.tatrman:ttr-concept` lowers what agents say in concepts to data operations:
> [statements, assertions and rendering](#statements-assertions-rendering), and `ttr cn materialize`.
>
> **0.18.1–0.18.3 — served.** The compiled lexicon carries the concepts, and a Tatrman server reads them when it
> matches a question and answers frames over its doors ([Concepts at run time](#concepts-at-run-time)). Candidates
> become concepts through a steward's pull request ([The promotion loop](#the-promotion-loop)).
>
> **0.18.10–0.18.12 — the er lane.** A statement md has no single read of is read on E-R
> ([Statements](#statements)).
>
> **0.20 — things in time.** `def subkind`; an event is an instant; a kind can relate things and have a lifetime; a
> phase holds over episodes; a quality's value can be read as of a day
> ([Six stereotypes, four tenses](#six-stereotypes-four-tenses)). `extent:` is retired. An assertion names a `concept`
> ([Assertions](#assertions)).

| where | what |
|---|---|
| a `model cn` file | the concepts: `def kind` · `def subkind` · `def role` · `def phase` · `def quality` · `def event` · `def relation_type` |
| a `model binding` file | the groundings: `def cn2md_*` · `def cn2er_*` · `def cn2db_concept` |
| inside an md or er def | the `concept { … }` sugar — the def's own concept |
| a `model lexicon` file | terms and examples whose `for:` is a concept (`for: cn.Customer`) |

A concept is referred to as `cn.<Name>` from other models (`kind: cn.Item`, `for: cn.Customer`), and by its bare
name inside a `model cn` file — see [Names and references](#names-and-references).

The examples are Hartland's, with `labels:` left out.

## Six stereotypes, four tenses

A concept is one of six stereotypes. Two questions place it.

**Does it happen, exist, hold or vary?** That is its tense, and the tense says what time the concept carries:

| tense | stereotype | the time it carries | Hartland |
|---|---|---|---|
| **happens** | `event` | an instant: `period: { at }` | `Sale`, `Return`, `Inventory` (a stock reading), and the transitions `OrderPlaced`, `OrderDelivered`, `InvoiceIssued`, `ReturnRequested` |
| **exists, is valid** | `kind` · `subkind` · `role` | a lifetime or a validity: `period: { start, end }` — or none | `Order`, `OrderLine`, `Invoice`, `ListPrice` (valid from–to), `PriceAgreement` |
| **holds** | `phase` | an interval per episode | `HeldOrder`, `OverdueInvoice`, `DebtorCustomer`, `OutOfService`, `OpenOrder` |
| **varies** | `quality` with `valued_state: true` | an interval per value | `channel_price` of an item per channel, `agreed_price` of a customer per item |

**Can it stop being one without ceasing to exist?** A web sale never becomes a store sale: `WebSale` is a `subkind`
of `Sale`, rigid, one from the start. A held order is released and is still the same order: `HeldOrder` is a `phase`
of `Order`, anti-rigid, and it is evaluated as of a day. A customer is a party in a part it plays: `Customer` is a
`role`, defined by the event that makes it one.

The point thing is the event. The interval thing is either an object's lifetime or validity (a kind) or a state's
episode (a phase). **No event lasts:** a happening with a duration is a kind whose lifetime two events bound — an
order, placed by `OrderPlaced` and closed by `OrderDelivered` — which is also how an event stream carries it.

```ttrm
package hartland
model cn

def kind Order {
    involves: [Customer, Warehouse as fulfilling],
    period: { start: placed_date, end: delivered_date, end_open: open },
    qualities: [order_value, state, channel, placed_date, delivered_date]
}
def event OrderPlaced    { involves: [Order, Customer], period: { at: placed_date } }
def event OrderDelivered { involves: [Order],           period: { at: delivered_date } }
def phase OpenOrder      { of: Order, from: OrderPlaced, until: OrderDelivered }
```

### `kind`

A rigid thing — it is what it is for its whole life: a product, a channel, an order, a document. It carries
`grounds:`, `qualities:` and, for a vocabulary kind, `hierarchy:`.

- **A relator.** With `involves:` a kind is an object whose identity lies in what it relates: `Order` relates a
  customer and the fulfilling warehouse, `OrderLine` an order, an item and a warehouse. Its participants are labelled
  as an event's are, and `exists Order involving this as customer where …` ranges over it.
- **A lifetime or a validity.** With `period: { start, end, end_open }` a kind carries an object's lifetime (an
  order's, placed → delivered) or a row's validity (a price valid from–to, an SCD2 version). A kind's period is a
  start and an end, never an `at` (`cn/lifetime-period`); `end_open: open` reads a missing end as still running.
- `abstract: true` — a kind only its subkinds instantiate: `Document`.

```ttrm
def kind Document { abstract: true }
def kind ListPrice {
    involves: [Item, Channel],
    period: { start: pl_from, end: pl_to, end_open: open },
    qualities: [pl_price, pl_channel, pl_from, pl_to]
}
def kind PriceAgreement {
    involves: [Customer, Item],
    period: { start: cp_from, end: cp_to, end_open: open },
    qualities: [cp_price, cp_from, cp_to]
}
```

### `subkind`

A rigid specialization: `def subkind X { of: <bearer>, … }`, the bearer a kind, a subkind, a role or an event
(`cn/subkind-bearer` otherwise). A subkind is defined by a `definition:` with no time in it (or `members:` on a
vocabulary kind), or it is a kind of its own, with its own participants and qualities:

```ttrm
def subkind StoreSale       { of: Sale, definition: """channel = 'store'""" }
def subkind WebSale         { of: Sale, definition: """channel = 'web'""" }
def subkind MarketplaceSale { of: Sale, definition: """channel = 'marketplace'""" }

def subkind Invoice {
    of: Document,
    involves: [Customer, Order],
    qualities: [amount, paid, invoice_status, issued, due]
}
```

- **No time.** A definition that reads `:as_of` or a `:name` parameter, a temporal word (`before`, `contains`,
  `within(…)`, `age(…)`, …), a valued-state quality or a phase is `cn/subkind-temporal-definition`. So a subkind is
  always materializable: `ttr cn materialize` never refuses one for its parameters.
- **Partitions.** Subkinds of one bearer, each an equality on one attribute (`channel = 'web'`), partition it: two on
  the same value are `cn/partition-overlap`. When the attribute's value space lists its members, a member no subkind
  covers is `cn/partition-incomplete` (warning). Hartland's three subkinds cover `channel`'s three members.
- **Landing.** A subkind with no grounding lands where its bearer lands — `WebSale` on `er.entity.sales` — and a
  statement filtered by it reads its definition on the bearer's row. Its own `grounds:` is a stacked entity, as a
  phase's is.
- **The old form.** `def kind X { is_a: Y, members: … }` still reads, as a subkind, with `cn/subkind-as-kind`
  (warning).
- **The word** is OntoUML's. *Cohort* and *segment* were turned down: a cohort is a time-defined group, and customers
  move between segments.

The author decides which side a concept is on, and the lints check only what its definition reads. `LargeOrder`
(`order_value > 1000000`) would be a subkind if an order's value never changed; Hartland edits orders while they are
held, so it is a phase.

### `role`

A kind in a part it plays, defined by an event: `event:` is required and must name an event (`cn/role-no-event`), and
`of:` may name the base kind. `Order` is a kind now, so a Hartland customer is whoever places one:

```ttrm
def role Customer { event: OrderPlaced, grounds: md.Customer }
```

A `def role` in a `model cn` file is a concept role. In a `model cnc` or `model automation` file it is the role of
that model, and the cn properties are an error there.

### `phase`

An anti-rigid state of a bearer (`of:` is required). It holds over **episodes**, from exactly one of four sources —
two sources, or a `from:` without an `until:`, is `cn/phase-source-conflict`:

| source | written | Hartland |
|---|---|---|
| a definition, evaluated as of a day | `definition: """…"""` | `HeldOrder`, `OverdueInvoice`, `DebtorCustomer` |
| a stacked entity — the definition materialized | `grounds: er.entity.<stacked>` | `PlacedOrder` on `placed_order` |
| an episode table — its rows are the episodes | `period: { start, end }` and a grounding on the table | `OutOfService` on `warehouse_outage` |
| two events — the stream starts and ends it | `from: <event>, until: <event>` | `OpenOrder`, from `OrderPlaced` until `OrderDelivered` |

```ttrm
def phase HeldOrder      { of: Order,    definition: """state = 'held'""" }
def phase OverdueInvoice { of: Invoice,  definition: """invoice_status = 'open' and due < :as_of""" }
def phase DebtorCustomer { of: Customer, definition: """exists Invoice involving this as customer where OverdueInvoice""" }
def phase PlacedOrder    { of: Order,    grounds: er.entity.placed_order }
def phase OutOfService {
    of: Warehouse,
    involves: [Warehouse],
    period: { start: outage_from, end: outage_to },
    qualities: [outage_from, outage_to, outage_reason]
}
def phase OpenOrder      { of: Order, from: OrderPlaced, until: OrderDelivered }
```

- **An episode table** (`OutOfService`): each row of `warehouse_outage` is one episode. Its relation to the bearer's
  entity names the subject (`cn/episode-subject-missing` when no relation reaches it), its `period:` the interval, and
  its qualities are the episode's own (`outage_reason`). Hartland grounds it in the binding file ([below](#bindings)).
- **Event bounds** (`OpenOrder`): each event needs exactly one participant the bearer can be
  (`cn/phase-bounds-participant`). The bounds are half-open: a `from` occurrence starts an episode, and the first
  `until` for that subject at or after it ends it. So an `until` at the start's own instant closes it — an order
  delivered the day it is placed is never open — and so does one at the instant a new start reopens it. No `until`
  leaves the episode open.
- An episode predicate selects each subject once, also when its episodes overlap.
- Episodes, relators and projected qualities are E-R constructs; an md read of one is refused with `G-MD-1`.

A condition is a phase too; [Time](#time) has its `basis:`.

### `quality`

A characteristic of a bearer (`of:` is required): grounded (`revenue` on `md.revenue`) or defined
(`open_amount`, below), with an optional `value_space:` kind whose members its values are. `valued_state: true` marks
a value that changes over time.

**A projection.** A valued state kept in a relator's rows is read *through* the relator: `from: <Relator>.<quality>`,
with `per:` naming the other participant that picks the row.

```ttrm
def quality channel_price { of: Item,     valued_state: true, per: Channel, from: ListPrice.pl_price }
def quality agreed_price  { of: Customer, valued_state: true, per: Item,    from: PriceAgreement.cp_price }
```

The row's identity lives once, in the relator, and the quality grounds through it, so one grounding per concept
holds. Model the relator when the row relates two or more things or has qualities of its own; the valued state alone
when it is one subject and one value (stock on hand, an address); both when the value is asked about on its bearer.

A definition reads a projection with a call whose arguments are named (DC-13): `at:` is the instant, `:as_of` when
left out, and the receiver may follow participant paths. The call reads the relator's row valid at that instant, so it
and the lookup over the relator say the same thing:

| the valued state | the relator |
|---|---|
| `this.item.channel_price(channel: c, at: t)` | `max(ListPrice involving this.item where pl_channel = c and period contains t).pl_price` |

- The source is a quality of a kind that involves the bearer and the `per:` concept, and `per:` covers every other
  participant, so a read gives one row per bearer (`cn/projection-source`).
- The period test is inclusive (`end >= :as_of`): when one row's closed end equals the next row's start, both match at
  that instant. Use `end_open: open`, or bounds that do not touch — a property of the data, which no lint can check.
- A `this.` receiver always reads the bearer, also inside an `exists` or an aggregate body. A dotted call that names
  no projected quality is refused.
- The calls need TTR-P expression grammar 0.7.

**Which valued state wins is a definition, never a construct.** "The customer's individual price beats the channel's
list price" is Hartland's `applicable_price`; [Recipes over concepts](#recipes-over-concepts) shows its rendering.

```ttrm
def quality applicable_price {
    of: OrderLine,
    definition: """coalesce(
        this.order.customer.agreed_price(item: this.item, at: this.order.placed_date),
        this.item.channel_price(channel: this.order.channel, at: this.order.placed_date))"""
}
```

### `event`

An occurrence at an instant — the stream: a sale, a return, a stock reading, a kind's transitions. It carries
`involves:`, `period: { at, precision }` and `qualities:`.

```ttrm
def event Sale          { involves: [Customer, Item, Channel], period: { at: sold_date }, qualities: [revenue, quantity, sold_date] }
def event InvoiceIssued { involves: [Invoice], period: { at: issued } }
```

- An event exists only where the data has an instant. Hartland stores no payment date, so there is no `InvoicePaid`:
  `PaidInvoice` is a phase over the invoice's status.
- `period: { start, end }` on an event, or `extent: durative`, is `cn/durative-event` (error): model a kind with a
  lifetime, or a phase with episodes.
- `extent: instantaneous` is retired: still accepted, as a no-op, with `cn/extent-retired` (warning). The token goes at
  the next major release.
- An event with no `period:` is `cn/event-no-period` (hygiene) — legitimate for, say, a diagnosis as an act.

The seventh def, `relation_type`, belongs to [the open tier](#the-open-tier): it relates concepts and grounds nothing.

## Properties

| key | value | meaning |
|---|---|---|
| `description` · `label` · `tags` | as on every def | `label` is a locale map |
| `sources` | `["doc://…"]` | compact provenance |
| `is_a` | a concept, or a list | the super-concept(s) |
| `abstract` | `true` | a concept only its sub-concepts instantiate |
| `of` | a concept | the bearer of a quality, a phase or a subkind; a role's base kind |
| `event` | an event | a role's defining event |
| `grounds` | `md.<X>` · `er.entity.<e>` · `er.entity.<e>.<a>` | shorthand for the concept's one binding |
| `hierarchy` | `{ closure, ancestor, descendant, ranges: node \| lexical }` | how a vocabulary kind's codes nest (a property of its grounding) |
| `involves` | `[ Customer, Warehouse as fulfilling ]` | the participants of an event or a relator, each optionally labelled |
| `period` | `{ at, precision }` · `{ start, end, end_open: open \| unknown }` | an event's instant; a kind's lifetime or validity, an episode table's interval — through qualities |
| `extent` | `instantaneous` | retired (`cn/extent-retired`); `durative` is an error |
| `from` · `until` | an event | a phase's bounds |
| `from` | `<Relator>.<quality>` | a valued-state quality's source |
| `per` | a concept | the participant that picks a projection's row |
| `qualities` | `[ … ]` | the qualities a concept carries |
| `value_space` | a kind | the kind a quality's values come from |
| `valued_state` | `true` | a quality whose value changes over time |
| `definition` | an expression block | what a defined quality, phase or subkind means |
| `members` | an expression block | which members a subkind of a vocabulary kind restricts to |
| `imprecision` | `certainly` · `possibly` | how a definition reads an imprecise time (default `certainly`) |
| `basis` | `occurrence` · `current_state` · `past_state` | what a condition phase is evaluated on |
| `relations` | `[ { type: <relation_type>, to: <concept> } ]` | open-tier relations |
| `lexicon` | `{ terms: […] }` | the inline lexicon shorthand, targeting the concept |

Any other `key: value` whose key is a plain name parses as a free-form property (a literal, a name, a list or an
object). The key cannot be a TTR keyword: `to:`, `type:`, `name:`, `key:`, `source:`, `default:` and the like are parse
errors at the top of a concept body (inside a nested object such as a `relations:` entry, `type:` and `to:` are fine).
The keys above that the grammar types — `definition`, `members`, `grounds`, `involves`, `period`, `extent`, `basis`,
`value_space`, `event`, `from`, `until`, `per` — must carry their typed value: `definition: OverdueInvoice` is a parse
error, not a free-form property.

## Names and references

A reference to a concept is written with the `cn.` head (`for: cn.Customer`, `kind: cn.Item`), or bare in a slot that
can only name a concept (`of: Party`, `role: Customer`). Either way it resolves **among concepts only** — the concept
`Customer` and the md dimension `md.Customer` never meet — in the usual order: package-qualified, the same package, a
named import, the wildcard imports, then a name unique in the project.

| where | slot | resolves as |
|---|---|---|
| a concept | `is_a` · `of` · `event` · `involves` · `value_space` · `from` · `until` · `per` · `relations[].type` / `.to` | a concept — a quality's `from:` is `<Relator>.<quality>`, the relator and its quality |
| a concept | `qualities:` items · `period:` `start` / `end` / `at` | a quality of the bearer — a name no `def quality` declares is an implicit quality, not an error |
| a concept, a binding | `grounds` · `target` · `hierarchy.closure` · `via` · `participants:` paths · `period:` | an md / er / db element, as written |
| a binding | the concept slot (`kind:` `role:` `quality:` `event:` `phase:` `concept:`) | a concept |
| a binding | `participants:` keys | the concept's participant labels |
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
In a definition, `this` is the bearer, a concept's participants are reached by their labels, a phase name is a
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

def quality open_amount { of: Invoice, definition: """amount - paid""" }
def quality on_hand {
    of: OrderLine,
    definition: """coalesce(latest(Inventory involving this.item, this.warehouse, by: as_of_date).quantity_on_hand, 0)"""
}
def quality outage {
    of: OrderLine,
    definition: """exists OutOfService involving this.warehouse where period contains :as_of"""
}

def phase PendingOrder  { of: Order, definition: """state in ('placed', 'held')""" }
def phase ShortLine     { of: OrderLine, definition: """outage or on_hand < line_quantity""" }
def phase MispricedLine {
    of: OrderLine,
    definition: """applicable_price is null or abs(unit_price - applicable_price) > :tolerance"""
}
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
| `this.item.channel_price(channel: c)` | `channel_price(this.item, c)` | a projection read: a call on its bearer, arguments named |

### Names in a definition

- `this` is the bearer. A bare name reads the innermost subject first — the concept an `exists` or an aggregate ranges
  over — then the bearer; a variable (`exists Invoice i …`) or a ranged concept's name names its row.
- On a subject: `period` (with `.start`, `.end`, `.at`), a participant label (`this.warehouse`), a quality (listed in
  its `qualities:`, or a `def quality` of it — up `of:` and `is_a`), a projected quality's call, or a phase of it,
  which reads as a predicate.
- `exists` ranges over any concept with `involves:` — an event, a relator kind, an episode phase. Each
  `involving` participant is one the ranged concept involves — by label (`as customer`) or, unlabelled, its one
  participant of that concept.
- `descendants(<Kind>, '<code>')` needs a kind grounded with a `hierarchy:`.
- A binding's `filter:` reads its target entity's attributes.

The editor shows a definition as text; `ttr cn hygiene` parses and checks it (syntax, names, cycles, reachability,
the `basis:` shape, instant comparisons — see [Diagnostics](#diagnostics)).

## Time

Seven words carry time through the model and the frames:

| word | means | in cn |
|---|---|---|
| **instant** | a point on the time line, at a granularity (minute · day · month · year) | an event's `period: { at, precision }`; `:as_of` |
| **interval** | the time from a begin instant to an end, closed-open `[begin, end)`; an absent end is either still running or unknown | a kind's lifetime or validity, a phase's episode: `period: { start, end, end_open: open \| unknown }` |
| **duration** | a length of time with no position | `days(365)`, `hours(24)` |
| **occurrence** | one happening of an event, at its instant | a row of the event's grounding |
| **state** | something that holds of a subject at instants | a phase; a quality with `valued_state: true` |
| **episode** | the longest interval a state holds, from the occurrence that starts it to the one that ends it (either may be unknown) | a phase's: a row of its episode table, or the span from a `from:` occurrence to its `until:` |
| **as-of** | the instant a definition is evaluated at, or a claim is made at | `:as_of` in a definition; a statement's `as_of`; an assertion's `asserted_as_of` |

Every occurrence is instantaneous. A counted happening with a duration — a surgery, a hospital stay — is a kind with
a lifetime that two events bound (`Surgery`, from `SurgeryStarted` to `SurgeryEnded`), not an occurrence that lasts.
A stored end at a granularity names the last granule the interval covers, and the interval runs to that granule's
end: an outage whose `to_date` is 2026-11-03 holds on the 3rd, so `contains` reads `to_date >= :as_of`.

A **condition** is a phase whose definition reads occurrences or episodes at `:as_of`; its `basis:` says which
comparison makes it hold (see [the `basis:` shapes](#diagnostics)). `occurrence` holds forever once the occurrence
happened (*post-infarction*), `current_state` while an episode contains the day (*has a broken leg*), `past_state`
once an episode has ended (*history of a fracture*). No construct is added for conditions: the basis is visible in
the definition, and the hygiene report checks that the definition has the comparison its `basis:` names.

```ttrm
model cn

def phase OverdueInvoice {
    of: Invoice,
    definition: """invoice_status = 'open' and due < :as_of"""
}
def phase LateReturnLine {
    of: ReturnLine,
    definition: """this.request.order.delivered_date < :as_of - :return_window"""
}
```

An order not delivered yet has no `delivered_date`, so `LateReturnLine` never calls its lines late: the comparison is
not true.

## Bindings

Every concept is grounded **once** — to the md layer (what analysts aggregate) or to the er layer (the base). The
binding names the concept by its stereotype key and the element by `target:`.

| binding | concept | target |
|---|---|---|
| `cn2md_kind` · `cn2md_role` | `kind:` · `role:` | an md dimension |
| `cn2md_quality` | `quality:` | an md measure, or a dimension attribute |
| `cn2md_event` | `event:` | an md cubelet |
| `cn2er_kind` | `kind:` | an entity, or an attribute (a value kind); `hierarchy:`; a relator's `participants:` and a lifetime's `period:` |
| `cn2er_role` | `role:` | its own entity, or a base entity plus a `via:` relation path |
| `cn2er_event` | `event:` | an entity; `participants:` (label → relation path) and `period: { at }` |
| `cn2er_quality` | `quality:` | an attribute; `filter:` selects a valued state's series |
| `cn2er_phase` | `phase:` | a query-backed (stacked) entity; or an episode table, with `period:` and `participants:` |
| `cn2db_concept` | `concept:` | a db object — documentation only, nothing reads it |

```ttrm
package hartland
model binding

def cn2md_kind    item_g     { kind: cn.Item, target: md.Product }
def cn2md_quality revenue_g  { quality: cn.revenue, target: md.revenue }

def cn2er_event sale_g {
    event: cn.Sale,
    target: er.entity.sales,
    period: { at: er.entity.sales.sold_date },
    participants: { customer: [er.relation.rel_sales_customer], item: [er.relation.rel_sales_item] }
}
def cn2er_kind order_g {
    kind: cn.Order,
    target: er.entity.order,
    participants: { customer: [er.relation.rel_order_customer], fulfilling: [er.relation.rel_order_warehouse] }
}
def cn2er_kind price_list_g {
    kind: cn.ListPrice,
    target: er.entity.price_list,
    period: { start: er.entity.price_list.valid_from, end: er.entity.price_list.valid_to },
    participants: { item: [er.relation.rel_price_list_item] }
}
def cn2er_phase outage_g {
    phase: cn.OutOfService,
    target: er.entity.warehouse_outage,
    period: { start: er.entity.warehouse_outage.from_date, end: er.entity.warehouse_outage.to_date },
    participants: { warehouse: [er.relation.rel_outage_warehouse] }
}
def cn2er_event order_placed_g {
    event: cn.OrderPlaced,
    target: er.entity.order,
    period: { at: er.entity.order.placed_date },
    participants: { order: [], customer: [er.relation.rel_order_customer] }
}
```

Attributes are referred to as `er.entity.<entity>.<attribute>`.

- **An empty path is the row itself.** A transition is a column of its kind's row: `OrderPlaced` lands on `order`,
  and its `order` participant is that row (`order: []`). An empty path must name a participant whose concept lands on
  the binding's own entity (`cn/participant-path-broken`).
- **A participant that is a column** of the row (`Channel` on `sales` and on `price_list`) needs no path.
- **Nothing is written for a projection:** `channel_price` grounds through `ListPrice`. An event-bounded phase needs
  no binding either.

The top-down forms above have explicit binding equivalents — an episode table named by `episodes:`, the bounds by
`bounds:`, a projection by `via:` and `per:`:

```ttrm
model binding
def cn2er_phase outages {
    phase: cn.OutOfService, episodes: er.entity.warehouse_outage,
    period: { start: er.entity.warehouse_outage.from_date, end: er.entity.warehouse_outage.to_date }
}
def cn2er_phase open_order { phase: cn.OpenOrder, bounds: { from: cn.OrderPlaced, until: cn.OrderDelivered } }
def cn2er_quality projected_price { quality: cn.channel_price, via: ListPrice.pl_price, per: Channel }
```

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
    concept { role: Customer, event: OrderPlaced }
}
```

## One grounding per concept

`grounds:` and a `concept { }` block are shorthand for a binding: `def kind Item { grounds: md.Product }` stands for a
`cn2md_kind` binding of `Item` to `md.Product`. The binding kind follows from the concept's stereotype and the
target's layer; a phase grounds only to a query-backed entity or an episode table. A concept has **one** grounding: a
second binding, `grounds:` or `concept { }` block for it is `cn/duplicate-grounding` at each later one, and the first
in document order is the one read. A `concept { }` block naming a concept a cn def declares may add what the def leaves
unset, never restate it differently.

Where a concept lands on the er layer is its **grade**:

| grounding | grade | lands on |
|---|---|---|
| none | ungrounded | — |
| `cn2er_*` | er-grounded | its target |
| `cn2md_*`, and the hop resolves the target | md-grounded | the mapped entity or attribute — through the **hop** |
| `cn2md_*`, and the hop cannot resolve it | md-grounded-hop-missing | — `md/er-unmapped` (`md/er-ambiguous` when two map it and the bearer's entity does not pick one) |
| `cn2db_concept` | ungrounded | — documentation only |

Some concepts land through another one, with no grounding of their own: a defined phase or subkind takes its bearer's
grade and landing, an event-bounded phase lands on its bearer's entity, and a projected quality on its relator's
attribute (`channel_price` on `price_list.price`). The hop first reads explicit `md2er_cubelet` and `md2er_dimension`
bindings, then derives through shared MD/ER physical bindings and relations. A quality first restricts derived
candidates to its bearer's entity, including stacked-query column origins. `ttr md hop <dir>` reports the same
landings and derived roles for the whole MD model. See
[the hop bindings and derivation](15-md-model.md#the-md--er-hop-what-you-pin-what-is-derived).

### The hop in practice

The Hartland demo model grounds its analytic concepts to md and authors **no `md2er` binding**: every md-grounded
concept lands on the er layer through a derived hop, the md element's physical binding read back through the er
layer's. Its hygiene summary on grammar 0.20:

```
94 concepts — 75 er-grounded · 11 md-grounded · 8 ungrounded
```

and no `md-grounded-hop-missing` row (`LargeOrder` came in through [the promotion loop](#the-promotion-loop)). Two of
the derived landings:

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
- **consumer** — `ttr-concept` (toolchain 0.17.6) refuses a statement, an assertion or a recipe with it: its validates
  and lowers, which the tools that read the model at run time call (see
  [Statements, assertions, rendering](#statements-assertions-rendering)).

| slug | grade | fires in | meaning |
|---|---|---|---|
| `cn/duplicate-grounding` | error | editor | a second grounding of one concept, or a `concept { }` block restating a cn def differently |
| `cn/grounding-target-kind` | error | editor | the target is not what the stereotype grounds to on that layer (a kind to a measure, a phase to md, …), or a `concept { }` on a cubelet's inline measure |
| `concept-bearer-missing` | error | editor | a quality or a phase that names no bearer — `of:` is required (`ttr/required-property-missing` in the Kotlin and Python toolchains) |
| `cn/role-no-event` | error | editor | a role names no defining event, or its `event:` is not an event |
| `cn/phase-double-definition` | error | editor | a phase with both `definition:` and a grounding |
| `cn/phase-source-conflict` | error | editor | a phase with more than one source (a definition, a grounding, `from:` + `until:`), or a `from:` without its `until:` |
| `cn/phase-bounds-participant` | error | editor · consumer | a `from:` / `until:` that is not an event with exactly one participant the bearer can be — none, or two (the message names their labels) |
| `cn/episode-subject-missing` | error | editor · consumer | an episode table with no relation to the phase's bearer |
| `cn/subkind-bearer` | error | editor | a subkind whose `of:` is not a kind, a subkind, a role or an event |
| `cn/subkind-temporal-definition` | error | editor · `ttr cn hygiene` | a subkind whose definition reads `:as_of`, a `:name` parameter, a temporal word, a valued-state quality or a phase |
| `cn/partition-overlap` | error | editor | two subkinds of one bearer defined by the same value of one attribute |
| `cn/durative-event` | error | editor | an event with `period: { start, end }` or `extent: durative` — model a kind with a lifetime, or an episode phase |
| `cn/lifetime-period` | error | editor | a kind's, a role's, a subkind's or a phase's `period:` that is not a `start` and an `end` |
| `cn/projection-source` | error | editor | a valued-state `from:` that is not a quality of a kind involving the bearer and the `per:` concept, or a `per:` that leaves another of its participants uncovered, or two participants the bearer can be |
| `cn/hierarchy-shape` | error | editor | a `hierarchy:` closure entity lacks its two named attributes or a key |
| `cn/participant-path-broken` | error | editor | a `participants:` relation path does not chain from the binding's entity to the participant's, or an empty path names a participant that does not land on that entity |
| `cn/involves-unlabelled-duplicate` | error | editor | the same concept twice in `involves:` without labels |
| `cn/unknown-relation-type` | error | editor | an open-tier edge's `type:` names no `def relation_type` |
| `cn/duplicate-default-sense` | error | editor | two defaults for one bare form and locale |
| `cn/antirigid-supertype` | warning | editor | a kind `is_a` a role or a phase |
| `cn/partition-incomplete` | warning | editor | subkinds partition an attribute whose value space lists a member none of them covers |
| `cn/subkind-as-kind` | warning | editor | the legacy `def kind X { is_a: …, members: … }` — read as a subkind |
| `cn/extent-retired` | warning | editor | `extent:` on an event — retired, every event is an instant |
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
| `cn/basis-mismatch` | error | `ttr cn hygiene` · consumer | a condition whose definition has no comparison of its `basis:` shape (below); a state claimed as of an instant before its episode starts |
| `cn/definition-unresolved` | hygiene | `ttr cn hygiene` | a definition reading an ungrounded concept |
| `cn/definition-unreachable(md)` · `(er)` | hygiene · refusal | `ttr cn hygiene` · consumer | a concept the definition reads has no grounding on that layer |
| `cn/imprecise-comparison` | advisory | `ttr cn hygiene` | instants of different granularity compared under `imprecision: certainly` |
| `cn/retired-code` | warning | report | a textual code no longer valid in the current vocabulary — not checked yet: versions register, but only a code-system importer knows which codes a version retires |
| `cn/not-analytically-reachable` | error | consumer | an aggregating statement slot that neither lane reads: md cannot reach it and E-R does not read the statement (when E-R does, it is `cn/lowered-on-er` instead), or E-R refuses the slot |
| `cn/not-recipe-reachable` | error | consumer | a recipe's concept with no landing on the er layer |
| `cn/not-assertable` | error | consumer | an asserted concept with no row to write: no landing on the er layer, a defined phase, or a subkind whose definition is not a settable discriminator |
| `cn/unit-mismatch` | error | consumer | an asserted value's unit differs from the attribute's |
| `cn/ambiguous-statement` · `cn/ambiguous-assertion` | error | consumer | a statement or assertion with unresolved candidates |
| `cn/missing-time` | error | consumer | a statement with no time and not `all_time`, or an assertion with no period where its concept declares one |
| `cn/unsupported-ask` | error | consumer | a statement asking for a set (`ASK_SET`, reserved) or asking nothing |
| `cn/unknown-participant-label` | error | consumer | an asserted participant label the concept's `involves:` does not declare |
| `cn/slot-mismatch` | error | consumer | a concept the slot does not take (a phase as a measure, …), or an asserted value its attribute's type does not take, or NULL where it is not `optional` |
| `cn/malformed-frame` | error | consumer | a frame field out of its shape: a time the vocabulary lacks, a filter with no form, an end before the start, one attribute asserted with two values, … |
| `cn/missing-as-of` | error | consumer | a state claim asserted without `asserted_as_of`: a phase episode, an event that bears a current- or past-state phase, or a valued-state quality |
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
| a phase, a subkind or a defined quality, by name | — | `definitionRender` → md predicate or er SQL; `materialize` → model text | — |

A frame that cannot be served is refused with its findings (`cn/missing-time`, `cn/ambiguous-statement`,
`cn/not-analytically-reachable`, `cn/not-assertable`, `cn/slot-mismatch`, …, see [Diagnostics](#diagnostics)) and
nothing else: no plan, no rows, no text.

### Statements

A statement names measures, filters, grouping, a time and optionally an `as_of`, all as concepts. Each slot goes
through its `cn2md` grounding; a phase or subkind filter's definition is inlined (and phases inside it, transitively); `:name`
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

`WebSale` is a subkind of `Sale`; the filter is its definition on the sale's row.

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
- **Phase and subkind filters** inline their definition on the event's row, while the rows stay the event's: a phase that joins a
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

An assertion claims one thing about a concept that has a time, and `assertionLower` turns it into the row a writer
stores; cn itself never writes. The frame names the `concept`, its participants, its period and its qualities:

| claim | `concept` | lowers to |
|---|---|---|
| an occurrence of an event | an event (`cn.Sale`, a transfer) | a row of the event's entity, `at` its instant |
| a row of a kind, with its validity | a kind with a `period:` (`cn.ListPrice`) | a row of the kind's entity, its validity as `start` / `end` |
| an episode of a phase | a phase with an episode table (`cn.OutOfService`) | a row of the episode table |

A defined phase has no row to write (`cn/not-assertable`). A subkind claim lands on its bearer's row and writes the
value its definition fixes (`channel = 'web'`), and each such value up its `of:` chain.

**A state claim needs an as-of.** An episode says a state holds, so the frame carries `assertedAsOf` — the instant
the source claims it for — and the instant must fall in the claimed period. The same holds for an event that bears a
current- or past-state phase and for a valued-state quality. Warehouse 1 is out for stocktaking (*inventura*) on 2
and 3 November 2026:

```json
{ "concept": { "qname": "cn.OutOfService" },
  "identity": { "new": true },
  "participants": [{ "label": "warehouse", "concept": { "qname": "cn.Warehouse" },
                     "member": { "label": "1", "resolvedValue": "1" } }],
  "qualities": [{ "quality": { "qname": "cn.outage_reason" }, "value": { "stringValue": "inventura" } }],
  "period": { "start": { "value": "2026-11-02", "granularity": "DAY" },
              "end": { "value": "2026-11-03", "granularity": "DAY" } },
  "provenance": { "producer": "manual:marketa" },
  "assertedAsOf": { "value": "2026-11-02", "granularity": "DAY" } }
```

With no `assertedAsOf` the frame is refused `cn/missing-as-of`. As of 2026-10-31 it is refused `cn/basis-mismatch`:
"the state is claimed as of `2026-10-31` but starts `2026-11-02`, after it — it does not hold then". As of 2026-11-02
it lowers to one row:

```json
{ "rows": [{ "entity": "warehouse_outage",
             "values": { "warehouse": "1", "from_date": "2026-11-02", "to_date": "2026-11-03", "reason": "inventura" } }],
  "assertedAsOf": "2026-11-02",
  "obligations": [] }
```

- A participant becomes the foreign-key value its labelled relation writes on the row; it must carry a resolved
  member. A participant two relations away (the patient of the encounter a transfer starts from) is an
  **obligation**: the writer checks that the row the path reaches holds that member.
- Instants are written as the frame writes them (`2026-03`, `2026-03-14T09:30`); an imprecise period's stored upper
  bound is the next granule (`2026-04`). An absent end is NULL, and whether it is open or unknown travels beside the
  row.
- A value that does not fit its attribute's type, or one attribute given two values, refuses the assertion.

Field 1 of `assertion.v1` was renamed from `event` to `concept` in translator 0.13.0, with its number and binary
encoding kept. A JSON producer must write `concept` (`"event"` is refused), and JVM and Python callers rebuild against
the new proto artifact.

### Explanations

The sentence a frame says, in English or Czech, built from the frame (never from its lowering), for the inspector to
show before anything runs:

```
Net revenue · customer Kaufland · January 2026.
Čistý obrat · zákazník Kaufland · leden 2026.
Net revenue · large sale while net revenue > large_sale (= 10000) · January 2026.
Transfer · patient: Patient P-1001 · from: Encounter E-501 · to: Encounter E-502 · at 2026-03-14 09:30 · kind of transfer: step-down · asserted as of 2026-03-14 · source: manual:steward
```

A concept reads as its label in the locale, else its lexicon term in the locale, else its English label, else its
name in words (`LargeSale` → `large sale`); a parameter as `name (= value)`. A phase filter says its definition after
*while* (*dokud*), a subkind filter after a colon; a
construct the explanation cannot put into words is quoted from the source, never guessed. A refused frame is still
explained and ends `— refused: <slug> (<subject>)`. Another locale reads English, with `cn/explain-locale-fallback`.

### Rendering a definition

`definitionRender` renders a phase, a defined quality or a subkind on the **md** layer (a predicate
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

An event-bounded phase renders as a start with no termination since: Hartland's `OpenOrder`, both of whose events
are columns of the order's own row.

```sql
-- cn.OpenOrder: from OrderPlaced until OrderDelivered
SELECT "order".order_number
FROM "order"
WHERE EXISTS (
  SELECT 1
  FROM (
    SELECT order_2.order_number AS subject_0, order_2.placed_date AS "at"
    FROM "order" order_2
  ) starts
  WHERE starts.subject_0 = "order".order_number AND starts."at" <= :as_of AND NOT EXISTS (
    SELECT 1
    FROM (
      SELECT order_3.order_number AS subject_0, order_3.delivered_date AS "at"
      FROM "order" order_3
    ) terminations
    WHERE terminations.subject_0 = starts.subject_0 AND terminations."at" >= starts."at" AND terminations."at" <= :as_of
  )
)
```

The layout is fixed (one clause per line, a subquery indented two spaces), so a rendering diffs cleanly. A concept the
definition mentions that has no grounding on the layer refuses it (`cn/definition-unreachable(er)` · `(md)`).

### Recipes over concepts

A recipe names its checks as phases, and reads each one as the island `CONTEXT` mode renders: the bearer joined to
the candidates, the defined qualities the definition reads lifted to joins, and the `columns:` asked for projected. Hartland's
order release (`release_order`) checks a pending order three ways before it releases it:

| check | phase | reads |
|---|---|---|
| the customer has an overdue invoice | `DebtorCustomer` (of `Customer`) | `OverdueInvoice` of `Invoice` |
| the center cannot cover a line | `ShortLine` (of `OrderLine`) | `outage` — an `OutOfService` episode containing the day — and `on_hand`, the latest stock reading |
| no price applies to a line, or the charged one is off by more than `:tolerance` | `MispricedLine` (of `OrderLine`) | `applicable_price`, through `agreed_price` and `channel_price` |

`MispricedLine` over the candidates `PendingOrder`, with `columns: [item, unit_price, applicable_price]`:

```sql
SELECT sales_line.order_number, sales_line.line_no, sales_line.item, sales_line.unit_price, COALESCE(customer_price.price, price_list.price) AS applicable_price
FROM "order"
JOIN sales_line ON sales_line.order_number = "order".order_number
LEFT JOIN customer_price ON customer_price.customer = "order".customer AND customer_price.item = sales_line.item AND customer_price.valid_from <= "order".placed_date AND (customer_price.valid_to IS NULL OR customer_price.valid_to >= "order".placed_date)
LEFT JOIN price_list ON price_list.item = sales_line.item AND price_list.channel = "order".channel AND price_list.valid_from <= "order".placed_date AND (price_list.valid_to IS NULL OR price_list.valid_to >= "order".placed_date)
WHERE "order".state IN ('placed', 'held') AND (COALESCE(customer_price.price, price_list.price) IS NULL OR ABS(sales_line.unit_price - COALESCE(customer_price.price, price_list.price)) > :tolerance)
```

Each projection is one `LEFT JOIN` to its relator's row valid on the day the order was placed — a valid row is at
most one, so the lookup is read flat — and the `COALESCE` puts the agreement before the list price, as the definition
does. `:tolerance` stays a placeholder for the recipe to bind.

The recipe's own hand-written islands are the bar. On the demo's data, as of 2026-10-31 with a tolerance of 0.01, the
three renderings return the same rows as the islands: 7 (`DebtorCustomer`), 13 (`ShortLine`) and 1,130
(`MispricedLine`), compared row for row.

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
`definition_text` or a `suggested_grounding`. `cn-candidate.v1` predates grammar 0.20: its `stereo` is `kind`, `role`,
`phase`, `quality` or `event`, and a kind proposal carries no `involves`, so a subkind or a relator is not proposed
yet — a steward writes it.

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
So do grammar 0.20's `subkind`, `until` and `per`: a quality named `per`, an attribute `until`.
