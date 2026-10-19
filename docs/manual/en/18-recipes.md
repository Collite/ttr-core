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

> **Status.** The recipe block parses in the toolchain today (grammar TTR-P 0.4). The recipe checker (wiring, parameters,
> the per-candidate / set-level mode verdict) and the compile to a recipe bundle follow; until then `ttrp build`
> refuses a recipe (`TTRP-REC-099`) and `ttrp explain` prints its skeleton. The actions a recipe wires are defined in
> TTR-M — see [Automation](19-automation.md).

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

## Words that stay names

Every recipe word — `key`, `mode`, `detail`, `context`, `set`, `call`, `days`, … — is a *soft* keyword: outside its
place in the recipe block it is an ordinary name, so existing columns and variables keep working.

## Diagnostics

| id | when |
|---|---|
| `TTRP-PRS-006` | a header or switch value outside the listed ones (`mode: sometimes`) |
| `TTRP-PRS-007` | a nested recipe, a `target` role without a body, `because:`/`reason:` mistakes, a header written twice, a switch without routes, a route named `all`/`none`, an `out` port on a decision |
| `TTRP-PRS-008` | a named argument on a function that takes positional ones, or a name given twice |
| `TTRP-REC-099` | `ttrp build` on a recipe — the recipe compile is not available yet |
