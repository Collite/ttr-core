# MD-hop pending audit (HP-P2)

The CN suite was rerun against the derived hop with Hartland's fixture changed to the ruled Sales shape:
three physical fact tables/entities/cubelets, one ER sales_union query, one stacked sales entity, zero md2er bindings.
Revenue and quantity land on the stacked entity under the Sale bearer.

- `temporal/dc4-return-share` is active: its ER SQL reads the derived sales.ext_sales_price and is checked by the independent SQL oracle and the production translator (PostgreSQL and MSSQL).
- `channel/by-channel-sales` now names `CN-P6 A-H-7 (ER statement grouping; MD needs Channel grain)`. Design §4 expressly keeps MD's three cubelets without a Channel dimension; the hop cannot manufacture one.
- `channel/where-websale-carrier` now names `CN-P6 A-H-7 (phase-to-carrier binding)`. Inferring a carrier from a literal in query SQL would be the forbidden name/shape heuristic; a CN carrier primitive remains necessary.

The existing positive Channel-grain reverse-hop goldens run on a separate, explicitly synthetic `channel-reverse` world. They still assert the same dot-path and plan bytes. They do not assert that the ruled Hartland world has a Channel grain.
Other pending demand IDs are unchanged. Hartland itself remains HP-H / CN-P6 acceptance.
