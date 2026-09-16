// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.validate.SqlConformance

/**
 * DuckDB Calcite [SqlDialect]. DuckDB's SQL surface for the constructs the unparser emits
 * (SELECT / projections / filters / joins / GROUP BY / ORDER BY / LIMIT / OFFSET / CTEs /
 * casts) is largely Postgres-compatible, so this dialect extends [PostgresqlSqlDialect] and
 * only overrides the database-product identifier.
 *
 * Calcite engagement rule #1 still applies: callers must reach DuckDB only through the
 * [Dialects] registry, never via a static singleton — keeps the constructor knobs in one place.
 *
 * Differences from PostgreSQL the unparser doesn't currently exercise (deferred until a
 * concrete round-trip test exposes them):
 *   - DuckDB accepts `GROUP BY ALL`, `SELECT * EXCLUDE (...)`, struct/list types — none of which
 *     the v1 unparser emits.
 *   - String concatenation, casts, function names are mostly identical to Postgres.
 *
 * If a test surfaces a real divergence, override the relevant `unparseXxx` method here rather
 * than special-casing in the unparser.
 */
class DuckDbSqlDialect(
    context: Context,
) : PostgresqlSqlDialect(context) {
    /** TF-P2.S2 — ORDER BY keys as expressions, so an order-only key does not leak a result column; see [SortByExpressionConformance]. */
    override fun getConformance(): SqlConformance = SortByExpressionConformance(super.getConformance())

    companion object {
        @JvmField
        val DEFAULT_CONTEXT: Context =
            PostgresqlSqlDialect.DEFAULT_CONTEXT
                .withDatabaseProduct(SqlDialect.DatabaseProduct.UNKNOWN)
                .withIdentifierQuoteString("\"")

        @JvmField
        val DEFAULT: DuckDbSqlDialect = DuckDbSqlDialect(DEFAULT_CONTEXT)
    }
}
