// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.cn

import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.ConceptBindingDef
import org.tatrman.ttr.parser.model.ConceptBody
import org.tatrman.ttr.parser.model.ConceptDef
import org.tatrman.ttr.parser.model.ConceptProperty
import org.tatrman.ttr.parser.model.ConceptSugar
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.MdDomainDef
import org.tatrman.ttr.parser.model.MeasureDef
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.semantics.buildCanonicalKey
import org.tatrman.ttr.semantics.kindOf

// 0.17 — the cn desugar (CN-P2 S2.2; cn contracts §1.2, §1.4; CN-D9 "sugar in both directions"). A concept is
// grounded three ways — an authored `def cn2<layer>_*` binding, `grounds:` on the concept def (top-down), or the
// `concept { }` block inside the md / er def it grounds to (bottom-up). All three project into ONE canonical
// grounding shape here; the sugar also declares its concept. Per document and mechanical: which concept an
// authored binding grounds, and whether a concept is grounded twice, is [analyzeGroundings]'. Mirrors the TS
// twin (`packages/semantics/src/cn/desugar.ts`) — synthesized names and the binding table byte-for-byte.

/** How a grounding was written. A symbol the desugar synthesized carries [Grounds] or [Sugar] (`cnOrigin`). */
enum class GroundingOrigin { Binding, Grounds, Sugar }

private val LAYERS = setOf("md", "er", "db")

private val STEREOTYPE_KEYS = setOf("kind", "role", "quality", "event", "phase")

/**
 * The binding kind a grounding of a [stereotype] concept on [layer] desugars to (cn contracts §1.2), or null
 * where the table has none: a phase grounds only to an er entity (AM-2), an open-tier relation type never
 * grounds. Which element a target may be is `cn/grounding-target-kind`'s.
 */
fun bindingKindFor(
    stereotype: String,
    layer: String,
): String? =
    when {
        stereotype == "relation_type" -> null
        layer == "db" -> if (stereotype == "phase") null else "cn2db_concept"
        layer == "md" -> if (stereotype == "phase") null else "cn2md_$stereotype"
        else -> "cn2er_$stereotype"
    }

/** The slot of a binding that names its concept (`event:` on `cn2md_event`, `concept:` on `cn2db_concept`). */
fun bindingConceptKey(bindingKind: String): String =
    if (bindingKind == "cn2db_concept") "concept" else bindingKind.substringAfter('_')

/** The layer a written target names — its first `md` / `er` / `db` segment (a package prefix is allowed). */
fun layerOf(ref: Reference): String? = ref.parts.firstOrNull { it in LAYERS }

/**
 * The name of a binding the desugar synthesizes for [concept]: `<Concept>.grounds` for a `grounds:`,
 * `<Concept>.concept` for a `concept { }` block — two segments, never an authored binding's name.
 */
fun synthesizedBindingName(
    concept: String,
    origin: GroundingOrigin,
): String = if (origin == GroundingOrigin.Sugar) "$concept.concept" else "$concept.grounds"

/** A concept declared by the `concept { }` block of an md / er def (cn contracts §1.4). */
data class SugarConcept(
    val name: String,
    val stereotype: String,
    /** The block; every slot but the declaring one is a property of the concept. */
    val sugar: ConceptSugar,
    /** The declaring slot (`event: Return`) — the concept's authoring site. */
    val declaring: ConceptProperty.Ref,
    /** The host def's canonical key — what the concept grounds to. */
    val hostKey: String,
    val layer: String,
)

/**
 * One grounding of a concept — the canonical binding def. An authored binding names its concept by reference
 * ([conceptRef], resolved in [analyzeGroundings]); the two sugars declare it by name in the document's package
 * ([conceptName]).
 */
data class CnGrounding(
    val origin: GroundingOrigin,
    /** Null when the stereotype × layer pair has none, or a `grounds:` names no layer — nothing is grounded. */
    val bindingKind: String?,
    /** The stereotype a `grounds:` (its def's) or a block (its declaring slot's) states; null on a binding. */
    val stereotype: String?,
    /** The authored binding's name, or [synthesizedBindingName]. */
    val name: String,
    val conceptName: String? = null,
    val conceptRef: Reference? = null,
    /** The written target (an authored binding's bare `target:`, a `grounds:`). */
    val target: Reference? = null,
    /** The sugar's host def, by canonical key. */
    val targetKey: String? = null,
    /** The binding's properties past its concept slot and `target:`; a `grounds:` carries its `hierarchy:`. */
    val properties: List<ConceptProperty> = emptyList(),
    /** The binding def, the `grounds:` property or the `concept { }` block. */
    val source: SourceLocation,
)

data class ConceptDesugar(
    val concepts: List<SugarConcept>,
    val groundings: List<CnGrounding>,
)

/** The `concept { }` sugar of a host def — a cubelet, dimension, domain, measure, entity or attribute. */
fun conceptSugarOf(def: Definition): ConceptSugar? =
    when (def) {
        is EntityDef -> def.concept
        is AttributeDef -> def.concept
        is DimensionDef -> def.concept
        is MdDomainDef -> def.concept
        is MeasureDef -> def.concept
        is CubeletDef -> def.concept
        else -> null
    }

