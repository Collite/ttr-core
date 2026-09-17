// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * TF-P1.S1 (G A9, contracts §3.3) — a CAST's target type rides the wire as a physical type code
 * (`varchar:20`, `decimal:18,2`, `int`, `date`, …) instead of the surface tag (`text`, `float`, …), so
 * length, precision and scale survive, and a `date` cast is no longer widened to a timestamp and folded
 * away (P0 finding, `s3_date_cast_cmp`).
 */
class CastPhysicalTypeSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun plan(sql: String): PlanNode {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        fun mssql(sql: String): String {
            val u = translator.unparseFromRelNode(plan(sql), Language.SQL, SqlDialect.MSSQL)
            u.shouldBeInstanceOf<UnparseResult.Success>()
            return u.output.replace(Regex("""\s+"""), " ").trim()
        }

        /** Every `cast` FunctionCall's result_type, in plan text order. */
        fun castTypes(p: PlanNode): List<String> =
            buildList {
                fun visit(e: Expression) {
                    if (e.hasFunction()) {
                        if (e.function.operation == "cast") add(e.resultType)
                        e.function.operandsList.forEach(::visit)
                    }
                }

                fun walk(n: PlanNode) {
                    if (n.hasProject()) {
                        n.project.expressionsList.forEach { visit(it.expression) }
                        walk(n.project.input)
                    }
                    if (n.hasFilter()) {
                        visit(n.filter.condition)
                        walk(n.filter.input)
                    }
                }
                walk(p)
            }

        val cases =
            listOf(
                Triple("CAST(a.ID AS varchar(20))", "varchar:20", "CAST([ID] AS VARCHAR(20))"),
                Triple("CAST(a.ID AS decimal(18,2))", "decimal:18,2", "CAST([ID] AS DECIMAL(18, 2))"),
                Triple("CAST(a.NAME AS int)", "int", "CAST([NAME] AS INTEGER)"),
                Triple("CAST(a.ID AS varchar)", "varchar", "CAST([ID] AS VARCHAR)"),
                Triple("CAST(a.ID AS float)", "float", "CAST([ID] AS FLOAT)"),
                Triple("CAST(a.NAME AS bigint)", "bigint", "CAST([NAME] AS BIGINT)"),
            )
        for ((cast, code, rendered) in cases) {
            "$cast rides as '$code' and unparses to $rendered" {
                val sql = "SELECT $cast AS X FROM A a"
                castTypes(plan(sql)) shouldBe listOf(code)
                mssql(sql) shouldBe "SELECT $rendered AS [X] FROM [dbo].[A]"
            }
        }

        "a DATE cast in a comparison survives the round trip (P0 finding s3_date_cast_cmp)" {
            val sql = "SELECT a.ID FROM A a WHERE CAST(GETDATE() AS date) >= CAST('2026-01-01' AS date)"
            castTypes(plan(sql)) shouldBe listOf("date", "date")
            mssql(sql) shouldBe "SELECT [ID] FROM [dbo].[A] WHERE CAST(GETDATE() AS DATE) >= '2026-01-01'"
        }

        // Calcite declares the MSSQL DATEDIFF operands as DATE, so the validator coerces datetimes with an
        // implicit CAST(… AS DATE). While casts rode as surface tags that cast decoded to a TIMESTAMP and
        // vanished; carried faithfully it would make DATEDIFF(HOUR, …) count whole days.
        for (unit in listOf("day", "hour", "minute")) {
            "DATEDIFF($unit, …) keeps its datetime operands — no implicit DATE cast" {
                mssql("SELECT DATEDIFF($unit, GETDATE(), GETDATE()) AS D FROM A a") shouldBe
                    "SELECT DATEDIFF(${unit.uppercase()}, GETDATE(), GETDATE()) AS [D] FROM [dbo].[A]"
            }
        }

        // The codes other producers send without precision — the TTR-P MD write lowerings (`datetime`,
        // `decimal`, `date`) and pre-TF plans (`text`, `float`, `bool`) — decode to a concrete type.
        // `datetime` is TIMESTAMP(3) now (T-SQL datetime), where the surface tag gave Calcite's TIMESTAMP(0).
        for ((code, expected) in listOf(
            "datetime" to "TIMESTAMP(3)",
            "datetime2:0" to "TIMESTAMP(0)",
            "decimal" to "DECIMAL(19, 0)",
            "decimal:18,2" to "DECIMAL(18, 2)",
            "date" to "DATE",
            "text" to "VARCHAR",
            "varchar:max" to "VARCHAR(65536)",
            "float" to "DOUBLE",
            "bool" to "BOOLEAN",
            "money" to "DECIMAL(19, 4)",
        )) {
            "cast code '$code' decodes to $expected" {
                val builder =
                    org.tatrman.translator.framework
                        .TranslatorFramework(FixtureModel.tfHandle())
                        .newRelBuilder()
                val expr =
                    Expression
                        .newBuilder()
                        .setFunction(
                            org.tatrman.plan.v1.FunctionCall
                                .newBuilder()
                                .setOperation("cast")
                                .addOperands(
                                    Expression.newBuilder().setLiteral(
                                        org.tatrman.plan.v1.Literal
                                            .newBuilder()
                                            .setStringValue("1")
                                            .setType("text"),
                                    ),
                                ),
                        ).setResultType(code)
                        .build()
                Expressions.decode(builder, expr).type.fullTypeString shouldBe "$expected NOT NULL"
            }
        }

        "an unknown cast code is refused instead of decoding to ANY" {
            val builder =
                org.tatrman.translator.framework
                    .TranslatorFramework(FixtureModel.tfHandle())
                    .newRelBuilder()
            val expr =
                Expression
                    .newBuilder()
                    .setFunction(
                        org.tatrman.plan.v1.FunctionCall
                            .newBuilder()
                            .setOperation("cast")
                            .addOperands(
                                Expression.newBuilder().setLiteral(
                                    org.tatrman.plan.v1.Literal
                                        .newBuilder()
                                        .setStringValue("1")
                                        .setType("text"),
                                ),
                            ),
                    ).setResultType("geography")
                    .build()
            io.kotest.assertions.throwables
                .shouldThrow<UnsupportedOperationException> { Expressions.decode(builder, expr) }
                .message shouldBe "Cast target type 'geography' is not in the v1 wire format"
        }

        "an int cast is INTEGER, not BIGINT, after the round trip (P0 finding r38_cast_int_in_plus)" {
            mssql("SELECT CAST(a.NAME AS int) + 1 AS Y FROM A a") shouldBe
                "SELECT CAST([NAME] AS INTEGER) + 1 AS [Y] FROM [dbo].[A]"
        }
    })
