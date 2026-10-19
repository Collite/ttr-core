# Recipes

A **recipe** is a TTR-P document that decides something about a set of business objects and says what to do about each
decision: *release this order*, *hold it for Sales*, *ask the sales manager first*. It is the unit the automation
service runs — on a schedule, on an event, or for one object on request — and every run leaves a verdict per object
that people and agents can read back.

A recipe is an ordinary `.ttrp` program with one extra block, `recipe { … }`. Inside it the islands play named
**roles**:

| role | what it is |
|---|---|
| `candidates` | the objects to decide about — one row per object, keyed by the recipe's `key` |
| `context` | facts about each candidate (or lookups shared by all of them) |
| `decision` | joins candidates and context, and routes each candidate with a `switch` |
| `actions` | the wiring: which route triggers which action, with which arguments |

> **Status.** The recipe block parses (grammar TTR-P 0.4) and is checked against the world and the model — `ttrp check`
> runs the checker, `ttrp explain` prints the skeleton and what the checker learned (see [What the checker
> checks](#what-the-checker-checks)). The compile to a recipe bundle follows; until then `ttrp build` refuses a recipe
> (`TTRP-REC-099`). The actions a recipe wires are defined in TTR-M — see [Automation](19-automation.md).

## A whole recipe

The order-release recipe of the Hartland demo — candidates, three contexts, one decision, the wiring:

```ttrp
uses world "hartland.worlds.demo"
import hartland.*
import hartland.automation.*

recipe {
    key:        order.order_number
    as of:      now
    parameters: [tolerance, confirmation_threshold]
    document:   "releasing-a-placed-order.md"

    // the state filter, never the key filter: the program's port picks single orders
    candidates placed_orders(out orders) target erp """sql
        SELECT o.order_number, o.customer, o.channel, o.fulfilling_warehouse, o.value, o.placed_date, o.state
        FROM   er.entity.order o
        WHERE  o.state IN ('placed', 'held')
    """

    context overdue(in orders, out rows) target erp """sql
        SELECT o.order_number, i.invoice_id, i.due, i.amount - i.paid AS open_amount
        FROM   orders o JOIN er.entity.invoice i ON i.customer = o.customer
        WHERE  i.status = 'open' AND i.due < :as_of
    """

    // … the shortfall and price_differences contexts …

    decision verdict(in orders, in shortfall, in price_differences, in overdue) {
        n_due = overdue -> aggregate { group_by: order_number  n_due = count(invoice_id) }
        v = orders -> join(right: n_due, kind: left, on: left.order_number = right.order_number)
                   -> calc(n_due = coalesce(n_due, 0))
        v -> switch {
            mode: overlapping
            hold_ar:  n_due > 0                                     because: "overdue invoices"   detail: overdue
            release:  n_due = 0 and value <= :confirmation_threshold because: "all checks passed"
        }
    }

    actions {
        verdict.release -> release { order: order_number }
        verdict.hold_ar -> hold    { order: order_number, department: "AR" }
        verdict.hold_ar -> notify  { who: role.ar, detail: overdue }
    }
}
```

## The header

| header | meaning |
|---|---|
| `key: order.order_number` | the attribute that identifies a candidate; `[a.x, a.y]` for a composite key |
| `as of: now` | the instant the run reads the world at — `now`, or a literal instant (`"2026-10-01T06:00:00+02:00"`) for replays; it is bound to `:as_of` |
| `parameters: [tolerance, …]` | the `def parameter`s the recipe uses (values live in TTR-M, so a change is reviewed) |
| `document: "…"` | the literate document the recipe is written in |
| `protocol: always \| on_route` | write the run protocol for every candidate, or only for the routed ones |

A document holds **one** recipe block.

## Role containers and island forms

A role container is a container header with a role word in front and an **island form** in place of plain `target`:

| form | runs |
|---|---|
| `target erp` + a body | SQL, canonical TTR-P or a fragment, like any island |
| `builtin geo.distance { … }` | a capability of the automation service |
| `foreign python per candidate schema s` + a body | foreign code, once per candidate or once for the set, its rows checked against `def schema s` |
| `call pricing.quote [per candidate \| set]` | a `def service` |
| `turn agents.care ask "…" answer s` | an agent's answer — never alone behind an action that needs no approval |

A context may declare a **window** — `window { by: item over: day }` (also `over: rows: 5`, `over: gap: 30 minutes`) —
for facts that are computed over a span of candidates rather than one.

## The decision

A `decision` ends in a `switch` statement. Each route is a name, **one** condition and optional attributes:

- `mode: exclusive` — first matching route wins; `mode: overlapping` — every matching route fires.
- `because: "…"` — the explanation stored with the verdict; `{column}` is filled from the row.
- `detail: shortfall, price_differences` — context rows attached to the verdict.
- `else: <route>` — the route of an unmatched candidate; `else: none` — no verdict row for it.

## The wiring

`<decision>.<route>, … -> <action> { arg: value, … } when: <condition>`. A value is an expression (`order_number`,
`role.sales`, `"AR"`), a duration (`2 working_days`, `days(:inbound_reminder)`), a bare list (`detail: shortfall,
price_differences`) or another action (`on_due: hold { order: order_number }`). `verdict.all` wires every routed row.

## Expressions in recipes

- `:name` binds a parameter (`:tolerance`) or a value of the run (`:as_of`), in canonical bodies and in SQL alike —
  one word, no space after the colon.
- A duration is written `2 working_days`, `30 days`, `4 weeks`, `15 minutes` as a **wiring value** (`due: 2
  working_days`) or a window's `gap:`. **Inside an expression** it is a call: `:as_of - days(30)`, or `days(:p)` when
  the amount is a parameter. (Two juxtaposed words would be ambiguous in a config block: `lead = 30` followed by
  `days = amount`.)
- The recipe's time functions take named arguments: `age(since: obligation.created_at)`,
  `working_days(between: due, and: :as_of)`. Every other function is called positionally (`TTRP-PRS-008`).
- A SQL island reads the same forms: `:as_of - days(365)`, `:as_of - :return_window`.

## Time

`:as_of` is the run's instant (a `timestamp`); it is never a parameter. A `:name` is a parameter the recipe declares
(typed by its `def parameter`) or a value the run binds — a column of the program's event, or the key under `port:
key`. Durations and dates combine as you would expect: `date ± days(…) | weeks(…) | months(…)` is a date,
`timestamp ± a duration` a timestamp, `date − date` a number of days; a duration compares with a number of its unit
(`days_open > :inbound_close`). Two rules keep the arithmetic honest:

- **Working days need the calendar.** `working_days(between: due, and: :as_of)` counts them, and a wiring's `due: 2
  working_days` sets a due date; a working-days duration in arithmetic is `TTRP-TYP-001`.
- **An integer next to a date is read as days**, with a warning — write `:as_of - days(365)`, not `:as_of - 365`.

Two ops read time from the model (they are lowered to plain ops by the compile):

| op | keeps | meaning |
|---|---|---|
| `latest(by: as_of_date, per: item, warehouse)` | its input's columns | the last snapshot per `per:` key — `by:` is a date or the snapshot's surrogate date (an sk) |
| `within(sold_date, last: weeks(8))` · `within(placed_date, from: :as_of - months(24), to: :as_of - months(12))` | its input's columns | the rows whose date falls in the period, relative to `:as_of`; an sk works when the model leads it to the calendar (an entity with `semantics { role: event_date }`) |

Summing a snapshot measure (`semantics { measures: [{ attribute: quantity_on_hand, aggregation: last }] }`) across
its snapshot dates without `latest` first is the warning `TTRP-REC-050`.

## What the checker checks

**Key lineage and the mode verdict.** Every context is classified, never declared:

| class | when | |
|---|---|---|
| lookup | it reads no candidates port | a read of the model at `as of` |
| per-candidate | every row it produces carries the key and comes from exactly one candidate | |
| set-level | it reads the candidates but an op loses the key | needs a `window`, or runs in batch only |

The key survives a `filter`, `calc`, `sort`, `within`; a `latest` whose `per:` includes the key; a
`select`/projection that keeps the key column; an `aggregate` that groups by it; a `union` whose inputs carry it at the
same position. A `limit` loses it. The first N rows of all the candidates keep one only while fewer than N others come
first, so "the 10 largest orders" picks a different set in a batch of 500 than for one order on its own event. Such a
context is set-level: declare a `window`, or restate the rank as a threshold. A `limit` over a lookup (the two highest
income bands) reads no candidate and is fine. A join keeps it when the other side is a **lookup** (rows of the model, none built from the
candidates): an `inner` join, a `left` or `semi` join with the candidates on the left, or a join whose other side
equates to the key — `on: left.order_number = right.order_number`, or `on: relation rel_order_line` from the key's
entity to the many side. Joining candidate rows with candidate rows on the key is the same candidate. Joining them
with rows built from the candidates — an aggregate of them, a set-level context, or the candidates on anything but
the key — is one candidate meeting the others, and the context is set-level. An `IN` or `EXISTS` subquery inside a
larger SQL `WHERE` cannot be followed; restate it as a join. The checker is conservative: what it cannot prove keyed
is set-level, and its message names the op that lost the key.

A recipe whose contexts are all lookups or per-candidate is **per-candidate** — any program may run it, for one key or
for all. A set-level context with a `window` makes it **set-level, windowed**; without one, **set-level, batch-only**.
`ttrp explain` prints the verdict, each context's class and the routes.

**The wiring.** Each wiring names an action of the imports, gives its required columns and no other, and gives each
a value of the column's kind: `role.<name>` for a role, a context's name for rows, an action (or an action call) for an
action, the column's attribute type otherwise — a text column takes any value; a bare name the rows do not carry is a
symbol (`to: rejected`). Every route is wired. An action's `transition` must reach a state the attribute's `states:`
lists, along its `transitions:`. A bare name the route's rows do not carry is read as a text (`to: rejected`); it is
checked against the attribute's `states:` or value labels where the action writes one — elsewhere, quote texts. A
column typed by an attribute (`order: order.order_number`) takes that attribute, or one the model's relations equate to
it. A route decided on an agent's answer (a `turn` context, followed through every island) — or a wiring whose `when:`
reads one — may only fire an action with `approval: required`, and no unapproved `on_due:`.

## Words that stay names

Every recipe word — `key`, `mode`, `detail`, `context`, `set`, `call`, `days`, … — is a *soft* keyword: outside its
place in the recipe block it is an ordinary name, so existing columns and variables keep working.

## Diagnostics

| id | when |
|---|---|
| `TTRP-PRS-006` | a header or switch value outside the listed ones (`mode: sometimes`) |
| `TTRP-PRS-007` | a nested recipe, a `target` role without a body, `because:`/`reason:` mistakes, a header written twice, a switch without routes, a route named `all`/`none`, an `out` port on a decision |
| `TTRP-PRS-008` | a named argument on a function that takes positional ones, or a name given twice |
| `TTRP-REC-000` | the recipe's shape: two recipes in a file, a `program` header beside one, no or two `candidates`, two decisions, no `actions`, an `in` port nothing feeds |
| `TTRP-REC-001` | a wiring names an action the imports lack, a column the action does not have, or leaves out a required one |
| `TTRP-REC-002` | a wiring's value does not fit its column |
| `TTRP-REC-003` | a route nothing wires, or a wiring naming no route |
| `TTRP-REC-004` | the candidates do not project the key |
| `TTRP-REC-005` | the candidates filter on the key — the program's port picks single keys |
| `TTRP-REC-010` | a set-level context without a `window` (a warning; an error under an on-event program), a `window` grouping by a column its rows lack, a decision whose switch reads rows not keyed |
| `TTRP-REC-011` | a batch program's schedule does not cover whole windows |
| `TTRP-REC-012` | a program with `port: key` runs a set-level recipe |
| `TTRP-REC-020` | a `:name` that is neither a parameter nor a value the run binds |
| `TTRP-REC-021` | `parameters:` names a `def parameter` the imports lack, or one no island uses (a warning) |
| `TTRP-REC-030` | an action moves an attribute to a state its `states:` lacks, or along a transition its `transitions:` does not allow |
| `TTRP-REC-040` | a route decided on an agent's answer fires an action that needs no approval |
| `TTRP-REC-050` | a snapshot measure summed across its snapshot dates without `latest` (a warning) |
| `TTRP-REC-060` | an island writes (`INSERT`, `UPDATE`, `DELETE`, `MERGE`, `store`) — actions write |
| `TTRP-REC-070` | a decision declares an island of its own |
| `TTRP-REC-099` | `ttrp build` on a recipe — the recipe compile is not available yet |
