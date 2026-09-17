// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.rex.RexUtil
import org.apache.calcite.sql.SqlKind

/**
 * Expand every Calcite `SEARCH` node back into the `OR`/`AND` of comparisons it was folded from,
 * before a logical [RelNode] reaches [org.tatrman.translator.wire.PlanNodeEncoder].
 *
 * `SqlToRelConverter` (via `RexSimplify`) collapses an `IN`-list of literals and comparison ranges
 * into a single `SEARCH($ref, Sarg[…])` call: `VLPODTYP_SLOZ IN (1, 4)` becomes
 * `SEARCH($col, Sarg[1; 4])`. The `Sarg` rides as the *value* of a `RexLiteral` whose declared type
 * is the column type (e.g. `INTEGER`). The `plan.v1` wire format has no `SEARCH` operator and no
 * `Sarg` literal shape, so [org.tatrman.translator.wire.Expressions.encodeLiteral] takes the numeric
 * branch for that `INTEGER` literal and throws `class org.apache.calcite.util.Sarg cannot be cast to
 * class java.lang.Number` — surfacing to callers as `parse_pipeline_failed`.
 *
 * [RexUtil.expandSearch] is Calcite's own inverse of that fold: it rewrites `SEARCH($col, Sarg[1; 4])`
 * into `OR(=($col, 1), =($col, 4))` (and ranges into `AND`/`OR` of `</<=/>/>=`), all of which the
 * wire format already carries (`or`, `and`, `eq`, `lt`, `le`, `gt`, `ge`). The result is
 * semantically identical and unparses back to an equivalent predicate. No-op on trees without a
 * `SEARCH` (the common case), so it is safe to run unconditionally and idempotent under the
 * two-half pipeline's REL_NODE re-entry (a re-decoded plan carries no `SEARCH`).
 */
object SearchExpander {
    fun apply(rel: RelNode): RelNode {
        val rexBuilder = rel.cluster.rexBuilder
        val shuttle =
            object : RexShuttle() {
                override fun visitCall(call: RexCall): RexNode {
                    // Expand any nested SEARCH in the operands first, then expand this node if it is
                    // itself a SEARCH — so a SEARCH under NOT/AND/OR is reached too. expandSearch's
                    // output is SEARCH-free, so no further recursion into it is needed.
                    val visited = super.visitCall(call)
                    return when {
                        visited is RexCall && visited.kind == SqlKind.SEARCH ->
                            RexUtil.expandSearch(rexBuilder, null, visited)
                        // TF-P1.S1 — an expanded `NOT IN (-2, 10)` is `AND(<>, <>)`; left nested inside the
                        // enclosing AND it makes the condition non-flat, and Calcite's Filter asserts
                        // `RexUtil.isFlat` (a parse that threw only under -ea). Re-flatten AND/OR parents.
                        visited !== call &&
                            visited is RexCall &&
                            (visited.kind == SqlKind.AND || visited.kind == SqlKind.OR) ->
                            RexUtil.flatten(rexBuilder, visited)
                        else -> visited
                    }
                }
            }

        // `RelNode.accept(RexShuttle)` rewrites only a node's OWN expressions, not its inputs — so a
        // bare `rel.accept(shuttle)` would miss a SEARCH living in a Filter/Join/Project below the
        // top node. Descend the whole tree, rewriting each node's rexes (mirrors ParameterTyper).
        fun rewrite(node: RelNode): RelNode {
            val newInputs = node.inputs.map { rewrite(it) }
            val withInputs = if (newInputs == node.inputs) node else node.copy(node.traitSet, newInputs)
            return withInputs.accept(shuttle)
        }
        return rewrite(rel)
    }
}
