# hartland-way — REL_NODE re-entry fixtures (ttr-core#407)

Byte-identical copies of three `decide_return` islands from Collite/ttr-demo at
`133349ff1af08dd1c04c7cf23137bb78703b9f49`, `automation/decide_return/decide_return.bundle/islands/`:

- `way.reject_late.sql` — the port whose REL_NODE re-entry failed (`rel_node_schema_resolution_failed`): it is the only
  port that casts a parameter (`CAST(:return_window AS VARCHAR)`, `return_window` declared `int`) inside `||`.
- `way.to_customer_care.sql` — a port of the same way that re-entered fine (same body, no parameter cast).
- `request_value.rows.sql` — the per-line sub-plan, the control.

`world/` is the slice of the same commit's `model/` the way reads: the six entities (`er.ttrm`), their tables
(`db.ttrm`) and their `er2db` mappings (`er2db.ttrm`), extracted verbatim by `extract-world.py`
(`python3 -I extract-world.py <ttr-demo checkout> <commit> world`). One substitution: ttr-demo declares
`return_request` as a db **view**; `ErModelHandle` exposes db tables only, so the fixture declares the view's columns
as a table and maps the entity to it.

Read by `BoundedWayReentrySpec` with the parameter types the bundle's Door binds.
