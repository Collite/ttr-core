// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.rewrite

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Except
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Intersect
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.TtrpGraph

/** The compiler-shipped rewrite-rule set (T6-d α: rules are compiler knowledge, not manifest data). */
object Rules {
    val ALL: List<RewriteRule> by lazy {
        // REJECT_ELABORATION rules run first (stratum order); within the stratum, join-ON
        // decomposition precedes guard-and-branch (RejectElaboration.RULES order).
        RejectElaboration.RULES +
            listOf(
                SelectToProject,
                CalcToProject,
                DistinctToAggregate,
                HavingSplit,
                BranchToFilters,
                RightJoinSwap,
                IntersectToSemiJoin,
                ExceptToAntiJoin,
            )
    }

    private fun native(
        node: Node,
        graph: TtrpGraph,
        ctx: RewriteContext,
    ): Boolean {
        val kind = node::class.simpleName ?: return true
        val eng = ctx.engineOf(node.id, graph) ?: return true // program-level: nothing to lower against
        return eng.manifest.nodes.containsKey(kind)
    }

    // ---- Stratum: SUGAR (engine-independent, in-place swaps) ----

    val SelectToProject =
        rule("select->project", Stratum.SUGAR) { node, g, _ ->
            if (node !is org.tatrman.ttrp.graph.model.Select) {
                null
            } else {
                // `select(a, b)` = replace-semantics projection of exactly the listed columns (renames →
                // aliases). AG-P0 F-AG: the rule used to build an EMPTY Project, so every `select`
                // emitted `SELECT 1` on the SQL path (and an empty row type downstream).
                val p =
                    Project(
                        node.id,
                        node.label,
                        node.location,
                        columns = node.columns.map { ColumnRef(null, it, node.location) },
                        aliases = node.columns.map { node.renames[it] },
                        provenance = node.provenance,
                    )
                replaced(
                    GraphOps.swapNode(g, node.id, p),
                    "select->project",
                    Stratum.SUGAR,
                    node,
                    node.location,
                    null,
                    "Select is Project sugar",
                )
            }
        }

    val CalcToProject =
        rule("calc->project", Stratum.SUGAR) { node, g, _ ->
            if (node !is Calc) {
                null
            } else {
                val p =
                    Project(
                        node.id,
                        node.label,
                        node.location,
                        // `calc { name = expr }` ADDS named computed columns over the input row
                        // (language-design §): carry the assignment names as aliases and pass the
                        // input columns through. Bare-expression columns alone would drop both.
                        columns = node.assignments.map { it.value },
                        aliases = node.assignments.map { it.name },
                        passthrough = true,
                        provenance = node.provenance,
                    )
                replaced(
                    GraphOps.swapNode(g, node.id, p),
                    "calc->project",
                    Stratum.SUGAR,
                    node,
                    node.location,
                    null,
                    "Calc is Project sugar",
                )
            }
        }

    val DistinctToAggregate =
        rule("distinct->aggregate", Stratum.SUGAR) { node, g, _ ->
            if (node !is Distinct) {
                null
            } else {
                // Group-by-ALL-columns Aggregate with no aggregate calls (B-T10 sweep). The
                // `distinctAllColumns` marker distinguishes this dedup from a scalar GROUP BY ()
                // (empty groupBy); the input schema is enumerated at P3 emit, not known here.
                val a =
                    Aggregate(
                        node.id,
                        node.label,
                        node.location,
                        distinctAllColumns = true,
                        provenance = node.provenance,
                    )
                replaced(
                    GraphOps.swapNode(g, node.id, a),
                    "distinct->aggregate",
                    Stratum.SUGAR,
                    node,
                    node.location,
                    null,
                    "Distinct is group-by-all Aggregate",
                )
            }
        }

    val HavingSplit =
        rule("having->aggregate+filter", Stratum.SUGAR) { node, g, _ ->
            if (node !is Aggregate || node.having == null) {
                null
            } else {
                val filterId = "${node.id}~having"
                val containerId = GraphOps.containerIdOf(g, node.id)
                val filter = Filter(filterId, node.label, node.location, predicate = node.having)
                var ng = GraphOps.swapNode(g, node.id, node.copy(having = null))
                ng = GraphOps.addNode(ng, filter, containerId)
                // Insert the filter between the aggregate's out and its consumers.
                ng = GraphOps.redirectFrom(ng, PortRef(node.id, PortNames.OUT), PortRef(filterId, PortNames.OUT))
                ng =
                    GraphOps.addEdges(
                        ng,
                        listOf(Edge(PortRef(node.id, PortNames.OUT), PortRef(filterId, PortNames.IN), EdgeKind.DATA)),
                    )
                ng = remapContainerPort(ng, node.id, PortNames.OUT, PortRef(filterId, PortNames.OUT))
                replaced(
                    ng,
                    "having->aggregate+filter",
                    Stratum.SUGAR,
                    node,
                    node.location,
                    null,
                    "HAVING expressed as downstream Filter",
                )
            }
        }

