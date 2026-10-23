# Channel — reverse hop and the H-1 Sales ruling

The positive `where-websale-dimension` and `by-channel-dimension` cases run on the synthetic
`channel-reverse` world, which explicitly provides a Channel grain and its reverse hop. Their original
MD dot-path and plan expectations are retained.

The Hartland world follows design §4 (H-1): three per-channel fact cubelets, no Channel grain,
and a stacked `sales` entity over an in-model UNION ALL query. Its revenue quality resolves through
R7 under the Sale bearer. A query's literal channel does not create an MD dimension or a carrier binding.

`by-channel-sales.pending` therefore waits on CN-P6's ER grouping / MD Channel-grain work;
`where-websale-carrier.pending` waits on the phase-to-carrier binding. See `../PENDING.md`.
