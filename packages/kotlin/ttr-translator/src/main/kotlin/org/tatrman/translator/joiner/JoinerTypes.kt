// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.ModelRelation

/**
 * Per-stage result for [JoinerLogical] and [JoinerPhysical]. Both stages are non-failing —
 * unresolved or ambiguous joins fall back to Cartesian and surface a [JoinerWarning] for the
 * orchestrator to forward as a `ResponseMessage(severity=WARNING, code=...)`.
 */
data class JoinerResult(
    val plan: PlanNode,
    val warnings: List<JoinerWarning> = emptyList(),
)

sealed interface JoinerWarning {
    /** Wire form: `ResponseMessage(severity=WARNING, code="join_unresolved_cartesian", ...)`. */
    data class NoRelation(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
    ) : JoinerWarning

    /** Wire form: `ResponseMessage(severity=WARNING, code="join_ambiguous_multiple_relations", ...)`. */
    data class AmbiguousRelations(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
        val candidateRelations: List<ModelRelation>,
    ) : JoinerWarning

    /**
     * Exactly one relation connects the two entities, but it carries no attribute join pairs — it is
     * bound to its FK only (`binding: { fk: … }`, no `join:` list). The logical Joiner cannot build a
     * condition from it and leaves the join unconditioned; [JoinerPhysical] fills it from that FK
     * after MAP_TO_PHYSICAL.
     *
     * Wire form: `ResponseMessage(severity=WARNING, code="join_relation_without_pairs", ...)`.
     */
    data class RelationWithoutJoinPairs(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
        val relation: ModelRelation,
    ) : JoinerWarning
}
