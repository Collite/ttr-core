# Materialize — a phase as model text (CN-D1, A-CN-16)

World `hartland`. `materialize(phase)` writes the er rendering as a logical query and the entity stacked on it.

| case | |
|---|---|
| `placed-order` | `PlacedOrder` (`state = 'placed'`, parameter-free) → `placed_order.ttrm`: `def query placed_order_q` + `def entity placed_order` bound to it (the query is not named after the entity — LQ's note) |
| `mispriced-refused` | `MispricedLine` reads `:tolerance` → `cn/materialize-parametric` (LQ refuses parameters) |

Oracle: the query body translates on the strict ER lane.

Pending convention, case format and oracles: [`../README.md`](../README.md).
