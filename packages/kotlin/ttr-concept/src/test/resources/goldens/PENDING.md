# MD-hop pending audit (HP-P2)

The CN suite was rerun against the derived hop with Hartland's fixture changed to the ruled Sales shape:
three physical fact tables/entities/cubelets, one ER sales_union query, one stacked sales entity, zero md2er bindings.
Revenue and quantity land on the stacked entity under the Sale bearer.

- `temporal/dc4-return-share` is active: its ER SQL reads the derived sales.ext_sales_price and is checked by the independent SQL oracle and the production translator (PostgreSQL and MSSQL).
- `channel/by-channel-sales` named `CN-P6 A-H-7 (ER statement grouping; MD needs Channel grain)` — **active since CN-P6
  ⚑S62-1** (the er statement lane, 2026-10-25): the statement reads the stacked `sales` and groups by its `channel`.
- `channel/where-websale-carrier` named `CN-P6 A-H-7 (phase-to-carrier binding)` — **withdrawn**: the er lane reads
  `revenue where WebSale` as `channel = 'web'` on `sales` (`channel/where-websale-sales`), so no carrier primitive is
  needed. Inferring a carrier from a literal in query SQL stays the forbidden name/shape heuristic.

The existing positive Channel-grain reverse-hop goldens run on a separate, explicitly synthetic `channel-reverse` world. They still assert the same dot-path and plan bytes. They do not assert that the ruled Hartland world has a Channel grain.
Other pending demand IDs are unchanged. Hartland itself remains HP-H / CN-P6 acceptance.