/**
 * The slot of a `concept { }` sugar that declares the concept — the stereotype slot. A role sugar also names
 * its defining event (`{ role: Customer, event: Order }`), so `role:` wins; otherwise the first stereotype ref.
 */
fun sugarStereotypeKey(body: ConceptBody): String? {
    var first: String? = null
    for (p in body.properties) {
        if (p !is ConceptProperty.Ref) continue
        if (p.key == "role") return "role"
        if (first == null) first = p.key
    }
    return first
}

/** The first stereotype ref under [key] (`event: Order`, `kind: cn.Item`, …). */
fun conceptRefOf(
    body: ConceptBody,
    key: String,
): ConceptProperty.Ref? =
    body.properties.firstOrNull {
        it is ConceptProperty.Ref && it.key == key
    } as? ConceptProperty.Ref

/** Properties a binding carries for documentation only — not part of what it grounds. */
private fun isDocProperty(p: ConceptProperty): Boolean =
    p is ConceptProperty.Description || p is ConceptProperty.Label || p is ConceptProperty.Tags

private data class SugarHost(
    val host: Definition,
    val key: String,
    val layer: String,
)

/** The md / er defs that may hold a `concept { }` block, nested attributes included, with their keys. */
private fun sugarHosts(
    def: Definition,
    packageName: String,
): List<SugarHost> {
    fun key(parts: List<String>) = buildCanonicalKey(packageName, "", kindOf(def), parts)
    val out = mutableListOf<SugarHost>()

    fun nested(
        attrs: List<AttributeDef>,
        layer: String,
    ) = attrs.forEach { out += SugarHost(it, key(listOf(def.name, it.name)), layer) }
    when (def) {
        is EntityDef -> {
            out += SugarHost(def, key(listOf(def.name)), "er")
            nested(def.attributes, "er")
        }
        is AttributeDef -> out += SugarHost(def, key(listOf(def.name)), if (def.domainRef != null) "md" else "er")
        is DimensionDef -> {
            out += SugarHost(def, key(listOf(def.name)), "md")
            nested(def.attributes, "md")
        }
        is MdDomainDef, is MeasureDef, is CubeletDef -> out += SugarHost(def, key(listOf(def.name)), "md")
        else -> {}
    }
    return out
}

/** The concept a `concept { }` block declares, or null when its declaring slot names none. */
private fun sugarConcept(
    sugar: ConceptSugar,
    hostKey: String,
    layer: String,
): SugarConcept? {
    val key = sugarStereotypeKey(sugar.body) ?: return null
    if (key !in STEREOTYPE_KEYS) return null
    val declaring = conceptRefOf(sugar.body, key) ?: return null
    return SugarConcept(declaring.ref.parts.last(), key, sugar, declaring, hostKey, layer)
}

/**
 * Project a document's groundings into canonical binding defs, and collect the concepts its `concept { }` blocks
 * declare. [packageName] is the document's effective package — the one its sugar declares in.
 */
fun desugarConcepts(
    definitions: List<Definition>,
    packageName: String,
): ConceptDesugar {
    val concepts = mutableListOf<SugarConcept>()
    val groundings = mutableListOf<CnGrounding>()

    for (def in definitions) {
        if (def is ConceptBindingDef) {
            val slot = bindingConceptKey(def.bindingKind)
            val target =
                def.body.properties
                    .firstNotNullOfOrNull { it as? ConceptProperty.Target }
                    ?.value
            groundings +=
                CnGrounding(
                    origin = GroundingOrigin.Binding,
                    bindingKind = def.bindingKind,
                    stereotype = null,
                    name = def.name,
                    conceptRef = conceptRefOf(def.body, slot)?.ref,
                    target = (target as? TargetReferenceValue)?.ref,
                    properties =
                        def.body.properties.filter {
                            !(it is ConceptProperty.Ref && it.key == slot) &&
                                it !is ConceptProperty.Target &&
                                !isDocProperty(it)
                        },
                    source = def.source,
                )
            continue
        }

        if (def is ConceptDef) {
            val grounds = def.body.properties.firstNotNullOfOrNull { it as? ConceptProperty.Grounds } ?: continue
            val layer = layerOf(grounds.ref)
            val hierarchy = def.body.properties.firstOrNull { it is ConceptProperty.Entry && it.key == "hierarchy" }
            groundings +=
                CnGrounding(
                    origin = GroundingOrigin.Grounds,
                    bindingKind = layer?.let { bindingKindFor(def.stereotype, it) },
                    stereotype = def.stereotype,
                    name = synthesizedBindingName(def.name, GroundingOrigin.Grounds),
                    conceptName = def.name,
                    target = grounds.ref,
                    properties = listOfNotNull(hierarchy),
                    source = grounds.source,
                )
            continue
        }

        for ((host, key, layer) in sugarHosts(def, packageName)) {
            val sugar = conceptSugarOf(host) ?: continue
            val concept = sugarConcept(sugar, key, layer) ?: continue
            concepts += concept
            groundings +=
                CnGrounding(
                    origin = GroundingOrigin.Sugar,
                    bindingKind = bindingKindFor(concept.stereotype, layer),
                    stereotype = concept.stereotype,
                    name = synthesizedBindingName(concept.name, GroundingOrigin.Sugar),
                    conceptName = concept.name,
                    targetKey = key,
                    source = sugar.source,
                )
        }
    }

    return ConceptDesugar(concepts, groundings)
}
