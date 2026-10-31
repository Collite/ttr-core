# Concept hygiene

20 concepts — 17 er-grounded · 1 md-grounded · 1 md-grounded-hop-missing · 1 ungrounded

| Concept | Stereotype | Grade | Via | Grounding | Hop | Lands on | Open tier |
|---|---|---|---|---|---|---|---|
| cn.FrequentShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.GoldShopper | subkind | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.Joined | event | er-grounded | direct | er.entity.shopper | — | er.entity.shopper | — |
| cn.Left | event | er-grounded | direct | er.entity.shopper | — | er.entity.shopper | — |
| cn.LoyalShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.Member | phase | er-grounded | direct | er.entity.shopper | — | er.entity.shopper (← cn.Shopper) | — |
| cn.Membership | kind | er-grounded | direct | er.entity.membership | — | er.entity.membership | — |
| cn.Sale | event | md-grounded | md-hop | md.cubelet.sales | md.cubelet.sales → er.entity.sale | er.entity.sale | — |
| cn.Shopper | kind | er-grounded | direct | er.entity.shopper | — | er.entity.shopper | loyalty_programme |
| cn.Store | kind | md-grounded-hop-missing | md-hop | md.dimension.Store | md.dimension.Store → md/er-unmapped | — | — |
| cn.StoreVisit | event | er-grounded | direct | er.entity.store_visit | — | er.entity.store_visit | — |
| cn.Supplier | kind | ungrounded | — | — | — | — | supplies → cn.Store |
| cn.VipShopper | phase | er-grounded | direct | — | — | er.entity.shopper (← cn.Shopper) | — |
| cn.fee | quality | er-grounded | direct | er.entity.membership.fee | — | er.entity.membership.fee | — |
| cn.joined_on | quality | er-grounded | direct | er.entity.shopper.joined_on | — | er.entity.shopper.joined_on | — |
| cn.left_on | quality | er-grounded | direct | er.entity.shopper.left_on | — | er.entity.shopper.left_on | — |
| cn.membership_fee | quality | er-grounded | direct | er.entity.membership.fee | — | er.entity.membership.fee (← cn.Membership) | — |
| cn.tier | quality | er-grounded | direct | er.entity.shopper.tier | — | er.entity.shopper.tier | — |
| cn.valid_from | quality | er-grounded | direct | er.entity.membership.valid_from | — | er.entity.membership.valid_from | — |
| cn.valid_to | quality | er-grounded | direct | er.entity.membership.valid_to | — | er.entity.membership.valid_to | — |

Open-tier relation types: cn.supplies.

## Dangling groundings

- cn.StoreVisit — `visit_g`: er.entity.store_visit.visited_at

## Redundant term duplicates

- cn.Sale — "sale" (en): term `sale`, and term `sales_cubelet` on md.cubelet.sales
- cn.Shopper — "shopper" (en): term `shopper`, and term `shopper_entity` on er.entity.shopper

## Phase as default sense

- cn.FrequentShopper — term `frequent` defaults "shopper" to the phase, but "shopper" also names cn.Shopper up its `of:` chain (cn/phase-as-default)

## Retired codes

None — checked once vocabulary versions register (CN-P5 S5.3).

## Definitions

Not checked in the editor — `ttr cn hygiene <dir>` reports the definition-level findings (syntax, names, cycles,
reachability, basis, instant comparisons; CN-D29).
