# DFDSL codec

Two-way mapping between **DFDSL** (the dataframe-shaped DSL, protobuf `Pipeline` message) and
the canonical `org.tatrman.plan.v1.PlanNode` proto. Pure proto-to-proto; no Calcite, no I/O.

```
DFDSL JSON ─┐                       ┌─► DFDSL Pipeline proto
            ├─►  DfDslCodec  ◄─────►│
DFDSL Pipe ─┘                       └─► PlanNode (canonical)
```

## Public API

`object DfDslCodec` in `DfDslCodec.kt`:

| Method | Direction | Returns |
|---|---|---|
| `parse(pipeline)` | `Pipeline` → `PlanNode` | canonical plan |
| `parseJson(json)` | DFDSL JSON → `PlanNode` | canonical plan |
| `unparse(plan)` | `PlanNode` → `Pipeline` | DFDSL `Pipeline` |
| `unparseJson(plan)` | `PlanNode` → DFDSL JSON | string |

## DFDSL operator catalog

A DFDSL `Pipeline` is an ordered sequence of operations (`FromOp`, `FilterOp`, `SelectOp`,
`MapOp`, `AggregateOp`, `SortOp`, `LimitOp`, `JoinOp`, …). The codec parses each operation into
the corresponding `PlanNode` shape and stitches them into the plan tree (input ← previous op).

Source operations:

- `from(table | view | query_ref)` — table scan / view scan / referenced query.
- `from_workspace(workspace_id)` — **NOT YET IMPLEMENTED** (Phase 08 B2). The codec
  currently rejects `from_workspace` with `operation_not_supported`. Cross-engine handoff (e.g.
  read a Polars workspace from a SQL plan) is the v1 use case; deeper integration is planned
  for Phase 09.

Transformations: `filter`, `select`, `map`, `aggregate`, `sort`, `limit`, `offset`.

Joins: **currently rejected with `operation_not_supported`** (Phase 08 B1).
`HubNSpokeSpec` confirms the rejection; will assert success once B1 lands. Decision #10 in the
master plan (Joiner-service vs. push-into-translator) scopes the implementation.

## Supported / unsupported

Supported:

- Linear pipelines: from → filter → select → map → aggregate → sort → limit.
- Sub-pipelines via nested `from(query_ref)`.

Unsupported (Phase 08 follow-ups):

- Joins (B1).
- `from_workspace` (B2).
- Some operator-enum extensions, incl. `IN` (B4).

## Extending the codec

1. Add the new operation to the DFDSL proto (`ttr-plan-proto`: `src/main/proto/org/tatrman/dfdsl/v1`).
2. Add a `case` in `parseInto` (and `unparseInto` if the canonical plan can produce it).
3. Add a round-trip case to `RoundTripSpec` (`src/test/kotlin/org/tatrman/translator/wire/`).
4. For operators that map onto the canonical `Expression` enum, update the operator
   catalog in `query-translator/.../expr` first so the round-trip stays valid.
