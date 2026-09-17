// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.TfParam
import org.tatrman.translator.tf.roundTrip

/**
 * TF-P2.S1 (G A4; contracts §3.5, ⚑TF-1 ruled `CONCAT`) — SQL Server has no `||`; the MSSQL dialect
 * renders it as `CONCAT(…)`, flattening a chain into one call.
 */
class MssqlConcatSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun mssql(
            sql: String,
            vararg params: TfParam,
        ): Outcome = roundTrip(translator, sql, params.toList())

        "a || b → CONCAT(a, b)" {
            mssql("SELECT 'a' || CAST(a.ID AS varchar(10)) AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('a', CAST([ID] AS VARCHAR(10))) AS [X] FROM [dbo].[A]")
        }

        "a three-way chain is flattened into one CONCAT call" {
            mssql("SELECT 'a' || a.NAME || 'b' AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('a', [NAME], 'b') AS [X] FROM [dbo].[A]")
        }

        "a parameter inside a LIKE pattern chain" {
            mssql("SELECT a.ID FROM A a WHERE a.NAME LIKE '%' || {q} || '%'", TfParam("q", "varchar")) shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] WHERE [NAME] LIKE CONCAT('%', ?, '%')")
        }

        "a parenthesised right-nested chain flattens left-to-right too" {
            mssql("SELECT 'a' || (a.NAME || 'b') AS X FROM A a") shouldBe
                Outcome.Unparsed("SELECT CONCAT('a', [NAME], 'b') AS [X] FROM [dbo].[A]")
        }
    })
