// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * TF-P1.S3 (G A6, C6, C7, C8; contracts §1.2, §2, §5.2, §5.4) — the aggregate shapes the v1 wire used
 * to get wrong without saying so: grouping sets and `FILTER (WHERE …)` were silently dropped (the query
 * ran with a different meaning), and `TRY_CAST` / `STRING_AGG` parsed but died at unparse. Grouping sets
 * and FILTER are now refused at parse; `safe_cast` and `listagg` (with `separator` / `within_group`)
 * ride the wire.
 */
class AggregateWireSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun plan(sql: String): PlanNode {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        fun parseFailure(sql: String): ParseResult.Failure {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Failure>()
            return r
        }

        fun mssql(sql: String): String {
            val u = translator.unparseFromRelNode(plan(sql), Language.SQL, SqlDialect.MSSQL)
            u.shouldBeInstanceOf<UnparseResult.Success>()
            return u.output.replace(Regex("""\s+"""), " ").trim()
        }

        fun aggregates(p: PlanNode): List<AggregateCall> =
            when {
                p.hasAggregate() -> p.aggregate.aggregatesList
                p.hasProject() -> aggregates(p.project.input)
                p.hasSort() -> aggregates(p.sort.input)
                p.hasFilter() -> aggregates(p.filter.input)
                else -> emptyList()
            }

        val groupingSets =
            listOf(
                "GROUP BY ROLLUP(a.B_ID)",
                "GROUP BY CUBE(a.B_ID)",
                "GROUP BY GROUPING SETS ((a.B_ID), ())",
            )
        for (groupBy in groupingSets) {
            "$groupBy is refused at parse, not silently flattened (G A6)" {
                val f = parseFailure("SELECT a.B_ID, COUNT(*) AS N FROM A a $groupBy")
                f.code shouldBe "parse_pipeline_failed"
                f.message shouldContain "GROUP BY ROLLUP/CUBE/GROUPING SETS is not in the v1 wire format"
            }
        }

        "a plain GROUP BY still has one group set and parses" {
            aggregates(plan("SELECT a.B_ID, COUNT(*) AS N FROM A a GROUP BY a.B_ID")).single().function shouldBe "count"
        }

        "COUNT(*) FILTER (WHERE …) is refused at parse, not silently unfiltered (C7)" {
            val f = parseFailure("SELECT COUNT(*) FILTER (WHERE a.ID > 1) AS N FROM A a")
            f.code shouldBe "parse_pipeline_failed"
            f.message shouldContain "Aggregate FILTER (WHERE …) is not in the v1 wire format"
        }

        "TRY_CAST rides as safe_cast with a physical target type and unparses as TRY_CAST (C8)" {
            val sql = "SELECT TRY_CAST(a.NAME AS int) AS X, TRY_CONVERT(int, a.NAME) AS Y FROM A a"
            val project = plan(sql).project
            val tryCast = project.expressionsList[0].expression
            tryCast.function.operation shouldBe "safe_cast"
            tryCast.resultType shouldBe "int"
            mssql(sql) shouldBe
                "SELECT TRY_CAST([NAME] AS INTEGER) AS [X], TRY_CONVERT(INTEGER, [NAME]) AS [Y] FROM [dbo].[A]"
        }

        "TRY_CAST keeps length and precision (varchar:20, decimal:18,2)" {
            val sql = "SELECT TRY_CAST(a.NAME AS varchar(20)) AS X, TRY_CAST(a.NAME AS decimal(18,2)) AS Y FROM A a"
            plan(sql).project.expressionsList.map { it.expression.resultType } shouldBe
                listOf("varchar:20", "decimal:18,2")
            mssql(sql) shouldBe
                "SELECT TRY_CAST([NAME] AS VARCHAR(20)) AS [X], TRY_CAST([NAME] AS DECIMAL(18, 2)) AS [Y] FROM [dbo].[A]"
        }

