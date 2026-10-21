// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import org.tatrman.ttr.parser.model.EmbeddedBlock
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.semantics.cn.GroundingOrigin

/**
 * Grammar 0.17 — the conceptual tier (`model cn` and its `model binding` groundings; CN-P2 S2.5, cn contracts §1.1–§1.3,
 * §5): the typed, reconciled concept model, built over the DESUGARED project (`ttr-semantics` `validateCn`). Every
 * concept carries what the project states of it — its cn def's properties, then those each `concept { }` block naming
 * it adds (the first of a key is the one read) — its ONE grounding (CN-D12: the first in document order; a second is
 * `cn/duplicate-grounding`) and where it lands on E-R: [Concept.erTarget], [Concept.via], [Concept.grade] (AM-15,
 * contracts §1.2). [reverseIndex] lists, per md and er element, the concepts grounded on it — the facts P4 writes into
 * lexicon archive v6 as `liftsTo` (A-CN-4).
 *
 * Library-only like the automation tier: never sent over the metadata wire. Definitions stay TEXT here — an
 * [EmbeddedBlock] with its source map — because this module cannot call `ttrp-frontend` (which depends on it); P2 S2.6
 * parses them in `ttr-concept` (CN-D29).
 *
 * Element refs are spelled as TTR-M and the lexicon archive spell them, package-less and kinded: `er.entity.<e>`,
 * `er.entity.<e>.<a>` (A-CN-9), `er.relation.<r>`, `md.dimension.<D>`, `md.dimension.<D>.<a>`, `md.measure.<m>`,
 * `md.cubelet.<c>`, `md.domain.<d>`, `db.<schema>.<table>`. A concept's lexicon / archive spelling is [Concept.ref],
 * `cn.<Name>`. Typed fields read leniently (the automation precedent): a reference that does not resolve, or a value
 * outside its vocabulary, reads as absent — the cn validator (`ttr-semantics`) is where it is reported; a grounding
 * lists its unresolved refs in [Grounding.dangling].
 *
 * Core vs open tier (CN-D7 — by vocabulary): a concept of the five stereotypes and its stock properties are core; a
 * `def relation_type` ([CnRelationType]), a concept's `relations:` edges ([CnRelation]) and every free-form entry the
 * stock vocabulary does not name ([Concept.openEntries]) are the open tier — no deterministic consumer reads them
 * (contracts §1.3).
 */
data class CnSchema(
    /** The concepts of the five stereotypes, keyed `QualifiedName(CN, "concept", <Name>, <package>)`. */
    val concepts: Map<QualifiedName, Concept> = emptyMap(),
    /**
     * Every grounding, keyed `QualifiedName(CN, <binding kind>, <name>, <package>)` — an authored binding by its name,
     * one a `grounds:` or a `concept { }` block stands for by `<Concept>.grounds` / `<Concept>.concept` (S2.2).
     * A second grounding of a concept is here too; [Concept.grounding] is the first.
     */
    val bindings: Map<QualifiedName, Grounding> = emptyMap(),
    /** The open tier's relation types (`def relation_type`), keyed like concepts — they share the namespace. */
    val relationTypes: Map<QualifiedName, CnRelationType> = emptyMap(),
    /**
     * The registered vocabulary versions (Q15-5; the MV A-MV-10 precedent): vocabulary → version. Present, and empty
     * until P4 S4.2 registers them.
     */
    val vocabularyVersions: Map<String, String> = emptyMap(),
    /**
     * The canonical lexicon entries whose `for:` names an md or er element, by element ref, in document order (CN-P2
     * S2.7) — what the hygiene report sets beside a concept's own terms: a form on both the concept and an element
     * that lifts to it is a redundant duplicate (the C-Δ1 γ union).
     */
    val elementTerms: Map<String, List<LexiconLink>> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "cn"

    /**
     * The reverse index (contracts §1.2, §5; A-CN-4): per md and er element, the concepts grounded on it, each stamped
     * with the concept's [Via] — a function of the concept (one grounding), so an element lists a concept once. An
     * er-grounded concept is indexed under its target (a value kind or a quality under the ATTRIBUTE, never the entity
     * around it); an md-grounded one under its md target and, when the hop reaches E-R, under its [Concept.erTarget]
     * too. **A phase is never in it** (L17-2); a db grounding reaches no consumer, so it is not either. Sorted by
     * element, then concept. Which lifts a hit takes (category and owner, never across a relation) is P4's (S4.1).
     */
    val reverseIndex: Map<String, List<CnLift>> by lazy { reverseIndexOf(concepts.values) }

    /** What [element] lifts to — `liftsTo: [{qname, via}]` (A-CN-4); empty when no concept is grounded on it. */
    fun liftsTo(element: String): List<CnLift> = reverseIndex[element].orEmpty()

    /** The concept named [name] (any package), or null. */
    fun byName(name: String): Concept? = concepts.values.firstOrNull { it.qname.name == name }

    override fun objects(): Sequence<ModelObject> =
        concepts.values.asSequence() + relationTypes.values.asSequence() + bindings.values.asSequence()
}

