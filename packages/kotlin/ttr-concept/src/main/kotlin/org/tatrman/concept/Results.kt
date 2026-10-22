// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.plan.v1.PlanNode

// The data classes of cn contracts §3, with the fields §3 names and empty-safe defaults (CN-P0 S0.4). Field sets the
// contracts leave open are the smallest that carry §3's prose; CN-P3 grows them additively.

/** The layer a definition renders on (⚑CN-13: one definition syntax, two renderers). */
enum class Layer { MD, ER }

/**
 * How a concept's ER landing was reached (AM-15): its own cn2er grounding, or its md grounding through the hop. The
 * typed model carries it, so it lives in `ttr-metadata` since CN-P2 S2.5; this alias keeps the §3 spelling.
 */
typealias Via = org.tatrman.ttr.metadata.model.Via

/** The three-valued reading a definition compares instants with (AM-42); default `CERTAINLY`. */
enum class ImprecisionMode { CERTAINLY, POSSIBLY }

enum class Severity { ERROR, WARNING, ADVISORY, HYGIENE }

/**
 * One cn diagnostic (a `cn/…` slug, contracts §7): the slug, its grade, the concept or slot it is about, and — for a
 * definition-level finding (CN-P2 S2.6) — where in the `.ttrm` it is (a position inside a definition block is mapped
 * through the block's source map).
 */
data class Diagnostic(
    val slug: String,
    val severity: Severity,
    val message: String = "",
    val subject: String = "",
    val location: org.tatrman.ttr.parser.model.SourceLocation? = null,
)

/** Which binding a statement slot went through (`concept → binding def → md/er element`, with `via` for hops). */
data class BindingPath(
    val slot: String,
    val concept: String,
    val bindingDef: String = "",
    val element: String = "",
    val via: Via = Via.DIRECT,
)

/** What every lower echoes (architecture §7): enough to reproduce and audit the result. */
data class LowerProvenance(
    val snapshotHash: String = "",
    val grammarVersion: String = "",
    val catalogVersion: String = "",
    val bindingPaths: List<BindingPath> = emptyList(),
    /** Phase definitions inlined, transitively (phase-in-phase, AM-26). */
    val inlinedDefinitions: List<String> = emptyList(),
    /** `:name` parameter → the value bound from the world (R-cn-7; `parameter()` before TTRP 0.5, A-CN-26). */
    val parametersBound: Map<String, String> = emptyMap(),
    /** The evaluation instant used (ISO-8601); empty = now. */
    val asOf: String = "",
    val imprecisionMode: ImprecisionMode = ImprecisionMode.CERTAINLY,
)

