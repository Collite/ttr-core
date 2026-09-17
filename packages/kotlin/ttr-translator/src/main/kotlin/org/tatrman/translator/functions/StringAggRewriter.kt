// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlNodeList
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.util.SqlShuttle

/**
 * TF-P1.S3 (G C6) — accept T-SQL's `STRING_AGG(x, sep) WITHIN GROUP (ORDER BY …)`.
 *
 * Calcite parses `STRING_AGG` as `SqlLibraryOperators.STRING_AGG`, the PostgreSQL/BigQuery form
 * `STRING_AGG(x, sep ORDER BY …)`, whose `requiresGroupOrder` is FORBIDDEN — so the T-SQL spelling
 * fails validation (`Aggregate expression 'STRING_AGG' must not contain a WITHIN GROUP clause`).
 * Moving the order list inside the call does not help either: function resolution then types the
 * `SqlNodeList` as a third argument. This shuttle swaps the inner operator to the standard
 * `LISTAGG`, which allows `WITHIN GROUP` — the node `AggConverter` lowers every `STRING_AGG` to
 * anyway, so the RelNode is the same. No-op for queries without it. Runs on the parsed SqlNode
 * before validation, next to [ConvertRewriter].
 *
 * TF-P2.S2 (G A8) — every `WITHIN GROUP` order key without an explicit `NULLS FIRST/LAST` gets T-SQL's
 * default spelled out (ascending → `NULLS FIRST`, descending → `NULLS LAST`). Calcite's `AggConverter`
 * resolves an unspecified null direction with `Direction.defaultNullDirection()` (the HIGH collation),
 * ignoring the validator's `NullCollation.LOW` that ORDER BY honours, and after conversion an explicit
 * `NULLS LAST` is indistinguishable from none — so the default has to be made explicit here.
 */
class StringAggRewriter : SqlShuttle() {
    override fun visit(call: SqlCall): SqlNode? {
        if (call.kind != SqlKind.WITHIN_GROUP) return super.visit(call)
        val inner = call.operandList[0]
        val aggregate =
            if (inner is SqlCall && inner.kind == SqlKind.STRING_AGG) {
                val operands = inner.operandList.map { it.accept(this) ?: it }
                SqlStdOperatorTable.LISTAGG.createCall(inner.functionQuantifier, inner.parserPosition, operands)
            } else {
                inner.accept(this) ?: inner
            }
        val orderList = call.operandList[1] as SqlNodeList
        val tsqlOrder =
            SqlNodeList(orderList.map { withTsqlNullDirection(it.accept(this) ?: it) }, orderList.parserPosition)
        return call.operator.createCall(call.functionQuantifier, call.parserPosition, aggregate, tsqlOrder)
    }

    private fun withTsqlNullDirection(item: SqlNode): SqlNode =
        when (item.kind) {
            SqlKind.NULLS_FIRST, SqlKind.NULLS_LAST -> item
            SqlKind.DESCENDING -> SqlStdOperatorTable.NULLS_LAST.createCall(item.parserPosition, item)
            else -> SqlStdOperatorTable.NULLS_FIRST.createCall(item.parserPosition, item)
        }

    companion object {
        /** Fresh rewriter per call (SqlShuttle is cheap and not thread-safe to share). */
        fun rewriter(): StringAggRewriter = StringAggRewriter()
    }
}