/** How a concept's E-R landing is reached (AM-15): its own er grounding, or its md grounding through the hop. */
enum class Via { DIRECT, MD_HOP }

/** The hygiene grades of S-6 v2 (contracts §3 `hygieneReport`). */
enum class GroundingGrade { UNGROUNDED, ER_GROUNDED, MD_GROUNDED, MD_GROUNDED_HOP_MISSING }

/** The layer a grounding targets — its binding kind's (`cn2md_*` · `cn2er_*` · `cn2db_concept`). */
enum class GroundingLayer { MD, ER, DB }

/** An event's temporal extent (AM-39, D-T1). */
enum class Extent { INSTANTANEOUS, DURATIVE }

/**
 * How a period's end reads (AM-43, D-T6; A-CN-19): [PRESENT] — no `end_open:`, the end is always there (no NULL arm);
 * [OPEN] — an absent end means still running; [UNKNOWN] — an absent end is three-valued.
 */
enum class EndState { PRESENT, OPEN, UNKNOWN }

/** The three-valued reading a definition compares instants with (AM-42, D-T5); a consumer defaults to `CERTAINLY`. */
enum class Imprecision { CERTAINLY, POSSIBLY }

/** A condition's basis (AM-44, D-T7). */
enum class Basis { OCCURRENCE, CURRENT_STATE, PAST_STATE }

/** A closure's ranges (A-CN-13): a block code `H10-H19` is a closure NODE (the default), or a LEXICAL `BETWEEN`. */
enum class HierarchyRanges { NODE, LEXICAL }

/**
 * One concept (`def kind | role | event | phase | quality`, or one only `concept { }` blocks declare). Keyed
 * `QualifiedName(CN, "concept", <Name>, <package>)` — `cn.concept.Customer` in `hartland.stores`.
 */
