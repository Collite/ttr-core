// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.params

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode

/**
 * Converts the pipeline's **named** parameter side-channel into the **positional** sequence a JDBC
 * `PreparedStatement` binds — one entry per `?` placeholder, in the order the placeholders appear
 * in the unparsed SQL.
 *
 * Why this exists: [ParameterBridge] emits one positional `?` per *occurrence* of a `{name}`, so a
 * name referenced twice — e.g. `s_store_id LIKE {x} || '%' OR s_store_name LIKE '%' || {x} || '%'` —
 * produces **two** `?` but only **one** binding. Binding the distinct named values 1:1 (as the
 * worker does) leaves the trailing positions unset (`The value is not set for the parameter
 * number N`). This object replays each `?` to its name's value, so repeats are bound at every
 * position.
 *
 * The placeholder order is taken from Calcite's `SqlString.getDynamicParameters()` — the indices
 * of the `RexDynamicParam`s in true `?`-appearance order, not an assumed index ordering. The
 * `index -> name` mapping is recovered from the wire plan's [org.tatrman.plan.v1.ParameterRef]s
 * (a `RexDynamicParam` keeps only its index after decode; the name lives on the wire).
 */
object PositionalParameters {
    /** Collect `positional_index -> name` from every `ParameterRef` reachable in [plan]. */
    fun namesByIndex(plan: PlanNode): Map<Int, String> {
        val acc = LinkedHashMap<Int, String>()
        collectPlan(plan, acc)
        return acc
    }

    /**
     * Expand named [bindings] into one [ParameterBinding] per `?`, ordered by [order].
     *
     * @param order `RexDynamicParam` indices in `?`-appearance order
     *   (`SqlString.getDynamicParameters()`). Empty → returns empty (no parameters).
     * @param namesByIndex `positional_index -> name`, from [namesByIndex].
     * @param bindings the distinct named bindings (the pipeline's side-channel values). Empty *with
     *   no placeholders* is a no-op; empty *with placeholders present* is a contract violation and
     *   throws (a param-less SQL has `order.isEmpty()`, so preview callers still never throw).
     * @throws IllegalArgumentException when a `?` cannot be resolved — there are placeholders but no
     *   bindings at all (H1), its index has no name in the plan, or its name has no supplied
     *   binding. All are contract violations worth surfacing loudly instead of as a downstream
     *   "parameter not set" JDBC error. The orchestrator's `unparseSql` catch converts these into a
     *   structured `sql_unparse_failed` Failure.
     */
    fun positional(
        order: List<Int>,
        namesByIndex: Map<Int, String>,
        bindings: List<ParameterBinding>,
    ): List<ParameterBinding> {
        if (order.isEmpty()) return emptyList()
        if (bindings.isEmpty()) {
            throw IllegalArgumentException(
                "SQL has ${order.size} placeholder(s) but no bindings were supplied",
            )
        }
        val byName = bindings.associateBy { it.name }
        return order.map { idx ->
            val name =
                namesByIndex[idx]
                    ?: throw IllegalArgumentException(
                        "Dynamic parameter #$idx has no name in the plan; cannot bind positionally",
                    )
            byName[name]
                ?: throw IllegalArgumentException(
                    "No binding supplied for parameter '{$name}' (dynamic parameter #$idx); " +
                        "known: ${byName.keys}",
                )
        }
    }

    private fun collectPlan(
        plan: PlanNode,
        acc: MutableMap<Int, String>,
    ) {
        when (plan.nodeCase) {
            PlanNode.NodeCase.PROJECT -> {
                collectPlan(plan.project.input, acc)
                plan.project.expressionsList.forEach { collectExpr(it.expression, acc) }
            }
            PlanNode.NodeCase.FILTER -> {
                collectPlan(plan.filter.input, acc)
                collectExpr(plan.filter.condition, acc)
            }
            PlanNode.NodeCase.JOIN -> {
                collectPlan(plan.join.left, acc)
                collectPlan(plan.join.right, acc)
                if (plan.join.hasCondition()) collectExpr(plan.join.condition, acc)
            }
            PlanNode.NodeCase.AGGREGATE -> collectPlan(plan.aggregate.input, acc)
            PlanNode.NodeCase.SORT -> collectPlan(plan.sort.input, acc)
            PlanNode.NodeCase.LIMIT_OFFSET -> collectPlan(plan.limitOffset.input, acc)
            PlanNode.NodeCase.SUBQUERY -> collectPlan(plan.subquery.subquery, acc)
            // A set-op branch can carry its own ParameterRefs (e.g. a per-branch
            // WHERE), so recurse into every input.
            PlanNode.NodeCase.UNION -> plan.union.inputsList.forEach { collectPlan(it, acc) }
            // A parametrised write ({name} in the RHS SELECT) carries its ParameterRefs under
            // `store.input`; missing this arm makes positional binding silently drop them.
            PlanNode.NodeCase.STORE -> collectPlan(plan.store.input, acc)
            // TABLE_SCAN / SCAN / VALUES (Literal cells) / WORKSPACE_REF carry no ParameterRefs.
            else -> Unit
        }
    }

    private fun collectExpr(
        expr: Expression,
        acc: MutableMap<Int, String>,
    ) {
        when (expr.exprCase) {
            Expression.ExprCase.PARAMETER -> {
                val index = expr.parameter.positionalIndex
                val name = expr.parameter.name
                val existing = acc[index]
                // The same positional index re-appearing with a *different* name means the plan's
                // ParameterRefs disagree about what `?N` binds — a corrupt mapping. Re-appearing
                // with the SAME name is fine (one name referenced at several call sites collapses to
                // one index). Surface the collision loudly rather than silently keeping the last.
                require(existing == null || existing == name) {
                    "Dynamic parameter #$index maps to two names ('$existing' and '$name'); " +
                        "the plan's ParameterRefs are inconsistent"
                }
                acc[index] = name
            }
            Expression.ExprCase.FUNCTION ->
                expr.function.operandsList.forEach { collectExpr(it, acc) }
            Expression.ExprCase.CAST ->
                collectExpr(expr.cast.value, acc)
            Expression.ExprCase.SUBQUERY -> {
                expr.subquery.operandsList.forEach { collectExpr(it, acc) }
                collectPlan(expr.subquery.subquery, acc)
            }
            else -> Unit
        }
    }
}
