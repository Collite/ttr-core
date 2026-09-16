// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import com.google.protobuf.Message
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FrameBound
import org.tatrman.plan.v1.OverExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.roundTrip

/**
 * TF-P3.S3 (G A5, C5; contracts §1.1, §1.2, §5.3) — ranking/offset window functions and offset frame
 * bounds on the plan.v1 wire.
 *
 * `OverExpression.aggregate` takes `row_number`, `rank`, `dense_rank`, `ntile`, `lag`, `lead`,
 * `first_value`, `last_value`; `WindowFrame` takes `n PRECEDING` / `n FOLLOWING` bounds with the offset
 * in `lower_offset` / `upper_offset` (an integer literal; anything else is refused at parse). SQL Server
 * spelling: no frame on NTILE; a `RANGE` frame with an offset bound is refused at unparse.
 */
class WindowFunctionSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun plan(sql: String): PlanNode {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        /** Every expression in the plan, depth first. */
        fun expressions(m: Message): List<Expression> =
            m.allFields.values.flatMap { v ->
                (if (v is List<*>) v else listOf(v)).filterIsInstance<Message>().flatMap { child ->
                    (if (child is Expression) listOf(child) else emptyList()) + expressions(child)
                }
            }

        fun overs(p: PlanNode): List<OverExpression> = expressions(p).filter { it.hasOver() }.map { it.over }

        fun byteIdentical(p: PlanNode) {
            PlanNodeEncoder.encode(PlanNodeDecoder.decode(p, TranslatorFramework(FixtureModel.tfHandle()))) shouldBe p
        }

        // --- ranking and offset functions (P3.3.1) ------------------------------------------------

        // (select expression as written, MSSQL output, wire aggregate code)
        val functions =
            listOf(
                Triple(
                    "ROW_NUMBER() OVER (PARTITION BY a.B_ID ORDER BY a.ID DESC)",
                    "ROW_NUMBER() OVER (PARTITION BY [B_ID] ORDER BY [ID] DESC)",
                    "row_number",
                ),
                Triple("RANK() OVER (ORDER BY a.ID)", "RANK() OVER (ORDER BY [ID])", "rank"),
                Triple("DENSE_RANK() OVER (ORDER BY a.ID)", "DENSE_RANK() OVER (ORDER BY [ID])", "dense_rank"),
                // Calcite gives NTILE the default frame; SQL Server refuses a frame on it, so none prints.
                Triple("NTILE(4) OVER (ORDER BY a.ID)", "NTILE(4) OVER (ORDER BY [ID])", "ntile"),
                Triple(
                    "NTILE(4) OVER (PARTITION BY a.B_ID ORDER BY a.ID DESC)",
                    "NTILE(4) OVER (PARTITION BY [B_ID] ORDER BY [ID] DESC)",
                    "ntile",
                ),
                Triple("LAG(a.ID) OVER (ORDER BY a.ID)", "LAG([ID]) OVER (ORDER BY [ID])", "lag"),
                Triple("LAG(a.ID, 2, 0) OVER (ORDER BY a.ID)", "LAG([ID], 2, 0) OVER (ORDER BY [ID])", "lag"),
                Triple("LEAD(a.ID) OVER (ORDER BY a.ID)", "LEAD([ID]) OVER (ORDER BY [ID])", "lead"),
                // FIRST_VALUE / LAST_VALUE take a frame: the validator's default one prints explicitly (same meaning).
                Triple(
                    "FIRST_VALUE(a.NAME) OVER (PARTITION BY a.B_ID ORDER BY a.ID)",
                    "FIRST_VALUE([NAME]) OVER (PARTITION BY [B_ID] ORDER BY [ID] " +
                        "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)",
                    "first_value",
                ),
                // Calcite's RexBuilder turns an unbounded-both-ways ROWS frame into RANGE (same rows).
                Triple(
                    "LAST_VALUE(a.NAME) OVER (PARTITION BY a.B_ID ORDER BY a.ID " +
                        "ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
                    "LAST_VALUE([NAME]) OVER (PARTITION BY [B_ID] ORDER BY [ID] " +
                        "RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)",
                    "last_value",
                ),
            )
        for ((expr, rendered, code) in functions) {
            "$expr rides as over.aggregate '$code' and unparses as $rendered" {
                val sql = "SELECT a.ID, $expr AS W FROM A a"
                overs(plan(sql)).map { it.aggregate } shouldBe listOf(code)
                roundTrip(translator, sql, emptyList()) shouldBe
                    Outcome.Unparsed("SELECT [ID], $rendered AS [W] FROM [dbo].[A]")
            }
            "encode(decode(plan)) is byte-identical for $expr" {
                byteIdentical(plan("SELECT a.ID, $expr AS W FROM A a"))
            }
        }