    // ---- Stratum: NODE_LOWERING (engine-relative) ----

    val BranchToFilters =
        rule("branch->filter", Stratum.NODE_LOWERING) { node, g, ctx ->
            if (node !is Branch || native(node, g, ctx)) {
                null
            } else {
                val eng = ctx.engineOf(node.id, g)!!
                val containerId = GraphOps.containerIdOf(g, node.id)
                val pred = node.predicate
                val inEdge = g.edges.firstOrNull { it.to == PortRef(node.id, PortNames.IN) }
                val src = inEdge?.from
                val tId = "${node.id}~t"
                val fId = "${node.id}~f"
                val ft = Filter(tId, "${node.label}~t", node.location, predicate = pred, provenance = node.provenance)
                // 3VL-correct complement: a NULL predicate row goes to the FALSE port.
                val ff =
                    Filter(
                        fId,
                        "${node.label}~f",
                        node.location,
                        predicate =
                            pred?.let {
                                notCoalesceFalse(it)
                            },
                        provenance = node.provenance,
                    )
                var ng = GraphOps.addNode(g, ft, containerId)
                ng = GraphOps.addNode(ng, ff, containerId)
                if (src != null) {
                    ng =
                        GraphOps.addEdges(
                            ng,
                            listOf(
                                Edge(src, PortRef(tId, PortNames.IN), EdgeKind.DATA),
                                Edge(src, PortRef(fId, PortNames.IN), EdgeKind.DATA),
                            ),
                        )
                }
                // Redirect the Branch's out-ports onto the two Filters, then drop it. The Branch's
                // err/rejects (C3-f) route onto the true-side filter `ft`, which carries the Branch's
                // own predicate and so shares its error/reject rows — without this the `removeNode`
                // below would silently drop any branch.err / branch.rejects consumer.
                val redirect =
                    mapOf(
                        PortRef(node.id, PortNames.TRUE) to PortRef(tId, PortNames.OUT),
                        PortRef(node.id, PortNames.FALSE) to PortRef(fId, PortNames.OUT),
                        PortRef(node.id, PortNames.ERR) to PortRef(tId, PortNames.ERR),
                        PortRef(node.id, PortNames.REJECTS) to PortRef(tId, PortNames.REJECTS),
                    )
                ng = ng.copy(edges = ng.edges.map { e -> redirect[e.from]?.let { e.copy(from = it) } ?: e })
                for ((old, new) in redirect) ng = remapContainerPort(ng, node.id, old.port, new)
                ng = GraphOps.removeNode(ng, node.id)
                replaced(
                    ng,
                    "branch->filter",
                    Stratum.NODE_LOWERING,
                    node,
                    node.location,
                    eng.engine.qname.name,
                    "Branch not native on ${eng.manifest.id}",
                )
            }
        }

    val RightJoinSwap =
        rule("right-join->left-join", Stratum.NODE_LOWERING) { node, g, ctx ->
            if (node !is Join || node.type != JoinType.RIGHT) {
                null
            } else {
                val eng = ctx.engineOf(node.id, g) ?: return@rule null
                if ("right" in (eng.manifest.nodes["Join"]?.types ?: emptyList())) {
                    return@rule null
                }
                // Swap inputs (left<->right) and flip to LEFT; a Project restoring column order is a P3 emit concern.
                // The `on` condition is port-qualified (`left.x = right.y`), so swapping the inputs
                // demands swapping the `left`/`right` qualifiers inside it too — otherwise the
                // condition would reference columns on the now-wrong sides.
                var ng =
                    GraphOps.swapNode(
                        g,
                        node.id,
                        node.copy(type = JoinType.LEFT, on = node.on?.let { swapLeftRightPorts(it) }),
                    )
                ng =
                    ng.copy(
                        edges =
                            ng.edges.map { e ->
                                when (e.to) {
                                    PortRef(node.id, PortNames.LEFT) -> e.copy(to = PortRef(node.id, PortNames.RIGHT))
                                    PortRef(node.id, PortNames.RIGHT) -> e.copy(to = PortRef(node.id, PortNames.LEFT))
                                    else -> e
                                }
                            },
                    )
                replaced(
                    ng,
                    "right-join->left-join",
                    Stratum.NODE_LOWERING,
                    node,
                    node.location,
                    eng.engine.qname.name,
                    "right join lowered by input swap",
                )
            }
        }

    val IntersectToSemiJoin =
        setOpRule("intersect->semi-join", { it is Intersect }) { node -> JoinType.SEMI to node }

    val ExceptToAntiJoin =
        setOpRule("except->anti-join", { it is Except }) { node -> JoinType.ANTI to node }

    // ---- helpers ----

