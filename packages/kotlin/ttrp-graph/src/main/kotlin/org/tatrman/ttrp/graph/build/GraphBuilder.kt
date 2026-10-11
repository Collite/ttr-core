package org.tatrman.ttrp.graph.build

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ChainToPort
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ControlBlock
import org.tatrman.ttrp.ast.ControlDep
import org.tatrman.ttrp.ast.ControlKind
import org.tatrman.ttrp.ast.CubeletStmt
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortKind as AstPortKind
import org.tatrman.ttrp.ast.RelationArg
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Aggregation
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Display
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Except
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.FragmentSource
import org.tatrman.ttrp.graph.model.Intersect
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Pivot
import org.tatrman.ttrp.graph.model.Port
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.ReservedPorts
import org.tatrman.ttrp.graph.model.Select
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.Store
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.model.Union
import org.tatrman.ttrp.graph.model.Values
import org.tatrman.ttrp.resolve.ErRewrite
import org.tatrman.ttrp.resolve.Provenance
import org.tatrman.ttrp.resolve.TtrpChecker

/**
 * Builds the one internal [TtrpGraph] (B-T4) from the Phase-1 resolved report:
 * statements are incremental graph construction; variables desugar to named edges
 * with SSA reassignment (Q7-γ); containers map their ports onto internal node ports
 * (B-T9); control keywords become FS/SS edges (FF ⇒ TTRP-CTL-001). Build-time
 * structural diagnostics (FF, cross-container `err`, reserved port names) are emitted
 * here; acyclicity / single-in / display-sink checks are the StructureValidator's job.
 */
class GraphBuilder {
    data class BuildResult(
        val graph: TtrpGraph,
        val diagnostics: List<TtrpDiagnostic>,
    )

    fun build(report: TtrpChecker.Report): BuildResult {
        val ctx = Ctx(report.rewrites, report.displaySchemas)
        val doc = report.document

        // Pass 1: build containers (so cross-container refs resolve in pass 2).
        for (stmt in doc.statements) {
            if (stmt is ContainerDecl) ctx.buildContainer(stmt)
        }
        // Pass 2: program-level wiring / leaves / control.
        for (stmt in doc.statements) {
            when (stmt) {
                is ContainerDecl -> Unit
                is Assignment -> ctx.evalChain(stmt.chain, ctx.topScope, target = stmt.target, memberIds = null)
                is ChainStmt -> ctx.evalChain(stmt.chain, ctx.topScope, target = null, memberIds = null)
                is ControlDep -> ctx.controlEdge(stmt)
                is ControlBlock -> stmt.deps.forEach { ctx.controlEdge(it) }
                // T-W1: a cubelet write statement is validated by the frontend (CubeletStatementChecker)
                // but the graph→bundle pipeline does not yet execute MD writes — the plan is built and
                // lowered only via the conformance harness. Warn at the drop point so a `+=`/`:=`/`-=`
                // in a real `.ttrp` is not silently omitted from the bundle with no signal at all.
                is CubeletStmt ->
                    ctx.diags +=
                        TtrpDiagnostic(
                            TtrpDiagnosticId.MD_024,
                            Severity.WARNING,
                            "MD write statement is checked but not yet executed by the compile pipeline " +
                                "(S5C deferral) — it produces no bundle output",
                            stmt.lhs.location,
                        )
                else -> Unit
            }
        }
        // Carry S3's MD resolutions onto the graph, keyed by location — the S4 read lowering reads
        // them off the graph rather than re-resolving in emit (see TtrpGraph.mdResolutions).
        // AG-P0: each model-object Load gets its frontend-resolved physical source (keyed by the
        // load op's location, which is the Load node's location).
        val loadSources =
            ctx.nodes.values
                .filterIsInstance<Load>()
                .mapNotNull { l -> report.loadSources[l.location]?.let { l.id to it } }
                .toMap()
        return BuildResult(
            TtrpGraph(
                ctx.nodes,
                ctx.edges,
                ctx.containers,
                mdResolutions = report.mdResolutions.associateBy { it.location },
                loadSources = loadSources,
                logicalJoins = ctx.logicalJoins.toMap(),
            ),
            ctx.diags,
        )
    }

