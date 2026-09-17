// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.tf.Outcome
import org.tatrman.translator.tf.roundTrip

/**
 * TF-P2.S2 (G A8; contracts §3.6) — ORDER BY output (MSSQL, plus the Postgres spelling of the same order): (1) T-SQL null collation on validation, so
 * a plain sort key renders as itself instead of behind a `CASE WHEN x IS NULL …` emulation prefix;
 * (2) an ORDER BY expression outside the select list keeps the result shape (no extra column).
 */
class OrderByShapeSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun mssql(sql: String): Outcome = roundTrip(translator, sql, emptyList())

        fun postgres(sql: String): String {
            val parsed = translator.parseToRelNode(sql, Language.SQL)
            parsed.shouldBeInstanceOf<ParseResult.Success>()
            val u = translator.unparseFromRelNode(parsed.plan, Language.SQL, SqlDialect.POSTGRESQL)
            u.shouldBeInstanceOf<UnparseResult.Success>()
            return u.output.replace(Regex("""\s+"""), " ").trim()
        }

        // --- null collation -----------------------------------------------------------------------

        "TOP + NOLOCK + ORDER BY renders the bare key" {
            mssql("SELECT TOP 5 a.ID FROM A a WITH (NOLOCK) ORDER BY a.ID") shouldBe
                Outcome.Unparsed("SELECT TOP (5) [ID] FROM [dbo].[A] WITH (NOLOCK) ORDER BY [ID]")
        }

        "ORDER BY … DESC renders the bare key" {
            mssql("SELECT a.ID FROM A a ORDER BY a.ID DESC") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] ORDER BY [ID] DESC")
        }

        "an explicit NULLS LAST that T-SQL does not do by default still gets the CASE emulation" {
            mssql("SELECT a.ID FROM A a ORDER BY a.ID NULLS LAST") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] ORDER BY CASE WHEN [ID] IS NULL THEN 1 ELSE 0 END, [ID]")
        }

        "an explicit NULLS FIRST that matches T-SQL's default needs no emulation" {
            mssql("SELECT a.ID FROM A a ORDER BY a.ID NULLS FIRST") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] ORDER BY [ID]")
        }

        "OFFSET … FETCH" {
            mssql("SELECT a.ID FROM A a ORDER BY a.ID OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] ORDER BY [ID] OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY")
        }

        "DISTINCT TOP (Calcite's GROUP BY form is kept)" {
            mssql("SELECT DISTINCT TOP 3 a.B_ID FROM A a ORDER BY a.B_ID") shouldBe
                Outcome.Unparsed("SELECT TOP (3) [B_ID] FROM [dbo].[A] GROUP BY [B_ID] ORDER BY [B_ID]")
        }

        // --- ORDER BY expressions keep the result shape -------------------------------------------

        "ORDER BY CASE … keeps exactly one result column" {
            mssql("SELECT a.ID FROM A a ORDER BY CASE WHEN a.NAME = 'x' THEN 0 ELSE 1 END, a.ID") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] ORDER BY CASE WHEN [NAME] = 'x' THEN 0 ELSE 1 END, [ID]")
        }

        "ORDER BY a function of a column not in the select list" {
            mssql("SELECT a.ID FROM A a ORDER BY LEN(a.NAME)") shouldBe
                Outcome.Unparsed("SELECT [ID] FROM [dbo].[A] ORDER BY LEN([NAME])")
        }

        "the pt-style hand-written null ordering is left as written" {
            mssql("SELECT a.ID FROM A a ORDER BY CASE WHEN a.B_ID IS NULL THEN 1 ELSE 0 END, a.NAME") shouldBe
                Outcome.Unparsed(
                    "SELECT [ID] FROM [dbo].[A] ORDER BY CASE WHEN [B_ID] IS NULL THEN 1 ELSE 0 END, [NAME]",
                )
        }

        // --- Postgres: the same source semantics, spelled natively --------------------------------

        "Postgres: the T-SQL null order is spelled out (PG sorts NULLs last ascending)" {
            postgres("SELECT a.ID FROM A a ORDER BY a.ID, a.NAME DESC") shouldBe
                "SELECT \"ID\" FROM \"A\" ORDER BY \"ID\" NULLS FIRST, \"NAME\" DESC NULLS LAST"
        }

        "Postgres: an ORDER BY expression keeps the result shape too" {
            postgres("SELECT a.ID FROM A a ORDER BY UPPER(a.NAME)") shouldBe
                "SELECT \"ID\" FROM \"A\" ORDER BY UPPER(\"NAME\") NULLS FIRST"
        }
    })
