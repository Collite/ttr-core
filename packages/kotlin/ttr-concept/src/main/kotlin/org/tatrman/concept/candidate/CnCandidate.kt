// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

/**
 * CN-P5 S5.2 (cn contracts §6, AM-20) — one `cn-candidate.v1`: a proposal for the conceptual model, where it came from,
 * and what became of it. Source-agnostic (GI-4): a document pipeline, the recipe authoring surface, an evidence miner
 * and a steward all submit this one shape; the store (`services/metadata`, S5.3) keeps it, a steward promotes or rejects
 * it, and a promotion is a model TEXT change ([Promotion]).
 *
 * Read and written by [CnCandidates]; the published contract is `schemas/cn-candidate.v1.schema.json`. Refs are
 * qualified (`cn.Order`); timestamps are RFC 3339 text, kept as written.
 */
data class CnCandidate(
    val id: String,
    val proposal: Proposal,
    val provenance: Provenance,
    val status: Status,
    val resolution: Resolution? = null,
)

/** Exactly one of a concept, a term, an open-tier relation. */
sealed interface Proposal {
    /**
     * A new concept def. Which fields a stereotype takes follows the grammar (cn contracts §1.1); [definitionText] is a
     * TTR-P definition expression (the body of `definition: """…"""`); [erRendering] is filled at promotion, for a phase.
     */
    data class Concept(
        val stereo: Stereo,
        val name: String,
        val label: Map<String, String> = emptyMap(),
        val isA: List<String> = emptyList(),
        val involves: List<Participant> = emptyList(),
        val of: String? = null,
        val event: String? = null,
        val definitionText: String? = null,
        val basis: Basis? = null,
        val suggestedGrounding: String? = null,
        val erRendering: String? = null,
    ) : Proposal

    /** Words for a concept in the model ([targetQname]) or one still in the queue ([proposedConceptRef], its id). */
    data class Term(
        val targetQname: String?,
        val proposedConceptRef: String?,
        val locale: String,
        val forms: List<String>,
        val default: Boolean? = null,
    ) : Proposal

    /** `relations: [ { type: <typeName>, to: <to> } ]` on [from] — the open tier; it never lowers. */
    data class Relation(
        val typeName: String,
        val from: String,
        val to: String,
    ) : Proposal
}

enum class Stereo(
    val word: String,
) {
    KIND("kind"),
    ROLE("role"),
    PHASE("phase"),
    QUALITY("quality"),
    EVENT("event"),
}

/** A phase's `basis:` (AM-44). */
enum class Basis(
    val word: String,
) {
    OCCURRENCE("occurrence"),
    CURRENT_STATE("current_state"),
    PAST_STATE("past_state"),
}

/** One participant of an event: `Encounter as from` is `Participant("cn.Encounter", "from")`. */
data class Participant(
    val concept: String,
    val label: String? = null,
)

data class Provenance(
    val producer: String,
    val sourceRef: String?,
    val confidence: CandidateConfidence,
    val evidence: Evidence?,
    val createdAt: String,
)

/** The resolver's Confidence shape: the number, who computed it, how. */
data class CandidateConfidence(
    val value: Double,
    val producer: String,
    val method: String,
)

/** AM-20 — why a steward should care. Example text is verbatim; de-identifying it is the producer's duty. */
data class Evidence(
    val questionCount: Int?,
    val documentCount: Int?,
    val examples: List<EvidenceExample>,
)

data class EvidenceExample(
    val kind: ExampleKind,
    val ref: String,
    val text: String,
)

/** What an evidence example is; its `ref` scheme follows ([ExampleKind.scheme]). */
enum class ExampleKind(
    val word: String,
    val scheme: String,
) {
    QUESTION("question", "question"),
    DOCUMENT("document", "doc"),
    RECIPE_SENTENCE("recipe_sentence", "recipe"),
    PROCESSOR_OUTPUT("processor_output", "doc"),
}

enum class Status(
    val word: String,
) {
    PROPOSED("proposed"),
    REJECTED("rejected"),
    PROMOTED("promoted"),
}

/** A steward's act: [canonRef] (`<merged commit>:<qname>`) on a promotion, never on a rejection. */
data class Resolution(
    val by: String,
    val at: String,
    val canonRef: String? = null,
    val note: String? = null,
)
