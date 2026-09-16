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
import org.apache.calcite.sql.SqlWindow
import org.apache.calcite.sql.SqlWriter
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.`fun`.SqlLibraryOperators
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.validate.SqlConformance

/**
 * MS SQL Server dialect that fixes CAST target type names Calcite renders with
 * ANSI names SQL Server rejects.
 *
 * Calcite's [MssqlSqlDialect] emits `CAST(... AS DOUBLE)` for [SqlTypeName.DOUBLE],
 * but SQL Server has no `DOUBLE` type — its 8-byte double-precision type is
 * `FLOAT`. The bad cast produced `Incorrect syntax near ')'` at the database.
 * We override [getCastSpec] to render `DOUBLE` as `FLOAT`, and (TF-P3.S1) `BOOLEAN` as `BIT`,
 * `TIMESTAMP(3)` as `DATETIME`, any other `TIMESTAMP(p)` as `DATETIME2(p)` and the VARCHAR ceiling as
 * `VARCHAR(MAX)`; every other type defers to the stock dialect.
 *
 * Also lowers the standard `EXTRACT(<unit> FROM <datetime>)` to T-SQL `DATEPART(<part>, <datetime>)`
 * — SQL Server has no `EXTRACT` (error 195 `'EXTRACT' is not a recognized built-in function name`),
 * and Calcite's stock [MssqlSqlDialect] has no unparse rule for it (FLOOR/MOD only), so the
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
            SqlTypeName.DOUBLE -> alien("FLOAT", type)
            // TF-P3.S1 (G C9; contracts §3.5) — re-spell from the Calcite type alone; the authored T-SQL
            // name (nvarchar, money, uniqueidentifier, …) does not survive validation (⚑TF-6).
            SqlTypeName.BOOLEAN -> alien("BIT", type)
            SqlTypeName.TIMESTAMP ->
                when (type.precision) {
                    DATETIME_PRECISION -> alien("DATETIME", type)
                    else -> alien("DATETIME2(${type.precision})", type)
                }
            SqlTypeName.VARCHAR ->
                if (type.precision >= VARCHAR_MAX_PRECISION) alien("VARCHAR(MAX)", type) else super.getCastSpec(type)
            else -> super.getCastSpec(type)
        }

    private fun alien(
        spelling: String,
        type: RelDataType,
    ): SqlNode =
        SqlDataTypeSpec(SqlAlienSystemTypeNameSpec(spelling, type.sqlTypeName, SqlParserPos.ZERO), SqlParserPos.ZERO)

    /** TF-P2.S2 — ORDER BY keys as expressions, so an order-only key does not leak a result column; see [SortByExpressionConformance]. */
    override fun getConformance(): SqlConformance = SortByExpressionConformance(super.getConformance())

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
        } else if (call.operator === SqlStdOperatorTable.CONCAT) {
            unparseConcat(writer, call)
        } else if (call.operator in DATEPART_FUNCTIONS && datepartUnit(call) != null) {
            unparseDatepartCall(writer, call, datepartUnit(call)!!)
        } else if (call.kind == SqlKind.EXTRACT) {
            unparseExtract(writer, call)
        } else if (call.kind == SqlKind.LISTAGG) {
            unparseStringAgg(writer, call)
        } else if (call.kind == SqlKind.OVER) {
            unparseOver(writer, call, leftPrec, rightPrec)
        } else if (call.kind == SqlKind.SAFE_CAST) {
            // TF-P1.S3 (G C8) — RelToSql builds the SAFE_CAST operator, which renders under its own
            // name; T-SQL spells it TRY_CAST (same `expr AS type` operand shape).
            SqlLibraryOperators.TRY_CAST.unparse(writer, call, leftPrec, rightPrec)
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

    /**
     * TF-P2.S1 (G A4; contracts §3.5, ⚑TF-1 ruled `CONCAT`) — the ANSI `a || b` (and every T-SQL string
     * `+`, which TsqlPlusLowering turns into `||`) → `CONCAT(a, b)`. SQL Server has no `||`. A chain
     * `a || b || c` arrives as nested binary calls and is flattened left-to-right into one
     * `CONCAT(a, b, c)`.
     *
     * NULL semantics differ from T-SQL `+`: `CONCAT` treats a NULL operand as `''`, `'a' + NULL` is
     * NULL. Accepted deviation (⚑TF-1 records the rejected alternative, `a + b` with
     * `CAST(… AS VARCHAR)` wrappers).
     */
    private fun unparseConcat(
        writer: SqlWriter,
        call: SqlCall,
    ) {
        val frame = writer.startFunCall("CONCAT")
        concatOperands(call).forEach { operand ->
            writer.sep(",")
            operand.unparse(writer, 0, 0)
        }
        writer.endFunCall(frame)
    }

    private fun concatOperands(node: SqlNode): List<SqlNode> =
        if (node is SqlCall && node.operator === SqlStdOperatorTable.CONCAT) {
            node.operandList.flatMap { concatOperands(it) }
        } else {
            listOf(node)
        }

    /**
     * TF-P3.S3 (G C5) — `agg OVER (…)` in the frames SQL Server takes.
     *
     * * `NTILE(n) OVER (…)` prints without a frame. SQL Server refuses `ROWS`/`RANGE` on a ranking
     *   function, but Calcite's NTILE (unlike ROW_NUMBER/RANK/LAG/LEAD) allows framing, so the default
     *   frame the validator gave it (`RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`) would print.
     *   The frame changes nothing for NTILE, so dropping it is exact.
     * * A `RANGE` frame with an offset bound (`RANGE BETWEEN 5 PRECEDING AND CURRENT ROW`) has no SQL
     *   Server equivalent (it takes only `UNBOUNDED` and `CURRENT ROW` there): it fails at translate
     *   time rather than at the engine, as `STRING_AGG(DISTINCT …)` does.
     */
    private fun unparseOver(
        writer: SqlWriter,
        call: SqlCall,
        leftPrec: Int,
        rightPrec: Int,
    ) {
        val window = call.operand<SqlNode>(1) as? SqlWindow
        if (window == null || window.lowerBound == null) {
            super.unparseCall(writer, call, leftPrec, rightPrec)
            return
        }
        val offsetBound = listOfNotNull(window.lowerBound, window.upperBound).firstOrNull(::isOffsetBound)
        if (!window.isRows && offsetBound != null) {
            throw IllegalArgumentException("RANGE frame bound '$offsetBound' has no SQL Server equivalent (use ROWS)")
        }
        if (call.operand<SqlNode>(0).kind != SqlKind.NTILE) {
            super.unparseCall(writer, call, leftPrec, rightPrec)
            return
        }
        val unframed =
            SqlWindow.create(
                null,
                window.refName,
                window.partitionList,
                window.orderList,
                SqlLiteral.createBoolean(window.isRows, window.parserPosition),
                null,
                null,
                null,
                window.exclude,
                window.parserPosition,
            )
        super.unparseCall(
            writer,
            SqlStdOperatorTable.OVER.createCall(call.parserPosition, call.operand(0), unframed),
            leftPrec,
            rightPrec,
        )
    }

    private fun isOffsetBound(bound: SqlNode): Boolean =
        !SqlWindow.isUnboundedPreceding(bound) &&
            !SqlWindow.isUnboundedFollowing(bound) &&
            !SqlWindow.isCurrentRow(bound)

    /**
     * TF-P1.S3 (G C6; contracts §5.4) — `LISTAGG(x, sep)` → T-SQL `STRING_AGG(x, sep)`. Only the name
     * changes: a `WITHIN GROUP (ORDER BY …)` is a separate `SqlWithinGroupOperator` call wrapping this
     * one, which renders its clause itself after delegating the inner call here. SQL Server has no
     * `STRING_AGG(DISTINCT …)`: that fails at translate time (as an unmapped EXTRACT unit does) rather
     * than at the engine.
     */
    private fun unparseStringAgg(
        writer: SqlWriter,
        call: SqlCall,
    ) {
        if (call.functionQuantifier != null) {
            throw IllegalArgumentException("STRING_AGG(${call.functionQuantifier}) has no SQL Server equivalent")
        }
        val frame = writer.startFunCall("STRING_AGG")
        call.operandList.forEach { operand ->
            writer.sep(",")
            operand.unparse(writer, 0, 0)
        }
        writer.endFunCall(frame)
    }

    /** The datepart of a DATE* call, or null when operand 0 is not a plain time unit (left to the stock unparse). */
    private fun datepartUnit(call: SqlCall): TimeUnit? =
        call.operandList.firstOrNull()?.let { runCatching { extractUnit(it) }.getOrNull() }

    /**
     * TF-P3.S2 (G C11; contracts §3.5, §5.1) — `DATEPART`/`DATEADD`/`DATEDIFF`/`DATENAME`/`DATETRUNC` with the
     * datepart spelled in T-SQL: Calcite's unit names `DOW`/`DOY` are not T-SQL dateparts, so they print as
     * `WEEKDAY`/`DAYOFYEAR`; every other unit prints under its name, as before.
     */
    private fun unparseDatepartCall(
        writer: SqlWriter,
        call: SqlCall,
        unit: TimeUnit,
    ) {
        val frame = writer.startFunCall(call.operator.name)
        writer.keyword(DATEPART_OF[unit] ?: unit.name)
        call.operandList.drop(1).forEach {
            writer.sep(",", true)
            it.unparse(writer, 0, 0)
        }
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
        /** The T-SQL functions whose first operand is a datepart ([unparseDatepartCall]). */
        val DATEPART_FUNCTIONS: Set<org.apache.calcite.sql.SqlOperator> =
            setOf(
                SqlLibraryOperators.DATEPART,
                SqlLibraryOperators.DATEADD,
                org.tatrman.translator.functions.DateOperators.DATEDIFF,
                org.tatrman.translator.functions.TsqlTailOperators.DATENAME,
                org.tatrman.translator.functions.TsqlTailOperators.DATETRUNC,
            )

        /** T-SQL `datetime` is TIMESTAMP(3); every other TIMESTAMP precision is a `datetime2(p)`. */
        const val DATETIME_PRECISION = 3

        /** `varchar(max)` — Calcite's VARCHAR ceiling, which the wire spells `varchar:max` (contracts §3.3). */
        val VARCHAR_MAX_PRECISION: Int =
            org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT
                .getMaxPrecision(SqlTypeName.VARCHAR)

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
