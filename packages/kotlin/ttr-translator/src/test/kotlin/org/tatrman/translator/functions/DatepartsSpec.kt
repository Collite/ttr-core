// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.avatica.util.TimeUnit
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.roundTrip

/**
 * TF-P3.S2 (G C11; contracts §5.1, §3.5) — the T-SQL `weekday` / `dayofyear` dateparts and their
 * abbreviations map to Calcite's DOW / DOY, and every DATE* call spells the unit back in T-SQL
 * (`WEEKDAY`, `DAYOFYEAR`) instead of Calcite's `DOW` / `DOY`.
 */
class DatepartsSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun mssql(sql: String): Outcome = roundTrip(translator, sql, emptyList())

        "weekday, dw and w are DOW; dayofyear, dy and y are DOY" {
            for (name in listOf("weekday", "dw", "w", "WEEKDAY")) Dateparts.toTimeUnit(name) shouldBe TimeUnit.DOW
            for (name in listOf("dayofyear", "dy", "y")) Dateparts.toTimeUnit(name) shouldBe TimeUnit.DOY
        }

        "iso_week stays unmapped" {
            Dateparts.toTimeUnit("iso_week") shouldBe null
        }

        "DATEPART(weekday, …) → DATEPART(WEEKDAY, …)" {
            mssql("SELECT DATEPART(weekday, GETDATE()) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT DATEPART(WEEKDAY, GETDATE()) AS [X] FROM [dbo].[A]")
        }

        "DATEPART(dy, …) → DATEPART(DAYOFYEAR, …)" {
            mssql("SELECT DATEPART(dy, GETDATE()) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT DATEPART(DAYOFYEAR, GETDATE()) AS [X] FROM [dbo].[A]")
        }

        "DATEADD / DATEDIFF with weekday or dayofyear count days, as T-SQL documents → DAY" {
            mssql("SELECT DATEADD(dw, 1, GETDATE()) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT DATEADD(DAY, 1, GETDATE()) AS [X] FROM [dbo].[A]")
            mssql("SELECT DATEDIFF(dayofyear, GETDATE(), GETDATE()) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT DATEDIFF(DAY, GETDATE(), GETDATE()) AS [X] FROM [dbo].[A]")
        }

        "DATEPART keeps weekday distinct from day" {
            mssql("SELECT DATEPART(w, GETDATE()) AS X, DATEPART(d, GETDATE()) AS Y FROM A a") shouldBe
                Outcome.Unparsed(
                    "SELECT DATEPART(WEEKDAY, GETDATE()) AS [X], DATEPART(DAY, GETDATE()) AS [Y] FROM [dbo].[A]",
                )
        }

        "DATEDIFF(dd, …) is unchanged" {
            mssql("SELECT DATEDIFF(dd, GETDATE(), GETDATE()) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT DATEDIFF(DAY, GETDATE(), GETDATE()) AS [X] FROM [dbo].[A]")
        }

        "DATEPART(iso_week, …) is still a validation error (documented unsupported)" {
            val r = mssql("SELECT DATEPART(iso_week, GETDATE()) AS X FROM A a")
            r.shouldBeInstanceOf<Outcome.ParseFailed>()
            r.code shouldBe "validation_failed"
            r.message shouldContain "'iso_week' is not a valid time frame"
        }
    })
