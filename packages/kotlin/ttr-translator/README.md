# ttr-translator

The **TTR-P translation core**: island ↔ RelNode ↔ SQL / `plan.v1` payloads, backed by
Apache Calcite. This is the "Proteus translation core" of TTR-P decision **E-a α′** —
a Kotlin library the PL compiler (`ttrp-emit`) embeds offline/in-process, and that
kantheon's Proteus consumes as a thin gRPC wrapper.

## Provenance

Extracted **whole** (decision TR-1) from kantheon `shared/libs/kotlin/query-translator`
@ `f2e2efb` (2026-07-06). No behavioral change rode the move (TR-7): the diff vs.
kantheon is **package names + build wiring only** — proven by a test-name parity check
(34 specs / 359 tests, identical modulo package prefix).

**Package map (TR-2):** `shared.translator.*` → `org.tatrman.translator.*`. Source dirs
normalized to match packages (kantheon kept sources under `src/main/kotlin/shared/translator/`;
that quirk is dropped). Subpackages preserved: `codec/{sql,transdsl,dfdsl}`, `detect`,
`dialects`, `framework`, `joiner`, `orchestrator`, `params`, `schema`, `suggest`, `wire`.

Two wire-adjacent symbols the lib compiles against — the `org.tatrman.proteus.v1`
`Language`/`SqlDialect` enums and `org.tatrman.plan.v1.{parseSchemaCode,schemaCodeToToken}` —
travelled into the sibling **`ttr-plan-proto`** artifact (blocker A2-1, FQCNs unchanged).

## API surface

Package root `org.tatrman.translator` — see
`docs/ttr-translator/architecture/contracts.md` §3 for the full entry-point map
(`orchestrator.Translator`, `framework.{TranslatorFramework, ModelHandle SPI}`,
`codec.*`, `wire.{PlanNodeEncoder,PlanNodeDecoder}`, `dialects`, `schema`, `params`,
`detect`/`suggest`). `InMemoryModelHandle` (the `ModelHandle` SPI test double) ships
via `java-test-fixtures` so consumers can test against the SPI without a real model.

- **Calcite is an `api` dependency** — RelNode types appear in signatures. Consumers that
  must stay Calcite-free (e.g. `ttrp-emit` outside its `TranslatorFacade`) enforce that on
  their side (the facade is the only class importing `org.tatrman.translator.*`;
  see TTR-P `tasks-p3-s3.1` T3.1.1/T3.1.7 for the Calcite-engagement canon).
- The test JVM pins the Calcite default charset to UTF-8 (see `build.gradle.kts`) — carried
  from the source lib to keep Unicode-literal coverage deterministic.

## Model joins

MJ (`translator/v0.12.0`) — in the ER catalog, a join between entities written without a condition is
conditioned from the model's declared relations, so the LLM lane can write `FROM a JOIN b JOIN c` and
let the engine fill in the `ON`s. The join is decided by `joiner.JoinPolicy` — the new entity against
**every** entity already on the other side; exactly one relation must resolve; all of its pairs are
`AND`-ed — and carried by `codec.sql.ModelJoinRewriter` (SQL, pre-validation) and `joiner.JoinerLogical`
/ `JoinerPhysical` (wire). What cannot be resolved stays a Cartesian product with a warning on
`ParseResult.Success.warnings` (`joiner.JoinerMessages` renders code / severity / text).

| Form | Result |
|---|---|
| `a JOIN b` · `a INNER JOIN b` · `a LEFT JOIN b` · `a RIGHT JOIN b` · `a FULL JOIN b` (no `ON`/`USING`) | conditioned from the model in SQL; join type preserved |
| `a, b` · `a CROSS JOIN b` | conditioned from the model on the wire (`JoinerLogical`), as `INNER` — the key-name collision guard applies |
| `a JOIN b ON …` · `USING (…)` · `NATURAL JOIN` | untouched |
| `a JOIN b JOIN c …` (chain, any length, parentheses allowed) | each join decided against the full entity set of its other side |
| `a JOIN (SELECT …) s` · `a JOIN db_table` | the non-entity side contributes no entity → `ON TRUE` + `JOIN_NO_RELATION` |
| aliases `FROM zákazník z JOIN dodací_místo dm` | the conditions use the aliases |

Warnings: `JOIN_NO_RELATION`, `JOIN_AMBIGUOUS_RELATIONS` (two relations, or an entity repeated across
the join — write the `ON`), `JOIN_RELATION_WITHOUT_PAIRS` (INFO; left to the FK after
MAP_TO_PHYSICAL), `JOIN_KEY_NAME_COLLISION` (wire carrier only). An entity that already has its `ON`
(the OZ + VOT double) does not block a later bare join. `explain` exposes the rewritten
statement as stage `model_joins`. Design, contracts and the acceptance corpus:
`project/tatrman/features/ttr-translator/model-joins/`.

Published as `org.tatrman:ttr-translator`, lockstep with `ttr-plan-proto` under the
`kotlin-translator/v*` tag. See `docs/ttr-translator/` for the full arc.

## Row-write plans (`translator/v0.14.0`)

`PlanNode.row_writes` (oneof field 18) is an ordered batch of INSERT/UPDATE/DELETE row writes,
separate from MD `StoreNode`. The wire vocabulary is `RowWrites`, `RowWrite`, `RowWriteOp` and
`ColumnValue` (`name` + `value: Expression`); existing `QualifiedName` and `Expression` are reused.
The authoritative wire and answer contract is [AU Server contracts.md, “The door’s write wire”
(D-163)](https://github.com/Collite/project/blob/master/kantheon/features/automation-agents/implementation/ttr-server/contracts.md).

`MapToPhysical.apply` maps an ER target through `ModelHandle.entityMapping` (`ToTable` only).
Values, `read_before`, and predicate columns use `attributeColumnRenames[attr] ?: attr`, then
must exist in `columns(table)`; missing entities/columns produce named diagnostics. Query-backed
or filtered bindings refuse. The REL_NODE front half preserves writes without a Calcite read
round trip. Read walkers see no scans or output columns, including fingerprint callers.

`RowWriteUnparser.unparse(row, POSTGRESQL, bindings, keyColumns)` returns one SQL statement and
its parameters in placeholder order. Literals and named parameters are bound, identifiers quoted;
the executor runs statements in order on one transaction and attaches `seq` to the answers.
`Translator.unparseFromRelNode` supports the same path (a multi-row batch produces statements
separated by semicolons and flattened positional bindings; consumers executing per row can send
one row or call `RowWriteUnparser` directly). Postgres resolves tables through the connection’s
`search_path`, as on the read path.

For `read_before`, a materialized key-only SELECT locks the row before the modifying CTE evaluates
its guard. A CAS miss still returns the locked prior; an absent key returns null. `prior` contains
only `read_before` columns, using their physical names after lowering. Supply the physical primary
key columns when available; otherwise the unparser uses top-level equality predicates outside
`read_before`, and refuses a missing key predicate. Each statement returns exactly one row
`(rows_affected bigint, prior text)`. Other dialects refuse with `row_writes_unsupported_dialect`.

The executed PG specs opt in with `TTRP_CONFORM_PG=1` and `TTR_CONN_ERP_PG` (the same URI convention
as `ttrp-conform`); a configured, unreachable PG fails the suite. `RowWriteUnparserSpec` creates
and drops its own schema. JVM consumers rebuild against the new proto/translator artifacts.
