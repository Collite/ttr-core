// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.ModelRelation

/**
 * MJ-P3 (contracts §4) — the ONE rendering of a [JoinerWarning] as a wire message: the code an operator
 * greps for, the severity, and the sentence a user (or the LLM lane) reads. `services/translator` maps
 * 1:1 into `ResponseMessage(severity, code, human_message)`; Golem keys its demotion on the codes
 * (`classify_and_plan.JOIN_WARNING_CODES`), which is why they are the cz names (⚑MJ-5).
 */
object JoinerMessages {
    const val NO_RELATION = "JOIN_NO_RELATION"
    const val AMBIGUOUS_RELATIONS = "JOIN_AMBIGUOUS_RELATIONS"
    const val RELATION_WITHOUT_PAIRS = "JOIN_RELATION_WITHOUT_PAIRS"
    const val KEY_NAME_COLLISION = "JOIN_KEY_NAME_COLLISION"

    enum class Severity { INFO, WARNING }

    fun code(warning: JoinerWarning): String =
        when (warning) {
            is JoinerWarning.NoRelation -> NO_RELATION
            is JoinerWarning.AmbiguousRelations -> AMBIGUOUS_RELATIONS
            is JoinerWarning.RelationWithoutJoinPairs -> RELATION_WITHOUT_PAIRS
            is JoinerWarning.KeyNameCollision -> KEY_NAME_COLLISION
        }

    fun severity(warning: JoinerWarning): Severity =
        when (warning) {
            is JoinerWarning.RelationWithoutJoinPairs -> Severity.INFO
            else -> Severity.WARNING
        }

    fun text(warning: JoinerWarning): String = "${reason(warning)}; ${fallback(warning)}"

    /** What the carrier found: the first half of [text]. */
    private fun reason(warning: JoinerWarning): String =
        when (warning) {
            is JoinerWarning.NoRelation ->
                "no declared relation between ${names(warning.leftEntities)} and ${names(warning.rightEntities)}"
            is JoinerWarning.AmbiguousRelations ->
                when {
                    warning.repeated.isNotEmpty() ->
                        "entity ${warning.repeated.joinToString { name(it) }} appears more than once in the join"
                    warning.candidateRelations.isEmpty() ->
                        "more than one foreign key could join ${names(warning.leftEntities)} and " +
                            names(warning.rightEntities)
                    else -> {
                        val candidates = warning.candidateRelations.joinToString { relation(it) }
                        "${warning.candidateRelations.size} relations could join ${names(warning.leftEntities)} and " +
                            "${names(warning.rightEntities)}: $candidates"
                    }
                }
            is JoinerWarning.RelationWithoutJoinPairs ->
                "relation ${relation(warning.relation)} declares no join pairs"
            is JoinerWarning.KeyNameCollision ->
                "join key ${warning.attribute} exists on more than one entity of the " +
                    "${warning.side.name.lowercase()} side"
        }

    /** What became of the join: the second half of [text]. */
    private fun fallback(warning: JoinerWarning): String =
        when (warning) {
            is JoinerWarning.NoRelation -> "Cartesian product preserved"
            is JoinerWarning.AmbiguousRelations -> "write the ON"
            is JoinerWarning.RelationWithoutJoinPairs -> "left to its foreign key"
            is JoinerWarning.KeyNameCollision -> "Cartesian product preserved — write the ON"
        }

    /**
     * ttr-server#232 — the code of a parse refused because a bare `JOIN` stayed a Cartesian product
     * (`Translator.parseToRelNode(refuseUnconditionedBareJoins = true)`). Lower case, like the translator's other
     * failure codes; the `JOIN_*` codes above are warnings.
     */
    const val UNCONDITIONED = "join_unconditioned"

    /**
     * The refusal's text for one join: why neither carrier conditioned it, from what each reported on that join
     * ([logical] names the entities the author wrote, [physical] the tables), and the two ways out.
     */
    fun refusal(
        logical: JoinerWarning?,
        physical: JoinerWarning?,
    ): String {
        val why =
            when (logical) {
                is JoinerWarning.NoRelation ->
                    if (physical == null || physical is JoinerWarning.NoRelation) {
                        noRelationOrKey(logical.leftEntities, logical.rightEntities)
                    } else {
                        "no relation joins ${names(logical.leftEntities)} and ${names(logical.rightEntities)}, and " +
                            physicalReason(physical)
                    }
                // Left to the foreign key, which did not condition it either: both halves of the story.
                is JoinerWarning.RelationWithoutJoinPairs ->
                    reason(logical) + (physical?.let { ", and ${physicalReason(it)}" } ?: "")
                // The logical carrier did not decide the join (a side with no entity it can see): the physical one did.
                null ->
                    when (physical) {
                        is JoinerWarning.NoRelation -> noRelationOrKey(physical.leftEntities, physical.rightEntities)
                        null -> "the model has no relation or foreign key for it"
                        else -> reason(physical)
                    }
                else -> reason(logical)
            }
        return "a JOIN without ON that the model cannot condition: $why; write the ON, " +
            "or CROSS JOIN if every pairing is meant"
    }

    private fun noRelationOrKey(
        left: List<QualifiedName>,
        right: List<QualifiedName>,
    ): String = "no relation or foreign key joins ${names(left)} and ${names(right)}"

