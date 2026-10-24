// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.methods

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.collapse.ExecutionGraph
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Port
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.lsp.protocol.ContainerView
import org.tatrman.ttrp.lsp.protocol.DerivedView
import org.tatrman.ttrp.lsp.protocol.EdgeView
import org.tatrman.ttrp.lsp.protocol.GetGraphResult
import org.tatrman.ttrp.lsp.protocol.GraphView
import org.tatrman.ttrp.lsp.protocol.IslandView
import org.tatrman.ttrp.lsp.protocol.NodeView
import org.tatrman.ttrp.lsp.protocol.PortView
import org.tatrman.ttrp.lsp.protocol.ProvenanceView
import org.tatrman.ttrp.lsp.protocol.RangeView
import org.tatrman.ttrp.lsp.protocol.TransferView
import org.tatrman.ttrp.lsp.protocol.AbstractCoord
import org.tatrman.ttrp.lsp.viewstate.CanvasGraphs
import org.tatrman.ttrp.lsp.viewstate.ZetaKeys

/**
 * Serializes a [TtrpPipeline.PlanResult] into the `ttrp/getGraph` wire shape
 * (contracts §4). Structure comes from the **authored** build graph (see
 * [org.tatrman.ttrp.lsp.protocol.GetGraphResult] — the canvas is an authoring surface,
 * so `Branch` shows as `Branch`, not the polars `branch→filter` lowering); the derived
 * orchestration overlay (island engines, synthesized transfers, waves) comes from the
 * collapsed [ExecutionGraph]. A cross-engine program edge is annotated with the ζ of
 * the transfer the compiler synthesized for it, and each transfer also surfaces as a
 * synthesized program leaf.
 */
object GraphViewBuilder {
    fun build(
        program: String,
        plan: TtrpPipeline.PlanResult,
        elaborated: Boolean = false,
    ): GetGraphResult {
        // Default: the authored graph (the editing canvas). `elaborated`: the normalized graph with
        // the reject cluster visible (a read-only inspection overlay, RS-4) — `synthProvenance` then
        // back-references each synth node to its authored origin (see [nodeView]).
        val authored = (if (elaborated) plan.graph else plan.authoredGraph) ?: TtrpGraph.EMPTY
        val exec = plan.exec

        // container id → the transfer synthesized OUT of it, if cross-engine (fromIsland is a container id).
        val transferByFromContainer =
            exec
                ?.transfers
                ?.filter { it.fromIsland != null }
                ?.associateBy { it.fromIsland!! }
                ?: emptyMap()

        val containerViews =
            authored.containers.values.map { c ->
                containerView(authored, c)
            }

        // Program leaves: authored program-level nodes (Display/Store) that are not containers,
        // plus each synthesized transfer as a read-only leaf.
        val programNodes =
            authored.nodes.values.filter { n ->
                !authored.containers.containsKey(n.id) && authored.containerOf(n.id) == null
            }
        val leaves = mutableListOf<NodeView>()
        programNodes.forEach { leaves += nodeView(ZetaKeys.leaf(it), it) }
        exec?.transfers?.forEach { t ->
            leaves +=
                NodeView(
                    zeta = t.id,
                    kind = "Transfer",
                    label = t.id,
                    range = null,
                    ports =
                        listOf(
                            PortView("in", "data", "in"),
                            PortView("out", "data", "out"),
                        ),
                    synthesized = true,
                )
        }

        // Program-level edges: both endpoints are containers or program leaves.
        val memberIds =
            authored.containers.values
                .flatMap { it.memberIds }
                .toSet()
        val programEdges =
            authored.edges
                .filter { it.from.nodeId !in memberIds && it.to.nodeId !in memberIds }
                .map { e ->
                    val via = transferByFromContainer[e.from.nodeId]?.id
                    edgeView(authored, e, via)
                }

        val provenance = LinkedHashMap<String, ProvenanceView>()
        for ((key, node) in ZetaKeys.all(authored)) {
            node.provenance?.let { provenance[key] = ProvenanceView(it.originQname, it.originName) }
        }

        // Deterministic auto-layout per canvas (the C1-b `autoLayout` contract field).
        val autoLayout =
            CanvasGraphs.autoLayouts(authored).mapValues { (_, coords) ->
                coords.mapValues { (_, c) -> AbstractCoord(c.layer, c.index) }
            }

        return GetGraphResult(
            graph = GraphView(program, containerViews, leaves, programEdges),
            provenance = provenance,
            derived = emptyList(), // bare-fragment derived sub-graphs land in P6
            orchestration = derivedView(exec),
            autoLayout = autoLayout,
            recipe =
                plan.recipe?.let { model ->
                    org.tatrman.ttrp.lsp.protocol.RecipeSummary(
                        model.name,
                        model.mode.spelling,
                        listOf(
                            org.tatrman.ttrp.lsp.protocol.RecipeLane(
                                "candidates",
                                listOfNotNull(
                                    model.candidates?.let {
                                        org.tatrman.ttrp.lsp.protocol.RecipeIsland(
                                            it,
                                            model.candidateKind ?: "canonical",
                                        )
                                    },
                                ),
                            ),
                            org.tatrman.ttrp.lsp.protocol.RecipeLane(
                                "context",
                                model.contexts.map {
                                    org.tatrman.ttrp.lsp.protocol
                                        .RecipeIsland(it.name, it.kind, it.contextClass.spelling)
                                },
                            ),
                            org.tatrman.ttrp.lsp.protocol.RecipeLane(
                                "decision",
                                listOfNotNull(
                                    model.decision?.let {
                                        org.tatrman.ttrp.lsp.protocol.RecipeIsland(
                                            it,
                                            "decision",
                                            routes =
                                                model.routes.map { r ->
                                                    r.name
                                                },
                                        )
                                    },
                                ),
                            ),
                            org.tatrman.ttrp.lsp.protocol.RecipeLane(
                                "actions",
                                model.wirings.map {
                                    org.tatrman.ttrp.lsp.protocol
                                        .RecipeIsland(it.action, "action", routes = it.routes)
                                },
                            ),
                        ),
                    )
                },
        )
    }

