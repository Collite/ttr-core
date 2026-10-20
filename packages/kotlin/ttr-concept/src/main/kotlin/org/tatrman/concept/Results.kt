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

/** One cn diagnostic (a `cn/…` slug, contracts §7): the slug, its grade, and the concept or slot it is about. */
data class Diagnostic(
    val slug: String,
    val severity: Severity,
    val message: String = "",
    val subject: String = "",
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
    /** `parameter()` name → the value bound from the world (R-cn-7). */
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

/** One E-R row of an assertion's lowered payload: the entity and its attribute → value text. */
data class RowObject(
    val entity: String,
    val values: Map<String, String> = emptyMap(),
)

/** `assertionLower`: the `Statement[<Entity>]` row payload — rows only; cn never writes them. */
data class RowObjects(
    val rows: List<RowObject> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * How a definition renders. ⚑ The two fields are the S0.3 spike's defaults, pending Bora (memo ⚑S03-10 / ⚑S03-1):
 * [mode] — `PREDICATE` (the bearer's keys under the predicate) or `CONTEXT` (sub-terms lifted to joins and projected,
 * the shape AU's recipe islands use); [placeholderStyle] — `CURLY` (`{name}`, the translator's) or `COLON` (`:name`,
 * AU's islands).
 */
data class RenderOptions(
    val mode: RenderMode = RenderMode.PREDICATE,
    val placeholderStyle: PlaceholderStyle = PlaceholderStyle.CURLY,
)

enum class RenderMode { PREDICATE, CONTEXT }

enum class PlaceholderStyle { CURLY, COLON }

/** `definitionRender`: the text, what it read, what it inlined, and how it compared instants. */
data class Rendering(
    val text: String = "",
    val parametersRead: List<String> = emptyList(),
    val definitionsInlined: List<String> = emptyList(),
    val imprecisionMode: ImprecisionMode = ImprecisionMode.CERTAINLY,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** `materialize`: model text, path → content (a `def query` + its stacked entity). Canon stays text (CN-D1). */
data class ModelText(
    val files: Map<String, String> = emptyMap(),
)

/** The hygiene grades of S-6 v2 — computed by the typed model, so in `ttr-metadata` since CN-P2 S2.5 (alias). */
typealias GroundingGrade = org.tatrman.ttr.metadata.model.GroundingGrade

data class ConceptHygiene(
    val qname: String,
    val grade: GroundingGrade = GroundingGrade.UNGROUNDED,
    val via: Via? = null,
    val openTier: Boolean = false,
    val danglingGroundings: List<String> = emptyList(),
    /** `cn/retired-code` rows: a textual code invalid in the current vocabulary bundle. */
    val retiredCodes: List<String> = emptyList(),
    /** `cn/phase-as-default` rows (AM-36). */
    val phaseAsDefault: List<String> = emptyList(),
)

/** Hygiene report v2 — per concept; the hop column rides [ConceptHygiene.grade] + [ConceptHygiene.via]. */
data class HygieneReport(
    val concepts: List<ConceptHygiene> = emptyList(),
)
