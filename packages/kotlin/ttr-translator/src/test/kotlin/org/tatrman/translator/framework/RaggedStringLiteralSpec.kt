// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.RelNode
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.codec.sql.RelToSqlUnparser
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.wire.Expressions
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.translator.wire.PlanNodeEncoder

/** D-231: ragged literal branches must never acquire CHAR padding, including on REL_NODE re-entry. */
class RaggedStringLiteralSpec :
    StringSpec({
        fun sqlRel(sql: String): RelNode {
            val result = SqlValidator.validateAndConvert(TranslatorFramework(FixtureModel.handle()).newPlanner(), sql)
            result.shouldBeInstanceOf<ValidateResult.Success>()
            return result.rel
        }

        fun check(
            rel: RelNode,
            dialect: SqlDialect,
        ) {
            val sql = RelToSqlUnparser.unparse(rel, dialect)
            rel.rowType.fieldList
                .single()
                .type.sqlTypeName shouldBe SqlTypeName.VARCHAR
            sql shouldContain "'a'"
            sql shouldContain "'to_customer_care'"
            sql.uppercase() shouldNotContain " AS CHAR("
            sql shouldNotContain "'a "
        }

        for (dialect in listOf(SqlDialect.POSTGRESQL, SqlDialect.MSSQL)) {
            for ((name, sql) in listOf(
                "CASE" to "SELECT CASE WHEN id = 1 THEN 'a' ELSE 'to_customer_care' END AS route FROM customers",
                "UNION ALL" to
                    "SELECT 'a' AS route FROM customers UNION ALL SELECT 'to_customer_care' AS route FROM customers",
            )) {
                "$name SQL literals are VARCHAR and unpadded on $dialect, also after REL_NODE re-entry" {
                    val rel = sqlRel(sql)
                    check(rel, dialect)
                    check(
                        PlanNodeDecoder.decode(PlanNodeEncoder.encode(rel), TranslatorFramework(FixtureModel.handle())),
                        dialect,
                    )
                }
            }

            "REL_NODE text CASE infers VARCHAR and keeps both literal values on $dialect" {
                val builder = TranslatorFramework(FixtureModel.handle()).newRelBuilder()
                builder.scan("customers")

                fun text(value: String): Expression =
                    Expression
                        .newBuilder()
                        .setLiteral(Literal.newBuilder().setType("text").setStringValue(value))
                        .build()
                val condition =
                    Expression
                        .newBuilder()
                        .setFunction(
                            FunctionCall
                                .newBuilder()
                                .setOperation("eq")
                                .addOperands(Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("id")))
                                .addOperands(Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(1))),
                        ).build()
                val expression =
                    Expression
                        .newBuilder()
                        .setFunction(
                            FunctionCall
                                .newBuilder()
                                .setOperation("case")
                                .addOperands(condition)
                                .addOperands(text("a"))
                                .addOperands(text("to_customer_care")),
                        ).setResultType("text")
                        .build()
                val decoded = Expressions.decode(builder, expression)
                builder.project(listOf(decoded), listOf("route"))
                val rel = builder.build()
                val rendered = RelToSqlUnparser.unparse(rel, dialect)
                decoded.type.sqlTypeName shouldBe SqlTypeName.VARCHAR
                rendered shouldContain "'a'"
                rendered shouldNotContain "'a "
                rendered.uppercase() shouldNotContain " AS CHAR("
            }
        }

        "equal typed CHAR column branches remain CHAR(21)" {
            val rel =
                sqlRel(
                    "SELECT CASE WHEN id = 1 THEN c ELSE d END AS route FROM " +
                        "(SELECT id, CAST(name AS CHAR(21)) AS c, CAST(name AS CHAR(21)) AS d FROM customers) t",
                )
            rel.rowType.fieldList
                .single()
                .type.sqlTypeName shouldBe SqlTypeName.CHAR
            rel.rowType.fieldList
                .single()
                .type.precision shouldBe 21
        }
    })
