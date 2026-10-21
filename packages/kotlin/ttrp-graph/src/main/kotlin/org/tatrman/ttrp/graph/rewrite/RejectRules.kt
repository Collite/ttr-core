// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.rewrite

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.expr.catalog.ValidityCatalog
import org.tatrman.ttrp.graph.model.Aggregation
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.TtrpGraph

/**
 * RJ-P1 rejects elaboration (contracts §5, R-E1-α), as revised by the RJ-P5 review. One rule runs in
 * the [Stratum.REJECT_ELABORATION] stratum — [GuardAndBranch], the guard-and-branch elaboration of a
 * **supported** single-in Calc reject site (declared BEFORE sugar — the single-pass engine cannot
 * re-lower the name-bearing guard/reject Calcs this stratum synthesizes if it ran after sugar;
 * a conscious amendment to contracts §5's "after sugar", recorded in the design log).
 *
 * Two positions are **fail-closed** in v1 rather than emitted (the former join-ON decomposition rule
 * was removed — it silently dropped rows): a reject-capable cast whose type v1 does not render
 * faithfully on both engines (`TTRP-RJ-107`) and a reject-capable expression in a join `on:`
 * (`TTRP-RJ-108`). [diagnostics] raises those as compile errors so nothing is emitted.
 *
 * All the reject-capability knowledge (what casts/ops can reject, their row codes, and whether v1
 * [ValiditySpec.supported] emits them) comes from the RJ-P0 [ValidityCatalog] — the single source of
 * truth shared with the measure and the post-stratum diagnostics.
 */
object RejectElaboration {
    private const val PREFIX = "_ttrp_"

    // ---- reject-capability (shared with RewriteEngine.measure and the diagnostics pass) ----

    /** One reject-capable sub-expression found on a node: its validity call, row code, and stable id. */
    private data class RejectSite(
        val validityCall: Expression,
        val code: String,
        val exprId: String,
        /** The catalogue pair this site rejects on (`text->date`, `op.div`, `fn.to_date`) — for RJ-107. */
        val pair: String,
        /** True iff v1 emits this site's guard faithfully on both engines ([ValiditySpec.supported]). */
        val supported: Boolean,
    )

    /** The validity type-pair suffix for a cast target (contracts §2 spellings). */
    private fun castSuffix(t: TtrpType): String =
        when (t.canonical) {
            "integer" -> "int64"
            "decimal" -> "decimal18_4"
            "float", "double", "number" -> "float64"
            "date" -> "date"
            "timestamp", "datetime" -> "timestamp"
            "bool" -> "bool"
            else -> t.canonical
        }

    /** The reject site for [e] if it is itself a reject-capable expr (cast / op.div / datetime parse), else null. */
    private fun siteOf(
        e: Expression,
        exprId: String,
    ): RejectSite? =
        when {
            e is Cast -> {
                val pair = "text->${castSuffix(e.target)}"
                ValidityCatalog.rejectCapability("cast", pair)?.let {
                    RejectSite(
                        internalCall(
                            "internal.is_castable",
                            listOf(e.expr, strLit(castSuffix(e.target), e.location)),
                            e.location,
                        ),
                        it.code,
                        exprId,
                        pair,
                        it.supported,
                    )
                }
            }
            e is FunctionCall && e.function.value == "op.div" ->
                ValidityCatalog.rejectCapability("op.div", "numeric,numeric->numeric")?.let {
                    RejectSite(
                        internalCall("internal.is_nonzero", listOf(e.args[1]), e.location),
                        it.code,
                        exprId,
                        "op.div",
                        it.supported,
                    )
                }
            e is FunctionCall && e.function.value in setOf("fn.to_date", "fn.to_timestamp") ->
                ValidityCatalog.all.firstOrNull { it.function == e.function.value }?.let { spec ->
                    val fmt = e.args.getOrNull(1) ?: strLit("", e.location)
                    RejectSite(
                        internalCall("internal.is_parseable_dt", listOf(e.args[0], fmt), e.location),
                        spec.code,
                        exprId,
                        e.function.value,
                        spec.supported,
                    )
                }
            else -> null
        }

