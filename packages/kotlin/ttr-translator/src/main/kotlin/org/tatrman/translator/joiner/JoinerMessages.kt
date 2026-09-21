// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

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

    fun text(warning: JoinerWarning): String =
        when (warning) {
            is JoinerWarning.NoRelation ->
                "no declared relation between ${names(warning.leftEntities)} and ${names(warning.rightEntities)}; " +
                    "Cartesian product preserved"
            is JoinerWarning.AmbiguousRelations ->
                when {
                    warning.repeated.isNotEmpty() ->
                        "entity ${warning.repeated.joinToString {
                            name(
                                it,
                            )
                        }} appears more than once in the join; write the ON"
                    warning.candidateRelations.isEmpty() ->
                        "more than one foreign key could join ${names(warning.leftEntities)} and " +
                            "${names(warning.rightEntities)}; write the ON"
                    else ->
                        "${warning.candidateRelations.size} relations could join ${names(warning.leftEntities)} and " +
                            "${names(
                                warning.rightEntities,
                            )}: ${warning.candidateRelations.joinToString { relation(it) }}; " +
                            "write the ON"
                }
            is JoinerWarning.RelationWithoutJoinPairs ->
                "relation ${relation(warning.relation)} declares no join pairs; joined from its foreign key"
            is JoinerWarning.KeyNameCollision ->
                "join key ${warning.attribute} exists on more than one entity of the " +
                    "${warning.side.name.lowercase()} side; Cartesian product preserved — write the ON"
        }

    private fun name(q: QualifiedName): String = if (q.namespace.isEmpty()) q.name else "${q.namespace}.${q.name}"

    private fun names(qs: List<QualifiedName>): String = qs.joinToString { name(it) }

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
 *    ("joined from its foreign key");
 *  - both **warned** on the same join → keep the logical (entity-level) warning, which names what the
 *    author wrote; the physical duplicate is dropped — unless the logical one was
 *    `RelationWithoutJoinPairs` (INFO), in which case the physical `NoRelation` is the real news;
 *  - a join only one carrier saw (a mixed entity/table side, a pure-DB statement) → reported as is.
 *
 * Order: logical first, then physical — the order the stages ran.
 */
object JoinerWarnings {
    fun merge(
        logical: JoinerResult,
        physical: JoinerResult,
    ): List<JoinerWarning> {
        val conditionedByPhysical =
            physical.outcomes
                .filter { it.warning == null }
                .map { it.joinPath }
                .toSet()
        val keptLogical =
            logical.outcomes.mapNotNull { o ->
                val w = o.warning ?: return@mapNotNull null
                if (o.joinPath in conditionedByPhysical && w !is JoinerWarning.RelationWithoutJoinPairs) null else w
            }
        val decidedByLogical =
            logical.outcomes
                .filter { it.warning != null && it.warning !is JoinerWarning.RelationWithoutJoinPairs }
                .map { it.joinPath }
                .toSet()
        val keptPhysical = physical.outcomes.mapNotNull { o -> o.warning?.takeIf { o.joinPath !in decidedByLogical } }
        return keptLogical + keptPhysical
    }
}
