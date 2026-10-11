// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.resolve.ColumnType

/**
 * The translator's cast target codes for TTR-P types. A cast rides `plan.v1` in the translator's FUNCTION
 * form — `FunctionCall(operation = "cast")` with the target as the wrapping expression's `result_type` — and
 * the translator reads that result type as a physical code `<kind>[:<precision>[,<scale>]]` (`varchar:max`,
 * `decimal:12,2`, `int`, `date`, `datetime`, …; contracts §3.3 of the translator). (The `Expression.cast`
 * oneof is NOT decoded by the translator — lowering to it was the `CastExpression decoding is TODO` failure.)
 *
 * Text is `varchar:max` (T-SQL `VARCHAR(MAX)`): an unsized `VARCHAR` is `VARCHAR(30)` in SQL Server, which
 * truncates long strings and overflows on wide numerics. Object, list and a custom type id the column vocabulary
 * ([ColumnType]) does not know have no SQL cast.
 */
object SqlCastTypes {
    fun codeOf(t: TtrpType): String =
        when (t) {
            is TtrpType.Integer -> "int"
            is TtrpType.Decimal ->
                when {
                    t.precision == null -> "decimal"
                    t.scale == null -> "decimal:${t.precision}"
                    else -> "decimal:${t.precision},${t.scale}"
                }
            is TtrpType.Float, is TtrpType.Double -> "float"
            is TtrpType.Number -> "decimal"
            is TtrpType.Bool -> "bit"
            is TtrpType.Str -> "varchar:max"
            is TtrpType.Date -> "date"
            is TtrpType.Datetime -> "datetime"
            is TtrpType.Timestamp -> "datetime2"
            // A custom type id that is a SQL spelling the column vocabulary knows (`bigint`, `money`, …).
            is TtrpType.Named -> ColumnType.parse(t.name)?.let { codeOf(it) } ?: noCast(t.canonical)
            is TtrpType.Obj, is TtrpType.Lst -> noCast(t.canonical)
        }

    /**
     * The cast code for a column type — every scalar a TTR-M schema column can declare: text (`varchar:max`, or
     * `varchar:<n>` with a length), `int` / `bigint` / `smallint` / `tinyint`, `decimal:<p>,<s>` (a decimal always
     * carries its precision — [ColumnType]'s default when undeclared), `float`, `real`, `bit`, `date`, `time`,
     * `datetime`, `datetime2`.
     */
    fun codeOf(t: ColumnType): String =
        when (t.kind) {
            ColumnType.Kind.TEXT -> t.length?.let { "varchar:$it" } ?: "varchar:max"
            ColumnType.Kind.INT -> "int"
            ColumnType.Kind.BIGINT -> "bigint"
            ColumnType.Kind.SMALLINT -> "smallint"
            ColumnType.Kind.TINYINT -> "tinyint"
            ColumnType.Kind.DECIMAL -> "decimal:${t.precision},${t.scale}"
            ColumnType.Kind.FLOAT -> "float"
            ColumnType.Kind.REAL -> "real"
            ColumnType.Kind.BOOL -> "bit"
            ColumnType.Kind.DATE -> "date"
            ColumnType.Kind.TIME -> "time"
            ColumnType.Kind.DATETIME -> "datetime"
            ColumnType.Kind.TIMESTAMP -> "datetime2"
        }

    /**
     * The cast code for a TTR-M / TTR-P type spelling — bare (`text`, `bigint`, `money`), TTR-P (`decimal(12,2)`)
     * or the TTR-M structured form (`{ type: decimal, length: 12, precision: 2 }`); see [ColumnType.parse].
     */
    fun codeOf(spelling: String): String = ColumnType.parse(spelling)?.let { codeOf(it) } ?: noCast(spelling)

    private fun noCast(type: String): Nothing =
        throw TtrpEmitException(
            EmitDiagnosticId.UNSUPPORTED_NODE,
            detail =
                "no SQL cast to `$type` — cast to a scalar type " +
                    "(text, int, bigint, decimal, float, bool, date, time, datetime, timestamp)",
        )
}
