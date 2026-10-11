# model-ttr-seed (Fixture A)

A self-contained package in the shape `just yaml-to-ttr` emits (directive-less `db.ttr` / `er.ttr` /
`cnc.ttr`, one package per source file): `returns`, a marketplace-returns slice of the Hartland demo
database (TPC-DS, US world) — tables with search blocks, two row-filter (`__filter`) queries backing
query-bound entities, parameterised queries with search patterns, entities with inline bindings and
value labels, relations bound to foreign keys, and er2cnc roles. Every `er.entity.*` it references is
defined within it — the stable guard for the loader/reconciler path. Consumed by `ModelTtrLoadSpec`.
