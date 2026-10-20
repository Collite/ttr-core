// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.movement

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.MdStageRef
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Port
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph

/**
 * MD-read hoist (S4-B4) — the stratum that makes an MD dot-path read runnable on a **non-SQL**
 * (Polars) engine. An MD read only exists over `db` (Postgres) fact tables; a Polars island reads
 * nothing but staged Arrow/CSV (contracts F-c), so it cannot scan them. Instead of teaching Polars to
 * reach Postgres, each MD read in a non-SQL container is **hoisted into its own db island** that
 * computes the (grain-pinned, scalar) read and stages a 1-row result; the Polars island reads that
 * value as `pl.lit(<port>.item(...))` (the [org.tatrman.ttrp.graph.model.TtrpGraph.mdStaging] map
 * feeds the emitter).
 *
 * Runs after capability/reject strata and **before** [MovementSynthesizer]: the synthesized
 * `db → polars` DATA edge is a genuine engine crossing, so movement wraps it into
 * `Store → Transfer → Load` and [org.tatrman.ttrp.graph.collapse.ContainerCollapse] islands + waves it
 * exactly like the authored `@PG`-fragment → Polars boundary (the `acc_prep` precedent) — no
 * bespoke island/transfer/wave bookkeeping. The md-source container's SQL is emitted from its carried
 * reads by [org.tatrman.ttrp.emit.sql.MdSourceIslandEmitter] at bundle time (SQL generation lives in
 * the emit layer, downstream of this graph stratum).
 *
 * Inert unless the program has an MD read placed on a non-SQL engine: SQL-placed MD reads still lower
 * inline (the S4-A path), and a program with no MD paths is untouched. Idempotent shape: the hoisted
 * reads carry the ORIGINAL `mdPath` nodes (same [SourceLocation]s), so both the db-island SQL emit
 * (via `mdResolutions`) and the Polars staging lookup resolve unchanged.
 */
class MdReadHoist(
    private val bound: BoundWorld,
) {
    fun hoist(graph: TtrpGraph): TtrpGraph {
        // A container whose engine cannot host MD reads (the only non-SQL v1 engine type is polars).
        val nonSqlContainers =
            graph.containers.values.filter { c ->
                bound.engines[c.target]
                    ?.manifest
                    ?.type
                    .let { it != null && !isSqlType(it) }
            }
        if (nonSqlContainers.isEmpty()) return graph
        val hostEngine = sqlHostEngine(graph) ?: return graph // no db engine to host the read → leave inert

        val nodes = graph.nodes.toMutableMap()
        val containers = graph.containers.toMutableMap()
        val edges = graph.edges.toMutableList()
        val mdStaging = graph.mdStaging.toMutableMap()
        val mdSources = graph.mdSourceContainers.toMutableSet()

        for (container in nonSqlContainers) {
            // Distinct MD reads in this container's members, in first-seen order (stable columns).
            val reads =
                container.memberIds
                    .mapNotNull { graph.nodes[it] }
                    .flatMap { mdPathsIn(it) }
                    .distinctBy { it.location }
            if (reads.isEmpty()) continue

            val projId = "mdsrc~${container.id}~proj"
            val srcId = "mdsrc~${container.id}"
            val aliases = reads.indices.map { "md_$it" }
            // The md-source container's single member: a bare projection of the reads. It is never
            // emitted through the graph SQL path — MdSourceIslandEmitter reads these columns + aliases
            // directly — but it keeps the container well-formed for collapse/SSA (a non-empty member
            // with a mapped OUT port).
            val proj =
                Project(
                    id = projId,
                    label = projId,
                    location = container.location,
                    columns = reads,
                    aliases = aliases,
                )
            val srcContainer =
                Container(
                    id = srcId,
                    label = srcId,
                    location = container.location,
                    target = hostEngine,
                    memberIds = listOf(projId),
                    declaredPorts = listOf(Port(PortNames.OUT, PortKind.DATA, PortDirection.OUT)),
                    portMapping = mapOf(PortNames.OUT to PortRef(projId, PortNames.OUT)),
                )
            nodes[projId] = proj
            nodes[srcId] = srcContainer
            containers[srcId] = srcContainer

            // Add the staged IN port to the consuming container + the cross-engine DATA edge that
            // MovementSynthesizer will wrap. The IN port is read as `staging/<MD_STAGE_PORT>.arrow`.
            val staged =
                container.copy(
                    declaredPorts =
                        container.declaredPorts + Port(MD_STAGE_PORT, PortKind.DATA, PortDirection.IN),
                )
            nodes[container.id] = staged
            containers[container.id] = staged
            edges += Edge(PortRef(srcId, PortNames.OUT), PortRef(container.id, MD_STAGE_PORT), EdgeKind.DATA)

            reads.forEachIndexed { i, read -> mdStaging[read.location] = MdStageRef(MD_STAGE_PORT, aliases[i]) }
            mdSources += srcId
        }

        return graph.copy(
            nodes = nodes,
            containers = containers,
            edges = edges,
            mdStaging = mdStaging,
            mdSourceContainers = mdSources,
        )
    }

    /** The SQL engine that hosts hoisted MD reads: the sole/first non-Polars engine (postgres in v1). */
    private fun sqlHostEngine(graph: TtrpGraph): String? =
        bound.engines
            .filterValues { it.manifest.type?.let(::isSqlType) == true }
            .keys
            .minOrNull() // deterministic; v1 worlds carry a single SQL engine

    private fun isSqlType(type: String): Boolean = type != "polars"

    // --- MD-read collection (mirrors CtePlanner/CapabilityChecker node-expression walks) -----------

    private fun mdPathsIn(node: Node): List<MdPath> = nodeExpressions(node).flatMap { collectMdPaths(it) }

    private fun nodeExpressions(node: Node): List<Expression> =
        when (node) {
            is Filter -> listOfNotNull(node.predicate)
            is Branch -> listOfNotNull(node.predicate)
            is Join -> listOfNotNull(node.on)
            is Aggregate -> node.aggregations.map { it.value } + listOfNotNull(node.having)
            is Project -> node.columns
            is Switch -> node.cases.mapNotNull { it.second }
            else -> emptyList()
        }

    private fun collectMdPaths(e: Expression): List<MdPath> =
        when (e) {
            is MdPath -> listOf(e)
            is FunctionCall -> e.args.flatMap { collectMdPaths(it) }
            is AggregateCall -> e.args.flatMap { collectMdPaths(it) }
            is Cast -> collectMdPaths(e.expr)
            is CaseWhen ->
                e.branches.flatMap { collectMdPaths(it.first) + collectMdPaths(it.second) } +
                    (e.elseExpr?.let { collectMdPaths(it) } ?: emptyList())
            is InList -> collectMdPaths(e.expr) + e.items.flatMap { collectMdPaths(it) }
            is IsNull -> collectMdPaths(e.expr)
            is ColumnRef, is Literal -> emptyList()
            is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> emptyList()
            // TTRP 0.5: a cn definition form is refused at check (TTRP-CN-001) — it never reaches a graph.
            is org.tatrman.ttrp.expr.ConceptForm -> emptyList()
        }

    companion object {
        /** The container IN port carrying the staged MD scalars (read as `staging/<name>.arrow`). */
        const val MD_STAGE_PORT = "mdstage"
    }
}
