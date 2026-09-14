// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rel.RelNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.SqlDialect as SqlDialectProto
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.translator.wire.PlanNodeEncoder

/**
 * `EXTRACT(<unit> FROM <datetime>)` on SQL Server. The stock Calcite MSSQL dialect has no unparse
 * rule for it, so it went to the engine verbatim and died with error 195 (`'EXTRACT' is not a
 * recognized built-in function name`). [org.tatrman.translator.dialects.MssqlSqlDialectWithFloatCast]
 * now lowers it to `DATEPART(<part>, <datetime>)`; Postgres keeps the standard form.
 *
 * Both shapes the unit arrives in are covered: the validator's `TimeUnitRange` flag (direct unparse)
 * and the wire's `TimeUnit` flag (encode → decode → unparse, the path every service takes).
 */
class ExtractLoweringSpec :
    StringSpec({

        fun relOf(sql: String): RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        fun mssql(sql: String): String = RelToSqlUnparser.unparse(relOf(sql), SqlDialectProto.MSSQL)

        fun postgres(sql: String): String = RelToSqlUnparser.unparse(relOf(sql), SqlDialectProto.POSTGRESQL)

        fun roundTripMssql(sql: String): String {
            val plan = PlanNode.parseFrom(PlanNodeEncoder.encode(relOf(sql)).toByteArray())
            val freshFw = TranslatorFramework(FixtureModel.handle())
            return RelToSqlUnparser.unparse(PlanNodeDecoder.decode(plan, freshFw), SqlDialectProto.MSSQL)
        }

        "EXTRACT(MONTH FROM col) renders as DATEPART(MONTH, col) on MSSQL" {
            val sql = mssql("SELECT EXTRACT(MONTH FROM signup) AS m FROM customers")
            sql shouldContain "DATEPART(MONTH, [signup])"
            sql shouldNotContainIgnoringCase "EXTRACT"
        }

        "YEAR(col) is rewritten to EXTRACT by the validator and lowers the same way" {
            val sql = mssql("SELECT YEAR(signup) AS y FROM customers")
            sql shouldContain "DATEPART(YEAR, [signup])"
            sql shouldNotContainIgnoringCase "EXTRACT"
        }

        "the wire path (TimeUnit flag after decode) lowers identically" {
            val sql = roundTripMssql("SELECT EXTRACT(YEAR FROM signup) AS y FROM customers")
            sql shouldContain "DATEPART(YEAR, [signup])"
            sql shouldNotContainIgnoringCase "EXTRACT"
        }

        "EXTRACT in WHERE, CASE and GROUP BY — the free-SQL year-over-year shape — leaves no EXTRACT behind" {
            val sql =
                roundTripMssql(
                    "SELECT EXTRACT(MONTH FROM signup) AS m, " +
                        "SUM(CASE WHEN EXTRACT(YEAR FROM signup) = 2025 THEN 1 ELSE 0 END) AS y2025 " +
                        "FROM customers WHERE EXTRACT(YEAR FROM signup) IN (2025, 2026) " +
                        "GROUP BY EXTRACT(MONTH FROM signup) ORDER BY m",
                )
            sql shouldNotContainIgnoringCase "EXTRACT"
            sql shouldContainIgnoringCase "DATEPART(MONTH, [signup])"
            sql shouldContainIgnoringCase "DATEPART(YEAR, [signup])"
        }

        "units whose T-SQL name differs are mapped (DOY → DAYOFYEAR, DOW → WEEKDAY)" {
            mssql("SELECT EXTRACT(DOY FROM signup) AS d FROM customers") shouldContain "DATEPART(DAYOFYEAR, [signup])"
            mssql("SELECT EXTRACT(DOW FROM signup) AS d FROM customers") shouldContain "DATEPART(WEEKDAY, [signup])"
        }

        "a unit SQL Server cannot express fails at translate time, not at the engine" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    mssql("SELECT EXTRACT(EPOCH FROM signup) AS e FROM customers")
                }
            ex.message shouldContain "EPOCH"
        }

        "Postgres keeps the standard EXTRACT" {
            val sql = postgres("SELECT EXTRACT(MONTH FROM signup) AS m FROM customers")
            sql shouldContainIgnoringCase "EXTRACT(MONTH FROM"
            sql shouldNotContainIgnoringCase "DATEPART"
        }
    })
