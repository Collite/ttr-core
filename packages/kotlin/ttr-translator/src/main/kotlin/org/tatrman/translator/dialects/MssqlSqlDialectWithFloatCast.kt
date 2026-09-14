// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.dialects

import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.avatica.util.TimeUnitRange
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.SqlAlienSystemTypeNameSpec
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlDataTypeSpec
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlHint
import org.apache.calcite.sql.SqlIntervalQualifier
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlNodeList
import org.apache.calcite.sql.SqlWriter
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName

/**
 * MS SQL Server dialect that fixes CAST target type names Calcite renders with
 * ANSI names SQL Server rejects.
 *
 * Calcite's [MssqlSqlDialect] emits `CAST(... AS DOUBLE)` for [SqlTypeName.DOUBLE],
 * but SQL Server has no `DOUBLE` type — its 8-byte double-precision type is
 * `FLOAT`. The bad cast produced `Incorrect syntax near ')'` at the database.
 * We override [getCastSpec] to render `DOUBLE` as `FLOAT`; every other type
 * defers to the stock dialect.
 *
 * Also lowers the standard `EXTRACT(<unit> FROM <datetime>)` to T-SQL `DATEPART(<part>, <datetime>)`
 * — SQL Server has no `EXTRACT` (error 195 `'EXTRACT' is not a recognized built-in function name`),
 * and Calcite's stock [MssqlSqlDialect] has no unparse rule for it (FLOOR/MOD/SAFE_CAST only), so the
 * call went out verbatim. Every date-part route lands on this node: `YEAR(x)`/`MONTH(x)` are rewritten
 * to `EXTRACT` by Calcite's validator (`SqlDatePartFunction.rewriteCall`), the MD dot-path viaCalc
 * lowering emits it directly, and a free-SQL planner writes it as the portable form.
 *
 * Reach this only through the [Dialects] registry (Calcite engagement rule #1).
 */
class MssqlSqlDialectWithFloatCast(
    context: SqlDialect.Context,
) : MssqlSqlDialect(context) {
    override fun getCastSpec(type: RelDataType): SqlNode? =
        when (type.sqlTypeName) {
            SqlTypeName.DOUBLE ->
                SqlDataTypeSpec(
                    SqlAlienSystemTypeNameSpec("FLOAT", type.sqlTypeName, SqlParserPos.ZERO),
                    SqlParserPos.ZERO,
                )
            else -> super.getCastSpec(type)
        }

    // RG-P3 — lower the platform grounding functions to MSSQL-native SQL (DATEFROMPARTS/DATEADD,
    // geography::Point.STDistance); everything else defers to the stock dialect.
    override fun unparseCall(
        writer: SqlWriter,
        call: SqlCall,
        leftPrec: Int,
        rightPrec: Int,
    ) {
        val rendered = GroundingFunctionUnparse.render(this, call, GroundingFunctionUnparse.Flavor.MSSQL)
        if (rendered != null) {
            writer.print(rendered)
            writer.setNeedWhitespace(true)
        } else if (call.kind == SqlKind.EXTRACT) {
            unparseExtract(writer, call)
        } else {
            super.unparseCall(writer, call, leftPrec, rightPrec)
        }
    }

    /**
     * `EXTRACT(<unit> FROM <datetime>)` → `DATEPART(<part>, <datetime>)`.
     *
     * The unit reaches us in one of two shapes: a [SqlIntervalQualifier] straight from the SQL parser,
     * or a SYMBOL [SqlLiteral] rebuilt from a `RexLiteral` flag — a [TimeUnitRange] when the RelNode
     * came from the validator, a [TimeUnit] when it came over the plan.v1 wire (`Expressions.decodeLiteral`
     * rebuilds every `symbol:*` tag as a `TimeUnit`). Units with no T-SQL datepart (EPOCH, DECADE,
     * CENTURY, MILLENNIUM, ISOYEAR, ISODOW) fail at translate time with a clear message rather than at
     * the engine — the same policy as an unsupported period `code_format` in [GroundingFunctionUnparse].
     */
    private fun unparseExtract(
        writer: SqlWriter,
        call: SqlCall,
    ) {
        val unit = extractUnit(call.operandList[0])
        val part =
            DATEPART_OF[unit]
                ?: throw IllegalArgumentException(
                    "EXTRACT unit $unit has no SQL Server DATEPART equivalent (supported: ${DATEPART_OF.keys.joinToString()})",
                )
        val frame = writer.startFunCall("DATEPART")
        writer.keyword(part)
        writer.sep(",", true)
        call.operandList[1].unparse(writer, 0, 0)
        writer.endFunCall(frame)
    }

    private fun extractUnit(node: SqlNode): TimeUnit =
        when (node) {
            is SqlIntervalQualifier -> node.timeUnitRange.startUnit
            is SqlLiteral ->
                when (val value = node.value) {
                    is TimeUnitRange -> value.startUnit
                    is TimeUnit -> value
                    else -> throw IllegalArgumentException("EXTRACT unit is not a time unit: $node")
                }
            else -> throw IllegalArgumentException("EXTRACT unit is not a time unit: $node")
        }

    private companion object {
        /** Calcite [TimeUnit] → T-SQL `DATEPART` part name (a keyword, not a string literal). */
        val DATEPART_OF: Map<TimeUnit, String> =
            mapOf(
                TimeUnit.YEAR to "YEAR",
                TimeUnit.QUARTER to "QUARTER",
                TimeUnit.MONTH to "MONTH",
                TimeUnit.WEEK to "WEEK",
                TimeUnit.DAY to "DAY",
                TimeUnit.DOY to "DAYOFYEAR",
                TimeUnit.DOW to "WEEKDAY",
                TimeUnit.HOUR to "HOUR",
                TimeUnit.MINUTE to "MINUTE",
                TimeUnit.SECOND to "SECOND",
                TimeUnit.MILLISECOND to "MILLISECOND",
                TimeUnit.MICROSECOND to "MICROSECOND",
                TimeUnit.NANOSECOND to "NANOSECOND",
            )
    }

    /**
     * NX-A.S4 (calcite-ext, D9) — render T-SQL table hints in their native post-alias position:
     * `[mu] AS [m] WITH (NOLOCK, ROWLOCK)`.
     *
     * `RelToSqlConverter.visit(TableScan)` wraps a hinted scan in a `SqlTableRef` whose `unparse`
     * delegates hint rendering here — the stock [SqlDialect] impl is a no-op (Postgres/DuckDB drop
     * the hint) and `AnsiSqlDialect` emits the `/*+ … */` comment form; SQL Server wants the
     * bracketed `WITH (…)` form. Option-bearing hints (`INDEX(0)`) already read as `INDEX(0)` in
     * `getName()` because [org.tatrman.translator.wire.PlanNodeDecoder] folds options into the name
     * (Calcite's `toSqlHint` drops list-options before they reach here).
     */
    override fun unparseTableScanHints(
        writer: SqlWriter,
        hints: SqlNodeList,
        leftPrec: Int,
        rightPrec: Int,
    ) {
        if (hints.isEmpty()) return
        // Render the whole `WITH (NOLOCK, INDEX(0))` clause as one keyword token: `keyword` manages
        // the leading separator space and prints internal spaces verbatim → the conventional
        // `… WITH (NOLOCK)` form (a FUN_CALL frame would emit the space-less `WITH(NOLOCK)`).
        val clause = hints.joinToString(", ", prefix = "WITH (", postfix = ")") { (it as SqlHint).name }
        writer.keyword(clause)
    }
}
