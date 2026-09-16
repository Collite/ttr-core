// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * TF-P3.S2 (G C10, C11; contracts §5.2, §4.2) — the T-SQL function tail: `FORMAT`, `EOMONTH`,
 * `DATENAME`, `DATETRUNC`, `SYSDATETIME`, `SYSUTCDATETIME`, `GETUTCDATE`, `DATEFROMPARTS`, `ISNUMERIC`,
 * `NEWID`. Each case parses, validates, rides the plan.v1 wire and unparses to MSSQL as written.
 */
class TsqlTailOperatorsSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun plan(sql: String): PlanNode {
            val r = translator.parseToRelNode(sql, Language.SQL)
            r.shouldBeInstanceOf<ParseResult.Success>()
            return r.plan
        }

        // (select expression as written, MSSQL output)
        val cases =
            listOf(
                "FORMAT(a.ID, 'N0')" to "FORMAT([ID], 'N0')",
                "FORMAT(GETDATE(), 'yyyy-MM', 'cs-CZ')" to "FORMAT(GETDATE(), 'yyyy-MM', 'cs-CZ')",
                "EOMONTH(GETDATE())" to "EOMONTH(GETDATE())",
                "EOMONTH(GETDATE(), -1)" to "EOMONTH(GETDATE(), -1)",
                "DATENAME(month, GETDATE())" to "DATENAME(MONTH, GETDATE())",
                "DATENAME(weekday, GETDATE())" to "DATENAME(WEEKDAY, GETDATE())",
                "DATENAME(mm, GETDATE())" to "DATENAME(MONTH, GETDATE())",
                "DATETRUNC(month, GETDATE())" to "DATETRUNC(MONTH, GETDATE())",
                "DATETRUNC(dd, GETDATE())" to "DATETRUNC(DAY, GETDATE())",
                "SYSDATETIME()" to "SYSDATETIME()",
                "SYSUTCDATETIME()" to "SYSUTCDATETIME()",
                "GETUTCDATE()" to "GETUTCDATE()",
                "DATEFROMPARTS(2026, 1, 1)" to "DATEFROMPARTS(2026, 1, 1)",
                "ISNUMERIC(a.NAME)" to "ISNUMERIC([NAME])",
                "NEWID()" to "NEWID()",
            )
        for ((expr, rendered) in cases) {
            "$expr round-trips to $rendered" {
                val sql = "SELECT $expr AS X FROM A a"
                roundTrip(translator, sql, emptyList()) shouldBe
                    Outcome.Unparsed("SELECT $rendered AS [X] FROM [dbo].[A]")
            }
            "encode(decode(plan)) is byte-identical for $expr" {
                val p = plan("SELECT $expr AS X FROM A a")
                PlanNodeEncoder.encode(PlanNodeDecoder.decode(p, TranslatorFramework(FixtureModel.tfHandle()))) shouldBe
                    p
            }
        }

        // --- return types: each validates where its T-SQL type is required -----------------------

        "DATENAME is character: compares with a string" {
            roundTrip(
                translator,
                "SELECT a.ID FROM A a WHERE DATENAME(month, GETDATE()) = 'January'",
                emptyList(),
            ) shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE DATENAME(MONTH, GETDATE()) = 'January'")
        }

        "EOMONTH is a date: compares with a date cast" {
            roundTrip(
                translator,
                "SELECT a.ID FROM A a WHERE EOMONTH(GETDATE()) >= CAST(GETDATE() AS date)",
                emptyList(),
            ) shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE EOMONTH(GETDATE()) >= CAST(GETDATE() AS DATE)")
        }

        "DATEFROMPARTS is a date: compares with GETDATE()" {
            val r =
                translator.parseToRelNode(
                    "SELECT a.ID FROM A a WHERE DATEFROMPARTS(2026, 1, 1) < GETDATE()",
                    Language.SQL,
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "ISNUMERIC is an integer: compares with 1" {
            roundTrip(translator, "SELECT a.ID FROM A a WHERE ISNUMERIC(a.NAME) = 1", emptyList()) shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE ISNUMERIC([NAME]) = 1")
        }

        "the non-deterministic niladics are not folded" {
            for (fn in listOf("SYSDATETIME", "SYSUTCDATETIME", "GETUTCDATE", "NEWID")) {
                val op = FunctionCatalog.DEFAULT.lookup(fn.lowercase())
                op?.isDynamicFunction shouldBe true
                op?.isDeterministic shouldBe false
            }
        }
    })
