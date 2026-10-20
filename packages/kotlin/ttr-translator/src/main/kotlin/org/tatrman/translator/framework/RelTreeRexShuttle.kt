// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.rex.RexSubQuery

/**
 * A [RexShuttle] applied to every expression of a whole [RelNode] tree: each node's own expressions,
 * every input's, and those of every sub-query body at any depth. Subclass it, override the `visit*`
 * methods the pass needs, and call [rewrite].
 *
 * Calcite gives neither half on its own. `RelNode.accept(RexShuttle)` rewrites only a node's OWN
 * expressions, not its inputs, so a bare `rel.accept(shuttle)` misses a Filter/Join below the top
 * node. And `RexShuttle.visitSubQuery` visits only a sub-query's operands (an `IN`'s left-hand side),
 * never `RexSubQuery.rel`. ttr-core#159 is what missing the second half cost one pass; every
 * rel-rewriting pass in this module extends this class so the walk lives in one place.
 */
abstract class RelTreeRexShuttle : RexShuttle() {
    /** The rewritten tree; [rel] itself (same instance) when no expression changed. */
    fun rewrite(rel: RelNode): RelNode {
        val newInputs = rel.inputs.map { rewrite(it) }
        val withInputs = if (newInputs == rel.inputs) rel else rel.copy(rel.traitSet, newInputs)
        return visitRel(withInputs.accept(this))
    }

    /** Each node once its inputs and its own expressions are rewritten: override it to change the node itself. */
    protected open fun visitRel(rel: RelNode): RelNode = rel

    override fun visitSubQuery(subQuery: RexSubQuery): RexNode {
        val visited = super.visitSubQuery(subQuery) as RexSubQuery
        val body = rewrite(visited.rel)
        return if (body === visited.rel) visited else visited.clone(body)
    }
}
