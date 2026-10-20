// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.apache.calcite.rel.hint.HintPredicate
import org.apache.calcite.rel.hint.HintPredicates
import org.apache.calcite.rel.hint.HintStrategyTable
import org.tatrman.translator.wire.ConditionRequiredHint
import org.tatrman.translator.wire.ExpandedFromHint

/**
 * The hint strategies of every cluster the translator builds: the plan.v1 stamps that ride Calcite as hints
 * ([ConditionRequiredHint] on a Join, [ExpandedFromHint] on a Project).
 *
 * Calcite filters hints through the cluster's table whenever it propagates them — `RelDecorrelator` (a correlated
 * sub-query), a Hep rule's `transformTo`, `RelFieldTrimmer` — and asserts that every name is registered (without
 * `-ea`, an NPE): with the default empty table, a stamped plan with a correlated sub-query failed to parse. Each
 * stamp belongs to the one node it was put on, so each is accepted on its own node type only and only un-inherited
 * (an empty inherit path): propagation never copies it onto another node.
 */
internal object TtrHintStrategies {
    val TABLE: HintStrategyTable =
        HintStrategyTable
            .builder()
            .hintStrategy(ConditionRequiredHint.NAME, own(HintPredicates.JOIN))
            .hintStrategy(ExpandedFromHint.NAME, own(HintPredicates.PROJECT))
            .build()

    private fun own(on: HintPredicate): HintPredicate =
        HintPredicate { hint, rel -> hint.inheritPath.isEmpty() && on.apply(hint, rel) }
}
