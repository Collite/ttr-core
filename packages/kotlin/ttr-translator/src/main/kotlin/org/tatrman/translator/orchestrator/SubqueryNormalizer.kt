// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.plan.hep.HepPlanner
import org.apache.calcite.plan.hep.HepProgramBuilder
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.rules.CoreRules
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.sql2rel.RelDecorrelator
import org.tatrman.translator.framework.TranslatorFramework

/**
 * NX-A — remove **correlated** sub-queries from a logical [RelNode] before it reaches
 * [org.tatrman.translator.wire.PlanNodeEncoder].
 *
 * The framework runs `SqlToRelConverter` with `expand = false`, so a `WHERE [NOT] EXISTS (…)` /
 * `IN (…)` survives as a `RexSubQuery` and encodes as a `SubqueryExpression`
 * (see `Expressions.encodeSubquery`). That is fine for an **uncorrelated** sub-query. A
 * **correlated** one — the inner query references the outer row, e.g. `td.customer_id = z.id` —
 * instead carries a `RexFieldAccess` over a `RexCorrelVariable`, which has no `plan.v1`
 * representation: the encoder throws `RexNode kind 'RexFieldAccess' is not in the v1 wire format`.
 *
 * This stage decorrelates **only when a correlated sub-query is present**, so uncorrelated
 * sub-queries keep their `SubqueryExpression` encoding and the two-half pipeline's REL_NODE
 * re-entry stays byte-stable (a decorrelated tree has no `RexSubQuery`, so re-entry is a no-op;
 * an uncorrelated tree is never touched). Decorrelation runs `SubQueryRemoveRule`
 * (`RexSubQuery` → `Correlate`) then [RelDecorrelator.decorrelateQuery] (`Correlate` → a
 * correlation-free tree).
 *
 * **Shape note (Calcite 1.41):** the decorrelator lowers correlated `NOT EXISTS` to the classic
 * `LEFT JOIN + Aggregate + IS NULL` (anti) and `EXISTS` to `INNER JOIN + distinct Aggregate`
 * (semi) — **not** a native `JoinRelType.SEMI/ANTI`. This holds even with `expand = true`
 * (verified empirically), so a native-semi/anti round-trip to literal `[NOT] EXISTS` is not
 * reachable from correlated input with stock Calcite rules. Both lowerings encode with existing
 * wire nodes and unparse to semantically-equivalent MSSQL. The `SEMI`/`ANTI` wire added in
 * NX-A.S1 is therefore not exercised by this path; it stays valid for hand-built joins and any
 * future direct-lowering. See the NX-A.S2 tracker note.
 */
object SubqueryNormalizer {
    fun apply(
        rel: RelNode,
        framework: TranslatorFramework,
    ): RelNode {
        if (!containsCorrelatedSubquery(rel)) return rel
        val program =
            HepProgramBuilder()
                .addRuleInstance(CoreRules.FILTER_SUB_QUERY_TO_CORRELATE)
                .addRuleInstance(CoreRules.PROJECT_SUB_QUERY_TO_CORRELATE)
                .addRuleInstance(CoreRules.JOIN_SUB_QUERY_TO_CORRELATE)
                .build()
        val planner = HepPlanner(program)
        planner.root = rel
        val correlated = planner.findBestExp()
        return RelDecorrelator.decorrelateQuery(correlated, framework.newRelBuilder())
    }

    /**
     * True iff [node] (or any nested sub-query rel) contains a **correlated** sub-query — a
     * `RexSubQuery` whose body references a correlation variable bound by an enclosing operator
     * ([RelOptUtil.getVariablesUsed] on the sub-query's rel is non-empty). Uncorrelated
     * sub-queries return false so they are left untouched.
     */
    private fun containsCorrelatedSubquery(node: RelNode): Boolean {
        var found = false
        node.accept(
            object : RexShuttle() {
                override fun visitSubQuery(subQuery: RexSubQuery): RexNode {
                    if (RelOptUtil.getVariablesUsed(subQuery.rel).isNotEmpty() ||
                        containsCorrelatedSubquery(subQuery.rel)
                    ) {
                        found = true
                    }
                    return subQuery
                }
            },
        )
        return found || node.inputs.any { containsCorrelatedSubquery(it) }
    }
}