    /** Per-build mutable state (insertion-ordered — determinism groundwork for 2.3). */
    private class Ctx(
        val rewrites: List<ErRewrite>,
        /** Action displays: display op location → its resolved row schema (the frontend's DSP pass). */
        val displaySchemas: Map<SourceLocation, org.tatrman.ttrp.resolve.DisplaySchema> = emptyMap(),
    ) {
        val nodes = LinkedHashMap<String, Node>()
        val edges = mutableListOf<Edge>()

        /** AG-P0: Join id → the logical-spelled relation join condition (see [TtrpGraph.logicalJoins]). */
        val logicalJoins = LinkedHashMap<String, org.tatrman.ttrp.expr.Expression>()
        val containers = LinkedHashMap<String, Container>()
        val diags = mutableListOf<TtrpDiagnostic>()

        /** Top-level names (containers) → the port that produces them. */
        val topScope = HashMap<String, PortRef>()
        private var counter = 0
        private val labelCounts = HashMap<String, Int>()

        private fun freshId(): String = "n${counter++}"

        private fun labelFor(name: String?): String {
            if (name == null) return "~$counter"
            val k = (labelCounts[name] ?: 0) + 1
            labelCounts[name] = k
            return "$name#$k"
        }

        // ---- containers ----

        fun buildContainer(decl: ContainerDecl) {
            val id = freshId()
            val declaredPorts = decl.ports.map { toPort(it.kind, it.name) }
            for (p in decl.ports) {
                val legitErr = p.kind == AstPortKind.ERR && (p.name == PortNames.ERR || p.name == PortNames.REJECTS)
                if (ReservedPorts.isReserved(p.name) && !legitErr) {
                    diags += err(TtrpDiagnosticId.CTL_006, "`${p.name}` is a reserved port name", p.location)
                }
            }
            val body = decl.body
            // A fragment with a P6 decomposition builds real members from the lowered
            // canonical statements (bare ≡ embedded ≡ canonical hold by construction); an
            // undecomposed fragment (ttrb — P7) stays opaque with a FragmentSource.
            val flowStatements: List<org.tatrman.ttrp.ast.Statement>? =
                when {
                    body is FlowBody -> body.statements
                    body is FragmentBody && body.decomposition != null -> body.decomposition!!.statements
                    else -> null
                }
            if (flowStatements == null && body is FragmentBody) {
                val ports = declaredPorts.ifEmpty { listOf(dataOut()) }
                val container =
                    Container(
                        id,
                        decl.name,
                        decl.location,
                        decl.target.parts.last(),
                        emptyList(),
                        ports,
                        emptyMap(),
                        FragmentSource(body.tag, body.sourceText),
                        onFailureOf = decl.onFailureOf,
                        retries = decl.retries,
                    )
                nodes[id] = container
                containers[id] = container
                topScope[decl.name] = PortRef(id, container.defaultOut() ?: PortNames.OUT)
                return
            }

            // Flow body (or decomposed fragment): scope seeded with the in-ports. A
            // fragment with no declared ports gets a synthetic default out (as the opaque
            // path did), so its cross-engine wiring endpoint survives decomposition.
            val ports = if (body is FragmentBody) declaredPorts.ifEmpty { listOf(dataOut()) } else declaredPorts
            val scope = HashMap<String, PortRef>()
            for (p in ports) if (p.direction == PortDirection.IN) scope[p.name] = PortRef(id, p.name)
            val memberIds = mutableListOf<String>()
            val portMapping = LinkedHashMap<String, PortRef>()
            var lastOut: PortRef? = null
            // `… -> <out port>` writes the chain's value to the port, exactly as `<out port> = …` does.
            val outPorts =
                ports
                    .filter { it.direction == PortDirection.OUT && it.kind == PortKind.DATA }
                    .map { it.name }
                    .toSet()
            for (authored in flowStatements.orEmpty()) {
                val stmt = (authored as? ChainStmt)?.let { ChainToPort.asAssignment(it, outPorts) } ?: authored
                when (stmt) {
                    is Assignment -> {
                        val out = evalChain(stmt.chain, scope, target = stmt.target, memberIds = memberIds)
                        if (out != null) lastOut = out
                        val declared = ports.firstOrNull { it.name == stmt.target }
                        if (declared != null && declared.direction == PortDirection.OUT && out != null) {
                            portMapping[stmt.target] = out
                        }
                    }
                    is ChainStmt ->
                        evalChain(stmt.chain, scope, target = null, memberIds = memberIds)?.let {
                            lastOut =
                                it
                        }
                    else -> Unit
                }
            }
            // A decomposed fragment still records its raw interior (C2-f) for hover / round-trip.
            val fragmentSource = (body as? FragmentBody)?.let { FragmentSource(it.tag, it.sourceText) }
            // The single default DATA out maps to the final internal value when no explicit `<out> = …`
            // assignment bound it — UNIFORMLY for a canonical FlowBody and a decomposed fragment, so
            // bare ≡ embedded ≡ canonical graphs match no matter which surface named the terminal (T6.3.5).
            run {
                val outName = ports.firstOrNull { it.direction == PortDirection.OUT && it.kind == PortKind.DATA }?.name
                if (outName != null && outName !in portMapping && lastOut != null) portMapping[outName] = lastOut
            }
            val container =
                Container(
                    id,
                    decl.name,
                    decl.location,
                    decl.target.parts.last(),
                    memberIds,
                    ports,
                    portMapping,
                    fragmentSource,
                    onFailureOf = decl.onFailureOf,
                    retries = decl.retries,
                )
            nodes[id] = container
            containers[id] = container
            topScope[decl.name] = PortRef(id, container.defaultOut() ?: PortNames.OUT)
        }

        // ---- chain evaluation ----

        /**
         * Evaluates a chain to the port producing its value, wiring nodes/edges.
         * [memberIds] non-null ⇒ new nodes join that container; [target] names the
         * produced value (SSA label + scope binding).
         */
        fun evalChain(
            chain: Chain,
            scope: HashMap<String, PortRef>,
            target: String?,
            memberIds: MutableList<String>?,
        ): PortRef? {
            var prev: PortRef? = null
            for ((i, elem) in chain.elements.withIndex()) {
                prev =
                    when (elem) {
                        is DottedRef -> {
                            val ref = resolveRef(elem, scope)
                            if (i > 0 && prev != null && ref != null) {
                                // `a -> b.port` wiring: connect prev's out to this in-port.
                                if (prev.port == PortNames.ERR) {
                                    diags +=
                                        err(
                                            TtrpDiagnosticId.CTL_004,
                                            "cross-container `err` (signal) cannot cross containers",
                                            elem.location,
                                        )
                                }
                                edges += Edge(prev, ref, EdgeKind.DATA, elem.location)
                            }
                            ref
                        }
                        is OpCall -> {
                            val name = if (i == chain.elements.lastIndex) target else null
                            buildOp(elem, prev, scope, name, memberIds)
                        }
                    }
            }
            if (target != null && prev != null && chain.elements.all { it is DottedRef }) {
                scope[target] = prev
            }
            return prev
        }

        private fun resolveRef(
            ref: DottedRef,
            scope: HashMap<String, PortRef>,
        ): PortRef? {
            val base = scope[ref.parts.first()] ?: topScope[ref.parts.first()] ?: return null
            return if (ref.parts.size >= 2) PortRef(base.nodeId, ref.parts[1]) else base
        }

        // ---- op → node ----

        private fun buildOp(
            op: OpCall,
            prev: PortRef?,
            scope: HashMap<String, PortRef>,
            target: String?,
            memberIds: MutableList<String>?,
        ): PortRef? {
            val id = freshId()
            val label = labelFor(target)
            val loc = op.location
            val prov = provenanceFor(op)
            val node: Node =
                when (op.name) {
                    "load" ->
                        Load(
                            id,
                            label,
                            loc,
                            source = refText(firstUnnamed(op)),
                            schemaRef = schemaRefOf(op),
                            provenance = prov,
                        )
                    "store" -> Store(id, label, loc, target = refText(firstUnnamed(op)))
                    "display" -> Display(id, label, loc, name = refText(firstUnnamed(op)), schema = displaySchemas[loc])
                    "filter" -> Filter(id, label, loc, predicate = predicateOf(op, prev), provenance = prov)
                    "branch" -> Branch(id, label, loc, predicate = predicateOf(op, prev), provenance = prov)
                    "switch" -> Switch(id, label, loc, cases = switchCases(op), hasElse = hasElse(op))
                    "join" -> {
                        logicalJoinCondition(op)?.let { logicalJoins[id] = it }
                        Join(
                            id,
                            label,
                            loc,
                            type = joinType(op),
                            on = joinCondition(op),
                            provenance = joinProvenance(op),
                        )
                    }
                    "aggregate" ->
                        Aggregate(
                            id,
                            label,
                            loc,
                            groupBy = groupByKeys(op),
                            aggregations = assignEntries(op),
                            provenance = prov,
                        )
                    "union" -> Union(id, label, loc, arity = unnamedArgs(op).size.coerceAtLeast(1))
                    "intersect" -> Intersect(id, label, loc)
                    "except" -> Except(id, label, loc)
                    "sort" -> Sort(id, label, loc, keys = columnArgs(op), provenance = prov)
                    "limit" -> Limit(id, label, loc, count = firstNumberArg(op))
                    "distinct" -> Distinct(id, label, loc)
                    "project" -> Project(id, label, loc, columns = exprArgs(op), provenance = prov)
                    "select" -> Select(id, label, loc, columns = columnArgs(op), provenance = prov)
                    "calc" -> Calc(id, label, loc, assignments = assignEntries(op), provenance = prov)
                    "values" -> Values(id, label, loc)
                    "pivot" -> Pivot(id, label, loc)
                    else -> Project(id, label, loc)
                }
            nodes[id] = node
            memberIds?.add(id)

            when (node) {
                is Join -> {
                    val left = namedArgRef(op, "left", scope) ?: prev
                    val right = namedArgRef(op, "right", scope)
                    if (left != null) edges += Edge(left, PortRef(id, PortNames.LEFT), EdgeKind.DATA, loc)
                    if (right != null) edges += Edge(right, PortRef(id, PortNames.RIGHT), EdgeKind.DATA, loc)
                }
                is Union, is Intersect, is Except -> {
                    val sources = unnamedArgs(op).mapNotNull { resolveArgRef(it, scope) }.toMutableList()
                    if (sources.isEmpty() && prev != null) sources += prev
                    sources.forEachIndexed {
                        i,
                        src,
                        ->
                        edges += Edge(src, PortRef(id, "in${i + 1}"), EdgeKind.DATA, loc)
                    }
                }
                is Load, is Values -> Unit
                else -> {
                    val input = prev ?: firstUnnamed(op)?.let { resolveArgRef(it, scope) }
                    if (input !=
                        null
                    ) {
                        edges += Edge(input, PortRef(id, node.defaultIn() ?: PortNames.IN), EdgeKind.DATA, loc)
                    }
                }
            }
            if (target != null) scope[target] = PortRef(id, node.defaultOut() ?: PortNames.OUT)
            return PortRef(id, node.defaultOut() ?: PortNames.OUT)
        }

        // ---- control edges ----

        fun controlEdge(dep: ControlDep) {
            // FF is unrepresentable in EdgeKind and already rejected with TTRP-CTL-001 by the
            // Phase-1 front-half (contracts-pinned); skip it here to avoid double-reporting.
            if (dep.kind == ControlKind.FF) return
            val subj = topScope[dep.subject]?.nodeId ?: return
            val ref = topScope[dep.reference]?.nodeId ?: return
            val kind = if (dep.kind == ControlKind.FS) EdgeKind.CONTROL_FS else EdgeKind.CONTROL_SS
            edges += Edge(PortRef(ref, PortNames.OUT), PortRef(subj, PortNames.IN), kind, dep.location)
        }

        // ---- argument helpers ----

        private fun firstUnnamed(op: OpCall): Arg? = op.args.firstOrNull { it.name == null }

        private fun unnamedArgs(op: OpCall): List<Arg> = op.args.filter { it.name == null }

        private fun refText(arg: Arg?): String {
            val expr = (arg?.value as? ExprArg)?.expr ?: return ""
            return when (expr) {
                is ColumnRef -> (expr.port?.let { "$it." } ?: "") + expr.column
                // A string/number literal source (e.g. a file path `load("data/sales.csv")`) renders as its
                // value — so a decomposed `ColumnRef`-carried path and a canonical `Literal` path agree (T6.3.5).
                is org.tatrman.ttrp.expr.Literal ->
                    when (val v = expr.value) {
                        is org.tatrman.ttrp.expr.LiteralValue.Str -> v.value
                        is org.tatrman.ttrp.expr.LiteralValue.Num -> v.raw
                        is org.tatrman.ttrp.expr.LiteralValue.Bool -> v.value.toString()
                        org.tatrman.ttrp.expr.LiteralValue.Null -> "null"
                    }
                else -> ""
            }
        }

        private fun schemaRefOf(op: OpCall): String? {
            val schemaArg = op.args.firstOrNull { it.name == "schema" }?.value as? ExprArg ?: return null
            return (schemaArg.expr as? ColumnRef)?.column
        }

        private fun resolveArgRef(
            arg: Arg,
            scope: HashMap<String, PortRef>,
        ): PortRef? {
            val ref = (arg.value as? ExprArg)?.expr as? ColumnRef ?: return null
            val base = scope[ref.column] ?: topScope[ref.column] ?: return null
            val port = ref.port
            return if (port != null) PortRef(base.nodeId, port) else base
        }

        private fun namedArgRef(
            op: OpCall,
            name: String,
            scope: HashMap<String, PortRef>,
        ): PortRef? = op.args.firstOrNull { it.name == name }?.let { resolveArgRef(it, scope) }

        private fun predicateOf(
            op: OpCall,
            prev: PortRef?,
        ): Expression? {
            val exprArgs = op.args.filter { it.value is ExprArg }
            val params = if (prev != null) exprArgs else exprArgs.drop(1)
            return (params.firstOrNull()?.value as? ExprArg)?.expr
        }

        private fun joinType(op: OpCall): JoinType {
            val name =
                ((op.args.firstOrNull { it.name == "type" }?.value as? ExprArg)?.expr as? ColumnRef)
                    ?.column
                    ?.uppercase() ?: return JoinType.INNER
            return runCatching { JoinType.valueOf(name) }.getOrDefault(JoinType.INNER)
        }

        private fun joinCondition(op: OpCall): Expression? {
            val onArg = op.args.firstOrNull { it.name == "on" } ?: return null
            return when (val v = onArg.value) {
                is ExprArg -> v.expr
                is RelationArg ->
                    rewrites.firstOrNull { it.location == v.location && it.joinCondition != null }?.joinCondition
                else -> null
            }
        }

        private fun logicalJoinCondition(op: OpCall): Expression? {
            val rel = op.args.firstOrNull { it.name == "on" }?.value as? RelationArg ?: return null
            return rewrites
                .firstOrNull { it.location == rel.location && it.logicalJoinCondition != null }
                ?.logicalJoinCondition
        }

        private fun joinProvenance(op: OpCall): Provenance? {
            val rel = op.args.firstOrNull { it.name == "on" }?.value as? RelationArg ?: return null
            return rewrites.firstOrNull { it.location == rel.location }?.provenance
        }

        /** The E-d provenance for an op: the first attribute rewrite whose location falls within the op span. */
        private fun provenanceFor(op: OpCall): Provenance? =
            rewrites
                .firstOrNull {
                    it.joinCondition == null &&
                        // offsets only compare within ONE file (a file-backed fragment has its own, AG B5)
                        it.location.file == op.location.file &&
                        it.location.offsetStart >= op.location.offsetStart &&
                        it.location.offsetEnd <= op.location.offsetEnd
                }?.provenance

        private fun groupByKeys(op: OpCall): List<String> =
            op.config
                ?.entries
                .orEmpty()
                .filterIsInstance<org.tatrman.ttrp.ast.GroupByEntry>()
                .flatMap { it.keys }

        private fun assignEntries(op: OpCall): List<Aggregation> =
            op.config?.entries.orEmpty().filterIsInstance<org.tatrman.ttrp.ast.AssignEntry>().map {
                Aggregation(
                    it.name,
                    it.value,
                )
            }

        private fun switchCases(op: OpCall): List<Pair<String, Expression?>> =
            op.config
                ?.entries
                .orEmpty()
                .filterIsInstance<org.tatrman.ttrp.ast.AssignEntry>()
                .map { it.name to it.value }

        private fun hasElse(op: OpCall): Boolean = switchCases(op).any { it.first == PortNames.ELSE }

        private fun columnArgs(op: OpCall): List<String> =
            op.args.mapNotNull { (it.value as? ExprArg)?.expr as? ColumnRef }.map { it.column }

        private fun exprArgs(op: OpCall): List<Expression> = op.args.mapNotNull { (it.value as? ExprArg)?.expr }

        private fun firstNumberArg(op: OpCall): Long? {
            val lit = (firstUnnamed(op)?.value as? ExprArg)?.expr as? Literal ?: return null
            return (lit.value as? LiteralValue.Num)?.raw?.toLongOrNull()
        }

        // ---- ports / diagnostics ----

        private fun toPort(
            kind: AstPortKind,
            name: String,
        ): Port =
            when (kind) {
                AstPortKind.IN -> Port(name, PortKind.DATA, PortDirection.IN)
                AstPortKind.OUT -> Port(name, PortKind.DATA, PortDirection.OUT)
                AstPortKind.ERR ->
                    Port(name, if (name == PortNames.REJECTS) PortKind.DATA else PortKind.CONTROL, PortDirection.OUT)
            }

        private fun dataOut() = Port(PortNames.OUT, PortKind.DATA, PortDirection.OUT)

        private fun err(
            id: TtrpDiagnosticId,
            message: String,
            loc: SourceLocation,
        ) = TtrpDiagnostic(id, Severity.ERROR, message, loc)
    }
}
