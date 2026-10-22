# H-1 contexts — the rendered islands vs AU's hand-written ones (A-H-2)

Source: `design/11-recipes-over-concepts.md` §3, against AU `17-recipes-retyped.md` §3; the S0.3 memo §2. World
`hartland`; `definitionRender(…, ER)` in CONTEXT mode (A-CN-20): the bearer joined to the candidates (`PendingOrder`),
sub-terms lifted to LEFT JOINs and projected, `columns` naming the projection.

| case | phase | bar |
|---|---|---|
| `overdue` | `DebtorCustomer` | the translated ER plan equals AU's island's, byte for byte |
| `shortfall` | `ShortLine` (`outage` and `on_hand` lifted; the outage end CLOSED — memo §2) | the same |
| `price-differences` | `MispricedLine` (`list_price` inlined flat) | row-set equality on live PG (`seed.sql`, tag `live`) — AU's CTE self-join is a shape the renderer does not reproduce (memo ⚑S03-5, A-CN-15) |

`au.sql` is AU's island with the `orders` port replaced by the candidates' filter (as S0.3 did; `shortfall`'s TTR-B
hand-translated faithfully). The expected SQL is S0.3's, with two re-spellings, both plan-neutral (the oracle proves
it): the derived table of `on_hand` is named after the quality it computes (`on_hand`, S0.3 §3 rule 2 — AU's `stock`
stays in `au.sql`), and `outage` is a defined quality of `OrderLine` (`ShortLine` reads `outage or on_hand <
line_quantity`, AU's disjunct order). A day-grain end is the period's LAST day: `contains` reads `to_date >= :as_of`,
AU's spelling — the rule every DC-12 golden follows (`../temporal/README.md`).

`price-differences`' `list_price` is a lookup — `max(CustomerPrice involving this.item, this.order.customer where period
contains this.order.period.start).cp_price`, the same for `PriceList` — expected rendered flat (one LEFT JOIN each, as
S0.3's `H1-price-cn`); S3.4 decides whether a lookup over a validity interval renders flat or as the grouped self-join,
the row-set bar holds either way.

Pending convention, case format and oracles: [`../README.md`](../README.md).