/** `statementLower`: the plan, the md dot-path it came from, and the provenance. */
data class LowerResult(
    val plan: PlanNode? = null,
    val dotPath: String = "",
    val provenance: LowerProvenance = LowerProvenance(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** A localized explanation (statement or assertion). */
data class Explanation(
    val locale: String = "",
    val text: String = "",
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** One E-R row of an assertion's lowered payload: the entity and its attribute → value text (null = SQL NULL). */
data class RowObject(
    val entity: String,
    val values: Map<String, String?> = emptyMap(),
)

/**
 * `assertionLower`: the `Statement[<Entity>]` row payload — data only; cn never writes it (contracts §2.3). CN-P3 S3.5
 * adds the last three, additively (A-CN-49): [assertedAsOf] — the instant the source claims the state for, as the frame
 * writes it (null when it names none); [meta] — what the frame carries that no attribute holds; [obligations] — the
 * participants the rows cannot hold, as checks for the writer (A-CN-47).
 */
data class RowObjects(
    val rows: List<RowObject> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val assertedAsOf: String? = null,
    val meta: RowMeta = RowMeta(),
    val obligations: List<Obligation> = emptyList(),
)

/**
 * What an assertion carries beside its rows (A-CN-49): its provenance, the assertions it consolidates or corrects, the
 * snapshot and locale it was built on, how its absent period end reads ([endState] — `END_OPEN` / `END_UNKNOWN`, the
 * frame's or else the event's `end_open:`; unspecified when the end is present or the event has no end), and each
 * quality's verbatim by the attribute it lands on.
 */
data class RowMeta(
    val provenance: org.tatrman.assertion.v1.Provenance? = null,
    val derivedFrom: List<String> = emptyList(),
    val snapshotHash: String = "",
    val locale: String = "",
    val endState: org.tatrman.concept.v1.EndState = org.tatrman.concept.v1.EndState.END_STATE_UNSPECIFIED,
    val verbatims: Map<String, String> = emptyMap(),
)

/**
 * A participant the event row cannot hold (A-CN-47): [label]'s path runs [relations] from the event's entity, its first
 * relation's foreign key [anchor] written by the participant [anchoredBy]; the row its path ends at must be [value] —
 * a lowering cannot read data, so the writer checks it.
 */
data class Obligation(
    val label: String,
    val value: String,
    val relations: List<String>,
    val anchor: String,
    val anchoredBy: String,
)

/**
 * How a definition renders (contracts §3, A-CN-11/20; CN-P3 S3.4 T1 adds the last three, additively). [mode] —
 * `PREDICATE` (`SELECT <bearer key> FROM <bearer> WHERE …`) or `CONTEXT` (the bearer joined to the [candidates],
 * sub-terms lifted to joins and projected — AU's recipe islands, A-CN-20); [placeholderStyle] — `CURLY` (`{name}`, the
 * translator's) or `COLON` (`:name`, AU's islands); [asOf] — the evaluation instant, null = now (a `latest(…, by:)` is
 * then unbounded, A-CN-17; given, it is bounded by `{as_of}`); [candidates] — CONTEXT: the phase whose bearer keys the
 * context (H-1's `cn.PendingOrder`), joined to the target's bearer through a participant path, its predicate first in
 * the WHERE; [columns] — what the projection carries after the keys: names in the definition's scope (a participant —
 * its key column; a grounded quality — its column, bare; a defined quality — `AS <name>`).
 */
data class RenderOptions(
    val mode: RenderMode = RenderMode.PREDICATE,
    val placeholderStyle: PlaceholderStyle = PlaceholderStyle.CURLY,
    val asOf: java.time.Instant? = null,
    val candidates: org.tatrman.concept.v1.ConceptRef? = null,
    val columns: List<String> = emptyList(),
)

enum class RenderMode { PREDICATE, CONTEXT }

enum class PlaceholderStyle { CURLY, COLON }

/**
 * A placeholder a rendering reads (A-CN-22): a `:name` parameter, `as_of`, or a bound the renderer DERIVES and names
 * (`window_lo`, `as_of_hi`) — [derivation] says how the consumer computes it (`as_of - days(365)`); null for one bound
 * as it is.
 */
data class ParameterRead(
    val name: String,
    val derivation: String? = null,
)

/**
 * `definitionRender`: the [text] (ER — SQL over entities in the formatting contract's layout, contracts §3; MD — the PL
 * predicate over the carrying cubelet's attributes), the placeholders it reads, in the order the text reads them, the
 * definitions it inlined (phases and defined qualities, transitively — the target itself not included), how it
 * compared instants, and why it did not render (an error-grade diagnostic, [text] empty) or what it degraded
 * (advisory).
 */
data class Rendering(
    val text: String = "",
    val parametersRead: List<ParameterRead> = emptyList(),
    val definitionsInlined: List<String> = emptyList(),
    val imprecisionMode: ImprecisionMode = ImprecisionMode.CERTAINLY,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * `materialize`: model text, path → content (a `def query` + its stacked entity). Canon stays text (CN-D1). Refused —
 * no files — with an error-grade diagnostic (`cn/materialize-parametric`, or why the er rendering failed).
 */
data class ModelText(
    val files: Map<String, String> = emptyMap(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** The hygiene grades of S-6 v2 — computed by the typed model, so in `ttr-metadata` since CN-P2 S2.5 (alias). */
typealias GroundingGrade = org.tatrman.ttr.metadata.model.GroundingGrade

/** A concept's grounding as the report shows it: its layer, binding kind and target (null when it does not resolve). */
data class GroundingRow(
    val layer: org.tatrman.ttr.metadata.model.GroundingLayer,
    val bindingKind: String,
    val target: String? = null,
)

/**
 * The hop column (contracts §7): an md grounding's element and where it lands on E-R ([erTarget]) — or the md/hop
 * slug saying why it does not ([slug]: `md/er-unmapped` · `md/er-ambiguous`) and its [message].
 */
data class HopColumn(
    val mdElement: String,
    val erTarget: String? = null,
    val slug: String? = null,
    val message: String = "",
)

/** A ref written in one of the concept's groundings that does not resolve — a dangling grounding. */
data class DanglingRef(
    val binding: String,
    val ref: String,
)

/**
 * A redundant term duplicate (the C-Δ1 γ union, design §12): a [form] the concept's term [conceptEntry] shares with
 * the term [elementEntry] on [element], an element that lifts to the concept anyway — one of the two can go.
 */
data class RedundantTerm(
    val form: String,
    val locale: String?,
    val conceptEntry: String,
    val element: String,
    val elementEntry: String,
)

/**
 * A `cn/phase-as-default` row (AM-36): the phase's term [entry] says `default: true`, and its [form] also names
 * [alsoNames], a concept up the phase's `of:` chain — a bare term never defaults to a phase.
 */
data class PhaseAsDefault(
    val entry: String,
    val form: String,
    val alsoNames: String,
)

/**
 * One concept's row of the hygiene report v2 (contracts §3, §7; CN-P2 S2.7): its grade and `via`, its grounding and
 * the hop column, where it lands on E-R (inherited from [landingFrom] for a defined phase), core vs open (a concept
 * is core; its open-tier content is listed), and the findings — dangling groundings, redundant term duplicates,
 * retired codes, phase-as-default rows.
 */
data class ConceptHygiene(
    /** The archive spelling, `cn.<Name>`. */
    val qname: String,
    val packageName: String = "",
    val stereotype: String = "",
    val grade: GroundingGrade = GroundingGrade.UNGROUNDED,
    val via: Via? = null,
    val grounding: GroundingRow? = null,
    val hop: HopColumn? = null,
    /** Where the concept lands on E-R — its own grounding's, or [landingFrom]'s for a defined phase. */
    val erTarget: String? = null,
    val landingFrom: String? = null,
    /** CN-D7 — by vocabulary, a stereotype concept is core; its open-tier content is [openEntries] and [relations]. */
    val openTier: Boolean = false,
    /** The free-form keys the stock vocabulary does not name. */
    val openEntries: List<String> = emptyList(),
    /** The open-tier `relations:` edges, `<type> → cn.<To>`. */
    val relations: List<String> = emptyList(),
    val danglingGroundings: List<DanglingRef> = emptyList(),
    val redundantTerms: List<RedundantTerm> = emptyList(),
    /** `cn/retired-code` rows: a textual code invalid in the current vocabulary bundle. */
    val retiredCodes: List<String> = emptyList(),
    /** `cn/phase-as-default` rows (AM-36). */
    val phaseAsDefault: List<PhaseAsDefault> = emptyList(),
)

/**
 * Hygiene report v2 (contracts §7): a row per concept, the open tier's relation types, and — Kotlin only, CN-D29 —
 * the definition-level findings ([ResolvedCnModel.definitionDiagnostics]). The editor's report is the same less
 * [definitions].
 */
data class HygieneReport(
    val concepts: List<ConceptHygiene> = emptyList(),
    /** The open tier's `def relation_type`s, `cn.<Name>`. */
    val relationTypes: List<String> = emptyList(),
    val definitions: List<Diagnostic> = emptyList(),
) {
    companion object {
        /** Why [ConceptHygiene.retiredCodes] is empty in P2. */
        const val RETIRED_CODES_NOTE: String =
            "checked once vocabulary versions register (CN-P5 S5.3) — none yet"
    }
}