        "LAG(x, 2, 0) carries its offset and default as operands" {
            overs(plan("SELECT LAG(a.ID, 2, 0) OVER (ORDER BY a.ID) AS W FROM A a"))
                .single()
                .operandsList
                .size shouldBe 3
        }

        "top N per group: ROW_NUMBER in a derived table, filtered outside" {
            roundTrip(
                translator,
                "SELECT t.ID FROM (SELECT a.ID, ROW_NUMBER() OVER (PARTITION BY a.B_ID ORDER BY a.ID DESC) AS RN " +
                    "FROM A a) t WHERE t.RN <= 3",
                emptyList(),
            ) shouldBe
                Outcome.Unparsed(
                    "SELECT [ID] FROM (SELECT [ID], ROW_NUMBER() OVER (PARTITION BY [B_ID] ORDER BY [ID] DESC) " +
                        "AS [RN] FROM [dbo].[A]) AS [t] WHERE [RN] <= 3",
                )
        }

        // --- offset frame bounds (P3.3.3) ---------------------------------------------------------

        "ROWS BETWEEN 3 PRECEDING AND CURRENT ROW rides as PRECEDING + lower_offset 3" {
            val sql =
                "SELECT a.ID, SUM(a.ID) OVER (ORDER BY a.ID ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) AS S FROM A a"
            overs(plan(sql)).forEach { over ->
                over.frame.isRows shouldBe true
                over.frame.lower shouldBe FrameBound.PRECEDING
                over.frame.lowerOffset shouldBe 3L
                over.frame.upper shouldBe FrameBound.CURRENT_ROW
                over.frame.upperOffset shouldBe 0L
            }
            val frame = "OVER (ORDER BY [ID] ROWS BETWEEN 3 PRECEDING AND CURRENT ROW)"
            roundTrip(translator, sql, emptyList()) shouldBe
                Outcome.Unparsed(
                    "SELECT [ID], CASE WHEN (COUNT([ID]) $frame) > 0 THEN SUM([ID]) $frame ELSE NULL END AS [S] FROM [dbo].[A]",
                )
        }

        "ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING keeps both offsets" {
            val sql =
                "SELECT a.ID, SUM(a.ID) OVER (ORDER BY a.ID ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS S FROM A a"
            overs(plan(sql)).forEach { over ->
                over.frame.lower shouldBe FrameBound.PRECEDING
                over.frame.lowerOffset shouldBe 1L
                over.frame.upper shouldBe FrameBound.FOLLOWING
                over.frame.upperOffset shouldBe 1L
            }
            val frame = "OVER (ORDER BY [ID] ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING)"
            roundTrip(translator, sql, emptyList()) shouldBe
                Outcome.Unparsed(
                    "SELECT [ID], CASE WHEN (COUNT([ID]) $frame) > 0 THEN SUM([ID]) $frame ELSE NULL END AS [S] FROM [dbo].[A]",
                )
        }

        "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW keeps its shape (no offsets)" {
            val sql =
                "SELECT a.ID, SUM(a.ID) OVER (ORDER BY a.ID RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS S FROM A a"
            overs(plan(sql)).forEach { over ->
                over.frame.lower shouldBe FrameBound.UNBOUNDED_PRECEDING
                over.frame.lowerOffset shouldBe 0L
                over.frame.upper shouldBe FrameBound.CURRENT_ROW
            }
            val frame = "OVER (ORDER BY [ID] RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"
            roundTrip(translator, sql, emptyList()) shouldBe
                Outcome.Unparsed(
                    "SELECT [ID], CASE WHEN (COUNT([ID]) $frame) > 0 THEN SUM([ID]) $frame ELSE NULL END AS [S] FROM [dbo].[A]",
                )
        }

