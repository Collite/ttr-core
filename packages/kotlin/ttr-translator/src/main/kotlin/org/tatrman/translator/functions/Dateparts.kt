// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.sql.SqlIntervalQualifier
import org.apache.calcite.sql.SqlLiteral
import org.apache.calcite.sql.SqlUtil
import org.apache.calcite.util.Static.RESOURCE

/**
 * Maps T-SQL datepart names and abbreviations to canonical Calcite [TimeUnit]s (tasks-dateadd.md).
 *
 * Used by the custom parser's `NormalizeDatepart` helper to rewrite a bare T-SQL datepart (parsed
 * by `TimeUnitOrName()` as a time-frame *name*, e.g. `dd`) into a `TimeUnit`-backed interval
 * qualifier. That makes it a SYMBOL operand after sql-to-rel, which unparses **bare and canonical**
 * (`DATEADD(DAY, …)`) instead of as a quoted time-frame string (`DATEADD('dd', …)`, invalid T-SQL).
 *
 * Every T-SQL datepart family with a Calcite [TimeUnit] is mapped. Calcite 1.41's built-in time
 * frames do **not** resolve `weekday`/`dw`/`dayofyear`/`dy` (validation fails with "'weekday' is not a
 * valid time frame"), so TF-P3.S2 maps them to DOW / DOY; the MSSQL dialect spells those back as
 * `WEEKDAY` / `DAYOFYEAR` in every DATE* call (Calcite's own names are not T-SQL dateparts).
 * `iso_week`/`isowk`/`isoww` stay unmapped and remain a validation error (documented unsupported).
 * Lookup is case-insensitive.
 */
object Dateparts {
    private val byName: Map<String, TimeUnit> =
        buildMap {
            fun map(
                unit: TimeUnit,
                vararg names: String,
            ) = names.forEach { put(it, unit) }
            map(TimeUnit.YEAR, "year", "yy", "yyyy")
            map(TimeUnit.QUARTER, "quarter", "qq", "q")
            map(TimeUnit.MONTH, "month", "mm", "m")
            map(TimeUnit.DAY, "day", "dd", "d")
            map(TimeUnit.WEEK, "week", "wk", "ww")
            map(TimeUnit.DOW, "weekday", "dw", "w")
            map(TimeUnit.DOY, "dayofyear", "dy", "y")
            map(TimeUnit.HOUR, "hour", "hh")
            map(TimeUnit.MINUTE, "minute", "mi", "n")
            map(TimeUnit.SECOND, "second", "ss", "s")
            map(TimeUnit.MILLISECOND, "millisecond", "ms")
            map(TimeUnit.MICROSECOND, "microsecond", "mcs")
            map(TimeUnit.NANOSECOND, "nanosecond", "ns")
        }

    /** The canonical [TimeUnit] for a T-SQL datepart spelling, or null if not a mapped datepart. */
    @JvmStatic
    fun toTimeUnit(name: String): TimeUnit? = byName[name.lowercase()]

    /**
     * TF-P3.S2 — the datepart of `DATENAME`/`DATETRUNC` as a SYMBOL [TimeUnit] literal: the operand
     * shape `DATEPART` has after `SqlToRel`, which the wire carries as `symbol:TimeUnit`. A bare
     * interval qualifier would convert to an interval literal instead. [q] has been through the
     * parser's `NormalizeDatepart`; a name that is still unmapped is `'<name>' is not a valid time frame`.
     */
    @JvmStatic
    fun symbol(q: SqlIntervalQualifier): SqlLiteral {
        val name = q.timeFrameName
        if (name != null) throw SqlUtil.newContextException(q.parserPosition, RESOURCE.invalidTimeFrame(name))
        return SqlLiteral.createSymbol(q.timeUnitRange.startUnit, q.parserPosition)
    }

    /**
     * TF-P3.S2 — the datepart of `DATEADD`/`DATEDIFF`. T-SQL documents `weekday` and `dayofyear` as
     * identical to `day` in both functions; Calcite rejects DOW/DOY there ("'DOW' is not a valid time
     * frame in 'DATEADD'"), so they become DAY. `DATEPART`/`DATENAME` keep DOW/DOY, where they differ.
     */
    @JvmStatic
    fun forArithmetic(q: SqlIntervalQualifier): SqlIntervalQualifier =
        if (q.timeFrameName == null && q.timeUnitRange.startUnit in setOf(TimeUnit.DOW, TimeUnit.DOY)) {
            SqlIntervalQualifier(TimeUnit.DAY, null, q.parserPosition)
        } else {
            q
        }
}