        "STRING_AGG rides as listagg with its separator and unparses as STRING_AGG (C6)" {
            val sql = "SELECT a.B_ID, STRING_AGG(a.NAME, ', ') AS NAMES FROM A a GROUP BY a.B_ID"
            val agg = aggregates(plan(sql)).single()
            agg.function shouldBe "listagg"
            agg.argsList.map { it.name } shouldBe listOf("NAME")
            agg.separator.stringValue shouldBe ", "
            agg.withinGroupCount shouldBe 0
            mssql(sql) shouldBe "SELECT [B_ID], STRING_AGG([NAME], ', ') AS [NAMES] FROM [dbo].[A] GROUP BY [B_ID]"
        }

        "STRING_AGG … WITHIN GROUP (ORDER BY …) carries the order on within_group (C6)" {
            val sql =
                "SELECT a.B_ID, STRING_AGG(a.NAME, ', ') WITHIN GROUP (ORDER BY a.NAME) AS NAMES FROM A a GROUP BY a.B_ID"
            val agg = aggregates(plan(sql)).single()
            agg.function shouldBe "listagg"
            agg.separator.stringValue shouldBe ", "
            agg.withinGroupList.map { Triple(it.column.name, it.descending, it.nullsFirst) } shouldBe
                listOf(Triple("NAME", false, false))
            // The CASE prefix is the null-collation emulation every sort key gets today; TF-P2.S2
            // (T-SQL null collation on validation) removes it here too — re-freeze then.
            mssql(sql) shouldBe
                "SELECT [B_ID], STRING_AGG([NAME], ', ') WITHIN GROUP " +
                "(ORDER BY CASE WHEN [NAME] IS NULL THEN 1 ELSE 0 END, [NAME]) AS [NAMES] FROM [dbo].[A] GROUP BY [B_ID]"
        }

        "STRING_AGG … WITHIN GROUP (ORDER BY … DESC) keeps the direction" {
            val sql =
                "SELECT a.B_ID, STRING_AGG(a.NAME, '|') WITHIN GROUP (ORDER BY a.ID DESC) AS NAMES FROM A a GROUP BY a.B_ID"
            val agg = aggregates(plan(sql)).single()
            agg.separator.stringValue shouldBe "|"
            agg.withinGroupList.map { Triple(it.column.name, it.descending, it.nullsFirst) } shouldBe
                listOf(Triple("ID", true, true))
            // CASE prefix: see above (TF-P2.S2 re-freeze).
            mssql(sql) shouldBe
                "SELECT [B_ID], STRING_AGG([NAME], '|') WITHIN GROUP " +
                "(ORDER BY CASE WHEN [ID] IS NULL THEN 0 ELSE 1 END, [ID] DESC) AS [NAMES] FROM [dbo].[A] GROUP BY [B_ID]"
        }

        "STRING_AGG(DISTINCT …) rides the wire but is refused at MSSQL unparse (SQL Server has no DISTINCT there)" {
            val p = plan("SELECT a.B_ID, STRING_AGG(DISTINCT a.NAME, ', ') AS NAMES FROM A a GROUP BY a.B_ID")
            aggregates(p).single().distinct shouldBe true
            val u = translator.unparseFromRelNode(p, Language.SQL, SqlDialect.MSSQL)
            u.shouldBeInstanceOf<UnparseResult.Failure>()
            u.code shouldBe "sql_unparse_failed"
            u.message shouldContain "STRING_AGG(DISTINCT) has no SQL Server equivalent"
        }

        val stable =
            listOf(
                "SELECT a.B_ID, STRING_AGG(a.NAME, ', ') AS NAMES FROM A a GROUP BY a.B_ID",
                "SELECT a.B_ID, STRING_AGG(a.NAME, ', ') WITHIN GROUP (ORDER BY a.ID DESC) AS NAMES FROM A a GROUP BY a.B_ID",
                "SELECT TRY_CAST(a.NAME AS decimal(18,2)) AS X FROM A a",
            )
        for (sql in stable) {
            // The listagg separator column is reused on decode, not re-projected (which renamed it each trip).
            "encode(decode(plan)) is byte-identical for <$sql>" {
                val p = plan(sql)
                PlanNodeEncoder.encode(PlanNodeDecoder.decode(p, TranslatorFramework(FixtureModel.tfHandle()))) shouldBe
                    p
            }
        }
    })
