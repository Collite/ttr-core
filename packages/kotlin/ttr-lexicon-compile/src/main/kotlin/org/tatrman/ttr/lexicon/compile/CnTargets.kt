// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import org.tatrman.ttr.lexicon.ConceptLift
import org.tatrman.ttr.lexicon.EntryProvenance
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.MatchProfile
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.LexiconOrigin
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.Via
import org.tatrman.ttr.metadata.source.entityOfElement
import java.nio.file.Path

/**
 * CN-P4 S4.1 (cn contracts §5, A-CN-2…8) — the conceptual tier as the lexicon compiler reads it: `ttr-metadata`'s
 * [CnSchema], keyed by the ARCHIVE spelling of a concept, `cn.<Name>`.
 *
 * The one place the compiler reads cn, so the four things it needs cannot drift into four readings: which `cn.*` refs
 * exist ([ModelRefIndex.Companion.ofCn]), what a concept's entry says ([factsOf]), what an element lifts to
 * ([liftsTo]), and whether a concept grounds to an element ([groundsTo] — R-cn-1's question, asked here at compile
 * time and by the resolver at run time, over the same archive facts).
 *
 * ## The spelling is package-less
 *
 * `cn.<Name>`, like every archive ref (A-CN-9): `QualifiedName.dotted()` drops the package for er and md too. Two
 * packages naming one concept twice therefore meet on one ref; the first in qname order (package, then name) is the one
 * read — deterministic, and the same posture [CnSchema.byName] takes.
 */
class CnTargets private constructor(
    private val cn: CnSchema,
) {
    /** Every concept by `cn.<Name>`, first in qname order. */
    val concepts: Map<String, Concept> =
        cn.concepts.values
            .sortedWith(compareBy({ it.qname.`package` }, { it.qname.name }))
            .fold(sortedMapOf<String, Concept>()) { acc, c -> acc.apply { putIfAbsent(c.ref, c) } }

    /**
     * The reverse index (A-CN-4) in the archive's shape: element ref → its lifts, sorted by concept. [CnSchema] already
     * leaves out phases (L17-2) and db groundings; an element with no lift is absent.
     */
    private val lifts: Map<String, List<ConceptLift>> =
        cn.reverseIndex
            .mapValues { (_, l) ->
                l.map { ConceptLift(it.ref, wire(it.via)) }.distinctBy { it.qname }.sortedBy { it.qname }
            }.filterValues { it.isNotEmpty() }

    /** The md elements a concept is grounded on, or lands through — each gets an entry carrying only its [liftsTo]. */
    val mdElements: List<String> get() = lifts.keys.filter { it.startsWith("md.") }

    /** The er elements a concept is grounded on, or lands on. */
    val erElements: List<String> get() = lifts.keys.filter { it.startsWith("er.") }

    fun liftsTo(element: String): List<ConceptLift> = lifts[element].orEmpty()

    /**
     * A concept's `targets` entry (contracts §5, A-CN-3): `ownerRef` = the ENTITY of its landing, `erTarget` = the landing
     * itself (an attribute for a value kind or a quality), `via` even when the hop is missing, the `of:` as `ofRef`.
     * Never members, reach or a mention facet: a concept is not a model object, and it governs only through its
     * landing (A-CN-6).
     */
    fun factsOf(c: Concept): TargetFacts =
        TargetFacts(
            objectKind = TargetFacts.CONCEPT,
            ownerRef = c.erTarget?.let(::entityOfElement),
            stereotype = c.stereotype,
            erTarget = c.erTarget,
            via = c.via?.let(::wire),
            ofRef = c.of?.let { "cn.${it.name}" },
        )

    /**
     * R-cn-1 (§1.5 step 6, A-CN-8): does [conceptRef] ground to [element] — is the concept the SAME thing the element
     * is? Yes iff the element is the concept's landing, or the element lifts to it (the reverse index holds a concept
     * under its grounding target and its landing). The resolver asks the same question of the same archive facts.
     */
    fun groundsTo(
        conceptRef: String,
        element: String,
    ): Boolean {
        val c = concepts[conceptRef] ?: return false
        return element == c.erTarget || liftsTo(element).any { it.qname == conceptRef }
    }

    /** Is [ref] a phase — the one stereotype §1.5's steps 1 and 3 single out? */
    fun isPhase(ref: String): Boolean = concepts[ref]?.stereotype == PHASE

    /**
     * The concepts up [ref]'s `of:` chain — its bearer, the bearer's bearer, … — itself excluded, a cycle cut where it
     * closes (the `cn/phase-as-default` lint walks the same chain).
     */
    fun bearers(ref: String): Set<String> {
        val out = linkedSetOf<String>()
        var at = concepts[ref]?.of?.let { "cn.${it.name}" }
        while (at != null && at != ref && out.add(at)) {
            at = concepts[at]?.of?.let { "cn.${it.name}" }
        }
        return out
    }

    companion object {
        private const val PHASE = "phase"

        /** The model's cn tier, or null when it has none (a pre-0.17 model, or a caller that built one by hand). */
        fun of(model: Model?): CnTargets? = (model?.schemas?.get(CN_SCHEMA) as? CnSchema)?.let(::CnTargets)

        private const val CN_SCHEMA = "cn"

        private fun wire(via: Via): String =
            when (via) {
                Via.DIRECT -> TargetFacts.VIA_DIRECT
                Via.MD_HOP -> TargetFacts.VIA_MD_HOP
            }
    }
}

