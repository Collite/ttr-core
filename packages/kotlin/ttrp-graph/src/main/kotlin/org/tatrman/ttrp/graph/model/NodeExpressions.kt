// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.model

import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull

/**
 * The expressions a node carries, walked and rewritten in one place (AU-core-P2) — the recipe lowerings rewrite them
 * (`:as_of ± duration`, the calendar counts) and the SQL emitter resolves their qualified column references.
 */
object NodeExpressions {
    /** Every sub-expression of [e], [e] first. */
    fun walk(e: Expression): List<Expression> =
        listOf(e) +
            when (e) {
                is FunctionCall -> e.args.flatMap { walk(it) }
                is AggregateCall -> e.args.flatMap { walk(it) }
                is Cast -> walk(e.expr)
                is CaseWhen ->
                    e.branches.flatMap { walk(it.first) + walk(it.second) } +
                        (e.elseExpr?.let { walk(it) } ?: emptyList())
                is InList -> walk(e.expr) + e.items.flatMap { walk(it) }
                is IsNull -> walk(e.expr)
                else -> emptyList()
            }

    /** [e] with every sub-expression [f] answers for replaced (bottom-up; null keeps it). */
    fun rewrite(
        e: Expression,
        f: (Expression) -> Expression?,
    ): Expression {
        val inner =
            when (e) {
                is FunctionCall -> e.copy(args = e.args.map { rewrite(it, f) })
                is AggregateCall -> e.copy(args = e.args.map { rewrite(it, f) })
                is Cast -> e.copy(expr = rewrite(e.expr, f))
                is CaseWhen ->
                    e.copy(
                        branches = e.branches.map { (w, t) -> rewrite(w, f) to rewrite(t, f) },
                        elseExpr = e.elseExpr?.let { rewrite(it, f) },
                    )
                is InList -> e.copy(expr = rewrite(e.expr, f), items = e.items.map { rewrite(it, f) })
                is IsNull -> e.copy(expr = rewrite(e.expr, f))
                else -> e
            }
        return f(inner) ?: inner
    }

    /** The expressions [n] carries. */
    fun of(n: Node): List<Expression> =
        when (n) {
            is Filter -> listOfNotNull(n.predicate)
            is Branch -> listOfNotNull(n.predicate)
            is Calc -> n.assignments.map { it.value }
            is Project -> n.columns
            is Join -> listOfNotNull(n.on)
            is Aggregate -> n.aggregations.map { it.value } + listOfNotNull(n.having)
            is TimeSugar -> n.args.map { it.second }
            else -> emptyList()
        }

    /** [n] with every expression it carries mapped by [f]. */
    fun map(
        n: Node,
        f: (Expression) -> Expression,
    ): Node =
        when (n) {
            is Filter -> n.copy(predicate = n.predicate?.let(f))
            is Branch -> n.copy(predicate = n.predicate?.let(f))
            is Calc -> n.copy(assignments = n.assignments.map { it.copy(value = f(it.value)) })
            is Project -> n.copy(columns = n.columns.map(f))
            is Join -> n.copy(on = n.on?.let(f))
            is Aggregate ->
                n.copy(
                    aggregations = n.aggregations.map { it.copy(value = f(it.value)) },
                    having = n.having?.let(f),
                )
            is TimeSugar -> n.copy(args = n.args.map { (k, v) -> k to f(v) })
            else -> n
        }
}
