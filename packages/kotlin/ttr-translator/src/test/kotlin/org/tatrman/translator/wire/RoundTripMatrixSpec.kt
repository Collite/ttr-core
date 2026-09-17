// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.tatrman.dfdsl.v1.FilterOp
import org.tatrman.dfdsl.v1.FromOp
import org.tatrman.dfdsl.v1.GroupByOp
import org.tatrman.dfdsl.v1.JoinOp
import org.tatrman.dfdsl.v1.LimitOp
import org.tatrman.dfdsl.v1.Operation
import org.tatrman.dfdsl.v1.OrderByOp
import org.tatrman.dfdsl.v1.Pipeline
import org.tatrman.dfdsl.v1.SelectColumn
import org.tatrman.dfdsl.v1.SelectOp
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.SortKey
import org.tatrman.transdsl.v1.AggregateColumn
import org.tatrman.transdsl.v1.Aggregation
import org.tatrman.transdsl.v1.Column
import org.tatrman.transdsl.v1.Query
import org.tatrman.transdsl.v1.Source
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.RelNode
import org.tatrman.translator.codec.dfdsl.DfDslCodec
import org.tatrman.translator.codec.sql.RelToSqlUnparser
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.codec.transdsl.TransDslCodec
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.plan.v1.parseSchemaCode

/**
 * Phase 08 A5 / DF-T07 — cross-language `RoundTripSpec` matrix.
 *
 * Three input languages × four output dialects × seven query shapes. Cells that aren't
 * expressible in a given input language are explicitly disabled with a reason — never silently
 * skipped. The matrix lives alongside the existing `RoundTripSpec` (which pins MSSQL-only
 * defaults); this spec widens coverage to every dialect once A1 (RESOLVE) + A3 (DuckDB) are in.
 *
 * Each cell drives the same pipeline:
 *
 *     <input lang> → PlanNode (via codec / SQL planner) → PlanNode (decoded into a fresh
 *     framework) → RelNode → `RelToSqlUnparser.unparse(rel, dialect)`
 *
 * Assertions focus on shape rather than byte-equality: the output SQL must contain the
 * dialect-appropriate keywords for the input shape (`select`, `where`, `join`, `group by`,
 * `order by`, …). Dialect-specific quoting / paging shapes are exercised in `DialectsSpec` and
 * `RelToSqlUnparserSpec`; the matrix doesn't duplicate them.
 *
 * Coverage matrix (✅ enabled, ❌ disabled, _ not applicable):
 *
 * | shape                 | SQL  | TransDSL | DFDSL |
 * |-----------------------|:----:|:--------:|:-----:|
 * | Q1  simple SELECT     |  ✅  |    ✅    |   ✅  |
 * | Q2  WHERE filter      |  ✅  |    ✅    |   ✅  |
 * | Q3  JOIN              |  ✅  |    ✅    |   ✅  |  (B1 + B3 made the DSL paths viable)
 * | Q4  GROUP BY + COUNT  |  ✅  |    ✅    |   ✅  |
 * | Q5  ORDER + LIMIT     |  ✅  |    ❌    |   ✅  |  (TransDSL Query has no orderby/limit slots)
 * | Q6  WHERE + ORDER BY  |  ✅  |    ❌    |   ✅  |  (same as Q5)
 * | Q7  LIMIT only        |  ✅  |    ❌    |   ✅  |  (same as Q5)
 * | W1…W9 window (TF-P3.S3) | ✅ |  _   |   _   |  (no window slot in TransDSL / DFDSL)
 *
 * Each enabled cell is one Kotest test (so failures map directly to a single matrix coordinate).
 */
