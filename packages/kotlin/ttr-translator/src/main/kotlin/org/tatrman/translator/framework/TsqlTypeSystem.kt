// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.apache.calcite.rel.type.RelDataTypeSystemImpl
import org.apache.calcite.sql.type.SqlTypeName

/**
 * TF-P3.S1 (G C9; contracts §3.6) — Calcite's default type system with T-SQL's fractional-second
 * ceiling. Calcite caps TIME/TIMESTAMP precision at 3 (`SqlTypeName.MAX_DATETIME_PRECISION`) and
 * clamps silently, so `datetime2` (= `datetime2(7)`) became TIMESTAMP(3) — the same Calcite type as
 * `datetime` — and was re-spelled `DATETIME` on unparse (range 1753+ and 3.33 ms rounding). T-SQL
 * `datetime2` and `time` go to 7.
 */
object TsqlTypeSystem : RelDataTypeSystemImpl() {
    /** T-SQL `datetime2(7)` / `time(7)`. */
    private const val MAX_FRACTIONAL_SECOND_PRECISION = 7

    override fun getMaxPrecision(typeName: SqlTypeName): Int =
        when (typeName) {
            SqlTypeName.TIME, SqlTypeName.TIMESTAMP -> MAX_FRACTIONAL_SECOND_PRECISION
            else -> super.getMaxPrecision(typeName)
        }
}
