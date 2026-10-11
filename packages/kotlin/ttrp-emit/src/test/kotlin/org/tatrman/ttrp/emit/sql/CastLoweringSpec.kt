// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.plan.v1.ColumnRef as PbColumnRef
import org.tatrman.plan.v1.Expression as PbExpression
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.codec.sql.ParseResult
import org.tatrman.translator.codec.sql.SqlParser
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.TtrpType

/**
 * `cast(x as <type>)` on the SQL emit paths: the translator decodes a cast as the FUNCTION form
 * (`FunctionCall(operation = "cast")`, target type on the wrapping expression's `result_type`) — the
 * `Expression.cast` (`CastExpression`) oneof is a decode TODO there, so lowering to it failed every
 * sql-text / CTE build with `TTRP-EMT-004 … CastExpression decoding is TODO`. The target rides as the
 * translator's physical cast code (`varchar:max`, `int`, `decimal:12,2`, …).
 */
class CastLoweringSpec :
    StringSpec({
        val loc = SourceLocation.UNKNOWN

        fun cast(
            column: String,
            to: TtrpType,
        ) = Cast(ColumnRef(null, column, loc), to, loc)

        "a cast lowers to the translator's function form with the target as the result type" {
            val e = PlanNodeBuilder().expr(cast("n", TtrpType.Str))
            e.exprCase shouldBe PbExpression.ExprCase.FUNCTION
            e.function.operation shouldBe "cast"
            e.function.operandsCount shouldBe 1
            e.function
                .getOperands(0)
                .columnRef.name shouldBe "n"
            e.resultType shouldBe "varchar:max"
        }

        "each TTR-P scalar type maps to a translator cast code" {
            SqlCastTypes.codeOf(TtrpType.Integer) shouldBe "int"
            SqlCastTypes.codeOf(TtrpType.Decimal()) shouldBe "decimal"
            SqlCastTypes.codeOf(TtrpType.Decimal(12, 2)) shouldBe "decimal:12,2"
            SqlCastTypes.codeOf(TtrpType.Decimal(12)) shouldBe "decimal:12"
            SqlCastTypes.codeOf(TtrpType.Float) shouldBe "float"
            SqlCastTypes.codeOf(TtrpType.Double) shouldBe "float"
            SqlCastTypes.codeOf(TtrpType.Bool) shouldBe "bit"
            SqlCastTypes.codeOf(TtrpType.Date) shouldBe "date"
            SqlCastTypes.codeOf(TtrpType.Datetime) shouldBe "datetime"
            SqlCastTypes.codeOf(TtrpType.Timestamp) shouldBe "datetime2"
            SqlCastTypes.codeOf(TtrpType.Str) shouldBe "varchar:max"
        }

        "every schema column spelling has a cast code — the SQL spellings a generated model carries included" {
            SqlCastTypes.codeOf("bigint") shouldBe "bigint"
            SqlCastTypes.codeOf("smallint") shouldBe "smallint"
            SqlCastTypes.codeOf("numeric") shouldBe "decimal:19,2"
            SqlCastTypes.codeOf("decimal") shouldBe "decimal:19,2"
            SqlCastTypes.codeOf("money") shouldBe "decimal:19,4"
            SqlCastTypes.codeOf("time") shouldBe "time"
            SqlCastTypes.codeOf("text") shouldBe "varchar:max"
            SqlCastTypes.codeOf("varchar(40)") shouldBe "varchar:40"
            SqlCastTypes.codeOf("{type:decimal,length:12,precision:2}") shouldBe "decimal:12,2"
            SqlCastTypes.codeOf(TtrpType.Named("bigint")) shouldBe "bigint"
            shouldThrow<TtrpEmitException> { SqlCastTypes.codeOf("geography") }.message!! shouldContain "geography"
        }

        "a cast to a non-scalar type is a clear emit error, not a translator crash" {
            val ex = shouldThrow<TtrpEmitException> { PlanNodeBuilder().expr(cast("n", TtrpType.Lst)) }
            ex.message!! shouldContain "cast"
            ex.message!! shouldContain "list"
        }

        // End to end through the translator: int → text and int → decimal unparse for both dialects, and the
        // MSSQL text parses back with the translator's own SQL parser (the host door reads it that way).
        val table =
            ModelTable(
                qname =
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(
                            SchemaCode.DB,
                        ).setNamespace("dbo")
                        .setName("T")
                        .build(),
                columns = listOf(ModelColumn("n", SurfaceType.INT)),
            )
        val scan =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("T"),
                        ).addOutputColumns(PbColumnRef.newBuilder().setName("n")),
                ).build()

        fun projected(vararg casts: Pair<String, TtrpType>): PlanNode {
            val p = ProjectNode.newBuilder().setInput(scan)
            casts.forEach { (alias, t) ->
                p.addExpressions(
                    NamedExpression.newBuilder().setExpression(PlanNodeBuilder().expr(cast("n", t))).setAlias(alias),
                )
            }
            return PlanNode.newBuilder().setProject(p).build()
        }

        "MSSQL: cast(n as string) and cast(n as decimal(12,2)) unparse and parse back" {
            val sql =
                TranslatorFacade(IslandModelHandle(listOf(table)), SqlDialect.MSSQL)
                    .unparse(projected("s" to TtrpType.Str, "d" to TtrpType.Decimal(12, 2)))
            sql shouldContain "CAST([n] AS VARCHAR(MAX))"
            sql shouldContain "CAST([n] AS DECIMAL(12, 2))"
            val doorText = SqlTextRender.doubleQuoted(sql)
            withClue(doorText) { (SqlParser.parseQuery(doorText) is ParseResult.Success) shouldBe true }
        }

        "PostgreSQL: cast(n as string) unparses" {
            val sql =
                TranslatorFacade(IslandModelHandle(listOf(table)), SqlDialect.POSTGRESQL)
                    .unparse(projected("s" to TtrpType.Str, "i" to TtrpType.Integer))
            sql shouldContain "CAST(\"n\" AS VARCHAR"
            sql shouldContain "AS INTEGER)"
        }
    })
