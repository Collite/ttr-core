# Channel — reverse hop and the H-1 Sales ruling

The positive `where-websale-dimension` and `by-channel-dimension` cases run on the synthetic
`channel-reverse` world, which explicitly provides a Channel grain and its reverse hop. Their original
MD dot-path and plan expectations are retained.

The Hartland world follows design §4 (H-1): three per-channel fact cubelets, no Channel grain,
and a stacked `sales` entity over an in-model UNION ALL query. Its revenue quality resolves through
R7 under the Sale bearer. A query's literal channel does not create an MD dimension or a carrier binding.

`by-channel-sales` and `where-websale-sales` are read on E-R (CN-P6 ⚑S62-1, the er statement lane, Bora 2026-10-25):
md carries no single read of either — three cubelets carry revenue and none has the channel — so the statement reads
the stacked `sales`, grouping by or filtering on its `channel` column. The phase-to-carrier binding the
`where-websale-carrier` demand named is withdrawn: the er lane answers the question without one. See `../PENDING.md`.