        // (select expression as written, MSSQL output) — no CASE wrapper, so each is byte-identical too.
        val frames =
            listOf(
                "MIN(a.ID) OVER (ORDER BY a.ID ROWS 2 PRECEDING)" to
                    "MIN([ID]) OVER (ORDER BY [ID] ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)",
                "COUNT(*) OVER (PARTITION BY a.B_ID ORDER BY a.ID ROWS BETWEEN 3 FOLLOWING AND 5 FOLLOWING)" to
                    "COUNT(*) OVER (PARTITION BY [B_ID] ORDER BY [ID] ROWS BETWEEN 3 FOLLOWING AND 5 FOLLOWING)",
                "LAST_VALUE(a.NAME) OVER (PARTITION BY a.B_ID ORDER BY a.ID " +
                    "ROWS BETWEEN 2 PRECEDING AND UNBOUNDED FOLLOWING)" to
                    "LAST_VALUE([NAME]) OVER (PARTITION BY [B_ID] ORDER BY [ID] " +
                    "ROWS BETWEEN 2 PRECEDING AND UNBOUNDED FOLLOWING)",
            )
        for ((expr, rendered) in frames) {
            "$expr round-trips to $rendered" {
                val sql = "SELECT a.ID, $expr AS W FROM A a"
                roundTrip(translator, sql, emptyList()) shouldBe
                    Outcome.Unparsed("SELECT [ID], $rendered AS [W] FROM [dbo].[A]")
                byteIdentical(plan(sql))
            }
        }

        "a non-literal frame offset is refused at parse" {
            roundTrip(
                translator,
                "SELECT a.ID, SUM(a.ID) OVER (ORDER BY a.ID ROWS BETWEEN a.B_ID PRECEDING AND CURRENT ROW) AS S FROM A a",
                emptyList(),
            ) shouldBe
                Outcome.ParseFailed(
                    "parse_pipeline_failed",
                    "Window frame bound '\$2 PRECEDING' is not in the v1 wire format (offset must be an integer literal)",
                )
        }

        "a fractional frame offset is refused at parse" {
            val r =
                translator.parseToRelNode(
                    "SELECT a.ID, MIN(a.ID) OVER (ORDER BY a.ID RANGE BETWEEN 2.5 PRECEDING AND CURRENT ROW) AS S FROM A a",
                    Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.message shouldContain "(offset must be an integer literal)"
        }

        "a RANGE offset bound rides the wire but is refused at MSSQL unparse (SQL Server takes only ROWS offsets)" {
            val p =
                plan(
                    "SELECT a.ID, MIN(a.ID) OVER (ORDER BY a.ID RANGE BETWEEN 5 PRECEDING AND CURRENT ROW) AS S FROM A a",
                )
            overs(p).single().frame.let {
                it.isRows shouldBe false
                it.lower shouldBe FrameBound.PRECEDING
                it.lowerOffset shouldBe 5L
            }
            val u = translator.unparseFromRelNode(p, Language.SQL, SqlDialect.MSSQL)
            u.shouldBeInstanceOf<UnparseResult.Failure>()
            u.code shouldBe "sql_unparse_failed"
            u.message shouldContain "RANGE frame bound '5 PRECEDING' has no SQL Server equivalent (use ROWS)"
        }

        // --- G A5 regression guard (P3.3.6) -------------------------------------------------------

        "RexOver is never flattened to a FunctionCall (G A5)" {
            val windowNames = setOf("over", "sum", "count", "row_number", "rank", "lag")
            listOf(
                "SELECT a.ID, ROW_NUMBER() OVER (ORDER BY a.ID) AS W FROM A a" to listOf("row_number"),
                "SELECT a.ID, LAG(a.ID) OVER (ORDER BY a.ID) AS W FROM A a" to listOf("lag"),
                "SELECT a.ID, SUM(a.ID) OVER (PARTITION BY a.B_ID) AS W FROM A a" to listOf("count", "sum"),
            ).forEach { (sql, aggregates) ->
                val p = plan(sql)
                overs(p).map { it.aggregate } shouldBe aggregates
                expressions(p)
                    .filter { it.hasFunction() && it.function.operation.lowercase() in windowNames }
                    .shouldBeEmpty()
            }
        }
    })