data class Concept(
    override val internalId: String,
    override val qname: QualifiedName,
    /** `kind · role · event · phase · quality`. */
    val stereotype: String,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val label: LocalizedText = LocalizedText.EMPTY,
    /** `sources:` — compact provenance (`doc://…#…`). */
    val sources: List<String> = emptyList(),
    /** `is_a:` — one ref or a list. */
    val isA: List<QualifiedName> = emptyList(),
    val abstract: Boolean = false,
    /** `of:` — a quality's or a phase's bearer (required), a role's base kind (optional). */
    val of: QualifiedName? = null,
    /** A role's defining event (CN-D6). */
    val event: QualifiedName? = null,
    /** An event's `extent:` as written; [effectiveExtent] applies the default. */
    val extent: Extent? = null,
    /** An event's labelled participants, in order (a label defaults to the concept's name, lower-cased). */
    val involves: List<CnParticipant> = emptyList(),
    val period: CnPeriod? = null,
    /** `qualities:` — the names as written: soft references (a quality concept, or a name a grounding gives). */
    val qualities: List<String> = emptyList(),
    /** A quality's `value_space:` — a kind (AM-17). */
    val valueSpace: QualifiedName? = null,
    /** A quality valued over time (AM-40 b). */
    val valuedState: Boolean = false,
    val imprecision: Imprecision? = null,
    val basis: Basis? = null,
    /** A defined phase's or quality's `definition:` — text and source map; parsed in `ttr-concept` (S2.6). */
    val definition: EmbeddedBlock? = null,
    /** A member-restricted sub-kind's `members:` (AM-16) — text and source map. */
    val members: EmbeddedBlock? = null,
    /** The open tier's `relations:` edges. */
    val relations: List<CnRelation> = emptyList(),
    /** Every lexicon entry targeting this concept — canonical (`def term … { for: cn.X }`) and inline. */
    val lexiconLinks: List<LexiconLink> = emptyList(),
    /** The free-form entries the stock vocabulary does not name, as authored, in order — the open tier. */
    val openEntries: List<Pair<String, PropertyValue>> = emptyList(),
    /** The ONE grounding read (CN-D12) — null when ungrounded. */
    val grounding: Grounding? = null,
    /**
     * Where the concept lands on E-R: an entity, or the attribute for a value kind or a quality (AM-27). Direct for an
     * er grounding, through the hop for an md one (null when the hop is missing); a defined phase inherits its
     * bearer's ([landingFrom]). Null for a db grounding and when the target does not resolve.
     */
    val erTarget: String? = null,
    /** How [erTarget] is reached: DIRECT (er), MD_HOP (md — even when the hop is missing); null when ungrounded. */
    val via: Via? = null,
    val grade: GroundingGrade = GroundingGrade.UNGROUNDED,
    /** The bearer whose landing a defined phase inherits (contracts §1.2); null when the landing is its own. */
    val landingFrom: QualifiedName? = null,
    /**
     * The hop's answer for this concept's own md grounding ([MdErHop]; the hygiene report's hop column, CN-P2 S2.7):
     * the er landing and its rule, or `md/er-unmapped` / `md/er-ambiguous` and why. Null for any other grounding,
     * an inherited landing, and an md target that does not resolve.
     */
    val hop: HopResult? = null,
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "concept"

    /** The lexicon / archive spelling (contracts §1.5, §5): `cn.<Name>`. */
    val ref: String get() = "cn.${qname.name}"

    /** CN-D7 — by vocabulary: every stereotype concept is core; the open tier is [relations] and [openEntries]. */
    val openTier: Boolean get() = false

    /** AM-39 — [extent] as written; else durative for a `{ start, end }` period, instantaneous for `{ at }`. */
    val effectiveExtent: Extent?
        get() =
            extent ?: period?.let {
                when {
                    it.at != null -> Extent.INSTANTANEOUS
                    it.start != null || it.end != null -> Extent.DURATIVE
                    else -> null
                }
            }
}

/** One `involves:` participant: its [label] (as written, else the concept's name lower-cased) and its concept. */
data class CnParticipant(
    val label: String,
    /** Null when the reference does not resolve. */
    val concept: QualifiedName?,
)

/**
 * A concept's `period:` (AM-19, L15-4): `{ start, end, end_open }` or `{ at, precision }`. The instants are QUALITY
 * NAMES as written (soft references); a grounding's period ([GroundingPeriod]) says which attributes carry them.
 */
data class CnPeriod(
    val start: String? = null,
    val end: String? = null,
    val at: String? = null,
    /** Null without an `end:`. */
    val endState: EndState? = null,
    /** `precision:` as written (`day`). */
    val precision: String? = null,
)

/** One open-tier `relations:` edge — `{ type: supplies, to: Warehouse }`, as written and resolved. */
data class CnRelation(
    val typeName: String,
    /** The relation type, when it resolves (undefined: `cn/unknown-relation-type`). */
    val type: QualifiedName?,
    val toName: String?,
    val to: QualifiedName?,
) {
    /** CN-D7 — a user relation type is the open tier. */
    val openTier: Boolean get() = true
}

/** `def relation_type <Name> { … }` — the open tier (contracts §1.3). Keyed like a concept. */
data class CnRelationType(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val label: LocalizedText = LocalizedText.EMPTY,
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "relation_type"

    /** CN-D7 — a user-defined relation type is open tier, always. */
    val openTier: Boolean get() = true
}

/** How a lexicon entry was declared: a canonical `def term | pattern | example`, or a concept's inline `lexicon { }`. */
enum class LexiconOrigin { CANONICAL, INLINE }