    private fun containerView(
        graph: TtrpGraph,
        c: Container,
    ): ContainerView {
        val nodes =
            c.memberIds.mapNotNull { graph.node(it) }.map { n ->
                // In the elaborated view a synth reject node back-references its authored origin.
                val synthOf = graph.synthProvenance[n.id]?.let { ZetaKeys.of(graph, it) }
                nodeView(ZetaKeys.member(c.label, n), n, synthOf)
            }
        val memberSet = c.memberIds.toSet()
        val internalEdges =
            graph.edges
                .filter { it.from.nodeId in memberSet && it.to.nodeId in memberSet }
                .map { edgeView(graph, it, via = null) }
        return ContainerView(
            path = c.label,
            target = c.target,
            derived = false,
            fragment = c.fragment?.tag,
            ports = portGroups(c.declaredPorts),
            nodes = nodes,
            edges = internalEdges,
        )
    }

    private fun nodeView(
        zeta: String,
        node: Node,
        synthOf: String? = null,
    ): NodeView =
        NodeView(
            zeta = zeta,
            kind = node::class.simpleName ?: "Node",
            label = node.label,
            range = rangeOf(node.location),
            ports = node.ports().map { portView(it) },
            synthesized = synthOf != null,
            synthOf = synthOf,
            provenance = node.provenance?.let { ProvenanceView(it.originQname, it.originName) },
        )

    private fun edgeView(
        graph: TtrpGraph,
        e: Edge,
        via: String?,
    ): EdgeView =
        EdgeView(
            from = ZetaKeys.of(graph, e.from.nodeId),
            to = ZetaKeys.of(graph, e.to.nodeId),
            fromPort = e.from.port,
            toPort = e.to.port,
            type =
                when (e.kind) {
                    EdgeKind.DATA -> "data"
                    EdgeKind.CONTROL_FS -> "control-fs"
                    EdgeKind.CONTROL_SS -> "control-ss"
                },
            via = via,
        )

    private fun portView(p: Port): PortView =
        PortView(
            name = p.name,
            kind = if (p.kind == PortKind.DATA) "data" else "control",
            direction = if (p.direction == PortDirection.IN) "in" else "out",
        )

    private fun portGroups(ports: List<Port>): Map<String, List<String>> {
        val groups = linkedMapOf("in" to mutableListOf<String>(), "out" to mutableListOf(), "err" to mutableListOf())
        for (p in ports) {
            val bucket =
                when {
                    p.name == "err" || p.name == "rejects" -> "err"
                    p.direction == PortDirection.IN -> "in"
                    else -> "out"
                }
            groups.getValue(bucket).add(p.name)
        }
        return groups.filterValues { it.isNotEmpty() }
    }

    private fun derivedView(exec: ExecutionGraph?): DerivedView {
        if (exec == null) return DerivedView(emptyList(), emptyList(), emptyList())
        return DerivedView(
            islands = exec.islands.map { IslandView(it.id, it.name, it.engine, it.invocation) },
            transfers = exec.transfers.map { TransferView(it.id, it.fromIsland, it.toIsland, it.via, it.format) },
            waves = exec.waves,
        )
    }

    /** ANTLR-style 1-based line → LSP 0-based; columns already 0-based. Null for [SourceLocation.UNKNOWN]. */
    private fun rangeOf(loc: SourceLocation): RangeView? {
        if (loc.line < 0) return null
        return RangeView(loc.line - 1, loc.column, loc.endLine - 1, loc.endColumn)
    }
}
