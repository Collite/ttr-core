// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.assertion.v1.Assertion
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.statement.v1.Statement
import java.time.Clock

/**
 * The cn layer below the determinism frontier (cn contracts §3; architecture §2). Three function families:
 *
 *  - **statements (read)** — validate a `statement.v1` frame, lower it through the md path to `plan.v1`, explain it;
 *  - **assertions (write)** — validate an `assertion.v1` frame, lower it to the E-R row payload a ledger writes,
 *    explain it as the inspector's sentence (cn never executes a write);
 *  - **definitions** — render a phase / defined quality / member-restricted kind on the md or the er layer, and
 *    materialize a phase as model text.
 *
 * Every function is a pure function of the resolved, snapshot-pinned model it was built over ([ResolvedCnModel]):
 * no LLM, no server call, no I/O mid-lower (architecture §7; `PuritySpec`, `DeterminismSpec`). The shape is pinned —
 * `ConceptServiceContractSpec` asserts the method names, arities and types, so a rename shows up as a test change, not
 * as drift. [CnConceptService] implements it.
 */
interface ConceptService {
    // --- statements (read) ---

    /**
     * Structural + referential + ambiguity (`cn/ambiguous-statement`) + time (`cn/missing-time`) + reachability PER
     * CONSUMER (AM-4): an aggregating slot (measures, group_by, compare) needs an md grounding
     * (`cn/not-analytically-reachable`); a PhaseFilter needs the md renderer or the stacked entity's md grounding (AM-5).
     */
    fun statementValidate(s: Statement): List<Diagnostic>

    /** Slots bound via cn2md, phases inlined transitively and rendered on md, `:name` / `:as_of` bound → plan.v1. */
    fun statementLower(s: Statement): LowerResult

    /** From the statement: concept labels in [locale], phase definitions as prose, parameters named (R-cn-7). */
    fun statementExplain(
        s: Statement,
        locale: String,
    ): Explanation

    // --- assertions (write) ---

    /** Refs resolved · the event has an ER landing · participant labels and paths · units · as-of · basis. */
    fun assertionValidate(a: Assertion): List<Diagnostic>

    /**
     * The `Statement[<Entity>]` row payload: the event's row — its known key, the participants' FK values via the
     * labelled relation paths, the period, the qualities — with the as-of, the meta and the obligations beside it.
     * Data only: cn never writes it. Refuses (the findings, no rows) on any `assertionValidate` ERROR.
     */
    fun assertionLower(a: Assertion): RowObjects

    /** The inspector's sentence (bubble ①'s twin) in [locale]. */
    fun assertionExplain(
        a: Assertion,
        locale: String,
    ): Explanation

    // --- definitions (both renderers, ⚑CN-13) ---

    /**
     * MD: the lowered PL expression (a filter predicate / calculated member for ttrp-emit). ER: SQL-over-entities text
     * in [dialect]. A mentioned concept with no grounding on [layer] → `cn/definition-unreachable(<layer>)`.
     */
    fun definitionRender(
        target: ConceptRef,
        layer: Layer,
        dialect: String,
        options: RenderOptions,
    ): Rendering

    /** `ttr cn materialize` — the er rendering written as a `def query` + stacked entity, as model TEXT (CN-D1). */
    fun materialize(
        phase: ConceptRef,
        dialect: String,
    ): ModelText

    /** Per concept: the grounding grade, `via`, core/open, dangling groundings, retired codes, phase-as-default rows. */
    fun hygieneReport(): HygieneReport
}

/**
 * The service over a [ResolvedCnModel] (cn contracts §3): [hygieneReport] (CN-P2 S2.7, [CnHygiene]); the validates
 * ([StatementValidator], [AssertionValidator] — S3.2); [statementLower] ([StatementLowerer] — S3.3); [definitionRender]
 * and [materialize] ([DefinitionRenderer] — S3.4); [assertionLower] ([AssertionLowerer] — S3.5); the explains
 * ([Explainer] — S3.6). A frame it cannot serve is refused with findings, never an exception. [clock] gives "now" — the
 * evaluation day of a statement with no `as_of` (recorded in its provenance), of an md rendering with no
 * `RenderOptions.asOf` and of an explanation's `:as_of`; nothing else reads a clock (`PuritySpec`).
 */
class CnConceptService(
    private val model: ResolvedCnModel,
    clock: Clock = Clock.systemUTC(),
) : ConceptService {
    private val statements = StatementValidator(model)
    private val assertions = AssertionValidator(model)
    private val rows = AssertionLowerer(model)
    private val lowerer = StatementLowerer(model, clock)
    private val definitions = DefinitionRenderer(model, clock)
    private val explainer = Explainer(model, clock)

    override fun statementValidate(s: Statement): List<Diagnostic> = statements.validate(s)

    override fun statementLower(s: Statement): LowerResult = lowerer.lower(s)

    override fun assertionValidate(a: Assertion): List<Diagnostic> = assertions.validate(a)

    override fun assertionLower(a: Assertion): RowObjects = rows.lower(a)

    /** A phase filter's definition is always said as prose — the inspector's bubble ① names it (H-4, A-CN-54). */
    override fun statementExplain(
        s: Statement,
        locale: String,
    ): Explanation = explainer.statement(s, locale, definitions = true)

    override fun assertionExplain(
        a: Assertion,
        locale: String,
    ): Explanation = explainer.assertion(a, locale)

    /** The er text is the shared dialect's (S0.3 §3); the translator unparses it per engine, so [dialect] is not read. */
    override fun definitionRender(
        target: ConceptRef,
        layer: Layer,
        dialect: String,
        options: RenderOptions,
    ): Rendering = definitions.render(target, layer, options)

    override fun materialize(
        phase: ConceptRef,
        dialect: String,
    ): ModelText = definitions.materialize(phase)

    override fun hygieneReport(): HygieneReport = CnHygiene.report(model)
}
