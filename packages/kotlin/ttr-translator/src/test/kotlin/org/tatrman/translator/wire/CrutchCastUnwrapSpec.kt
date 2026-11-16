// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.params.SqlParam

/**
 * review-318 F2 — the typed retry's validation crutch never reaches the executed SQL. For a declared type outside the
 * surface table the bridge writes `CAST(? AS VARCHAR)`; `ParameterTyper` must recognise that as the crutch (the same
 * type the bridge wrote), not compare it with its own `ANY` fallback, or the cast leaks into PG / MSSQL SQL (on MSSQL an
 * `nvarchar` value then passes the database code page). An author's cast to another type stays.
 */
class CrutchCastUnwrapSpec :
    StringSpec({
        val parity = ReentryParity(HartlandJoinModel.handle())

        val shapes =
            listOf(
                "concat" to "SELECT sale_month || {q} AS r FROM sale",
                "LIKE" to "SELECT sale_month FROM sale WHERE sale_month LIKE '%' || {q} || '%'",
            )

        for (type in listOf("nvarchar", "smallint", "time", "uuid")) {
            for ((shape, sql) in shapes) {
                "C — a `$type` parameter in $shape: no CAST in the PG or MSSQL SQL, either half" {
                    val q = listOf(SqlParam("q", type, "x"))
                    parity.sameSql(sql, q).forEach { (dialect, text) ->
                        withClue(dialect) { text shouldNotContain "CAST" }
                    }
                }
            }
        }

        "C — control: a `text` parameter in concat has no CAST either" {
            parity.sameSql(shapes.first().second, listOf(SqlParam("q", "text", "x"))).values.forEach {
                it shouldNotContain "CAST"
            }
        }

        "C — an author's CAST({q} AS VARCHAR(3)) over a `text` parameter keeps its cast, both halves" {
            val sql = parity.sameSql("SELECT CAST({q} AS VARCHAR(3)) AS r FROM sale", listOf(SqlParam("q", "text", "abcd")))
            sql.getValue(SqlDialect.POSTGRESQL) shouldContain "CAST(? AS VARCHAR(3))"
            sql.getValue(SqlDialect.MSSQL) shouldContain "CAST(? AS VARCHAR(3))"
        }
    })
