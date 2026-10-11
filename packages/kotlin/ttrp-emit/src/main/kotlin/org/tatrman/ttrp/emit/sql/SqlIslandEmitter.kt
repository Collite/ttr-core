// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.translate.v1.SqlDialect
import org.tatrman.ttr.semantics.md.MdBindings
import org.tatrman.ttr.semantics.md.MdModel
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.capability.RejectsSupport
import org.tatrman.ttrp.graph.collapse.Island
import org.tatrman.ttrp.graph.model.TtrpGraph

/**
 * SQL for one island. [text] is the island's payload SQL; [nodeRanges] maps each emitted
 * node id to its source span (provenance for P4 hover, E-d).
 */
data class SqlEmitResult(
    val text: String,
    val nodeRanges: Map<String, SourceLocation>,
)

/**
 * The three `SELECT count(*)` queries for one reject site's partition (RJ-P5): the guard-input
 * count (`in`), the clean-output count (`processed`), and the reject count (`rejects`). Emitted into
 * the PG island script; each result populates a `SiteCounts` row of `counts.json`.
 */
data class SiteCountQueries(
    val site: String,
    val inCount: String,
    val processed: String,
    val rejects: String,
)

/**
 * Public entry: emit a normalized island as Postgres-dialect SQL.
 *
 * Two island shapes reach SQL emit:
 *  - **Fragment islands** (`Container.fragment != null`) — an authored `"""sql … """` block.
 *    Its interior is emitted **verbatim** (C2-f: fragment interiors are never rewritten;
 *    graph-level decomposition of fragments into relational nodes is P6). This is the hero's
 *    `acc_prep` island.
 *  - **Decomposed relational islands** — a container of relational nodes. These route through
 *    [CtePlanner] (CTE-per-node). No v1 hero produces one (fragments stay opaque until P6), so
 *    the graph→[EmitNode] walk here is best-effort over the schema the graph carries
 *    (`Container.declaredPorts`, `Load.schema`); the synthetic golden corpus exercises
 *    [CtePlanner] directly with fully-specified [EmitNode]s.
 */
