// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlCharStringLiteral
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.type.SqlTypeFamily
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorScope
import org.apache.calcite.sql.validate.implicit.TypeCoercionFactory
import org.apache.calcite.sql.validate.implicit.TypeCoercionImpl

/**
 * Implicit type coercion with T-SQL's string→datetime rule for function operands.
 *
 * SQL Server converts a string operand to `datetime` wherever a datetime is expected —
 * `DATEDIFF(day, '19000101', GETDATE())`, `DATEADD(day, 7, '2026-09-14')` — and the LLM lane writes
 * the canonical "previous calendar week" idiom exactly that way. Calcite's `TypeCoercionImpl`
 * coerces a character operand only into the `DATE`, `TIME` and `TIMESTAMP` families; our
 * `DATEDIFF` ([org.tatrman.translator.functions.DateOperators.DATEDIFF], TF-P1.S1) and Calcite's
 * own `DATEADD` declare their operands as the wider `DATETIME` family precisely so a `datetime`
 * column is not cast down to `DATE` — and for that family Calcite inserts no cast at all, so the
 * call fails validation with `Cannot apply 'DATEDIFF' to arguments of type '… <CHAR(8)> …'`.
 *
 * Two additions, both scoped to a character operand where a `DATETIME`-family operand is expected:
 * 1. [implicitCast] coerces it to `TIMESTAMP` (the family's default concrete type — SQL Server's
 *    `datetime`, not `date`, is what the string would have become there too). Binary comparisons
 *    (`col >= '2026-09-14'`) already coerce through a different path and are untouched.
 * 2. [coerceOperandType] rewrites T-SQL's unseparated `'YYYYMMDD'` literal to ISO `'YYYY-MM-DD'`
 *    before the cast goes in. SQL Server accepts both; Calcite's date parser
 *    (`DateTimeUtils.dateStringToUnixDate`) accepts only the dashed form, and the second pass of
 *    query-runner's two-pass translate constant-folds `CAST(<literal> AS TIMESTAMP)` through it
 *    (see [org.tatrman.translator.wire.Expressions] on typed temporal literals). Any other shape
 *    (`'2026-09-21 10:00'`, `'20260921 10:00'`) rides through unchanged.
 */
class TsqlTypeCoercion(
    typeFactory: RelDataTypeFactory,
    validator: SqlValidator,
) : TypeCoercionImpl(typeFactory, validator) {
    override fun implicitCast(
        `in`: RelDataType,
        expected: SqlTypeFamily,
    ): RelDataType? {
        if (expected == SqlTypeFamily.DATETIME && SqlTypeUtil.isCharacter(`in`)) {
            return expected.getDefaultConcreteType(factory)
        }
        return super.implicitCast(`in`, expected)
    }

    override fun coerceOperandType(
        scope: SqlValidatorScope?,
        call: SqlCall,
        index: Int,
        targetType: RelDataType,
    ): Boolean {
        val operand = call.operandList[index]
        if (SqlTypeUtil.isDatetime(targetType) && operand is SqlCharStringLiteral) {
            val iso = isoDateOf(operand.toValue())
            if (iso != null) {
                call.setOperand(index, SqlLiteral.createCharString(iso, operand.parserPosition))
            }
        }
        return super.coerceOperandType(scope, call, index, targetType)
    }

    companion object {
        /** `SqlValidator.Config.withTypeCoercionFactory` binding. */
        val FACTORY: TypeCoercionFactory =
            TypeCoercionFactory {
                typeFactory,
                validator,
                ->
                TsqlTypeCoercion(typeFactory, validator)
            }

        private val COMPACT_DATE = Regex("""^(\d{4})(\d{2})(\d{2})$""")

        /** `'YYYYMMDD'` → `'YYYY-MM-DD'`; null for any other text (left as written). */
        internal fun isoDateOf(text: String?): String? {
            val m = COMPACT_DATE.matchEntire(text?.trim() ?: return null) ?: return null
            val (y, mo, d) = m.destructured
            return "$y-$mo-$d"
        }
    }
}
