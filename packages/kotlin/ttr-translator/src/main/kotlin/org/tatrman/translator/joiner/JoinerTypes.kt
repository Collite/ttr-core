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
 * ttr-server#232 — a **bare** join (`JOIN` with no `ON`) the SQL carrier `ModelJoinRewriter` could not condition
 * from the model's relations (none, or more than one) and left `ON TRUE`: the ER entities of each side, as the wire
 * carrier will see them. A side with no entity (a derived table) is empty, and so is a temporary entity's
 * contribution, which the wire carrier sees only as its inlined body.
 *
 * The SQL carrier knows a join was written bare; only the wire carriers know whether it stayed a Cartesian product,
 * since `JoinerPhysical` may still condition it from a table foreign key. [refusedBy] joins the two.
 */
data class BareJoin(
    val left: List<QualifiedName>,
    val right: List<QualifiedName>,
) {
    /**
     * The final verdict ([JoinerWarnings.merge]) that leaves this join a Cartesian product, or null: a
     * [JoinerWarning.NoRelation] or [JoinerWarning.AmbiguousRelations] on exactly these sides, entity for entity.
     * A verdict on other sides, a join the physical stage conditioned, or a side the carriers saw differently
     * finds nothing, and the join keeps its warning as before.
     */
    fun refusedBy(verdict: List<JoinerWarning>): JoinerWarning? =
        verdict.firstOrNull { w ->
            when (w) {
                is JoinerWarning.NoRelation -> sameSides(w.leftEntities, w.rightEntities)
                is JoinerWarning.AmbiguousRelations -> sameSides(w.leftEntities, w.rightEntities)
                else -> false
            }
        }

    private fun sameSides(
        l: List<QualifiedName>,
        r: List<QualifiedName>,
    ): Boolean = sorted(l) == sorted(left) && sorted(r) == sorted(right)

    private fun sorted(qs: List<QualifiedName>): List<String> =
        qs.map { "${it.schemaCode.number}:${it.namespace}.${it.name}" }.sorted()
}

/**
 * What EXPAND_JOINS could not condition and why. Codes and texts: `JoinerMessages` (MJ-P3; the cz names
 * `JOIN_NO_RELATION` / `JOIN_AMBIGUOUS_RELATIONS` / `JOIN_RELATION_WITHOUT_PAIRS` / `JOIN_KEY_NAME_COLLISION`
 * that Golem demotes on — ⚑MJ-5).
 *
 * MJ (⚑MJ-2): a join is decided against the **whole** entity set of each side, so [NoRelation] and
 * [AmbiguousRelations] carry those sets (`leftEntities` / `rightEntities`, tree order). `sideA` / `sideB`
 * are the sets' first members — the two `QualifiedName` slots the proto message keeps; the text renders the
 * lists (contracts §4). A side the wire carrier cannot see into (a derived table, review-097 R2) is an empty
 * list, and its slot is `QualifiedName.getDefaultInstance()`.
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