    private fun setOpRule(
        name: String,
        match: (Node) -> Boolean,
        toJoin: (Node) -> Pair<JoinType, Node>,
    ): RewriteRule =
        rule(name, Stratum.NODE_LOWERING) { node, g, ctx ->
            if (!match(node) || native(node, g, ctx)) {
                null
            } else {
                val eng = ctx.engineOf(node.id, g)!!
                val (type, _) = toJoin(node)
                // Set-semantics join on ALL columns (Distinct-wrapped inputs are a later concern);
                // in1/in2 become left/right of a semi/anti Join at the same id (edges preserved by
                // port rename). The `onAllColumns` marker records the full-row match — WITHOUT it a
                // null `on` would read as an unconditioned/cross match, not set intersection/difference.
                val join =
                    Join(
                        node.id,
                        node.label,
                        node.location,
                        type = type,
                        onAllColumns = true,
                        provenance = node.provenance,
                    )
                var ng = GraphOps.swapNode(g, node.id, join)
                ng =
                    ng.copy(
                        edges =
                            ng.edges.map { e ->
                                when (e.to) {
                                    PortRef(node.id, "in1") -> e.copy(to = PortRef(node.id, PortNames.LEFT))
                                    PortRef(node.id, "in2") -> e.copy(to = PortRef(node.id, PortNames.RIGHT))
                                    else -> e
                                }
                            },
                    )
                replaced(
                    ng,
                    name,
                    Stratum.NODE_LOWERING,
                    node,
                    node.location,
                    eng.engine.qname.name,
                    "$name (dataframe set-op lowering)",
                )
            }
        }

    private fun remapContainerPort(
        g: TtrpGraph,
        oldNodeId: String,
        oldPort: String,
        newRef: PortRef,
    ): TtrpGraph {
        val target = PortRef(oldNodeId, oldPort)
        val containers = LinkedHashMap(g.containers)
        val nodes = LinkedHashMap(g.nodes)
        for ((cid, c) in g.containers) {
            if (c.portMapping.values.none { it == target }) continue
            val remapped = c.portMapping.mapValues { if (it.value == target) newRef else it.value }
            val updated = c.copy(portMapping = remapped)
            containers[cid] = updated
            nodes[cid] = updated
        }
        return g.copy(nodes = nodes, containers = containers)
    }

    /**
     * Swap the `left`/`right` port qualifiers on every [ColumnRef] in [e], recursing through
     * the whole expression tree. Used when a Join's inputs are physically swapped (RightJoinSwap)
     * so the port-qualified `on` condition keeps referencing the correct sides. Unqualified refs
     * (`port == null`) and any other port name are left untouched.
     */
    private fun swapLeftRightPorts(e: Expression): Expression =
        when (e) {
            is ColumnRef ->
                e.copy(
                    port =
                        when (e.port) {
                            PortNames.LEFT -> PortNames.RIGHT
                            PortNames.RIGHT -> PortNames.LEFT
                            else -> e.port
                        },
                )
            is FunctionCall -> e.copy(args = e.args.map { swapLeftRightPorts(it) })
            is AggregateCall -> e.copy(args = e.args.map { swapLeftRightPorts(it) })
            is Cast -> e.copy(expr = swapLeftRightPorts(e.expr))
            is CaseWhen ->
                e.copy(
                    branches = e.branches.map { swapLeftRightPorts(it.first) to swapLeftRightPorts(it.second) },
                    elseExpr = e.elseExpr?.let { swapLeftRightPorts(it) },
                )
            is InList -> e.copy(expr = swapLeftRightPorts(e.expr), items = e.items.map { swapLeftRightPorts(it) })
            is IsNull -> e.copy(expr = swapLeftRightPorts(e.expr))
            is Literal -> e
            is MdPath -> e // no port-qualified refs inside an MD path
            is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> e // no port refs either
            is org.tatrman.ttrp.expr.ConceptForm -> e // TTRP 0.5: refused at check (TTRP-CN-001)
        }

    /** `not(coalesce(pred, false))` — 3VL-correct FALSE-port complement (NULL ⇒ false). */
    private fun notCoalesceFalse(pred: Expression): Expression {
        val falseLit = Literal(LiteralValue.Bool(false), pred.location)
        val coalesced = FunctionCall(CatalogId("fn.coalesce"), listOf(pred, falseLit), pred.location)
        return FunctionCall(CatalogId.NOT, listOf(coalesced), pred.location)
    }

    private fun replaced(
        g: TtrpGraph,
        rule: String,
        stratum: Stratum,
        before: Node,
        loc: SourceLocation,
        engine: String?,
        reason: String,
    ): RewriteResult.Replaced =
        RewriteResult.Replaced(
            g,
            AppliedRewrite(rule, stratum, before.label, before::class.simpleName ?: "?", engine, loc, reason),
        )

    private fun rule(
        ruleName: String,
        s: Stratum,
        body: (Node, TtrpGraph, RewriteContext) -> RewriteResult.Replaced?,
    ): RewriteRule =
        object : RewriteRule {
            override val name = ruleName
            override val stratum = s

            override fun apply(
                node: Node,
                graph: TtrpGraph,
                ctx: RewriteContext,
            ): RewriteResult = body(node, graph, ctx) ?: RewriteResult.Unchanged
        }
}
