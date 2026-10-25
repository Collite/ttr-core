# The golden worlds (CN-P3 S3.7)

Three model roots the goldens (`../goldens/`) run over, each loaded as one source and reconciled (`GoldenWorlds`, the
way Veles loads a project). Invented data; Hartland's public demo names, `X21`-style clinical codes — never client
content. `definition-diagnostics.txt` pins each world's definition-level findings (`GoldenWorldsSpec`): the model facts
(what cannot render on md / er), the granularity advisories, and the one deliberate cycle.

| world | what | layers |
|---|---|---|
| `hartland` | design `11` §1's package in the 0.5 spellings: the AU extension's recipe vocabulary (H-1, DC-2/3/5/9/10/11, materialize) on er; `revenue` / `quantity` on a channel cubelet (⚑CN-P8) | er + db, md + `md2er_cubelet`, cn, automation |
| `clinic` | invented clinical rows (DC-1/4/8/12, the Transfer assertion): transfers with two labelled relations to one encounter entity, condition records over a closure, fracture episodes, observations whose value grounding declares a `unit:` (⚑CN-P10) | er + db, cn |
| `sales` | the md hero (ttrp-cli's md-project `sales-model`) with concepts over it (E-0, ActiveCustomer ⚑CN-P9, LargeSale); nothing lands on er, so it also holds the er refusals | md + db, cn, automation |
| `er-parity` | review-154 E0: one md cubelet carries every plain read (`sales`), and `Sale` lands on E-R (`sale`) too, so md's principled refusals are reached and the er lane runs behind each — `ErLaneParitySpec`'s frames; a non-additive / semi-additive / `max` measure, join-free and joining phases, a 1:N shipment, a narrower `VipCustomer`, DATE and TIMESTAMP qualities, a nullable channel (`live-seed.sql`) | er + db, md, cn |

In `hartland` `Customer` is **er**-grounded: H-1 renders `DebtorCustomer` on er, and the hop for an md dimension is
md/hop's (P2's `ExplicitMdErHop` answers an explicit `md2er_cubelet` only). The md-grounded `Customer` of E-0 is
`sales`'. Quality concepts are named per event (`pl_price`, `cp_price`, `line_date`, …) — one `def quality` each.
