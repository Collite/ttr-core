// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlWriter
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.validate.SqlConformance

/**
 * PostgreSQL dialect that lowers the platform grounding catalog functions (feature-grounding A6):
 * `period_start`/`period_end` -> `make_date` / `+ INTERVAL`, `geo_distance_m` -> PostGIS
 * `ST_Distance(...::geography)`. PostGIS is assumed present on PG targets; the geo capability probe
 * surfaces a clear error when it is not.
 *
 * Everything else defers to the stock [PostgresqlSqlDialect]. Reach this only through the
 * [Dialects] registry (Calcite engagement rule #1).
 */
class PostgresqlSqlDialectWithGrounding(
    context: SqlDialect.Context,
) : PostgresqlSqlDialect(context) {
    /** TF-P2.S2 — ORDER BY keys as expressions, so an order-only key does not leak a result column; see [SortByExpressionConformance]. */
    override fun getConformance(): SqlConformance = SortByExpressionConformance(super.getConformance())

    override fun unparseCall(
        writer: SqlWriter,
        call: SqlCall,
        leftPrec: Int,
        rightPrec: Int,
    ) {
        val rendered = GroundingFunctionUnparse.render(this, call, GroundingFunctionUnparse.Flavor.POSTGRES)
        if (rendered != null) {
            writer.print(rendered)
            writer.setNeedWhitespace(true)
        } else {
            super.unparseCall(writer, call, leftPrec, rightPrec)
        }
    }

    companion object {
        @JvmField
        val DEFAULT: PostgresqlSqlDialectWithGrounding =
            PostgresqlSqlDialectWithGrounding(PostgresqlSqlDialect.DEFAULT_CONTEXT)
    }
}