/**
 * CN-P4 — the cn half of the reference index: `cn.<Name>` is a MODEL_OBJECT for every concept of the model's cn tier.
 * Composed beside [ofMd] with [orElse]; the key spaces cannot collide (the leading schema token differs).
 */
fun ModelRefIndex.Companion.ofCn(model: Model): ModelRefIndex {
    val refs =
        CnTargets
            .of(model)
            ?.concepts
            ?.keys
            .orEmpty()
    return ModelRefIndex { ref -> if (ref in refs) TargetClass.MODEL_OBJECT else null }
}

/**
 * DECLARED layer, cn half (CN-P4 S4.1) — the terms a concept's inline `lexicon { terms: [...] }` declares.
 *
 * The canonical `def term … { for: cn.X }` is [TtrmSugarExtractor]'s, read off the `model lexicon` units like any other
 * term; this reads only the INLINE links, which no unit holds as a `def term` (the sugar desugars in semantics, and
 * [CnSchema] is where it surfaces — the deferral [TtrmSugarExtractor] records, closed for concepts). So no row is read
 * twice. An inline block has no locale (RS-11): its forms take the authoring default, as an unlocalized unit's do.
 */
object CnLexiconExtractor {
    fun rows(
        cn: CnTargets?,
        repoRoot: Path?,
    ): List<SourceRow> =
        buildList {
            for ((ref, concept) in cn?.concepts.orEmpty()) {
                for (link in concept.lexiconLinks) {
                    if (link.origin != LexiconOrigin.INLINE || link.entryKind != "term") continue
                    val lang = link.locale?.let { Lang.ofWire(it) } ?: LexiconValidator.DEFAULT_LANG
                    for (form in link.forms) {
                        add(
                            SourceRow(
                                text = form,
                                lang = lang,
                                method = LexiconValidator.DEFAULT_METHOD,
                                targetRef = ref,
                                sourceTag = SourceTag.DECLARED,
                                provenance =
                                    EntryProvenance(
                                        repoRelative(link.sourceFile.ifBlank { concept.sourceFile }, repoRoot),
                                        link.sourceLocation?.line ?: concept.sourceLocation?.line ?: 0,
                                    ),
                                profile = MatchProfile.ofSugar(LexiconValidator.DEFAULT_METHOD),
                                defaultSense = link.default == true,
                                sensePriority = link.sensePriority,
                            ),
                        )
                    }
                }
            }
        }
}
