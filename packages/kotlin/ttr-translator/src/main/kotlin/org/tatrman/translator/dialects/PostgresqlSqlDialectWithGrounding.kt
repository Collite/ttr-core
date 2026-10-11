// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.sql.SqlBasicTypeNameSpec
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlDataTypeSpec
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlWriter
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.validate.SqlConformance

/**
 * PostgreSQL dialect that lowers the platform grounding catalog functions (feature-grounding A6):
 * `period_start`/`period_end` -> `make_date` / `+ INTERVAL`, `geo_distance_m` -> PostGIS
 * `ST_Distance(...::geography)`. PostGIS is assumed present on PG targets; the geo capability probe
 * surfaces a clear error when it is not.
 *
 * Also writes `varchar(max)` as an unbounded `VARCHAR` ([getCastSpec]). Everything else defers to the stock
 * [PostgresqlSqlDialect]. Reach this only through the [Dialects] registry (Calcite engagement rule #1).
 */
class PostgresqlSqlDialectWithGrounding(
    context: SqlDialect.Context,
) : PostgresqlSqlDialect(context) {
    /** TF-P2.S2 — ORDER BY keys as expressions, so an order-only key does not leak a result column; see [SortByExpressionConformance]. */
    override fun getConformance(): SqlConformance = SortByExpressionConformance(super.getConformance())

    /**
     * `varchar(max)` (Calcite's VARCHAR ceiling, the wire's `varchar:max`) as an unbounded `VARCHAR`. The stock
     * dialect wrote the ceiling as a length, `VARCHAR(65536)`, and Postgres truncates an explicit cast to
     * `varchar(n)` silently, so every longer value was cut. A VARCHAR with no length is already unbounded on
     * Postgres and stays bare; every other type defers to the stock dialect.
     */
    override fun getCastSpec(type: RelDataType): SqlNode? =
        if (type.sqlTypeName == SqlTypeName.VARCHAR && type.precision >= VARCHAR_MAX_PRECISION) {
            SqlDataTypeSpec(SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, SqlParserPos.ZERO), SqlParserPos.ZERO)
        } else {
            super.getCastSpec(type)
        }

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
        private val VARCHAR_MAX_PRECISION: Int = RelDataTypeSystem.DEFAULT.getMaxPrecision(SqlTypeName.VARCHAR)

        @JvmField
        val DEFAULT: PostgresqlSqlDialectWithGrounding =
            PostgresqlSqlDialectWithGrounding(PostgresqlSqlDialect.DEFAULT_CONTEXT)
    }
}
