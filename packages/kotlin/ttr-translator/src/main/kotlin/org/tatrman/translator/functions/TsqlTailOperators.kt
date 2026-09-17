// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.sql.SqlBasicFunction
import org.apache.calcite.sql.SqlFunction
import org.apache.calcite.sql.SqlFunctionCategory
import org.apache.calcite.sql.SqlOperatorTable
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlTypeFamily
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.util.SqlOperatorTables

/**
 * TF-P3.S2 (G C10, C11; contracts §5.2) — the T-SQL functions the legacy patterns use that no Calcite
 * library defines: `FORMAT`, `EOMONTH`, `DATENAME`, `DATETRUNC`, `SYSDATETIME`, `SYSUTCDATETIME`,
 * `GETUTCDATE`, `DATEFROMPARTS`, `ISNUMERIC`, `NEWID`.
 *
 * All are plain function-syntax operators: MSSQL unparses them as written, and the wire round-trips
 * them by name (`operationCode` lowercases, [FunctionCatalog] resolves) with no hand mapping.
 * `DATENAME`/`DATETRUNC` take a datepart first, which the parser production (`DateaddFunctionCall`,
 * parserImpls.ftl) turns into a SYMBOL [org.apache.calcite.avatica.util.TimeUnit] literal — the same
 * operand shape `DATEPART` has after `SqlToRel`, so it rides the wire as `symbol:TimeUnit` and the
 * MSSQL dialect spells the unit (`WEEKDAY`, `DAYOFYEAR`, …).
 *
 * The clock functions and `NEWID` are dynamic and non-deterministic, like [SqlGetDateFunction], so
 * constant reduction never folds them. Chained LAST in [CustomOperators.table]: no existing name is
 * shadowed.
 */
object TsqlTailOperators {
    private fun sig(vararg families: SqlTypeFamily) = OperandTypes.family(*families)

    private fun now(
        name: String,
        precision: Int,
    ): SqlFunction =
        SqlBasicFunction
            .create(
                name,
                ReturnTypes.explicit(SqlTypeName.TIMESTAMP, precision),
                OperandTypes.NILADIC,
                SqlFunctionCategory.TIMEDATE,
            ).withDynamic(true)
            .withDeterministic(false)

    /** `FORMAT(value, format [, culture])` → nvarchar. */
    @JvmField
    val FORMAT: SqlFunction =
        SqlBasicFunction.create(
            "FORMAT",
            ReturnTypes.VARCHAR_NULLABLE,
            OperandTypes.or(
                sig(SqlTypeFamily.ANY, SqlTypeFamily.CHARACTER),
                sig(SqlTypeFamily.ANY, SqlTypeFamily.CHARACTER, SqlTypeFamily.CHARACTER),
            ),
            SqlFunctionCategory.STRING,
        )

    /** `EOMONTH(date [, month_offset])` → date. */
    @JvmField
    val EOMONTH: SqlFunction =
        SqlBasicFunction.create(
            "EOMONTH",
            ReturnTypes.DATE_NULLABLE,
            OperandTypes.or(sig(SqlTypeFamily.DATETIME), sig(SqlTypeFamily.DATETIME, SqlTypeFamily.INTEGER)),
            SqlFunctionCategory.TIMEDATE,
        )

    /** `DATENAME(datepart, date)` → nvarchar; operand 0 is a SYMBOL TimeUnit (see the class KDoc). */
    @JvmField
    val DATENAME: SqlFunction =
        SqlBasicFunction.create(
            "DATENAME",
            ReturnTypes.VARCHAR_NULLABLE,
            sig(SqlTypeFamily.ANY, SqlTypeFamily.DATETIME),
            SqlFunctionCategory.TIMEDATE,
        )

    /** `DATETRUNC(datepart, date)` → the type of `date`. */
    @JvmField
    val DATETRUNC: SqlFunction =
        SqlBasicFunction.create(
            "DATETRUNC",
            ReturnTypes.ARG1_NULLABLE,
            sig(SqlTypeFamily.ANY, SqlTypeFamily.DATETIME),
            SqlFunctionCategory.TIMEDATE,
        )

    /** `SYSDATETIME()` → datetime2(7). */
    @JvmField
    val SYSDATETIME: SqlFunction = now("SYSDATETIME", 7)

    /** `SYSUTCDATETIME()` → datetime2(7). */
    @JvmField
    val SYSUTCDATETIME: SqlFunction = now("SYSUTCDATETIME", 7)

    /** `GETUTCDATE()` → datetime, typed like [SqlGetDateFunction]. */
    @JvmField
    val GETUTCDATE: SqlFunction =
        SqlBasicFunction
            .create(
                "GETUTCDATE",
                ReturnTypes.explicit(SqlTypeName.TIMESTAMP),
                OperandTypes.NILADIC,
                SqlFunctionCategory.TIMEDATE,
            ).withDynamic(true)
            .withDeterministic(false)

    /**
     * `DATEFROMPARTS(year, month, day)` → date. The source-side function; [org.tatrman.translator.dialects.GroundingFunctionUnparse]
     * separately *emits* DATEFROMPARTS for the grounding `period_start`.
     */
    @JvmField
    val DATEFROMPARTS: SqlFunction =
        SqlBasicFunction.create(
            "DATEFROMPARTS",
            ReturnTypes.DATE_NULLABLE,
            sig(SqlTypeFamily.INTEGER, SqlTypeFamily.INTEGER, SqlTypeFamily.INTEGER),
            SqlFunctionCategory.TIMEDATE,
        )

    /** `ISNUMERIC(expr)` → int (0 or 1, never NULL). */
    @JvmField
    val ISNUMERIC: SqlFunction =
        SqlBasicFunction.create("ISNUMERIC", ReturnTypes.INTEGER, OperandTypes.ANY, SqlFunctionCategory.NUMERIC)

    /** `NEWID()` → uniqueidentifier, carried as CHAR(36) (contracts §3.3). */
    @JvmField
    val NEWID: SqlFunction =
        SqlBasicFunction
            .create(
                "NEWID",
                ReturnTypes.explicit(SqlTypeName.CHAR, 36),
                OperandTypes.NILADIC,
                SqlFunctionCategory.SYSTEM,
            ).withDynamic(true)
            .withDeterministic(false)

    val table: SqlOperatorTable =
        SqlOperatorTables.of(
            FORMAT,
            EOMONTH,
            DATENAME,
            DATETRUNC,
            SYSDATETIME,
            SYSUTCDATETIME,
            GETUTCDATE,
            DATEFROMPARTS,
            ISNUMERIC,
            NEWID,
        )
}
