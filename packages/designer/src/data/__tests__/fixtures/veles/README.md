# Veles fixtures

`hartland-{er,db}-graph.json` — the Hartland model's two schemas as the catalog serves them
(`GET /model/graph?schema=`).

`layouts.json`, `layout-positions.json`, `layout-db_tables.json` — the layout routes of VL contracts §6
(`GET /model/layouts`, `GET /model/layout?name=`), built from the shared layout vectors `positions` and
`db-tables` (`packages/package-sdk/schemas/layout-vectors/`) resolved against these two graphs, as a catalog
would serve them: resolved cards as full qnames, the refs these graphs lack as `missing`, the foreign key
as `ignored`, positions of resolved cards only. Until ttr-server serves the routes (VL-P2) they are the
viewer's only source for them.
