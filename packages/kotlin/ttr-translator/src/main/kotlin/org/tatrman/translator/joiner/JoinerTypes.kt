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
    /** MJ — one entry per unconditioned join this stage decided (conditioned or warned), by tree path. */
    val outcomes: List<JoinOutcome> = emptyList(),
)

/**
 * MJ — what a carrier did with one unconditioned join, keyed by the join's position in the tree so the
 * orchestrator can reconcile the two stages ([JoinerWarnings.merge]): [joinPath] is the sequence of
 * child ordinals from the plan root in [JoinerPlanWalker.rewriteChildren]'s visiting order. MAP_TO_PHYSICAL
 * only rewrites *below* scan leaves (a table, a filtered table, a query body), so a join's path is the
 * same before and after it. [warning] is null when the stage conditioned the join.
 */
data class JoinOutcome(
    val joinPath: List<Int>,
    val warning: JoinerWarning?,
)

/**
 * What EXPAND_JOINS could not condition and why. Codes and texts: `JoinerMessages` (MJ-P3; the cz names
 * `JOIN_NO_RELATION` / `JOIN_AMBIGUOUS_RELATIONS` / `JOIN_RELATION_WITHOUT_PAIRS` / `JOIN_KEY_NAME_COLLISION`
 * that Golem demotes on — ⚑MJ-5).
 *
 * MJ (⚑MJ-2): a join is decided against the **whole** entity set of each side, so [NoRelation] and
 * [AmbiguousRelations] carry those sets (`leftEntities` / `rightEntities`, tree order). `sideA` / `sideB`
 * are the sets' first members — the two `QualifiedName` slots the proto message keeps; the text renders the
 * lists (contracts §4).
 */
sealed interface JoinerWarning {
    /** Which input of the join a [KeyNameCollision] is on. */
    enum class Side { LEFT, RIGHT }

    /** Code `JOIN_NO_RELATION` (was `join_unresolved_cartesian`), WARNING. */
    data class NoRelation(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
        val leftEntities: List<QualifiedName> = listOf(sideA),
        val rightEntities: List<QualifiedName> = listOf(sideB),
    ) : JoinerWarning

    /**
     * Code `JOIN_AMBIGUOUS_RELATIONS` (was `join_ambiguous_multiple_relations`), WARNING. Two or more
     * relations could join the sides — or ([repeated] non-empty, ⚑MJ-6) an entity occurs more than once
     * across the join (self-join, two aliases of one entity) and the carrier refuses to guess which one.
     */
    data class AmbiguousRelations(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
        val candidateRelations: List<ModelRelation>,
        val repeated: Set<QualifiedName> = emptySet(),
        val leftEntities: List<QualifiedName> = listOf(sideA),
        val rightEntities: List<QualifiedName> = listOf(sideB),
    ) : JoinerWarning

    /**
     * Exactly one relation connects the two entities, but it carries no attribute join pairs — it is
     * bound to its FK only (`binding: { fk: … }`, no `join:` list). The logical Joiner cannot build a
     * condition from it and leaves the join unconditioned; [JoinerPhysical] fills it from that FK
     * after MAP_TO_PHYSICAL.
     *
     * Code `JOIN_RELATION_WITHOUT_PAIRS` (was `join_relation_without_pairs`), INFO.
     */
    data class RelationWithoutJoinPairs(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
        val relation: ModelRelation,
    ) : JoinerWarning

    /**
     * Wire carrier only (F3b). The join resolved, but the chosen [attribute] exists on more than one
     * entity of the [side] it is read from — a bare `$L`/`$R` name ref would resolve to the FIRST holder
     * (`RelBuilder.field(inputCount, ord, name)`), silently mis-joining when the chosen entity is not it.
     * Left unconditioned; the author writes the ON (the SQL carrier never hits this: Calcite scopes the
     * aliases). Code `JOIN_KEY_NAME_COLLISION`, WARNING.
     */
    data class KeyNameCollision(
        val sideA: QualifiedName,
        val sideB: QualifiedName,
        val relation: ModelRelation,
        val attribute: String,
        val side: Side,
    ) : JoinerWarning
}
