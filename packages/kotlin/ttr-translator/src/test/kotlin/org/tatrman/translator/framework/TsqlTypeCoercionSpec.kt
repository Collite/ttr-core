// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * [TsqlTypeCoercion] — a string operand where a datetime-family operand is expected (T-SQL's
 * `DATEDIFF(day, '19000101', …)`) validates, is cast to TIMESTAMP, and the unseparated `'YYYYMMDD'` form
 * is normalised to ISO so the cast survives the wire and the decode-side constant fold. Found on
 * df-test 2026-09-21: the LLM lane's "previous calendar week" idiom died at `ParseToRelNode` with
 * `Cannot apply 'DATEDIFF' to arguments of type 'DATEDIFF(<INTERVAL DAY>, <CHAR(8)>, <DATE>)'`.
 *
 * Runs the service path (parse → plan.v1 → decode → unparse) so the second pass of query-runner's
 * two-pass translate is covered, not only the validator.
 */
class TsqlTypeCoercionSpec :
    StringSpec({
        val translator = Translator(FixtureModel.handle())

        fun parse(sql: String): ParseResult = translator.parseToRelNode(sql, Language.SQL)

        fun mssql(sql: String): String {
            val r = parse(sql)
            r.shouldBeInstanceOf<ParseResult.Success>()
            val u = translator.unparseFromRelNode(r.plan, Language.SQL, SqlDialect.MSSQL)
            u.shouldBeInstanceOf<UnparseResult.Success>()
            return u.output.replace(Regex("""\s+"""), " ").trim()
        }

        // The query as the LLM lane wrote it — Monday of last week to Monday of this week, anchored on
        // 1900-01-01 (a Monday) so it does not depend on the session's DATEFIRST.
        val lastWeek =
            "SELECT SUM(id) AS s FROM customers WHERE " +
                "signup >= DATEADD(day, -7, " +
                "DATEADD(day, (DATEDIFF(day, '19000101', CAST(GETDATE() AS date)) / 7) * 7, '19000101')) " +
                "AND signup < DATEADD(day, (DATEDIFF(day, '19000101', CAST(GETDATE() AS date)) / 7) * 7, '19000101')"

        "the previous-calendar-week idiom with '19000101' anchors translates end to end" {
            val out = mssql(lastWeek)
            out shouldContain "DATEDIFF(DAY, "
            out shouldNotContain "'19000101'"
            out shouldContain "'1900-01-01 00:00:00'"
            out shouldContain "DATEADD(DAY, -7, DATEADD(DAY, DATEDIFF(DAY, "
        }

        // The inserted CAST(<char literal> AS TIMESTAMP) is constant-folded on decode into a typed TIMESTAMP
        // literal, which the MSSQL unparse writes in the readable wire form (`'YYYY-MM-DD HH:MM:SS'`).
        "ISO string operands of DATEDIFF and DATEADD validate and become a datetime literal" {
            val out =
                mssql(
                    "SELECT DATEDIFF(day, '2026-09-14', signup) AS d, DATEADD(day, 7, '2026-09-14') AS e FROM customers",
                )
            out shouldContain "DATEDIFF(DAY, '2026-09-14 00:00:00', [signup])"
            out shouldContain "DATEADD(DAY, 7, '2026-09-14 00:00:00')"
        }

        "'YYYYMMDD' is rewritten to ISO before the cast, other shapes stay as written" {
            mssql("SELECT DATEADD(day, 1, '20260914') AS e FROM customers") shouldContain
                "DATEADD(DAY, 1, '2026-09-14 00:00:00')"
            mssql("SELECT DATEADD(day, 1, '2026-09-14 10:30:00') AS e FROM customers") shouldContain
                "DATEADD(DAY, 1, '2026-09-14 10:30:00')"
        }

        "a datetime column operand still gets no implicit cast (TF-P1.S1 guard)" {
            mssql("SELECT DATEDIFF(hour, signup, GETDATE()) AS d FROM customers") shouldContain
                "DATEDIFF(HOUR, [signup], GETDATE())"
        }

        "an integer anchor (DATEDIFF(wk, 0, …)) is still rejected — no silent int→datetime" {
            val r = parse("SELECT DATEADD(wk, DATEDIFF(wk, 0, GETDATE()) - 1, 0) AS d FROM customers")
            r.shouldBeInstanceOf<ParseResult.Failure>()
        }

        // Binary comparisons coerce through Calcite's own path (unchanged): same folded literal form.
        "plain comparisons are unaffected" {
            mssql(
                "SELECT SUM(id) AS s FROM customers WHERE signup >= '2026-09-14' AND signup < '2026-09-21'",
            ) shouldContain
                "[signup] >= '2026-09-14 00:00:00' AND [signup] < '2026-09-21 00:00:00'"
        }

        "isoDateOf accepts exactly eight digits" {
            TsqlTypeCoercion.isoDateOf("19000101") shouldBe "1900-01-01"
            TsqlTypeCoercion.isoDateOf(" 20260914 ") shouldBe "2026-09-14"
            TsqlTypeCoercion.isoDateOf("1900-01-01").shouldBeNull()
            TsqlTypeCoercion.isoDateOf("20260914 10:00").shouldBeNull()
            TsqlTypeCoercion.isoDateOf("2026091").shouldBeNull()
            TsqlTypeCoercion.isoDateOf(null).shouldBeNull()
        }
    })