    /** Every reject site in [e], in pre-order (document order), tagged with [exprId]. */
    private fun sitesIn(
        e: Expression,
        exprId: String,
    ): List<RejectSite> {
        val here = siteOf(e, exprId)?.let { listOf(it) } ?: emptyList()
        val nested =
            when (e) {
                is Cast -> sitesIn(e.expr, exprId)
                is FunctionCall -> e.args.flatMap { sitesIn(it, exprId) }
                is AggregateCall -> e.args.flatMap { sitesIn(it, exprId) }
                is CaseWhen ->
                    e.branches.flatMap { sitesIn(it.first, exprId) + sitesIn(it.second, exprId) } +
                        (e.elseExpr?.let { sitesIn(it, exprId) } ?: emptyList())
                is InList -> sitesIn(e.expr, exprId) + e.items.flatMap { sitesIn(it, exprId) }
                is IsNull -> sitesIn(e.expr, exprId)
                // MdPath is a leaf data-path reference (names/literals/sets/ranges — no nested
                // Expression), so no reject site can sit inside one. (Added when md-dotpath
                // introduced MdPath.)
                is ColumnRef, is Literal, is MdPath -> emptyList()
                // AU-core-P0: `:name` bindings and durations are leaves too.
                is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> emptyList()
                // TTRP 0.5: refused at check (TTRP-CN-001).
                is org.tatrman.ttrp.expr.ConceptForm -> emptyList()
            }
        // `here` first: a cast's own reject precedes any reject nested inside its operand (first-error).
        return here + nested
    }

    /** The ordered reject sites of a Calc's assignments (document order, each tagged with its column name). */
    private fun rejectSites(node: Node): List<RejectSite> =
        when (node) {
            is Calc -> node.assignments.flatMap { sitesIn(it.value, it.name) }
            else -> emptyList()
        }

    /** True if [node] carries ≥1 reject-capable expression (Calc assignments or a Join ON). */
    fun isRejectCapable(node: Node): Boolean =
        when (node) {
            is Calc -> node.assignments.any { sitesIn(it.value, it.name).isNotEmpty() }
            is Join -> node.on?.let { sitesIn(it, "on").isNotEmpty() } ?: false
            else -> false
        }