/**
 * One lexicon entry targeting a concept (contracts §1.5) — the input of S2.7's phase-as-default rows and P4's
 * tie-break. Mirrors the TS `CanonicalLexiconEntry`: a `term` has [forms], a `pattern` its [match], an `example` its
 * [text]; [default] and [sensePriority] only when written.
 */
data class LexiconLink(
    /** `term · pattern · example`. */
    val entryKind: String,
    /** The entry def's name (canonical), or the concept's (inline). */
    val name: String,
    val origin: LexiconOrigin,
    /** The unit's `model lexicon locale <id>`; null = the base / deployment locale (RS-11) — always, inline. */
    val locale: String? = null,
    val forms: List<String> = emptyList(),
    val match: String? = null,
    val text: String? = null,
    val default: Boolean? = null,
    val sensePriority: Double? = null,
    val sourceFile: String = "",
    val sourceLocation: SourceLocation? = null,
)

/**
 * One grounding — the canonical binding def (cn contracts §1.2): an authored `def cn2<layer>_*`, or the one a
 * `grounds:` / `concept { }` stands for (S2.2; [origin], and [binding] is `BoundSynthetic`).
 */
data class Grounding(
    override val internalId: String,
    override val qname: QualifiedName,
    /** `cn2md_kind` … `cn2db_concept`. */
    val bindingKind: String,
    /** The concept it grounds. */
    val concept: QualifiedName,
    val layer: GroundingLayer,
    val origin: GroundingOrigin,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    /** The target element (an element ref, see [CnSchema]); null when it does not resolve. */
    val target: String? = null,
    /** The target's kind — `entity · attribute · dimension · mdDomain · measure · cubelet · table · …`. */
    val targetKind: String? = null,
    /** A vocabulary-bound kind's closure (Q15-1 — a property of the grounding). */
    val hierarchy: CnHierarchy? = null,
    /** A `cn2er_event`'s participant paths: label → its relations, in order (L15-3). A path that breaks is left out. */
    val participants: Map<String, List<String>> = emptyMap(),
    /** The attributes that carry the event's instants. */
    val period: GroundingPeriod? = null,
    /** A `cn2er_role`'s `via:` — the base entity's path to the defining event (relations, in order). */
    val viaPath: List<String> = emptyList(),
    /** A valued state's series selector (A-CN-18) — text and source map. */
    val filter: EmbeddedBlock? = null,
    /** The refs written in this grounding that do not resolve, as written — the hygiene report's dangling ones. */
    val dangling: List<String> = emptyList(),
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = bindingKind
}

/** `hierarchy: { closure, ancestor, descendant, ranges }` — the attributes as refs on the closure entity. */
data class CnHierarchy(
    val closure: String?,
    val ancestor: String?,
    val descendant: String?,
    val ranges: HierarchyRanges = HierarchyRanges.NODE,
)

/** A grounding's `period:` — the er attributes carrying the instants. */
data class GroundingPeriod(
    val start: String? = null,
    val end: String? = null,
    val at: String? = null,
)

/** One reverse-index entry (A-CN-4): a concept an element lifts to, its stereotype (A-CN-7) and how it lands. */
data class CnLift(
    val concept: QualifiedName,
    val stereotype: String,
    val via: Via,
) {
    /** The archive spelling, `cn.<Name>`. */
    val ref: String get() = "cn.${concept.name}"
}

private fun reverseIndexOf(concepts: Collection<Concept>): Map<String, List<CnLift>> {
    val out = sortedMapOf<String, MutableList<CnLift>>()
    for (c in concepts) {
        if (c.stereotype == "phase") continue // L17-2
        val g = c.grounding ?: continue
        val via = c.via ?: continue
        if (g.layer == GroundingLayer.DB) continue
        val lift = CnLift(c.qname, c.stereotype, via)
        val elements = linkedSetOf<String>()
        g.target?.let { elements += it }
        c.erTarget?.let { elements += it }
        for (e in elements) out.getOrPut(e) { mutableListOf() } += lift
    }
    return out.mapValues { (_, lifts) -> lifts.sortedBy { "${it.concept.`package`}.${it.concept.name}" } }
}
