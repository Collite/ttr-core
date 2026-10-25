// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.plan.v1.*
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.ttrp.expr.ExpressionTypechecker
import org.tatrman.ttrp.expr.Column
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.parser.TtrpParser
import java.sql.DriverManager

/** Exercise actual translated SQL; checking the function name alone misses dialect/null mistakes. */
class ConcatSqlSpec :
    StringSpec({
        val qname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("stamps")
                .build()
        val table = ModelTable(qname, listOf(ModelColumn("stamp", SurfaceType.TEXT)))
        val source =
            """
            concat(concat(substring(stamp, 1, 4), '-Q'),
                case when substring(stamp, 6, 2) <= '03' then '1'
                     when substring(stamp, 6, 2) <= '06' then '2'
                     when substring(stamp, 6, 2) <= '09' then '3' else '4' end)
            """.trimIndent()
        val parsed = TtrpParser.parseExpression(source)
        val schema = mapOf("" to listOf(Column("stamp", TtrpType.Str)))
        val scan =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(qname)
                        .addOutputColumns(ColumnRef.newBuilder().setName("stamp")),
                ).build()
        val plan =
            PlanNode
                .newBuilder()
                .setProject(
                    ProjectNode
                        .newBuilder()
                        .setInput(scan)
                        .addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setAlias("quarter")
                                .setExpression(PlanNodeBuilder().expr(parsed.expression)),
                        ),
                ).build()

        "quarter expressions typecheck and lower to the existing concat operation" {
            parsed.diagnostics shouldBe emptyList()
            ExpressionTypechecker().check(parsed.expression, schema).diagnostics shouldBe emptyList()
            plan.project
                .getExpressions(0)
                .expression.function.operation shouldBe "||"
            org.tatrman.ttrp.emit.polars.PolarsExprRenderer().render(
                TtrpParser.parseExpression("concat('a', 'b')").expression,
            ) shouldContain
                "pl.concat_str("
            TranslatorFacade(IslandModelHandle(listOf(table)), SqlDialect.MSSQL).unparse(plan) shouldContain "[stamp]"
        }

        "PostgreSQL SQL executes with every quarter boundary and strict NULL propagation" {
            val sql = TranslatorFacade(IslandModelHandle(listOf(table)), SqlDialect.POSTGRESQL).unparse(plan)
            DriverManager.getConnection("jdbc:duckdb:").use { db ->
                db.createStatement().use { s ->
                    s.execute("CREATE SCHEMA dbo")
                    s.execute("SET schema = 'dbo'")
                    s.execute("CREATE TABLE dbo.stamps(stamp VARCHAR)")
                    for ((stamp, expected) in listOf(
                        "2025-12-31" to "2025-Q4",
                        "2026-01-01" to "2026-Q1",
                        "2026-03-31" to "2026-Q1",
                        "2026-04-01" to "2026-Q2",
                        "2026-06-30" to "2026-Q2",
                        "2026-07-01" to "2026-Q3",
                        "2026-09-30" to "2026-Q3",
                        "2026-10-01" to "2026-Q4",
                        null to null,
                    )) {
                        s.execute("DELETE FROM dbo.stamps")
                        db.prepareStatement("INSERT INTO dbo.stamps VALUES (?)").use { ps ->
                            ps.setString(1, stamp)
                            ps.executeUpdate()
                        }
                        s.executeQuery(sql).use { rows ->
                            rows.next() shouldBe true
                            rows.getString("quarter") shouldBe
                                expected
                        }
                    }
                }
            }
        }
    })
