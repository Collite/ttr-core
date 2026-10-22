# Concept hygiene

3 concepts — 1 er-grounded · 2 md-grounded

| Concept | Stereotype | Grade | Via | Grounding | Hop | Lands on | Open tier |
|---|---|---|---|---|---|---|---|
| cn.Customer | kind | md-grounded | md-hop | md.dimension.Customer | md.dimension.Customer → er.entity.customer | er.entity.customer | — |
| cn.Sale | kind | er-grounded | direct | er.entity.sales | — | er.entity.sales | — |
| cn.revenue | quality | md-grounded | md-hop | md.measure.revenue | md.measure.revenue → er.entity.sales.ext_sales_price | er.entity.sales.ext_sales_price | — |

Open-tier relation types: none.

## Dangling groundings

None.

## Redundant term duplicates

None.

## Phase as default sense

None.

## Retired codes

None — checked once vocabulary versions register (CN-P4 S4.2).

## Definitions

Not checked in the editor — `ttr cn hygiene <dir>` reports the definition-level findings (syntax, names, cycles,
reachability, basis, instant comparisons; CN-D29).
