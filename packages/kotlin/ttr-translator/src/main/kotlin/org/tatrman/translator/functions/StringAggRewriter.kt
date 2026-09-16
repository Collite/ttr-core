// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
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
 */
class StringAggRewriter : SqlShuttle() {
    override fun visit(call: SqlCall): SqlNode? {
        val inner = call.operandList.getOrNull(0)
        if (call.kind != SqlKind.WITHIN_GROUP || inner !is SqlCall || inner.kind != SqlKind.STRING_AGG) {
            return super.visit(call)
        }
        val operands = inner.operandList.map { it.accept(this) ?: it }
        val listagg = SqlStdOperatorTable.LISTAGG.createCall(inner.functionQuantifier, inner.parserPosition, operands)
        val orderList = call.operandList[1].accept(this) ?: call.operandList[1]
        return call.operator.createCall(call.functionQuantifier, call.parserPosition, listagg, orderList)
    }

    companion object {
        /** Fresh rewriter per call (SqlShuttle is cheap and not thread-safe to share). */
        fun rewriter(): StringAggRewriter = StringAggRewriter()
    }
}
