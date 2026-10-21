// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Model

/**
 * The resolved, snapshot-pinned cn model a [ConceptService] is built over (cn contracts §3, CN-P2 S2.6 T5): the
 * reconciled ttr-metadata [model] — its typed cn tier ([cn], CN-P2 S2.5: every concept's landing, `via`, grade and the
 * md→er hop facts) beside the er, md and automation tiers a definition reads — with every `definition:`, `members:`
 * and binding `filter:` parsed as a TTR-P 0.5 expression and name-resolved ([definitions], CN-D29), and the hash of
 * the snapshot it was built from ([snapshotHash], echoed in every `LowerProvenance`).
 */
data class ResolvedCnModel(
    val model: Model,
    val definitions: CnDefinitions,
    val snapshotHash: String,
) {
    /** The typed conceptual tier (`CnSchema` — the contracts' `CnModel`); empty when the model has none. */
    val cn: CnSchema get() = model.schemas["cn"] as? CnSchema ?: CnSchema()

    /**
     * Every definition-level diagnostic (contracts §7, Kotlin-only — CN-D29): the parse and name-resolution findings of
     * [definitions] and the model-level lints over them ([CnDefinitionLints]).
     */
    fun definitionDiagnostics(): List<Diagnostic> = definitions.diagnostics + CnDefinitionLints.run(this)

    companion object {
        /** Parses and resolves every definition block of [model]; [snapshotHash] defaults to the model version. */
        fun of(
            model: Model,
            snapshotHash: String = model.version.value,
        ): ResolvedCnModel = ResolvedCnModel(model, CnDefinitions.parse(model), snapshotHash)
    }
}