class RoundTripMatrixSpec :
    StringSpec({

        // The matrix operates against `db.dbo` (DB schema) and `er.entity` (ER schema).
        // All qnames are three-part: `db.dbo.customers`, `er.entity.customer`, etc.
        // The three-level Calcite schema tree (root → db/er/obj → namespace → tables)
        // correctly resolves them. DB paths use FixtureModel.handle() which has no
        // entity catalog; ER paths use FixtureModel.handleWithEntities() for entity queries.
        val customersQ = qname("db", "dbo", "customers")
        val ordersQ = qname("db", "dbo", "orders")
        val customerQ = qname("er", "entity", "customer")

        val dialects =
            listOf(
                "MSSQL" to SqlDialectProto.MSSQL,
                "POSTGRESQL" to SqlDialectProto.POSTGRESQL,
                "MYSQL_MARIADB" to SqlDialectProto.MYSQL_MARIADB,
                "DUCKDB" to SqlDialectProto.DUCKDB,
            )

        // ---------- Round-trip helpers ---------------------------------------------------

        fun parseSqlToRel(sql: String): RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        fun parseErSqlToRel(sql: String): RelNode {
            val fw =
                TranslatorFramework(FixtureModel.handleWithEntities(), schemaCode = SchemaCode.ER, namespace = "entity")
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        fun encodeDecode(rel: RelNode): RelNode {
            val plan = PlanNodeEncoder.encode(rel)
            val bytes = plan.toByteArray()
            val decodedPlan = PlanNode.parseFrom(bytes)
            val freshFw = TranslatorFramework(FixtureModel.handle())
            return PlanNodeDecoder.decode(decodedPlan, freshFw)
        }

        fun encodeDecodeEr(rel: RelNode): RelNode {
            val plan = PlanNodeEncoder.encode(rel)
            val bytes = plan.toByteArray()
            val decodedPlan = PlanNode.parseFrom(bytes)
            val freshFw =
                TranslatorFramework(FixtureModel.handleWithEntities(), schemaCode = SchemaCode.ER, namespace = "entity")
            return PlanNodeDecoder.decode(decodedPlan, freshFw)
        }

        fun sqlRoundTrip(
            sql: String,
            dialect: SqlDialectProto,
        ): String = RelToSqlUnparser.unparse(encodeDecode(parseSqlToRel(sql)), dialect)

        fun erSqlRoundTrip(
            sql: String,
            dialect: SqlDialectProto,
        ): String = RelToSqlUnparser.unparse(encodeDecodeEr(parseErSqlToRel(sql)), dialect)

        fun transDslRoundTrip(
            query: Query,
            dialect: SqlDialectProto,
        ): String {
            val plan = TransDslCodec.parse(query)
            val freshFw = TranslatorFramework(FixtureModel.handle())
            val rel = PlanNodeDecoder.decode(plan, freshFw)
            return RelToSqlUnparser.unparse(rel, dialect)
        }

        fun dfDslRoundTrip(
            pipeline: Pipeline,
            dialect: SqlDialectProto,
        ): String {
            val plan = DfDslCodec.parse(pipeline)
            val freshFw = TranslatorFramework(FixtureModel.handle())
            val rel = PlanNodeDecoder.decode(plan, freshFw)
            return RelToSqlUnparser.unparse(rel, dialect)
        }

        // ---------- Shape catalog --------------------------------------------------------
        // Each shape supplies a producer per input language (or null when not expressible).
        // Producers return a Pair<displayInput, RoundTrip-derived SQL>. The matrix loops
        // over (shape × dialect × input) and registers a test per cell.

        data class Shape(
            val name: String,
            val sql: String?,
            val transDsl: Query?,
            val dfDsl: Pipeline?,
            val expectedKeywords: List<String>,
            val transDslDisabledReason: String? = null,
            val dfDslDisabledReason: String? = null,
            val sqlDisabledReason: String? = null,
        )

        val q1 =
            Shape(
                name = "Q1 simple SELECT",
                sql = "SELECT id, name FROM customers",
                transDsl =
                    Query
                        .newBuilder()
                        .addCore(Source.newBuilder().setDataObject(customersQ))
                        .addColumns(Column.newBuilder().setName("id"))
                        .addColumns(Column.newBuilder().setName("name"))
                        .build(),
                dfDsl =
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customersQ)))
                        .addOps(
                            Operation
                                .newBuilder()
                                .setSelect(
                                    SelectOp
                                        .newBuilder()
                                        .addColumns(SelectColumn.newBuilder().setName("id"))
                                        .addColumns(SelectColumn.newBuilder().setName("name")),
                                ),
                        ).build(),
                expectedKeywords = listOf("select", "customers"),
            )

        val q2 =
            Shape(
                name = "Q2 WHERE filter",
                sql = "SELECT id FROM customers WHERE id > 5",
                transDsl =
                    Query
                        .newBuilder()
                        .addCore(Source.newBuilder().setDataObject(customersQ))
                        .addColumns(Column.newBuilder().setName("id"))
                        .setFilter(gt(columnRef("id"), intLit(5)))
                        .build(),
                dfDsl =
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customersQ)))
                        .addOps(
                            Operation
                                .newBuilder()
                                .setSelect(SelectOp.newBuilder().addColumns(SelectColumn.newBuilder().setName("id"))),
                        ).addOps(
                            Operation
                                .newBuilder()
                                .setFilter(FilterOp.newBuilder().setCondition(gt(columnRef("id"), intLit(5)))),
                        ).build(),
                expectedKeywords = listOf("select", "where"),
            )

        val q3 =
            Shape(
                name = "Q3 JOIN",
                sql = "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                transDsl =
                    // Phase 08 B3 — TransDSL multi-core requires a filter to connect the cores.
                    Query
                        .newBuilder()
                        .addCore(Source.newBuilder().setDataObject(customersQ))
                        .addCore(Source.newBuilder().setDataObject(ordersQ))
                        .addColumns(Column.newBuilder().setName("name"))
                        .addColumns(Column.newBuilder().setName("total"))
                        .setFilter(eq(columnRef("customer_id"), columnRef("id")))
                        .build(),
                dfDsl =
                    // Phase 08 B1 — DFDSL emits joins via the new JoinOp.
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customersQ)))
                        .addOps(
                            Operation
                                .newBuilder()
                                .setJoin(
                                    JoinOp
                                        .newBuilder()
                                        .setRightTable(ordersQ)
                                        .setJoinType(JoinType.INNER)
                                        .setOn(eq(columnRef("customer_id"), columnRef("id"))),
                                ),
                        ).addOps(
                            Operation
                                .newBuilder()
                                .setSelect(
                                    SelectOp
                                        .newBuilder()
                                        .addColumns(SelectColumn.newBuilder().setName("name"))
                                        .addColumns(SelectColumn.newBuilder().setName("total")),
                                ),
                        ).build(),
                // Note: TransDSL multi-core lowers to `Filter(Join(left, right, true))`, which
                // Calcite's unparser writes as an implicit `FROM a, b WHERE ...` rather than an
                // explicit `JOIN ... ON`. The matrix asserts that both tables are present (the
                // semantic invariant) and doesn't pin the JOIN keyword. DFDSL emits explicit
                // JOINs (via its new JoinOp); SQL preserves the input's join form.
                expectedKeywords = listOf("customers", "orders"),
            )

        val q4 =
            Shape(
                name = "Q4 GROUP BY + COUNT",
                sql = "SELECT customer_id, COUNT(*) AS n FROM orders GROUP BY customer_id",
                transDsl =
                    Query
                        .newBuilder()
                        .addCore(Source.newBuilder().setDataObject(ordersQ))
                        .setAggregation(
                            Aggregation
                                .newBuilder()
                                .addGroup("customer_id")
                                .addCount(AggregateColumn.newBuilder().setName("customer_id").setAlias("n")),
                        ).build(),
                dfDsl =
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(ordersQ)))
                        .addOps(
                            Operation
                                .newBuilder()
                                .setGroupby(
                                    GroupByOp
                                        .newBuilder()
                                        .addKeys("customer_id")
                                        .addAggregates(
                                            AggregateCall
                                                .newBuilder()
                                                .setFunction("count")
                                                .setAlias("n")
                                                .addArgs(ColumnRef.newBuilder().setName("customer_id")),
                                        ),
                                ),
                        ).build(),
                expectedKeywords = listOf("group by", "count"),
            )

        val q5 =
            Shape(
                name = "Q5 ORDER + LIMIT",
                sql = "SELECT id FROM customers ORDER BY id DESC OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
                transDsl = null,
                transDslDisabledReason =
                    "TransDSL Query has no native ORDER BY or LIMIT slot in v1; round-tripping a Sort+LimitOffset back through TransDSL drops them (HubNSpokeSpec documents this).",
                dfDsl =
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customersQ)))
                        .addOps(
                            Operation
                                .newBuilder()
                                .setSelect(SelectOp.newBuilder().addColumns(SelectColumn.newBuilder().setName("id"))),
                        ).addOps(
                            Operation
                                .newBuilder()
                                .setOrderby(
                                    OrderByOp
                                        .newBuilder()
                                        .addKeys(
                                            SortKey
                                                .newBuilder()
                                                .setColumn(ColumnRef.newBuilder().setName("id"))
                                                .setDescending(true),
                                        ),
                                ),
                        ).addOps(Operation.newBuilder().setLimit(LimitOp.newBuilder().setN(10).setOffset(5)))
                        .build(),
                expectedKeywords = listOf("order by"),
            )

        val q6 =
            Shape(
                name = "Q6 WHERE + ORDER BY",
                sql = "SELECT id, name FROM customers WHERE id > 5 ORDER BY name",
                transDsl = null,
                transDslDisabledReason =
                    "TransDSL has no native ORDER BY slot in v1 (see Q5).",
                dfDsl =
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customersQ)))
                        .addOps(
                            Operation
                                .newBuilder()
                                .setSelect(
                                    SelectOp
                                        .newBuilder()
                                        .addColumns(SelectColumn.newBuilder().setName("id"))
                                        .addColumns(SelectColumn.newBuilder().setName("name")),
                                ),
                        ).addOps(
                            Operation
                                .newBuilder()
                                .setFilter(FilterOp.newBuilder().setCondition(gt(columnRef("id"), intLit(5)))),
                        ).addOps(
                            Operation
                                .newBuilder()
                                .setOrderby(
                                    OrderByOp
                                        .newBuilder()
                                        .addKeys(
                                            SortKey.newBuilder().setColumn(ColumnRef.newBuilder().setName("name")),
                                        ),
                                ),
                        ).build(),
                expectedKeywords = listOf("where", "order by"),
            )

        val q7 =
            Shape(
                name = "Q7 LIMIT only",
                sql = "SELECT id FROM customers FETCH FIRST 100 ROWS ONLY",
                transDsl = null,
                transDslDisabledReason =
                    "TransDSL has no native LIMIT slot in v1 (see Q5).",
                dfDsl =
                    Pipeline
                        .newBuilder()
                        .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customersQ)))
                        .addOps(Operation.newBuilder().setLimit(LimitOp.newBuilder().setN(100)))
                        .build(),
                // MSSQL emits TOP; Postgres/MySQL emit LIMIT; DuckDB inherits Postgres' LIMIT.
                expectedKeywords = listOf("customers"),
            )

        val shapes = listOf(q1, q2, q3, q4, q5, q6, q7)

        // ---------- ER entity shapes (Q8, Q9) ------------------------------------------
        // Simple SELECT and WHERE filter against `er.entity.customer`.

        val q8 =
            Shape(
                name = "Q8 ER entity SELECT",
                sql = "SELECT id, name FROM er.entity.customer",
                transDsl = null,
                transDslDisabledReason = "TransDSL does not support ER schema",
                dfDsl = null,
                dfDslDisabledReason = "DFDSL does not support ER schema",
                expectedKeywords = listOf("select"),
            )

        val q9 =
            Shape(
                name = "Q9 ER entity WHERE filter",
                sql = "SELECT id FROM er.entity.customer WHERE id > 5",
                transDsl = null,
                transDslDisabledReason = "TransDSL does not support ER schema",
                dfDsl = null,
                dfDslDisabledReason = "DFDSL does not support ER schema",
                expectedKeywords = listOf("select", "where"),
            )

        val erShapes = listOf(q8, q9)

        // ---------- Window shapes (W1…W9, TF-P3.S3) ------------------------------------
        // One `OverExpression` per aggregate code added in TF-P3.S3, plus an offset frame. SQL input
        // only: neither TransDSL nor DFDSL has a window slot.

        val windowShapes =
            listOf(
                "W1 ROW_NUMBER" to
                    "SELECT customer_id, ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY total DESC) FROM orders",
                "W2 RANK" to "SELECT customer_id, RANK() OVER (ORDER BY total) FROM orders",
                "W3 DENSE_RANK" to "SELECT customer_id, DENSE_RANK() OVER (ORDER BY total) FROM orders",
                "W4 NTILE" to "SELECT customer_id, NTILE(4) OVER (ORDER BY total) FROM orders",
                "W5 LAG" to "SELECT customer_id, LAG(total, 1, 0) OVER (ORDER BY total) FROM orders",
                "W6 LEAD" to "SELECT customer_id, LEAD(total) OVER (ORDER BY total) FROM orders",
                "W7 FIRST_VALUE" to
                    "SELECT customer_id, FIRST_VALUE(total) OVER (PARTITION BY customer_id ORDER BY total) FROM orders",
                "W8 LAST_VALUE" to
                    "SELECT customer_id, LAST_VALUE(total) OVER (PARTITION BY customer_id ORDER BY total) FROM orders",
                "W9 offset frame" to
                    "SELECT customer_id, MIN(total) OVER (ORDER BY total ROWS BETWEEN 2 PRECEDING AND 1 FOLLOWING) FROM orders",
            )

        // ---------- Cell registration ----------------------------------------------------
        // SQL input — every shape, every dialect.

        for (shape in shapes) {
            val sqlText = shape.sql
            if (sqlText == null) continue
            for ((dialectName, dialect) in dialects) {
                "${shape.name} · SQL → $dialectName" {
                    val out = sqlRoundTrip(sqlText, dialect)
                    out.shouldContainIgnoringCase("select")
                    shape.expectedKeywords.forEach { out.shouldContainIgnoringCase(it) }
                }
            }
        }

        // ER entity SQL — Q8, Q9 only, every dialect.

        for (shape in erShapes) {
            val sqlText = shape.sql
            if (sqlText == null) continue
            for ((dialectName, dialect) in dialects) {
                "${shape.name} · SQL → $dialectName" {
                    val out = erSqlRoundTrip(sqlText, dialect)
                    out.shouldContainIgnoringCase("select")
                    shape.expectedKeywords.forEach { out.shouldContainIgnoringCase(it) }
                }
            }
        }

        // Window SQL — W1…W9, every dialect.

        for ((name, sqlText) in windowShapes) {
            val function = name.substringAfter(' ').substringBefore(' ')
            val keyword = if (function == "offset") "2 preceding" else function
            for ((dialectName, dialect) in dialects) {
                "$name · SQL → $dialectName" {
                    val out = sqlRoundTrip(sqlText, dialect)
                    out.shouldContainIgnoringCase("over")
                    out.shouldContainIgnoringCase(keyword)
                }
            }
        }

        // TransDSL input — Q1..Q4. Q5/Q6/Q7 disabled with a reason; we still register a
        // test under `config(enabled = false)` so the disabled cell is visible in the report.

        for (shape in shapes) {
            val q = shape.transDsl
            for ((dialectName, dialect) in dialects) {
                if (q == null) {
                    "${shape.name} · TransDSL → $dialectName — disabled: ${shape.transDslDisabledReason}"
                        .config(enabled = false) {
                            // documented disabled cell
                        }
                } else {
                    "${shape.name} · TransDSL → $dialectName" {
                        val out = transDslRoundTrip(q, dialect)
                        out.shouldContainIgnoringCase("select")
                        shape.expectedKeywords.forEach { out.shouldContainIgnoringCase(it) }
                    }
                }
            }
        }

        // DFDSL input — every shape (Q1..Q7), every dialect.

        for (shape in shapes) {
            val p = shape.dfDsl
            for ((dialectName, dialect) in dialects) {
                if (p == null) {
                    "${shape.name} · DFDSL → $dialectName — disabled: ${shape.dfDslDisabledReason}"
                        .config(enabled = false) {
                            // documented disabled cell
                        }
                } else {
                    "${shape.name} · DFDSL → $dialectName" {
                        val out = dfDslRoundTrip(p, dialect)
                        out.shouldContainIgnoringCase("select")
                        shape.expectedKeywords.forEach { out.shouldContainIgnoringCase(it) }
                    }
                }
            }
        }
    })

// ---------- Local helpers (mirrors of TransDslCodecSpec / DfDslCodecSpec helpers) -------

private fun qname(
    schema: String,
    namespace: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(
            parseSchemaCode(schema)
                ?: error("unknown schema: $schema"),
        ).setNamespace(namespace)
        .setName(name)
        .build()

private fun columnRef(name: String): Expression =
    Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

private fun intLit(v: Long): Expression =
    Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(v).setType("int")).build()

private fun eq(
    left: Expression,
    right: Expression,
): Expression =
    Expression
        .newBuilder()
        .setFunction(
            FunctionCall
                .newBuilder()
                .setOperation("eq")
                .addOperands(left)
                .addOperands(right),
        ).setResultType("bool")
        .build()

private fun gt(
    left: Expression,
    right: Expression,
): Expression =
    Expression
        .newBuilder()
        .setFunction(
            FunctionCall
                .newBuilder()
                .setOperation("gt")
                .addOperands(left)
                .addOperands(right),
        ).setResultType("bool")
        .build()
