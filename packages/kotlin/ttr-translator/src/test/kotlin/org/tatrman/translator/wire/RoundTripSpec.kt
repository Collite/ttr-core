// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.RelNode
import org.tatrman.translator.codec.sql.RelToSqlUnparser
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * RelNode → PlanNode → bytes → PlanNode → RelNode → SQL round-trip suite.
 *
 * The round-trip is the v1 wire-format acceptance bar (per
 * `tasks-phase-01-1-translator-lib.md` Section H). We don't compare RelNode
 * instances directly (Calcite's RelNode equality is structural and depends on
 * cluster identity, which a fresh framework deliberately changes between
 * passes). Instead we compare the produced SQL after a clean unparse —
 * structurally equivalent input/output round-trips produce equivalent SQL.
 */
class RoundTripSpec :
    StringSpec({

        fun parseToRel(sql: String): RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        fun roundTrip(sql: String): String {
            val original = parseToRel(sql)
            val plan = PlanNodeEncoder.encode(original)
            val bytes = plan.toByteArray()
            val decodedPlan = PlanNode.parseFrom(bytes)
            val freshFw = TranslatorFramework(FixtureModel.handle())
            val rel = PlanNodeDecoder.decode(decodedPlan, freshFw)
            return RelToSqlUnparser.unparse(rel, SqlDialectProto.MSSQL)
        }

        "Q1 - simple SELECT round-trips" {
            val out = roundTrip("SELECT id, name FROM customers")
            out.shouldContainIgnoringCase("select")
            out.shouldContainIgnoringCase("customers")
        }

        "Q2 - SELECT with WHERE filter round-trips" {
            val out = roundTrip("SELECT id FROM customers WHERE id > 5")
            out.shouldContainIgnoringCase("where")
        }

        "Q3 - JOIN round-trips (encoder produces a structurally valid PlanNode)" {
            // Phase 08 A1 / DF-T01 — RESOLVE stage. `RexInputRef`s inside a Join condition now
            // encode the real field name plus a `$L`/`$R` source-alias hint; the decoder routes
            // through `RelBuilder.field(inputCount, ord, name)` so the lookup hits the correct
            // join input. The pre-RESOLVE failure was: positional `$idx` indexed into the right
            // (top-of-stack) input only, taking a left-side reference out of range.
            val out =
                roundTrip(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            out.shouldContainIgnoringCase("join")
        }

        "Q4 - GROUP BY + COUNT round-trips" {
            val out = roundTrip("SELECT customer_id, COUNT(*) AS n FROM orders GROUP BY customer_id")
            out.shouldContainIgnoringCase("group by")
            out.shouldContainIgnoringCase("count")
        }

        "Q5 - sort + limit round-trips" {
            val out =
                roundTrip(
                    "SELECT id FROM customers ORDER BY id DESC OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
                )
            out.shouldContainIgnoringCase("order by")
        }

        "Q7 - UNION ALL round-trips (v1 Union op)" {
            // The v1 Union op — a LogicalUnion now survives encode → bytes → decode
            // and re-unparses to UNION ALL. Inputs share the output row type.
            val out = roundTrip("SELECT id FROM customers UNION ALL SELECT customer_id FROM orders")
            out.shouldContainIgnoringCase("union all")
        }

        "Q8 - UNION (distinct) round-trips" {
            val out = roundTrip("SELECT id FROM customers UNION SELECT customer_id FROM orders")
            out.shouldContainIgnoringCase("union")
            out.shouldNotContainIgnoringCase("union all")
        }

        "Q9 - searched CASE round-trips" {
            val out = roundTrip("SELECT CASE WHEN id > 5 THEN 'big' ELSE 'small' END FROM customers")
            out.shouldContainIgnoringCase("case")
            out.shouldContainIgnoringCase("when")
        }

        "Q10 - windowed aggregate (SUM OVER PARTITION/ORDER) round-trips" {
            // The v1 OverExpression — a RexOver survives encode → bytes → decode and
            // re-unparses to a windowed aggregate (partition + order + default RANGE frame).
            val out = roundTrip("SELECT SUM(total) OVER (PARTITION BY customer_id ORDER BY total) FROM orders")
            out.shouldContainIgnoringCase("over")
            out.shouldContainIgnoringCase("partition by")
        }

        "Q6 - combined WHERE + ORDER BY round-trips" {
            val out = roundTrip("SELECT id, name FROM customers WHERE id > 5 ORDER BY name")
            out.shouldContainIgnoringCase("where")
            out.shouldContainIgnoringCase("order by")
        }

        "Q7 - LIMIT-only (no sort) round-trips" {
            val out = roundTrip("SELECT id FROM customers FETCH FIRST 100 ROWS ONLY")
            // MSSQL emits TOP or OFFSET/FETCH; either is acceptable.
            (out.contains("FETCH", ignoreCase = true) || out.contains("TOP", ignoreCase = true))
                .shouldBe(true)
        }

        "Q3b - JOIN with WHERE on the post-join shape round-trips" {
            // Phase 08 A1 — exercise post-join Filter referencing fields from both inputs in the
            // single-input scope (Filter input row type is the joined shape; no $L/$R needed).
            val out =
                roundTrip(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id WHERE o.total > 100",
                )
            out.shouldContainIgnoringCase("join")
            out.shouldContainIgnoringCase("where")
        }

        "Q3c - JOIN condition encodes ColumnRef.source_alias as \$L/\$R" {
            // Locks in the wire-shape contract: a Join's condition carries left/right tags so the
            // decoder routes through the correct input. A regression that drops these reverts
            // Q3 to the pre-A1 failure mode.
            val rel =
                parseToRel(
                    "SELECT c.name FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            val plan = PlanNodeEncoder.encode(rel)
            // Walk: top is Project → Join. Read the Join's condition operands.
            val join = plan.project.input.join
            val cond = join.condition.function
            val tags = cond.operandsList.map { it.columnRef.sourceAlias }.toSet()
            tags.shouldBe(setOf(Expressions.LEFT_INPUT_TAG, Expressions.RIGHT_INPUT_TAG))
            // Both sides carry real field names (no `\$N` positional leak).
            cond.operandsList.forEach {
                it.columnRef.name
                    .startsWith("\$")
                    .shouldBe(false)
            }
        }

        "Q3d - self-JOIN on a colliding key name round-trips (Calcite uniquifies the right side)" {
            // Real-world repro of `field [<key>0] not found`: two joined tables both expose the
            // same key column, so Calcite renames the right one to `<key>0` in the
            // COMBINED row type. A self-join forces the same collision — the condition's
            // right operand is `id`, which is `id0` in the combined type. The encoder
            // must emit the per-INPUT name (`id`), or the decoder's $R lookup against
            // the right input (fields [id, name]) throws on the missing `id0`.
            val out =
                roundTrip(
                    "SELECT a.name FROM customers a JOIN customers b ON a.id = b.id",
                )
            out.shouldContainIgnoringCase("join")
        }

        "Q8 - full pattern-shaped query unparses to MSSQL end-to-end (decode→optimize→RelToSql)" {
            // One guard for the whole wire-operator + MSSQL-dialect surface the
            // legacy per-centre aggregation pattern needs — so we stop finding
            // gaps one prod round-trip at a time. Exercises, in a single plan:
            //   - `||` concat used to build LIKE patterns,
            //   - SUBSTRING(col, 1, 1),
            //   - CAST(text → float),
            //   - unary minus on an aggregate (the `-SUM(...)` negated-amount shape),
            //   - INNER JOIN on a colliding key name (id ↔ id),
            //   - MIN + SUM aggregates with GROUP BY,
            //   - ORDER BY <agg> DESC + a row limit.
            // Runs the real worker path: encode → (decode → optimize → RelToSql MSSQL)
            // via the orchestrator, asserting it produces SQL (not a Failure).
            val sql =
                """
                SELECT MIN(c.id) AS mn,
                       SUM(CAST(c.name AS FLOAT)) AS s,
                       -SUM(CAST(c.name AS FLOAT)) AS neg
                FROM customers c
                JOIN orders o ON o.customer_id = c.id
                WHERE (c.name LIKE c.name || '%' OR c.name LIKE '%' || c.name || '%')
                  AND SUBSTRING(c.name, 1, 1) = 'A'
                GROUP BY c.id, c.name
                ORDER BY SUM(CAST(c.name AS FLOAT)) DESC
                OFFSET 0 ROWS FETCH NEXT 30 ROWS ONLY
                """.trimIndent()
            val plan = PlanNode.parseFrom(PlanNodeEncoder.encode(parseToRel(sql)).toByteArray())
            val result =
                Translator(FixtureModel.handle())
                    .unparseFromRelNode(plan, Language.SQL, SqlDialectProto.MSSQL, optimize = true)
            result.shouldBeInstanceOf<UnparseResult.Success>()
            result.output.shouldContainIgnoringCase("select")
            // SQL Server has no DOUBLE type — the CAST must render as FLOAT, or
            // the DB rejects it ("Incorrect syntax near ')'").
            result.output.shouldContainIgnoringCase("as float")
            result.output.shouldNotContainIgnoringCase("double")
        }

        "Q9 - scalar subquery in WHERE round-trips (regression: was dropped as \$scalar_query)" {
            // Production repro: `WHERE x = (SELECT ...)`. Calcite represents the subquery as a
            // RexSubQuery (a RexCall subclass) whose body lives in `.rel`, not `.operands`. The
            // pre-fix encoder fell through the generic RexCall branch, emitting a bare
            // `$scalar_query` FunctionCall with no operands — silently dropping the subquery and
            // making the REL_NODE round-trip fail with "Operator '$scalar_query' is not in the
            // v1 wire format".
            val sql = "SELECT id FROM customers WHERE id = (SELECT customer_id FROM orders WHERE total > 100)"

            val plan = PlanNodeEncoder.encode(parseToRel(sql))
            val cond = plan.project.input.filter.condition.function // eq(id, <subquery>)
            val rhs = cond.operandsList[1]
            rhs.exprCase.shouldBe(org.tatrman.plan.v1.Expression.ExprCase.SUBQUERY)
            rhs.subquery.kind.shouldBe("scalar")
            // The corrupt placeholder must be gone entirely.
            plan.toString().shouldNotContainIgnoringCase("\$scalar_query")

            val out = roundTrip(sql)
            out.shouldContainIgnoringCase("where")
            out.shouldContainIgnoringCase("orders") // subquery body survived
            out.shouldContainIgnoringCase("customer_id")
        }

        "Q10 - IN subquery round-trips" {
            val sql = "SELECT id FROM customers WHERE id IN (SELECT customer_id FROM orders)"

            val plan = PlanNodeEncoder.encode(parseToRel(sql))
            // `x IN (SELECT ...)` encodes as a single SubqueryExpression(kind=in) whose operands
            // carry the LHS (`id`).
            val cond = plan.project.input.filter.condition
            cond.exprCase.shouldBe(org.tatrman.plan.v1.Expression.ExprCase.SUBQUERY)
            cond.subquery.kind.shouldBe("in")

            val out = roundTrip(sql)
            out.shouldContainIgnoringCase("in")
            out.shouldContainIgnoringCase("orders")
        }

        "Q11 - scalar subquery survives the orchestrator unparse (optimize=true)" {
            // The real worker path: decode → optimize (FILTER_REDUCE_EXPRESSIONS) → RelToSql.
            // Guards that the HEP optimizer doesn't choke on a RexSubQuery-bearing Filter.
            val sql = "SELECT id FROM customers WHERE id = (SELECT customer_id FROM orders WHERE total > 100)"
            val plan = PlanNode.parseFrom(PlanNodeEncoder.encode(parseToRel(sql)).toByteArray())
            val result =
                Translator(FixtureModel.handle())
                    .unparseFromRelNode(plan, Language.SQL, SqlDialectProto.MSSQL, optimize = true)
            result.shouldBeInstanceOf<UnparseResult.Success>()
            result.output.shouldContainIgnoringCase("orders")
        }
    })
