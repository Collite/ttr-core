// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Grounding
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.Relation

/**
 * How a participant reaches E-R (CN-P3 S3.5 — lifted out of S3.4's renderer, which joins along it; the assertion
 * lowering writes the foreign key it starts with): the label's relations, from the cn2er grounding up the owner's chain
 * that states them (`Grounding.participants`, L15-3 — holder first; a relation reads forward when its `from` holds the
 * key, else backward), or — a value kind with no path (AM-27) — the column of the owner's entity its grounding targets.
 */
internal class ParticipantPaths(
    model: ResolvedCnModel,
) {
    private val cn = model.cn
    private val er = model.model.schemas["er"] as? ErSchema
    private val lattice = ConceptChain(cn)

    sealed interface Path

    /** The label's relations, in order, as [grounding] states them. */
    data class Relations(
        val grounding: Grounding,
        val relations: List<Relation>,
    ) : Path

    /** A value kind's column — `er.entity.<entity>.<attribute>` — read (or written) on the owner's own row. */
    data class Column(
        val entity: String,
        val attribute: String,
    ) : Path

    fun relation(ref: String): Relation? {
        val name = ref.removePrefix("er.relation.")
        return er?.relations?.values?.firstOrNull { it.qname.name == name }
    }

    /** The cn2er grounding up [c]'s chain that names [pick] (its participants, its period). */
    fun grounding(
        c: Concept,
        pick: (Grounding) -> Boolean,
    ): Grounding? =
        lattice.chain(c).firstNotNullOfOrNull { x -> x.grounding?.takeIf { it.layer == GroundingLayer.ER && pick(it) } }

    /**
     * [owner]'s participant [label]: its relations, or the column [concept] — what stands for the participant, its
     * declared concept by default — lands on; null when it has neither (`cn/participant-path-broken`).
     */
    fun of(
        owner: Concept,
        label: String,
        concept: Concept? = null,
    ): Path? {
        val g = grounding(owner) { label in it.participants }
        val names = g?.participants?.get(label).orEmpty()
        if (g != null) return Relations(g, names.map { relation(it) ?: return null })
        val declared = lattice.participant(owner, label)?.second?.concept
        val stands = concept ?: declared?.let { cn.concepts[it] } ?: return null
        val column =
            when (val v = valued(owner, label, stands)) {
                Valued.None -> stands.erTarget
                Valued.Ambiguous -> return null
                is Valued.One -> v.quality.erTarget ?: return null
            }
        val (entity, attribute) = attributeRef(column) ?: return null
        return Column(entity, attribute)
    }

    private sealed interface Valued {
        data object None : Valued

        data object Ambiguous : Valued

        data class One(
            val quality: Concept,
        ) : Valued
    }

    /**
     * The owner's quality valued in [stands] (a value-kind participant read through it, AM-27): the one named [label],
     * else the only one, at the nearest level of the chain that has any. Several with no label match read no column —
     * `cn/participant-path-broken`, never the first of them.
     */
    private fun valued(
        owner: Concept,
        label: String,
        stands: Concept,
    ): Valued {
        for (c in lattice.chain(owner)) {
            val candidates =
                cn.concepts.values.filter {
                    it.stereotype == "quality" && it.of == c.qname && it.valueSpace == stands.qname
                }
            if (candidates.isEmpty()) continue
            val pick = candidates.firstOrNull { it.qname.name == label } ?: candidates.singleOrNull()
            return pick?.let(Valued::One) ?: Valued.Ambiguous
        }
        return Valued.None
    }

    companion object {
        /** `er.entity.<e>.<a>` → (`<e>`, `<a>`); anything else → null. */
        fun attributeRef(ref: String?): Pair<String, String>? {
            val segs = ref?.split('.') ?: return null
            if (segs.size != 4 || segs[0] != "er" || segs[1] != "entity") return null
            return segs[2] to segs[3]
        }
    }
}
