// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.assertion.v1.Assertion
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.statement.v1.Statement

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
 * no LLM, no server call, no I/O mid-lower (architecture §7). CN-P0 S0.4 pins the SHAPE only —
 * [ConceptServiceContractSpec] asserts the method names and arities, so a rename at CN-P3 shows up as a test change,
 * not as drift. Every body is CN-P3 ([NotImplementedConceptService]) but [hygieneReport] ([CnConceptService], S2.7).
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

    /** The `Statement[<Entity>]` row payload: participants' FK values via the labelled relation paths. */
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
 * The service over a [ResolvedCnModel] (cn contracts §3). CN-P2 S2.7 pulls [hygieneReport] forward — model-only, no
 * consumer ([CnHygiene]); CN-P3 lands the families stage by stage (S3.2: the two validates — [StatementValidator],
 * [AssertionValidator]); a family not landed yet refuses as the scaffold does.
 */
class CnConceptService(
    private val model: ResolvedCnModel,
) : ConceptService by NotImplementedConceptService() {
    private val statements = StatementValidator(model)
    private val assertions = AssertionValidator(model)

    override fun statementValidate(s: Statement): List<Diagnostic> = statements.validate(s)

    override fun assertionValidate(a: Assertion): List<Diagnostic> = assertions.validate(a)

    override fun hygieneReport(): HygieneReport = CnHygiene.report(model)
}

/** The scaffold stand-in: every call refuses with the phase that builds it. */
class NotImplementedConceptService : ConceptService {
    private fun later(): Nothing = throw UnsupportedOperationException("CN-P3")

    override fun statementValidate(s: Statement): List<Diagnostic> = later()

    override fun statementLower(s: Statement): LowerResult = later()

    override fun statementExplain(
        s: Statement,
        locale: String,
    ): Explanation = later()

    override fun assertionValidate(a: Assertion): List<Diagnostic> = later()

    override fun assertionLower(a: Assertion): RowObjects = later()

    override fun assertionExplain(
        a: Assertion,
        locale: String,
    ): Explanation = later()

    override fun definitionRender(
        target: ConceptRef,
        layer: Layer,
        dialect: String,
        options: RenderOptions,
    ): Rendering = later()

    override fun materialize(
        phase: ConceptRef,
        dialect: String,
    ): ModelText = later()

    override fun hygieneReport(): HygieneReport = later()
}
