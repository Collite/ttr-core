// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.sql.SqlFunction
import org.apache.calcite.sql.SqlFunctionCategory
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlOperatorTable
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.util.SqlOperatorTables

/**
 * Faithful T-SQL date/time functions that Calcite's libraries don't provide (master-plan §5.4/§5.5,
 * D5/D7). Today: `GETDATE()`.
 *
 * `GETDATE()` is the T-SQL "current transaction timestamp". It **parses** already (Calcite reads any
 * `IDENT(...)` as a generic function call), so no grammar production is needed — the gap was purely
 * that no operator with that name existed in the validator's table, so `GETDATE()` failed with
 * "No match found for function signature GETDATE()". Registering it here (via [DateOperators.table]
 * → [CustomOperators]) resolves validation; the wire round-trip is automatic (encode lowercases the
 * name to `getdate`; decode resolves it through [FunctionCatalog]); MSSQL unparse emits `GETDATE()`
 * from the default function syntax.
 *
 * It is modelled as an `object` subclass (not a bare [SqlFunction] instance) so it can declare itself
 * **dynamic / non-deterministic** — otherwise Calcite's constant-reduction rules could fold "now" to
 * a fixed literal during optimization.
 */
object SqlGetDateFunction : SqlFunction(
    "GETDATE",
    SqlKind.OTHER_FUNCTION,
    ReturnTypes.explicit(SqlTypeName.TIMESTAMP),
    null,
    OperandTypes.NILADIC,
    SqlFunctionCategory.TIMEDATE,
) {
    // "Now" must not be constant-folded or CSE-cached: mark it dynamic + non-deterministic so
    // FILTER_REDUCE_EXPRESSIONS / project reduction leave the call intact (mirrors how Calcite
    // treats CURRENT_TIMESTAMP).
    override fun isDynamicFunction(): Boolean = true

    override fun isDeterministic(): Boolean = false
}

object DateOperators {
    /**
     * TF-P1.S1 — T-SQL `DATEDIFF(unit, startdate, enddate)` with **datetime** operands. Calcite's
     * `SqlLibraryOperators.DATEDIFF` types them as DATE, so the validator inserted `CAST(… AS DATE)` and
     * `DATEDIFF(HOUR, …)` counted whole days once casts rode the wire faithfully. The parser production
     * (`DateaddFunctionCall`) and the wire decoder (`"datediff"`) both bind this instance.
     */
    val DATEDIFF: SqlFunction = org.apache.calcite.sql.`fun`.TsqlDateDiffFunctions.DATEDIFF

    /** Operator table exposed to the parser/validator (chained into [CustomOperators.table]). */
    val table: SqlOperatorTable = SqlOperatorTables.of(SqlGetDateFunction, DATEDIFF)
}