    /** A table-level reason: a physical `NoRelation` is about foreign keys, not declared relations. */
    private fun physicalReason(warning: JoinerWarning): String =
        when (warning) {
            is JoinerWarning.NoRelation ->
                "no foreign key joins ${names(warning.leftEntities)} and ${names(warning.rightEntities)}"
            else -> reason(warning)
        }

    private fun name(q: QualifiedName): String = if (q.namespace.isEmpty()) q.name else "${q.namespace}.${q.name}"

    /** An empty side is a derived table the wire carrier does not look into (review-097 R2). */
    private fun names(qs: List<QualifiedName>): String =
        if (qs.isEmpty()) "a derived table" else qs.joinToString { name(it) }

    /** `from → to (a = b, c = d)` — a relation has no name of its own on the model handle. */
    private fun relation(r: ModelRelation): String {
        val pairs =
            r.joinPairs.joinToString { (f, t) -> "${JoinPolicy.bareAttribute(f)} = ${JoinPolicy.bareAttribute(t)}" }
        return "${r.fromEntity.name} → ${r.toEntity.name}" + if (pairs.isEmpty()) "" else " ($pairs)"
    }
}

/**
 * MJ — reconciles the two carriers' verdicts on the same joins (contracts §4: one verdict per join).
 * With `targetSchema = DB` an unconditioned join is seen by [JoinerLogical] (entities) and then, after
 * MAP_TO_PHYSICAL, by [JoinerPhysical] (tables, foreign keys):
 *
 *  - the physical carrier **conditioned** a join the logical one warned on → the logical warning is
 *    stale and dropped, except [JoinerWarning.RelationWithoutJoinPairs], whose text says exactly that
 *    ("left to its foreign key");
 *  - both **warned** on the same join → keep the logical (entity-level) warning, which names what the
 *    author wrote; the physical duplicate is dropped — unless the logical one was
 *    `RelationWithoutJoinPairs` (INFO), in which case the physical `NoRelation` is the real news and the
 *    INFO is dropped (review-097 R3: no FK joined it);
 *  - a join only one carrier saw (a mixed entity/table side, a pure-DB statement) → reported as is.
 *
 * Order: logical first, then physical — the order the stages ran.
 */
object JoinerWarnings {
    fun merge(
        logical: JoinerResult,
        physical: JoinerResult,
    ): List<JoinerWarning> {
        // ttr-core#204 — the physical carrier reports a side it cannot see a table through as `NoRelation` with that
        // side empty. That is news only where the logical carrier left the join to its FK (INFO,
        // RelationWithoutJoinPairs); anywhere else the join was the logical carrier's to report, or a pure-DB
        // statement's derived table, as before.
        val leftToForeignKey =
            logical.outcomes
                .filter { it.warning is JoinerWarning.RelationWithoutJoinPairs }
                .map { it.joinPath }
                .toSet()
        val physicalOutcomes =
            physical.outcomes.filter { o ->
                val w = o.warning
                !(w is JoinerWarning.NoRelation && (w.leftEntities.isEmpty() || w.rightEntities.isEmpty())) ||
                    o.joinPath in leftToForeignKey
            }
        val conditionedByPhysical =
            physicalOutcomes
                .filter { it.warning == null }
                .map { it.joinPath }
                .toSet()
        val warnedByPhysical =
            physicalOutcomes
                .filter { it.warning != null }
                .map { it.joinPath }
                .toSet()
        val keptLogical =
            logical.outcomes.mapNotNull { o ->
                val w = o.warning ?: return@mapNotNull null
                when {
                    w is JoinerWarning.RelationWithoutJoinPairs -> w.takeIf { o.joinPath !in warnedByPhysical }
                    o.joinPath in conditionedByPhysical -> null
                    else -> w
                }
            }
        val decidedByLogical =
            logical.outcomes
                .filter { it.warning != null && it.warning !is JoinerWarning.RelationWithoutJoinPairs }
                .map { it.joinPath }
                .toSet()
        val keptPhysical = physicalOutcomes.mapNotNull { o -> o.warning?.takeIf { o.joinPath !in decidedByLogical } }
        return keptLogical + keptPhysical
    }

    /**
     * ttr-server#232 — the refusal text for the first join of [plan] (the physical stage's result) that is still marked
     * `condition_required` and has no condition, or null when there is none. The mark is the SQL carrier's, left on
     * this call or on the ER half before it, so the decision is per join: no other join's verdict can refuse it or
     * excuse it. The text is built from what each carrier reported on that join ([JoinerMessages.refusal]).
     */
    fun unconditioned(
        plan: PlanNode,
        logical: JoinerResult,
        physical: JoinerResult,
    ): String? {
        val path = firstUnconditioned(plan, emptyList()) ?: return null

        fun on(result: JoinerResult) = result.outcomes.firstOrNull { it.joinPath == path }?.warning
        return JoinerMessages.refusal(on(logical), on(physical))
    }

    /** Post-order, child ordinals as [JoinerPlanWalker.rewriteChildren] visits them: the joiners' [JoinOutcome.joinPath]. */
    private fun firstUnconditioned(
        plan: PlanNode,
        path: List<Int>,
    ): List<Int>? {
        var child = 0
        var found: List<Int>? = null
        JoinerPlanWalker.rewriteChildren(plan) { c ->
            val childPath = path + child++
            if (found == null) found = firstUnconditioned(c, childPath)
            c
        }
        if (found != null) return found
        val join = plan.join
        return path.takeIf {
            plan.nodeCase == PlanNode.NodeCase.JOIN && join.conditionRequired && !join.hasCondition()
        }
    }
}
