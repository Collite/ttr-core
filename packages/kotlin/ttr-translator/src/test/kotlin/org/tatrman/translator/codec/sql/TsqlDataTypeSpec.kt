// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.sql.type.SqlTypeFactoryImpl
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TsqlTypeSystem
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.roundTrip

/**
 * TF-P3.S1 (G C9; contracts §4.1, §3.3, §3.5) — T-SQL type names in `CAST` / `CONVERT` / `TRY_CONVERT`.
 *
 * The parser accepts the T-SQL spelling; the validated Calcite type rides the wire (`varchar:30`,
 * `decimal:19,4`, …) and the MSSQL dialect re-spells from that type alone, so `nvarchar(30)` comes
 * back as `VARCHAR(30)` and `money` as `DECIMAL(19, 4)` (⚑TF-6: the authored spelling is not kept).
 */
class TsqlDataTypeSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun plan(sql: String): PlanNode {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        /** The result_type of the first `cast` in the plan's top Project. */
        fun castCode(p: PlanNode): String? {
            fun find(e: Expression): String? =
                when {
                    !e.hasFunction() -> null
                    e.function.operation == "cast" -> e.resultType
                    else -> e.function.operandsList.firstNotNullOfOrNull(::find)
                }
            return p.project.expressionsList.firstNotNullOfOrNull { find(it.expression) }
        }

        // (column, T-SQL type as authored, wire code, MSSQL spelling)
        val cases =
            listOf(
                listOf("ID", "nvarchar(30)", "varchar:30", "VARCHAR(30)"),
                listOf("ID", "NVARCHAR", "varchar:30", "VARCHAR(30)"),
                listOf("ID", "nchar(10)", "char:10", "CHAR(10)"),
                listOf("ID", "nchar", "char:1", "CHAR(1)"),
                listOf("ID", "nvarchar(max)", "varchar:max", "VARCHAR(MAX)"),
                listOf("ID", "varchar(max)", "varchar:max", "VARCHAR(MAX)"),
                listOf("ID", "varchar(MAX)", "varchar:max", "VARCHAR(MAX)"),
                listOf("NAME", "text", "varchar:max", "VARCHAR(MAX)"),
                listOf("NAME", "ntext", "varchar:max", "VARCHAR(MAX)"),
                listOf("ID", "money", "decimal:19,4", "DECIMAL(19, 4)"),
                listOf("ID", "smallmoney", "decimal:10,4", "DECIMAL(10, 4)"),
                listOf("NAME", "datetime", "datetime", "DATETIME"),
                listOf("NAME", "datetime2", "datetime2:7", "DATETIME2(7)"),
                listOf("NAME", "datetime2(0)", "datetime2:0", "DATETIME2(0)"),
                listOf("NAME", "smalldatetime", "datetime2:0", "DATETIME2(0)"),
                listOf("NAME", "bit", "bit", "BIT"),
                listOf("NAME", "uniqueidentifier", "char:36", "CHAR(36)"),
                listOf("ID", "numeric(10,2)", "decimal:10,2", "DECIMAL(10, 2)"),
            )
        for ((column, tsql, code, spelled) in cases) {
            "CAST(… AS $tsql) rides as '$code' and unparses as $spelled" {
                val sql = "SELECT CAST(a.$column AS $tsql) AS X FROM A a"
                castCode(plan(sql)) shouldBe code
                roundTrip(translator, sql, emptyList()) shouldBe
                    Outcome.Unparsed("SELECT CAST([$column] AS $spelled) AS [X] FROM [dbo].[A]")
            }
        }

        "datetime2(3) is TIMESTAMP(3), which re-spells as DATETIME (⚑TF-6: the two share a Calcite type)" {
            val sql = "SELECT CAST(a.NAME AS datetime2(3)) AS X FROM A a"
            castCode(plan(sql)) shouldBe "datetime"
            roundTrip(translator, sql, emptyList()) shouldBe
                Outcome.Unparsed("SELECT CAST([NAME] AS DATETIME) AS [X] FROM [dbo].[A]")
        }

        "a type argument the T-SQL type does not take is a parse error" {
            val r = translator.parseToRelNode("SELECT CAST(a.ID AS money(5)) AS X FROM A a", Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Failure>()
            r.message shouldContain "Unknown datatype name 'MONEY(5)'"
        }

        "Calcite clamps TIMESTAMP precision to 3; TsqlTypeSystem keeps datetime2's 7" {
            SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT).createSqlType(SqlTypeName.TIMESTAMP, 7).precision shouldBe 3
            SqlTypeFactoryImpl(TsqlTypeSystem).createSqlType(SqlTypeName.TIMESTAMP, 7).precision shouldBe 7
        }

        "the T-SQL names stay usable as column aliases" {
            roundTrip(translator, "SELECT a.ID AS money, a.NAME AS text FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [ID] AS [money], [NAME] AS [text] FROM [dbo].[A]")
        }

        // --- CONVERT / TRY_CONVERT keep the type text as written (ConvertOperators) -------------

        "CONVERT(nvarchar(20), …) parses and keeps its type" {
            roundTrip(translator, "SELECT CONVERT(nvarchar(20), a.ID) AS X FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT CONVERT(NVARCHAR(20), [ID]) AS [X] FROM [dbo].[A]")
        }

        "TRY_CONVERT(money, …) parses and keeps its type" {
            roundTrip(translator, "SELECT TRY_CONVERT(money, a.NAME) AS X FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT TRY_CONVERT(MONEY, [NAME]) AS [X] FROM [dbo].[A]")
        }

        "CONVERT(varchar(max), …, 120) keeps MAX and the style" {
            roundTrip(translator, "SELECT CONVERT(varchar(max), a.NAME, 120) AS X FROM A a", emptyList()) shouldBe
                Outcome.Unparsed("SELECT CONVERT(VARCHAR(MAX), [NAME], 120) AS [X] FROM [dbo].[A]")
        }
    })