class SqlIslandEmitter(
    private val world: BoundWorld,
    /**
     * The cubelet `md2db_*` bindings for MD dot-path read lowering (S4-A) — an injection seam
     * mirroring S3's MdModel/member-snapshot wiring (production loading of the bindings is a later
     * seam; tests supply the shared `sales-model` binding fixture). Null when no MD lowering is
     * needed, in which case an `mdPath` reaching emit raises UNSUPPORTED_NODE.
     */
    private val mdBindings: MdBindings? = null,
    /**
     * The logical [MdModel] paired with [mdBindings] — needed by the lowering for anything beyond a
     * grain-direct column read: hop joins (grain keys + domain sources), calc coordinates (case-table /
     * inline drills), diff-journal grain, and deriving a calc for an authored coarser-than-grain
     * attribute. Null leaves those paths raising typed `md/…` errors, exactly as a missing binding does.
     */
    private val mdModel: MdModel? = null,
) {
    fun emit(
        island: Island,
        graph: TtrpGraph,
    ): SqlEmitResult {
        val outputs = emitOutputs(island, graph)
        return outputs.values.singleOrNull()
            ?: throw org.tatrman.ttrp.emit.TtrpEmitException(
                org.tatrman.ttrp.emit.EmitDiagnosticId.UNSUPPORTED_NODE,
                detail =
                    "SQL island '${island.name}' has ${outputs.size} outputs (${outputs.keys.joinToString()}); " +
                        "use emitOutputs() for multi-output decomposed islands",
                island = island.name,
            )
    }

    /**
     * Emit a SQL island as one statement **per (non-`rejects`) container OUT port** (the SQL
     * counterpart of the Polars multi-sink container). A fragment island yields its verbatim
     * interior under the island name (C2-f); a decomposed relational island routes each output's
     * dependency-cone through [SqlGraphEmitter] → [CtePlanner]. This is the entry the bundle uses
     * for a PG-targeted `crunch` (S3.5): the lowered Branch gives `result` (b.true) + `low`
     * (b.false), each a self-contained CTE chain.
     */
    fun emitOutputs(
        island: Island,
        graph: TtrpGraph,
    ): Map<String, SqlEmitResult> {
        val container = graph.containers[island.id]
        container?.verbatimFragment?.let {
            return mapOf(
                island.name to SqlEmitResult(it.sourceText.trim(), emptyMap()),
            )
        }
        requireNotNull(container) { "SQL island '${island.name}' has no container" }
        val dialect = dialect(island)
        val rejects = world.engines[island.engine]?.manifest?.rejectsSupport() ?: RejectsSupport.NONE

        val planner =
            CtePlanner(
                facade = { model -> TranslatorFacade(IslandModelHandle(model), dialect) },
                mdLowering = mdBindings?.let { MdPathLowering(it, mdModel) },
                mdResolutions = graph.mdResolutions,
                rejectsSupport = rejects,
            )
        return SqlGraphEmitter(graph, world).plansByOutput(container).mapValues { (_, plan) ->
            SqlEmitResult(planner.emit(plan, island.name), emptyMap())
        }
    }

    /**
     * The partition **count** queries for each elaborated reject site (RJ-P5 eighth point): a
     * `SELECT count(*)` over the guard-input relation (`in`), the guard's clean-output cone
     * (`processed`), and the reject terminal cone (`rejects`). Each is computed **independently**
     * (never `in − rejects`) so a broken producer imbalances the triple and the eighth point turns
     * red — on PG as it does on Polars. Empty for a fragment island or a rejects-free container.
     */
    fun countQueries(
        island: Island,
        graph: TtrpGraph,
    ): List<SiteCountQueries> {
        val container = graph.containers[island.id] ?: return emptyList()
        if (container.verbatimFragment != null) return emptyList()
        val dialect = dialect(island)
        val rejects = world.engines[island.engine]?.manifest?.rejectsSupport() ?: RejectsSupport.NONE
        val planner =
            CtePlanner(
                facade = { model -> TranslatorFacade(IslandModelHandle(model), dialect) },
                mdLowering = mdBindings?.let { MdPathLowering(it) },
                mdResolutions = graph.mdResolutions,
                rejectsSupport = rejects,
            )
        val gemit = SqlGraphEmitter(graph, world)
        return org.tatrman.ttrp.emit.core.RejectSites
            .of(graph, container)
            .map { site ->
                val cleanPlan =
                    gemit.planForNode(container, site.cleanNodeId)
                        ?: error("reject site '${site.site}': clean node is not a transform")
                val rejectPlan =
                    gemit.planForNode(container, site.rejectsNodeId)
                        ?: error("reject site '${site.site}': reject node is not a transform")
                val base = gemit.inputBaseRelation(container, site.inFrom)
                val inCount =
                    if (base != null) {
                        "SELECT count(*) AS n FROM \"$base\""
                    } else {
                        countOf(planner.emit(gemit.planForNode(container, site.inFrom.nodeId)!!, island.name))
                    }
                SiteCountQueries(
                    site = site.site,
                    inCount = inCount,
                    processed = countOf(planner.emit(cleanPlan, island.name)),
                    rejects = countOf(planner.emit(rejectPlan, island.name)),
                )
            }
    }

    private fun countOf(sql: String): String = "SELECT count(*) AS n FROM (\n${sql.trimEnd()}\n) _ttrp_c"

    /** Resolve the dialect for this island's engine from the bound world. */
    fun dialect(island: Island): SqlDialect {
        val engine = world.engines[island.engine]?.engine
        return DialectRegistry.forEngine(
            engineType = engine?.type ?: "postgres",
            version = engine?.version,
        )
    }
}
