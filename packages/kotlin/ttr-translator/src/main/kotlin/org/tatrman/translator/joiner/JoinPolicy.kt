// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation

/**
 * MJ (model joins) — the ONE place that decides whether an unconditioned join between two sides resolves
 * from the model's declared relations, and on which attribute pairs. Both carriers call it:
 * `codec/sql/ModelJoinRewriter` (SQL, pre-validation — handles are SQL aliases) and [JoinerLogical] (wire,
 * EXPAND_JOINS-logical — handles are scans). Pure Kotlin: no Calcite, no proto beyond [QualifiedName].
 *
 * Contract (project `model-joins/contracts.md §2`, ⚑MJ-2/6/7/8 ruled 2026-09-21):
 *  1. candidates = every `(l, r, rel)` with `l ∈ left`, `r ∈ right`, `rel` connecting them in either direction;
 *  2. an entity qname occurring more than once in `left ∪ right` (both sides counted together) drops every
 *     candidate naming it; if anything was dropped the join is [Verdict.Ambiguous] with the entity in
 *     [Verdict.Ambiguous.repeated]; an entity on both sides of the join (self-join) always is. A repeated
 *     entity within one side that no candidate names does not block the join — `(dm ⋈ oz ON … ⋈ vot ON …)
 *     ⋈ kp` still resolves `kp ↔ dm` (contracts §2 amendment C-1, review-097 R1);
 *  3. exactly one remaining candidate → [Verdict.Resolved]; none → [Verdict.NoRelation]; two or more →
 *     [Verdict.Ambiguous]. No proximity tie-break — deterministic on (statement, model) only;
 *  4. a resolved relation with no `joinPairs` → [Verdict.WithoutPairs] (FK-bound; JoinerPhysical may fill it);
 *  5. [Verdict.Resolved.pairs] carries ALL pairs (composite relations AND them), each already oriented
 *     left/right and reduced to the bare attribute name.
 *
 * This is the seam the v1.0 KDoc of [JoinerLogical] promised for "transitive chaining": path inference
 * (gated v2, `design.md §6`) plugs into [resolve] without either carrier changing.
 */
object JoinPolicy {
    /** One entity reference on a join side; [handle] is carrier-specific (SQL alias, wire scan, …). */
    data class SideRef<H>(
        val entity: QualifiedName,
        val handle: H,
    )

    /** A relation that could join `left` to `right`. */
    data class Candidate<H>(
        val left: SideRef<H>,
        val right: SideRef<H>,
        val relation: ModelRelation,
    )

    sealed interface Verdict<H> {
        data class Resolved<H>(
            val left: SideRef<H>,
            val right: SideRef<H>,
            val relation: ModelRelation,
            /** `(leftAttribute, rightAttribute)` bare names, oriented to the sides, all pairs of the relation. */
            val pairs: List<Pair<String, String>>,
        ) : Verdict<H>

        data class NoRelation<H>(
            val left: List<QualifiedName>,
            val right: List<QualifiedName>,
        ) : Verdict<H>

        data class Ambiguous<H>(
            val left: List<QualifiedName>,
            val right: List<QualifiedName>,
            val candidates: List<Candidate<H>>,
            /** Entities occurring more than once across both sides; empty when the ambiguity is purely relational. */
            val repeated: Set<QualifiedName>,
        ) : Verdict<H>

        data class WithoutPairs<H>(
            val left: SideRef<H>,
            val right: SideRef<H>,
            val relation: ModelRelation,
        ) : Verdict<H>
    }

    fun <H> resolve(
        model: ModelHandle,
        left: List<SideRef<H>>,
        right: List<SideRef<H>>,
    ): Verdict<H> {
        val leftNames = left.map { it.entity }
        val rightNames = right.map { it.entity }
        val repeated: Set<QualifiedName> =
            (leftNames + rightNames)
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val relations = model.relations()
        val all: List<Candidate<H>> =
            left.flatMap { l ->
                right.flatMap { r ->
                    relations
                        .filter { rel -> connects(rel, l.entity, r.entity) }
                        .map { rel -> Candidate(l, r, rel) }
                }
            }
        val (dropped, candidates) = all.partition { it.left.entity in repeated || it.right.entity in repeated }
        // C-1: a repeated entity blocks the join only when it is party to a candidate, or when it sits on BOTH
        // sides of this join (a self-join always needs its ON, related or not — H8).
        val partyToCandidate = dropped.flatMap { listOf(it.left.entity, it.right.entity) }.filter { it in repeated }
        val onBothSides = leftNames.filter { it in rightNames }
        val blocking = (partyToCandidate + onBothSides).toSet()
        return when {
            blocking.isNotEmpty() -> Verdict.Ambiguous(leftNames, rightNames, all, blocking)
            candidates.isEmpty() -> Verdict.NoRelation(leftNames, rightNames)
            candidates.size > 1 -> Verdict.Ambiguous(leftNames, rightNames, candidates, emptySet())
            else -> {
                val c = candidates.single()
                if (c.relation.joinPairs.isEmpty()) {
                    Verdict.WithoutPairs(c.left, c.right, c.relation)
                } else {
                    Verdict.Resolved(c.left, c.right, c.relation, orientedPairs(c.relation, c.left.entity))
                }
            }
        }
    }

    private fun connects(
        rel: ModelRelation,
        a: QualifiedName,
        b: QualifiedName,
    ): Boolean = (rel.fromEntity == a && rel.toEntity == b) || (rel.fromEntity == b && rel.toEntity == a)

    /**
     * All join pairs of [relation] as `(leftAttr, rightAttr)` bare names, given that [leftEntity] is the
     * entity on the join's left side. Attribute qnames carry `<entity>.<attribute>` in `name`; the bare
     * attribute is what both a Scan's row type and the validator's alias scope expose (unchanged from v1.0
     * `JoinerLogical.buildEqualityCondition`, now shared by both carriers).
     */
    fun orientedPairs(
        relation: ModelRelation,
        leftEntity: QualifiedName,
    ): List<Pair<String, String>> =
        relation.joinPairs.map { (fromAttr, toAttr) ->
            val from = bareAttribute(fromAttr)
            val to = bareAttribute(toAttr)
            if (relation.fromEntity == leftEntity) from to to else to to from
        }

    fun bareAttribute(attribute: QualifiedName): String = attribute.name.substringAfterLast('.')
}
