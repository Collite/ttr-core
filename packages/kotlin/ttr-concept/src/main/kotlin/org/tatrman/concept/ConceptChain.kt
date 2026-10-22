// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.CnParticipant
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.QualifiedName

/**
 * What a concept specializes, and what that lets it stand for (CN-P2 S2.6's resolver, shared since CN-P3 S3.2 — the
 * definitions and the frame validators read one lattice): a role's or a phase's `of:` and every `is_a`, breadth first.
 */
internal class ConceptChain(
    private val cn: CnSchema,
) {
    private fun of(q: QualifiedName?): Concept? = q?.let { cn.concepts[it] }

    /** [c] and what it specializes — a role's or a phase's `of:`, every `is_a` — breadth first. */
    fun chain(c: Concept): List<Concept> {
        val out = linkedMapOf(c.qname to c)
        val queue = ArrayDeque(listOf(c))
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            for (next in listOfNotNull(of(x.of)) + x.isA.mapNotNull { of(it) }) {
                if (out.putIfAbsent(next.qname, next) == null) queue += next
            }
        }
        return out.values.toList()
    }

    /** Whether a [want] slot takes a [got] row: one specializes the other. */
    fun compatible(
        want: Concept,
        got: Concept,
    ): Boolean = chain(got).any { it.qname == want.qname } || chain(want).any { it.qname == got.qname }

    /** [s]'s participant labelled [label] — its own `involves:`, else up its chain — and the concept declaring it. */
    fun participant(
        s: Concept,
        label: String,
    ): Pair<Concept, CnParticipant>? {
        for (c in chain(s)) c.involves.firstOrNull { it.label == label }?.let { return c to it }
        return null
    }
}
