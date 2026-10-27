// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.md.resolve.PathComponent
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.semantics.md.MdCubelet
import org.tatrman.ttr.semantics.md.MdModel

/**
 * An md element a concept reaches md by (CN-P3 S3.3 T1), parsed from the kinded element ref the typed model spells
 * (`md.measure.net`, `md.dimension.Customer`, `md.dimension.Time.day`, `md.cubelet.sales` — [CnSchema]'s spelling).
 */
internal sealed interface MdElement {
    /** The ref as a binding writes it, the kind left out (`md.net`, `md.Time.day`) — what a [BindingPath] names. */
    val written: String

    data class Measure(
        val name: String,
    ) : MdElement {
        override val written: String get() = "md.$name"
    }

    data class Dimension(
        val name: String,
    ) : MdElement {
        override val written: String get() = "md.$name"
    }

    data class Attribute(
        val dimension: String,
        val name: String,
    ) : MdElement {
        /** The resolver's spelling, `Dimension.attribute`. */
        val qname: String get() = "$dimension.$name"
        override val written: String get() = "md.$qname"
    }

    data class Cubelet(
        val name: String,
    ) : MdElement {
        override val written: String get() = "md.$name"
    }

    companion object {
        /** [ref] as an md element; null for any other ref (an md domain, an er or a db element). */
        fun parse(ref: String): MdElement? {
            val segs = ref.split('.')
            if (segs.size < 3 || segs[0] != "md") return null
            return when {
                segs[1] == "measure" && segs.size == 3 -> Measure(segs[2])
                segs[1] == "cubelet" && segs.size == 3 -> Cubelet(segs[2])
                segs[1] == "dimension" && segs.size == 3 -> Dimension(segs[2])
                segs[1] == "dimension" && segs.size == 4 -> Attribute(segs[2], segs[3])
                else -> null
            }
        }
    }
}

/**
 * One way a concept reaches md (S3.3 T1): the [element], the grounding def it went through ([bindingDef] — the er
 * grounding's for the reverse hop) and how: `DIRECT` — its own md grounding; `MD_HOP` — the reverse hop (⚑CN-P8,
 * A-CN-37: an md element whose hop lands on the concept's E-R landing).
 */
internal data class MdFace(
    val concept: Concept,
    val element: MdElement,
    val bindingDef: String,
    val via: Via,
) {
    fun path(slot: String): BindingPath = BindingPath(slot, concept.ref, bindingDef, element.written, via)

    companion object {
        /**
         * [c]'s md faces: its own md grounding (one, `DIRECT`); else every md element its E-R landing is reached from
         * (`MD_HOP`, in [Concept.reverseHop]'s sorted order — the carrier picks one); else none.
         */
        fun of(c: Concept): List<MdFace> {
            if (c.stereotype == "subkind" ||
                c.projection != null ||
                c.fromEvent != null ||
                c.grounding?.episodes != null
            ) {
                return emptyList() // coordinates cannot discard the subset predicate
            }
            val g = c.grounding
            if (g?.layer == GroundingLayer.MD) {
                val e = g.target?.let(MdElement::parse) ?: return emptyList()
                return listOf(MdFace(c, e, g.qname.name, Via.DIRECT))
            }
            return c.reverseHop.mapNotNull(MdElement::parse).map { MdFace(c, it, g?.qname?.name ?: "", Via.MD_HOP) }
        }
    }
}

/**
 * The carrying cubelet a statement lowers on (S3.3 T2), with what a slot asks of it: its [grain] (dimension → the grain
 * attribute, `Customer` → `Customer.name`) and the md [model] the coordinates are checked against.
 */
internal class Carrier(
    val cubelet: MdCubelet,
    val model: MdModel,
) {
    val name: String get() = cubelet.name

    val grain: Map<String, String> = cubelet.grain.associateBy { it.substringBeforeLast('.') }

    /** The attribute [face] constrains on this cubelet — a dimension's grain attribute, an attribute itself — or null. */
    fun attributeOf(face: MdFace): String? =
        when (val e = face.element) {
            is MdElement.Dimension -> grain[e.name]
            is MdElement.Attribute -> e.qname.takeIf { e.dimension in grain }
            else -> null
        }

    /** Whether [attribute]'s domain is a date — what a window or a period code counts in days on. */
    fun isDate(attribute: String): Boolean =
        model.attributes[attribute]
            ?.domainRef
            ?.let { model.underlyingDomain(it) }
            ?.let { model.domains[it]?.type }
            ?.lowercase() == "date"
}

/**
 * One coordinate a slot asks of the carrier (S3.3 T3): an md [attribute] (`Customer.name`) and its selector as a path
 * component — `Star` (free: a group-by key), a pinned member (`Quoted` / `IntLit`), a `SetLit` or an inclusive
 * `RangeLit`.
 */
internal data class Coord(
    val attribute: String,
    val target: PathComponent,
) {
    val dimension: String get() = attribute.substringBeforeLast('.')

    /** The dot-path spelling of the attribute, as the canonical renderer writes it (`customer.name`). */
    val spelled: String get() = "${dimension.lowercase()}.${attribute.substringAfterLast('.')}"
}
