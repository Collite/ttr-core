// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.sql.ExplicitOperatorBinding
import org.apache.calcite.sql.SqlBasicCall
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.type.SqlTypeFactoryImpl
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.roundTrip
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.translator.wire.PlanNodeEncoder

/**
 * TF-P2.S1 (G A2, C11; contracts §3.4) — T-SQL string `+` and `<datetime> ± <integer>`: swapped to
 * [TsqlPlusOperator]/[TsqlMinusOperator] before validation, lowered by operand type after `SqlToRel`.
 */
class TsqlPlusSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())
        val typeFactory = SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT)

        fun mssql(sql: String): Outcome = roundTrip(translator, sql, emptyList())

        fun plan(sql: String): PlanNode {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        // --- unit: the shuttle -------------------------------------------------------------------

        "the shuttle swaps binary + and - and recurses into nested operands" {
            val parsed = SqlParser.create("'a' + (1 - 2)").parseExpression()
            val swapped = parsed.accept(TsqlArithmeticShuttle.rewriter()) as SqlCall
            swapped.operator shouldBeSameInstanceAs TsqlPlusOperator
            (swapped.operandList[1] as SqlCall).operator shouldBeSameInstanceAs TsqlMinusOperator
        }

        "the shuttle leaves unary signs and other operators alone" {
            val parsed = SqlParser.create("-x * +y").parseExpression()
            val swapped = parsed.accept(TsqlArithmeticShuttle.rewriter()) as SqlBasicCall
            swapped.operator shouldBeSameInstanceAs SqlStdOperatorTable.MULTIPLY
            (swapped.operandList[0] as SqlCall).operator shouldBeSameInstanceAs SqlStdOperatorTable.UNARY_MINUS
        }

        // --- unit: return-type inference --------------------------------------------------------

        fun inferPlus(
            left: SqlTypeName,
            right: SqlTypeName,
        ) = TsqlPlusOperator.inferReturnType(
            ExplicitOperatorBinding(
                typeFactory,
                TsqlPlusOperator,
                listOf(typeFactory.createSqlType(left), typeFactory.createSqlType(right)),
            ),
        )

        "inference: a character operand makes + a VARCHAR" {
            inferPlus(SqlTypeName.VARCHAR, SqlTypeName.INTEGER).sqlTypeName shouldBe SqlTypeName.VARCHAR
            inferPlus(SqlTypeName.INTEGER, SqlTypeName.CHAR).sqlTypeName shouldBe SqlTypeName.VARCHAR
        }

        "inference: numeric + numeric is PLUS's numeric type" {
            inferPlus(SqlTypeName.INTEGER, SqlTypeName.INTEGER).sqlTypeName shouldBe SqlTypeName.INTEGER
            inferPlus(SqlTypeName.INTEGER, SqlTypeName.BIGINT).sqlTypeName shouldBe SqlTypeName.BIGINT
        }

        "inference: timestamp + integer stays a timestamp" {
            inferPlus(SqlTypeName.TIMESTAMP, SqlTypeName.INTEGER).sqlTypeName shouldBe SqlTypeName.TIMESTAMP
            inferPlus(SqlTypeName.INTEGER, SqlTypeName.DATE).sqlTypeName shouldBe SqlTypeName.DATE
        }

        // --- component: MSSQL output ------------------------------------------------------------

        "string + chain → one CONCAT (G A2, q10)" {
            mssql("SELECT 'Skladem (' + CAST(a.ID AS varchar(20)) + ' ks)' AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('Skladem (', CAST([ID] AS VARCHAR(20)), ' ks)') AS [X] FROM [dbo].[A]")
        }

        "numeric + is untouched" {
            mssql("SELECT a.ID + 1 AS X FROM A a") shouldBe Outcome.Unparsed("SELECT [ID] + 1 AS [X] FROM [dbo].[A]")
        }

        "a cast to a number keeps + numeric" {
            mssql("SELECT CAST(a.NAME AS int) + 1 AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CAST([NAME] AS INTEGER) + 1 AS [X] FROM [dbo].[A]")
        }

        "the pt05 shape: literal + varchar cast" {
            mssql("SELECT 'Stav ' + CAST(a.ID AS varchar(5)) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('Stav ', CAST([ID] AS VARCHAR(5))) AS [X] FROM [dbo].[A]")
        }

        "two text columns concatenate" {
            mssql("SELECT a.NAME + a.NAME AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT([NAME], [NAME]) AS [X] FROM [dbo].[A]")
        }

        "text + number concatenates with a VARCHAR cast (lenient: T-SQL would try a numeric conversion)" {
            mssql("SELECT 'x' + a.ID AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('x', CAST([ID] AS VARCHAR)) AS [X] FROM [dbo].[A]")
        }

        "numeric - is untouched" {
            mssql("SELECT a.ID - 1 AS X FROM A a") shouldBe Outcome.Unparsed("SELECT [ID] - 1 AS [X] FROM [dbo].[A]")
        }

        "datetime - integer → DATEADD(DAY, -n, d) (G C11, s7)" {
            mssql("SELECT a.ID FROM A a WHERE GETDATE() - 30 > GETDATE()") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE DATEADD(DAY, -30, GETDATE()) > GETDATE()")
        }

        "datetime + integer and integer + datetime → DATEADD(DAY, n, d)" {
            mssql("SELECT a.ID FROM A a WHERE GETDATE() + 7 > GETDATE() AND 1 + GETDATE() > GETDATE()") shouldBe
                Outcome.Unparsed(
                    "SELECT [ID] FROM [dbo].[A] WHERE DATEADD(DAY, 7, GETDATE()) > GETDATE() AND DATEADD(DAY, 1, GETDATE()) > GETDATE()",
                )
        }

        "datetime - a non-literal integer negates the expression" {
            mssql("SELECT a.ID FROM A a WHERE GETDATE() - a.ID > GETDATE()") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE DATEADD(DAY, - [ID], GETDATE()) > GETDATE()")
        }

        "a string + inside a sub-query is lowered too" {
            mssql("SELECT a.ID FROM A a WHERE a.NAME IN (SELECT b.NAME + 'x' FROM B b)") shouldBe
                Outcome.Unparsed(
                    "SELECT [ID] FROM [dbo].[A] WHERE [NAME] IN (SELECT CONCAT([NAME], 'x') FROM [dbo].[B])",
                )
        }

        "shapes the standard operators reject still fail validation" {
            val r = translator.parseToRelNode("SELECT a.ID FROM A a WHERE GETDATE() - 'x' > GETDATE()", Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.code shouldBe "validation_failed"
            r.message shouldContain "Cannot apply '-'"
        }

        // --- idempotency: the wire carries no T-SQL operator ------------------------------------

        val stable =
            listOf(
                "SELECT 'Skladem (' + CAST(a.ID AS varchar(20)) + ' ks)' AS X FROM A a",
                "SELECT a.ID FROM A a WHERE GETDATE() - 30 > GETDATE()",
                "SELECT a.ID + 1 AS X FROM A a",
            )
        for (sql in stable) {
            "encode(decode(plan)) is byte-identical for <$sql>" {
                val p = plan(sql)
                PlanNodeEncoder.encode(PlanNodeDecoder.decode(p, TranslatorFramework(FixtureModel.tfHandle()))) shouldBe
                    p
            }
        }
    })
