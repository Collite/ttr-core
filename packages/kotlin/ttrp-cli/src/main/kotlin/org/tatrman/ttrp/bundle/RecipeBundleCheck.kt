// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.emit.recipe.RecipeStatements
import org.tatrman.ttrp.graph.TtrpPipeline

/**
 * What `ttrp build` would refuse in a checked recipe, as diagnostics (review-119 M5) — so `ttrp check` and
 * `ttrp/validate` (the generate→validate→repair loop) never call clean a recipe the build cannot bundle:
 *
 * - an island whose form is not SQL or canonical (`builtin`, `foreign`, `call`, `turn`) — AU-core-P2 bundles SQL
 *   statements only;
 * - an island whose statement does not compile (a column no row carries, an `IN`/`EXISTS` inside an `AND` the
 *   decomposition cannot carry …) — the emit failure's text, at the island.
 *
 * Each is a TTRP-REC-080 error at the island's container.
 */
object RecipeBundleCheck {
    private val BUNDLED = setOf("sql", "canonical")

    fun check(plan: TtrpPipeline.PlanResult): List<TtrpDiagnostic> {
        val model = plan.recipe ?: return emptyList()
        val graph = plan.graph ?: return emptyList()
        val exec = plan.exec ?: return emptyList()
        val located = { island: String ->
            exec.islands.firstOrNull { it.name == island }?.let { graph.containers[it.id]?.location }
        }
        val out = mutableListOf<TtrpDiagnostic>()
        val unbundled = model.contexts.filter { it.kind !in BUNDLED }
        for (c in unbundled) {
            out +=
                TtrpDiagnostic(
                    TtrpDiagnosticId.REC_080,
                    Severity.ERROR,
                    "`${c.name}` is a `${c.kind}` island — a recipe bundle runs SQL statements only (AU-core-P2); " +
                        "the Server's other island kinds bundle later",
                    located(c.name) ?: continue,
                )
        }
        for ((island, e) in RecipeStatements.failures(plan, skip = unbundled.map { it.name }.toSet())) {
            out +=
                TtrpDiagnostic(
                    TtrpDiagnosticId.REC_080,
                    Severity.ERROR,
                    "`$island` does not compile to a statement: ${e.id.code} ${e.detail}",
                    e.location ?: located(island) ?: continue,
                )
        }
        return out
    }
}
