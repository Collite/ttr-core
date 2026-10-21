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

    data class Inspection(
        val diagnostics: List<TtrpDiagnostic>,
        val statements: List<org.tatrman.ttrp.emit.recipe.RecipeStatement>,
    )

    fun check(plan: TtrpPipeline.PlanResult): List<TtrpDiagnostic> = inspect(plan).diagnostics

    fun inspect(plan: TtrpPipeline.PlanResult): Inspection {
        val model = plan.recipe ?: return Inspection(emptyList(), emptyList())
        val graph = plan.graph ?: return Inspection(emptyList(), emptyList())
        val exec = plan.exec ?: return Inspection(emptyList(), emptyList())
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
        // an island inlines the cone it reads, so an island upstream that fails (or is not bundled) fails every island
        // downstream of it too — the finding is the upstream one's alone. An island reads the island owning the node
        // that feeds its IN port, or — a role reads a role by its name (`in overdue`) — the island that port names.
        val owner = exec.islands.flatMap { i -> (i.memberIds + i.id).map { it to i.name } }.toMap()
        val names = exec.islands.map { it.name }.toSet()
        val reads =
            exec.islands.associate { i ->
                i.name to
                    graph.edges
                        .filter { it.to.nodeId == i.id }
                        .flatMap { listOfNotNull(owner[it.from.nodeId], it.to.port.takeIf { p -> p in names }) }
                        .filter { it != i.name }
                        .toSet()
            }
        val compiled = RecipeStatements.compile(plan, skip = unbundled.map { it.name }.toSet())
        val failures = compiled.failures
        val broken = failures.keys + unbundled.map { it.name }

        fun upstreamBroken(
            island: String,
            seen: Set<String> = emptySet(),
        ): Boolean = reads[island].orEmpty().any { it !in seen && (it in broken || upstreamBroken(it, seen + island)) }
        for ((island, e) in failures.filterKeys { !upstreamBroken(it) }) {
            out +=
                TtrpDiagnostic(
                    TtrpDiagnosticId.REC_080,
                    Severity.ERROR,
                    "`$island` does not compile to a statement: ${e.id.code} ${e.detail}",
                    e.location ?: located(island) ?: continue,
                )
        }
        return Inspection(out, compiled.statements)
    }
}
