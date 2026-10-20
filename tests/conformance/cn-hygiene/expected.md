# Concept hygiene

8 concepts — 5 er-grounded · 1 md-grounded · 1 md-grounded-hop-missing · 1 ungrounded

| Concept | Stereotype | Grade | Via | Grounding | Hop | Lands on | Open tier |
|---|---|---|---|---|---|---|---|
| cn.FrequentShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.LoyalShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.Sale | event | md-grounded | md-hop | md.cubelet.sales | md.cubelet.sales → er.entity.sale | er.entity.sale | — |
| cn.Shopper | kind | er-grounded | direct | er.entity.shopper | — | er.entity.shopper | loyalty_programme |
| cn.Store | kind | md-grounded-hop-missing | md-hop | md.dimension.Store | md.dimension.Store → md/er-unmapped | — | — |
| cn.StoreVisit | event | er-grounded | direct | er.entity.store_visit | — | er.entity.store_visit | — |
| cn.Supplier | kind | ungrounded | — | — | — | — | supplies → cn.Store |
| cn.VipShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |

Open-tier relation types: cn.supplies.

## Dangling groundings

- cn.StoreVisit — `visit_g`: er.entity.store_visit.visited_at

## Redundant term duplicates

- cn.Sale — "sale" (en): term `sale`, and term `sales_cubelet` on md.cubelet.sales
- cn.Shopper — "shopper" (en): term `shopper`, and term `shopper_entity` on er.entity.shopper

## Phase as default sense

- cn.FrequentShopper — term `frequent` defaults "shopper" to the phase, but "shopper" also names cn.Shopper up its `of:` chain (cn/phase-as-default)

## Retired codes

None — checked once vocabulary versions register (CN-P4 S4.2).

## Definitions

- cn.LoyalShopper — cn/definition-cycle (error) at hartland/hygiene/c-concepts.ttrm:11: the definitions inline each other: LoyalShopper → VipShopper → LoyalShopper (AM-26)
- cn.VipShopper — cn/definition-cycle (error) at hartland/hygiene/c-concepts.ttrm:12: the definitions inline each other: VipShopper → LoyalShopper → VipShopper (AM-26)