    /** True if [node]'s `rejects` out-port is consumed — directly, or via a consumed container port. */
    fun hasWiredRejects(
        g: TtrpGraph,
        nodeId: String,
    ): Boolean {
        if (g.edges.any { it.from == PortRef(nodeId, PortNames.REJECTS) }) return true
        for (c in g.containers.values) {
            for ((port, ref) in c.portMapping) {
                if (ref == PortRef(nodeId, PortNames.REJECTS) && g.edges.any { it.from == PortRef(c.id, port) }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * The count of un-elaborated wired reject sites [GuardAndBranch] WILL elaborate — the leading,
     * strictly-decreasing measure term. Only **supported** single-in Calc sites qualify: a wired reject
     * on an unsupported cast type or inside a join `on:` is fail-closed to a compile error by
     * [diagnostics] (RJ-107 / RJ-108, RJ-P5 review), never elaborated — so it must not count as
     * "pending" or the T8 measure would never reach zero.
     */
    fun pendingSites(g: TtrpGraph): Int =
        g.nodes.values.count { n ->
            n is Calc &&
                n.id !in g.synthProvenance &&
                hasWiredRejects(g, n.id) &&
                rejectSites(n).let { sites -> sites.isNotEmpty() && sites.all { it.supported } }
        }

    // ---- the rules ----

    /** guard-and-branch elaboration (single-in reject-capable node with a wired rejects port). */
    val GuardAndBranch: RewriteRule =
        object : RewriteRule {
            override val name = "reject-guard-and-branch"
            override val stratum = Stratum.REJECT_ELABORATION

            override fun apply(
                node: Node,
                graph: TtrpGraph,
                ctx: RewriteContext,
            ): RewriteResult {
                if (node !is Calc) return RewriteResult.Unchanged
                if (node.id in graph.synthProvenance) return RewriteResult.Unchanged
                if (!hasWiredRejects(graph, node.id)) return RewriteResult.Unchanged
                val sites = rejectSites(node)
                if (sites.isEmpty()) return RewriteResult.Unchanged
                // Fail-closed (RJ-P5 review, B1): an unsupported reject-capable type is NOT elaborated
                // to a (would-be degenerate/divergent) guard — it is left wired for [diagnostics] to
                // raise TTRP-RJ-107, so nothing is emitted. Only fully-supported sites elaborate.
                if (sites.any { !it.supported }) return RewriteResult.Unchanged
                return elaborate(node, sites, graph)
            }
        }

    // NOTE (RJ-P5 review, B2): the former `JoinOnDecompose` rule was REMOVED. It relocated a
    // single-side reject-capable ON cast onto an unguarded per-side calc but never synthesized a
    // guard/branch/reject, so a `rejects` wire off a join silently produced an empty stream while
    // invalid rows became NULL join keys and dropped — a §5 partition-invariant violation. v1 is now
    // fail-closed: [diagnostics] raises TTRP-RJ-108 for any reject-capable join `on:` with a wired
    // rejects port. Author the `cast`/`op.div` in a `calc` before the join instead.

    val RULES: List<RewriteRule> = listOf(GuardAndBranch)

    private fun elaborate(
        node: Calc,
        sites: List<RejectSite>,
        g: TtrpGraph,
    ): RewriteResult.Replaced {
        val loc = node.location
        val ssa = node.label.substringBefore('#')
        val guardId = "${node.id}_guard"
        val branchId = "${node.id}_branch"
        val rejectId = "${node.id}_reject"

        // guard calc: one _ttrp_v<n> per reject site (document order), computed by the internal fn.
        val validity = sites.mapIndexed { i, s -> Aggregation("${PREFIX}v${i + 1}", s.validityCall) }
        val guard = Calc(guardId, "${ssa}_guard", loc, assignments = validity)

        // branch predicate: conjunction of every validity flag.
        val predicate =
            validity
                .map { col(it.name, loc) }
                .reduce { a, b -> FunctionCall(CatalogId.AND, listOf(a, b), loc) }
        val branch = Branch(branchId, "${ssa}_branch", loc, predicate = predicate)

        // reject projection: first-error CASE ladders for the code and the failing-expr id.
        val codeLadder = ladder(validity.map { it.name }, sites.map { it.code }, loc)
        val exprLadder = ladder(validity.map { it.name }, sites.map { it.exprId }, loc)
        val reject =
            Calc(
                rejectId,
                "${ssa}_reject",
                loc,
                assignments =
                    listOf(
                        Aggregation("${PREFIX}reject_code", codeLadder),
                        Aggregation("${PREFIX}reject_expr", exprLadder),
                    ),
            )

        val containerId = GraphOps.containerIdOf(g, node.id)
        var ng = g
        ng = GraphOps.addNode(ng, guard, containerId)
        ng = GraphOps.addNode(ng, branch, containerId)
        ng = GraphOps.addNode(ng, reject, containerId)

        // rewire the input: X -> node.in  becomes  X -> guard.in -> branch -> {node.in | reject.in}.
        val inEdge = ng.edges.firstOrNull { it.to == PortRef(node.id, PortNames.IN) }
        ng = ng.copy(edges = ng.edges.filterNot { it.to == PortRef(node.id, PortNames.IN) })
        val newEdges = mutableListOf<Edge>()
        if (inEdge != null) newEdges += Edge(inEdge.from, PortRef(guardId, PortNames.IN), EdgeKind.DATA, loc)
        newEdges += Edge(PortRef(guardId, PortNames.OUT), PortRef(branchId, PortNames.IN), EdgeKind.DATA, loc)
        newEdges += Edge(PortRef(branchId, PortNames.TRUE), PortRef(node.id, PortNames.IN), EdgeKind.DATA, loc)
        newEdges += Edge(PortRef(branchId, PortNames.FALSE), PortRef(rejectId, PortNames.IN), EdgeKind.DATA, loc)
        ng = GraphOps.addEdges(ng, newEdges)

        // rewire the rejects consumer from node.rejects onto reject.out.
        ng = rewireRejects(ng, node.id, PortRef(rejectId, PortNames.OUT))

        // provenance: every synthesized node points back to the authored node.
        ng =
            ng.copy(
                synthProvenance =
                    ng.synthProvenance + mapOf(guardId to node.id, branchId to node.id, rejectId to node.id),
            )

        return RewriteResult.Replaced(
            ng,
            AppliedRewrite(
                "reject-guard-and-branch",
                Stratum.REJECT_ELABORATION,
                node.label,
                "${ssa}_guard/branch/reject",
                null,
                loc,
                "wired rejects → guard-and-branch elaboration (${sites.size} site(s))",
            ),
        )
    }

    // ---- post-stratum diagnostics (RJ-101 dead wire, RJ-107 unsupported type, RJ-108 join-ON) ----

    fun diagnostics(g: TtrpGraph): List<TtrpDiagnostic> {
        val out = mutableListOf<TtrpDiagnostic>()
        for (n in g.nodes.values) {
            if (n.id in g.synthProvenance) continue
            // A Container forwarding an inner producer's rejects (its `rejects` port maps to a
            // synth reject node's out) is legitimately wired — it is not a leaf that "can never
            // reject", so it must not draw the RJ-101 dead-wire warning (RJ-P3 fix).
            if (n is org.tatrman.ttrp.graph.model.Container) continue
            if (!hasWiredRejects(g, n.id)) continue
            when {
                // B2 (RJ-P5 review): a reject-capable join `on:` does not produce rejects in v1.
                n is Join && isRejectCapable(n) ->
                    out +=
                        TtrpDiagnostic(
                            TtrpDiagnosticId.RJ_108,
                            Severity.ERROR,
                            "reject-capable expression in the `on:` of join `${n.label}` does not produce rejects " +
                                "in v1 — move the cast/`op.div` into a `calc` before the join and wire `rejects` there",
                            n.location,
                        )
                // A reject-capable Calc still wired here was NOT elaborated ⇒ it has an unsupported site
                // (supported ones elaborate and rewire their wire away). B1 (RJ-P5 review): fail-closed.
                n is Calc && isRejectCapable(n) ->
                    rejectSites(n).firstOrNull { !it.supported }?.let { bad ->
                        out +=
                            TtrpDiagnostic(
                                TtrpDiagnosticId.RJ_107,
                                Severity.ERROR,
                                "reject-capable `${bad.pair}` on `${n.label}` is not supported in v1 (only " +
                                    "`text->int64` and `op.div` produce rejects) — remove the `rejects` wire " +
                                    "or change the target type",
                                n.location,
                            )
                    }
                // Any other wired-rejects node can never reject: the wire is a dead (empty) stream.
                !isRejectCapable(n) ->
                    out +=
                        TtrpDiagnostic(
                            TtrpDiagnosticId.RJ_101,
                            Severity.WARNING,
                            "`${n.label}` can never reject — the `rejects` wire is dead (empty stream)",
                            n.location,
                        )
            }
        }
        return out
    }

    // ---- small expression + graph builders ----

    private fun rewireRejects(
        g: TtrpGraph,
        fromNodeId: String,
        to: PortRef,
    ): TtrpGraph {
        val fromRef = PortRef(fromNodeId, PortNames.REJECTS)
        var ng = g.copy(edges = g.edges.map { if (it.from == fromRef) it.copy(from = to) else it })
        val containers = LinkedHashMap(ng.containers)
        val nodes = LinkedHashMap(ng.nodes)
        for ((cid, c) in ng.containers) {
            if (c.portMapping.values.none { it == fromRef }) continue
            val remapped = c.portMapping.mapValues { if (it.value == fromRef) to else it.value }
            val updated = c.copy(portMapping = remapped)
            containers[cid] = updated
            nodes[cid] = updated
        }
        return ng.copy(nodes = nodes, containers = containers)
    }

    private fun ladder(
        flags: List<String>,
        values: List<String>,
        loc: SourceLocation,
    ): CaseWhen =
        CaseWhen(
            branches =
                flags.zip(values).map { (flag, value) ->
                    FunctionCall(CatalogId.NOT, listOf(col(flag, loc)), loc) as Expression to strLit(value, loc)
                },
            elseExpr = null,
            location = loc,
        )

    private fun internalCall(
        id: String,
        args: List<Expression>,
        loc: SourceLocation,
    ): Expression = FunctionCall(CatalogId(id), args, loc)

    private fun col(
        name: String,
        loc: SourceLocation,
    ): Expression = ColumnRef(null, name, loc)

    private fun strLit(
        s: String,
        loc: SourceLocation,
    ): Expression = Literal(LiteralValue.Str(s), loc)
}
