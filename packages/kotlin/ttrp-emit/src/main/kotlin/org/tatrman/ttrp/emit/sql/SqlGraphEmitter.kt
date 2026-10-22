// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.emit.core.SsaNames
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.TtrpGraph

/**
 * Walks a normalized **relational** container in the graph and produces, per (non-`rejects`)
 * container OUT port, the topologically-ordered [EmitNode] chain [CtePlanner] consumes — the SQL
 * counterpart of [org.tatrman.ttrp.emit.polars.PolarsGraphEmitter]. Where Polars reads Arrow/CSV
 * dynamically, SQL needs **static columns**, so every node's output row type is propagated here
 * (Load/staged schema → Filter passthrough → Join `right_on` dedup → Aggregate keys+aggs).
 *
 * Input resolution (per node, in declared in-port order — left before right):
 *  - a container IN port  → a **staged** base table named after the port, typed from the
 *    world-declared staging-boundary schema of the same name (S3.5 T3.5.1);
 *  - a member [Load]      → a base table (the CSV temp table), typed from its world storage schema;
 *  - an upstream transform → that node's CTE.
 *
 * A member [Load] is a base-table scan (like [CtePlanner]'s base tables), never its own CTE; the
 * transform nodes become the CTE chain. Each OUT port's plan is the dependency cone of its terminal
 * (shared CTEs are re-emitted per output — one self-contained statement each).
 *
 * **Multi-output** islands (e.g. a lowered Branch → `result`=b.true / `low`=b.false) yield one plan
 * per port. A **wired** `rejects` port is elaborated by the RJ-P1 stratum into a real reject
 * terminal and re-wired onto a normal `.out` producer, so it flows through here like any output
 * (the guard CTE renders raw — [CtePlanner]/[RejectGuardSql] — Option E). A port still mapped
 * literally to a node's `rejects` is a **dead wire** (a node that can never reject, RJ-101) and is
 * skipped — an empty erroneous-rows stream is not emitted.
 */
class SqlGraphEmitter(
    private val graph: TtrpGraph,
    private val world: BoundWorld,
    /**
     * AG-P0 `sql-text` mode: a model-object Load resolves to its physical source ([EmitInput.Model])
     * and an IN port fed by a same-engine container resolves to that container's output
     * ([EmitInput.Upstream]) — the inputs of a self-contained single-statement island. Off (the
     * default) keeps the CTE/ADBC behaviour: Loads are session temps, IN ports staged relations.
     */
    private val sqlText: Boolean = false,
    /**
     * AU-core-P2 (a recipe): a `"""sql` fragment with a decomposition emits from its members, like canonical — a
     * recipe's TTR-SQL reads E-R entities and other islands' ports, which no verbatim run could. Off (the default)
     * keeps the AG-P0 rule: an authored fragment runs as written.
     */
    private val fragmentsAsMembers: Boolean = false,
    /**
     * AU-core-P2 (a recipe's statements only): an equality of a text column with an INTEGER column compares the
     * integer as text — the automation stdlib's `obligation.key` is text, an entity's key a number. Off (the default)
     * leaves every comparison as written: an ordinary program's SQL never changes (review-119 B5).
     */
    private val textKeys: Boolean = false,
) {
    /** The fragment [c] runs as written — none when [fragmentsAsMembers] and [c] decomposed into members. */
    private fun verbatim(c: Container) =
        c.verbatimFragment?.takeUnless { fragmentsAsMembers && c.memberIds.isNotEmpty() }

    /** OUT port name → the ordered [EmitNode] plan (terminal last) producing that port's rows. */
    fun plansByOutput(container: Container): LinkedHashMap<String, List<EmitNode>> {
        val members = container.memberIds.mapNotNull { graph.nodes[it] }.map { logical(it) }
        qualifiedRefs = qualifiedRefsOf(members)
        val transforms = members.filter { it !is Load }
        val ordered = topoOrder(transforms)
        val cteNames = SsaNames.assign(ordered)
        val outCols = LinkedHashMap<String, List<EmitColumn>>()
        ordered.forEach { n -> outCols[n.id] = outputColumns(n, container, outCols) }

        val plans = LinkedHashMap<String, List<EmitNode>>()
        container.portMapping.forEach { (port, ref) ->
            // A port still mapped literally to `rejects` is a dead wire (RJ-101) — an elaborated
            // rejects producer is re-wired to a normal `.out` (RJ-P1), so it does not hit this guard.
            if (ref.port == "rejects") return@forEach
            val terminal = graph.nodes[ref.nodeId] ?: return@forEach
            if (terminal is Load) return@forEach // a raw Load can't be an island output on its own
            val cone = ancestorsAndSelf(terminal, transforms)
            val chain =
                ordered
                    .filter { it.id in cone }
                    .map { n -> emitNode(n, container, cteNames, outCols) }
            plans[port] = chain
        }
        return plans
    }

    /** AG-P0 sql-text: a relation join reads its logical-spelled condition (the loads keep logical names). */
    private fun logical(n: Node): Node =
        if (sqlText && n is Join) graph.logicalJoins[n.id]?.let { n.copy(on = it) } ?: n else n

    /**
     * The dependency-cone plan (terminal last) ending at an arbitrary transform member [nodeId] —
     * the same chain [plansByOutput] builds for a port producer, for any interior node. Null if
     * [nodeId] is not a transform member (e.g. a [Load] base table, which is a scan, not a CTE).
     * Used for the RJ-P5 partition **count** queries (guard-input / clean-output cones).
     */
    fun planForNode(
        container: Container,
        nodeId: String,
    ): List<EmitNode>? {
        val members = container.memberIds.mapNotNull { graph.nodes[it] }
        qualifiedRefs = qualifiedRefsOf(members)
        val transforms = members.filter { it !is Load }
        val terminal = transforms.firstOrNull { it.id == nodeId } ?: return null
        val ordered = topoOrder(transforms)
        val cteNames = SsaNames.assign(ordered)
        val outCols = LinkedHashMap<String, List<EmitColumn>>()
        ordered.forEach { n -> outCols[n.id] = outputColumns(n, container, outCols) }
        val cone = ancestorsAndSelf(terminal, transforms)
        return ordered.filter { it.id in cone }.map { n -> emitNode(n, container, cteNames, outCols) }
    }

    // --- qualified references (AU-core-P2) --------------------------------------------

    /**
     * Per node, `(qualifier, column)` of every qualified reference it makes (`out_.warehouse`) — a decomposed TTR-SQL
     * reference by its table operand ([TtrpGraph.refOperands]: `s.item` after a join is spelled `left.item` but reads
     * `s`), so a later `s.item IS NULL` keeps the LEFT join's right key it reads (review-119 B1).
     */
    private var qualifiedRefs: Map<String, Set<Pair<String, String>>> = emptyMap()

    private fun qualifiedRefsOf(members: List<Node>): Map<String, Set<Pair<String, String>>> =
        members.associate { n ->
            n.id to
                NodeExpressions
                    .of(n)
                    .flatMap { NodeExpressions.walk(it) }
                    .filterIsInstance<ColumnRef>()
                    .mapNotNull { r ->
                        val q =
                            graph.refOperands[r.location]
                                ?: r.port?.takeIf { it != PortNames.LEFT && it != PortNames.RIGHT }
                        q?.let { it to r.column }
                    }.toSet()
        }

    /** [n] as an [EmitNode]: its qualified references resolved against its input rows, a join's kept keys. */
    private fun emitNode(
        n: Node,
        container: Container,
        cteNames: Map<String, String>,
        outCols: Map<String, List<EmitColumn>>,
    ): EmitNode {
        val ins = inputCols(n, container, outCols)
        return EmitNode(
            cteNames.getValue(n.id),
            qualify(n, ins),
            inputsOf(n, container, cteNames, outCols),
            outCols.getValue(n.id),
            if (n is Join && ins.size == 2) joinKeep(n, ins[1]) else emptySet(),
        )
    }

    /**
     * [n] with every qualified column reference (`out_.warehouse`, `customer.center`) resolved to the column of its
     * input row the qualifier names — the latest such column (a second join of `latest` is the one read after it);
     * in a join's condition, on the side that carries it. A reference no qualifier matches is left as written.
     */
    private fun qualify(
        n: Node,
        ins: List<List<EmitColumn>>,
    ): Node {
        val joined = n is Join && ins.size == 2
        val flat = ins.flatten()
        // A projected plain column keeps its authored name when the reference resolves to a renamed one.
        if (n is Project) {
            val resolved = n.columns.map { c -> (c as? ColumnRef)?.let { resolveRef(it, flat) } ?: c }
            val aliases = n.columns.indices.map { i -> n.aliases.getOrNull(i) ?: (n.columns[i] as? ColumnRef)?.column }
            return NodeExpressions.map(n.copy(columns = resolved, aliases = aliases)) { e ->
                preserveIntegerRounding(coerceKeys(qualifyRefs(e, flat), flat, flat), flat)
            }
        }
        return NodeExpressions.map(n) { e ->
            if (!joined) return@map coerceKeys(qualifyRefs(e, flat), flat, flat)
            val resolved =
                NodeExpressions.rewrite(e) { x ->
                    val r = x as? ColumnRef ?: return@rewrite null
                    val q = r.port ?: return@rewrite null
                    // In a join's condition `left.`/`right.` are its own sides — the plan builder places them.
                    if (q == PortNames.LEFT || q == PortNames.RIGHT) return@rewrite null
                    find(q, r.column, ins[0])?.let { ColumnRef(PortNames.LEFT, it.name, r.location, r.type) }
                        ?: find(q, r.column, ins[1])?.let { ColumnRef(PortNames.RIGHT, it.name, r.location, r.type) }
                }
            coerceKeys(resolved, ins[0], ins[1])
        }
    }

    /** PostgreSQL promotes integer CEIL/ROUND to float8; retain the declared integer row type. */
    private fun preserveIntegerRounding(
        e: Expression,
        cols: List<EmitColumn>,
    ): Expression =
        NodeExpressions.rewrite(e) { x ->
            val call = x as? FunctionCall ?: return@rewrite null
            if (call.function.name !in setOf("ceil", "round")) return@rewrite null
            val type = call.args.firstOrNull()?.let { computedType(it, cols.associateBy { it.name }) }
            if (type != null && isNumber(type)) {
                val target =
                    when (type) {
                        "int", "integer" -> TtrpType.Integer
                        else -> TtrpType.Named(type)
                    }
                Cast(call, target, call.location)
            } else {
                null
            }
        }

    /** The latest column of [cols] the qualifier [q] names under the name [c]. */
    private fun find(
        q: String,
        c: String,
        cols: List<EmitColumn>,
    ) = cols.lastOrNull { it.base == c && q in it.qualifiers }

    /**
     * [r] resolved in [cols] when it is qualified and a column answers to it (`left.`/`right.` after a join too) — a
     * decomposed TTR-SQL reference by the operand its table qualifier named first ([TtrpGraph.refOperands]: `l.state`
     * and `r.state` both read `left.state`).
     */
    private fun resolveRef(
        r: ColumnRef,
        cols: List<EmitColumn>,
    ): ColumnRef? {
        val q = r.port ?: return null
        val c = graph.refOperands[r.location]?.let { find(it, r.column, cols) } ?: find(q, r.column, cols)
        return c?.let { ColumnRef(null, it.name, r.location, r.type) }
    }

    /** [e]'s qualified references resolved in [cols] (the latest column the qualifier names). */
    private fun qualifyRefs(
        e: org.tatrman.ttrp.expr.Expression,
        cols: List<EmitColumn>,
    ): org.tatrman.ttrp.expr.Expression =
        NodeExpressions.rewrite(e) { x ->
            (x as? ColumnRef)?.let {
                resolveRef(it, cols)
            }
        }

    /**
     * With [textKeys] (a recipe's statements): an equality of a text column with an integer column (`ob.key =
     * l.line_id`: the automation stdlib's `key` is text, an entity's key a number) compares the integer as text —
     * `CAST(l.line_id AS VARCHAR) = ob.key` — as its author would write it; the other way would cast every key of
     * every entity to a number (AU-core-P2). A decimal or a float is never cast: its text differs per engine.
     */
    private fun coerceKeys(
        e: org.tatrman.ttrp.expr.Expression,
        left: List<EmitColumn>,
        right: List<EmitColumn>,
    ): org.tatrman.ttrp.expr.Expression =
        if (!textKeys) {
            e
        } else {
            coerceTextKeys(e, left, right)
        }

    private fun coerceTextKeys(
        e: org.tatrman.ttrp.expr.Expression,
        left: List<EmitColumn>,
        right: List<EmitColumn>,
    ): org.tatrman.ttrp.expr.Expression =
        NodeExpressions.rewrite(e) { x ->
            val call = x as? FunctionCall ?: return@rewrite null
            if (call.function.name != "eq" && call.function.name != "neq") return@rewrite null
            val (a, b) = call.args.takeIf { it.size == 2 } ?: return@rewrite null

            fun typeOf(c: org.tatrman.ttrp.expr.Expression): String? {
                val r = c as? ColumnRef ?: return null
                val side = if (r.port == PortNames.RIGHT) right else left
                return (side.lastOrNull { it.name == r.column } ?: (left + right).lastOrNull { it.name == r.column })
                    ?.type
            }
            val ta = typeOf(a) ?: return@rewrite null
            val tb = typeOf(b) ?: return@rewrite null

            fun text(c: org.tatrman.ttrp.expr.Expression) = Cast(c, org.tatrman.ttrp.expr.TtrpType.Str, c.location)
            when {
                isText(ta) && isNumber(tb) -> call.copy(args = listOf(a, text(b)))
                isNumber(ta) && isText(tb) -> call.copy(args = listOf(text(a), b))
                else -> null
            }
        }

    private fun isText(t: String) = t.substringBefore('(').lowercase() in setOf("text", "varchar", "char", "string")

    private fun isNumber(t: String) =
        t.substringBefore('(').lowercase() in setOf("int", "integer", "bigint", "smallint")

    /**
     * The right equi-key columns of [join] a qualified reference reads after it — in any other node; the join's own
     * condition reads its keys before the join — the join keeps them (by their names). After a LEFT join the right key
     * is NULL where the left one is not, so `s.item IS NULL` must read the right one.
     */
    private fun joinKeep(
        join: Join,
        right: List<EmitColumn>,
    ): Set<String> {
        val keys = join.on?.let { JoinDedup.rightEquiKeys(it) }.orEmpty()
        val readAfter =
            qualifiedRefs
                .filterKeys { it != join.id }
                .values
                .flatten()
                .toSet()
        return right
            .filter { c -> c.name in keys && c.qualifiers.any { (it to c.base) in readAfter } }
            .map { it.name }
            .toSet()
    }

    /** The variable a node's output is bound to (`out_#2` → `out_`), when it has one. */
    private fun variableOf(n: Node): String? =
        n.label
            .substringBefore('#', "")
            .takeIf { it.isNotEmpty() && !it.startsWith("~") && '~' !in it }

    /**
     * The base relation name a guard's input edge [inFrom] reads, when it is a [Load] member (a CSV
     * temp table) or a container IN port (a staged relation) — the cheap `count(*)` source for `in`.
     * Null if the input is itself a transform (the caller counts its [planForNode] cone instead).
     */
    fun inputBaseRelation(
        container: Container,
        inFrom: org.tatrman.ttrp.graph.model.PortRef,
    ): String? =
        when {
            inFrom.nodeId == container.id -> inFrom.port // a staged IN-port relation
            else -> (graph.nodes[inFrom.nodeId] as? Load)?.source?.substringAfterLast('.')
        }

    // --- ordering ---------------------------------------------------------------------

    /** Kahn's over internal DATA edges among [transforms]; Load/IN-port inputs are external. */
    private fun topoOrder(transforms: List<Node>): List<Node> {
        val ids = transforms.map { it.id }.toSet()
        val incoming = transforms.associate { it.id to mutableSetOf<String>() }
        graph.edges.filter { it.kind == EdgeKind.DATA }.forEach { e ->
            if (e.from.nodeId in ids && e.to.nodeId in ids) incoming.getValue(e.to.nodeId).add(e.from.nodeId)
        }
        val byId = transforms.associateBy { it.id }
        val ready = ArrayDeque(transforms.filter { incoming.getValue(it.id).isEmpty() }.map { it.id })
        val remaining = incoming.mapValues { it.value.toMutableSet() }.toMutableMap()
        val out = mutableListOf<Node>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            out += byId.getValue(id)
            remaining.forEach { (n, deps) ->
                if (deps.remove(id) &&
                    deps.isEmpty() &&
                    out.none { it.id == n } &&
                    ready.none { it == n }
                ) {
                    ready.addLast(n)
                }
            }
        }
        transforms.filter { m -> out.none { it.id == m.id } }.forEach { out += it }
        return out
    }

    /** Transitive DATA-predecessors of [terminal] within [transforms], plus [terminal] itself. */
    private fun ancestorsAndSelf(
        terminal: Node,
        transforms: List<Node>,
    ): Set<String> {
        val ids = transforms.map { it.id }.toSet()
        val preds =
            transforms.associate { t ->
                t.id to
                    graph.edges
                        .filter { it.kind == EdgeKind.DATA && it.to.nodeId == t.id && it.from.nodeId in ids }
                        .map { it.from.nodeId }
            }
        val cone = linkedSetOf(terminal.id)
        val stack = ArrayDeque(listOf(terminal.id))
        while (stack.isNotEmpty()) {
            preds[stack.removeLast()].orEmpty().forEach { if (cone.add(it)) stack.addLast(it) }
        }
        return cone
    }

    // --- input resolution -------------------------------------------------------------

    private fun inputsOf(
        node: Node,
        container: Container,
        cteNames: Map<String, String>,
        outCols: Map<String, List<EmitColumn>>,
    ): List<EmitInput> {
        val portOrder = node.ports().filter { it.direction.name == "IN" }.map { it.name }
        val edges =
            graph.edges
                .filter { it.kind == EdgeKind.DATA && it.to.nodeId == node.id }
                .sortedBy { portOrder.indexOf(it.to.port).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return edges.map { e -> resolveInput(e.from.nodeId, e.from.port, container, outCols) }
    }

    private fun resolveInput(
        fromId: String,
        fromPort: String,
        container: Container,
        outCols: Map<String, List<EmitColumn>>,
    ): EmitInput =
        when {
            fromId == container.id && sqlText -> upstreamInput(container, fromPort)
            fromId == container.id -> // a container IN port → staged relation, typed from the world
                EmitInput.BaseTable(LOCAL_NS, fromPort, stagingSchema(fromPort, container))
            else -> {
                when (val src = graph.nodes[fromId]) {
                    is Load ->
                        graph.loadSources[src.id]?.takeIf { sqlText }?.let { modelInput(src.id, it) }
                            ?: loadBaseTable(src)
                    else -> EmitInput.Cte(fromId, outCols[fromId] ?: emptyList())
                }
            }
        }

    /** AG-P0: a model-object Load → its physical source, typed by the logical columns. */
    private fun modelInput(
        loadId: String,
        source: org.tatrman.ttrp.resolve.LoadSource,
    ): EmitInput.Model {
        // Qualified by the entity (`customer.center`) and by the variable the load is bound to (`out_`).
        val qualifiers =
            setOfNotNull(source.objectQname.substringAfterLast('.'), graph.nodes[loadId]?.let { variableOf(it) })
        return EmitInput.Model(loadId, source, source.columns.map { EmitColumn(it.name, it.type, qualifiers) })
    }

    /**
     * AG-P0: a container IN [port] in sql-text mode → the feeding container's OUT port, typed by that
     * container's computed output row (a decomposed producer) or, for an opaque fragment producer, by
     * the world-declared staging schema of the port's name (as the CTE path does).
     */
    private fun upstreamInput(
        container: Container,
        port: String,
    ): EmitInput {
        val feed =
            graph.edges.firstOrNull { it.kind == EdgeKind.DATA && it.to == PortRef(container.id, port) }
                ?: throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "island '${container.label}' IN port '$port' has no feeding edge",
                    location = container.location,
                )
        // A program-level model `load(…)` wired into the port (a bare program's derived in-port): the host
        // statement reads the model object itself, as a member load would.
        (graph.nodes[feed.from.nodeId] as? Load)?.let { load ->
            graph.loadSources[load.id]?.let { return modelInput(load.id, it) }
        }
        val producer =
            graph.containers[feed.from.nodeId]
                ?: throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail =
                        "island '${container.label}' IN port '$port' is fed by a non-container node " +
                            "(${feed.from.nodeId}) — sql-text islands read only other islands' OUT ports",
                    location = container.location,
                )
        val columns =
            if (verbatim(producer) != null) {
                stagingSchema(port, container)
            } else {
                SqlGraphEmitter(
                    graph,
                    world,
                    sqlText = true,
                    fragmentsAsMembers = fragmentsAsMembers,
                    textKeys = textKeys,
                ).plansByOutput(producer)[feed.from.port]
                    ?.lastOrNull()
                    ?.outputColumns
                    ?: throw TtrpEmitException(
                        EmitDiagnosticId.UNSUPPORTED_NODE,
                        detail = "island '${producer.label}' has no OUT port '${feed.from.port}'",
                        location = producer.location,
                    )
            }
        // An IN port's rows are qualified by the port's name (`orders.order_number`).
        return EmitInput.Upstream(producer.id, feed.from.port, columns.map { it.copy(qualifiers = setOf(port)) })
    }

    /**
     * A member [Load] → the session-local relation it materializes (a CSV temp table named after the
     * storage-object leaf, populated by the bundle's per-island preamble; S3.5 T3.5.4), typed from
     * its world storage schema.
     */
    private fun loadBaseTable(load: Load): EmitInput.BaseTable =
        EmitInput.BaseTable(LOCAL_NS, load.source.substringAfterLast('.'), schemaCols(resolveSchemaName(load)))

    // --- schema propagation -----------------------------------------------------------

    private fun outputColumns(
        node: Node,
        container: Container,
        outCols: Map<String, List<EmitColumn>>,
    ): List<EmitColumn> {
        val ins = inputCols(node, container, outCols)
        val cols =
            when (node) {
                is Filter, is Sort, is Limit, is Distinct -> ins.firstOrNull() ?: emptyList()
                is Join -> joinColumns(node, ins)
                is Aggregate -> aggregateColumns(node, ins.firstOrNull() ?: emptyList())
                is Project -> projectColumns(node, ins.firstOrNull() ?: emptyList())
                else ->
                    throw TtrpEmitException(
                        EmitDiagnosticId.UNSUPPORTED_NODE,
                        detail = "node ${node::class.simpleName} '${node.label}' has no SQL schema propagation",
                        location = node.location,
                    )
            }
        // The variable the node is bound to qualifies its whole row (`stock = … -> latest(…)`: `stock.x`).
        val variable = variableOf(node) ?: return cols
        return cols.map { it.copy(qualifiers = it.qualifiers + variable) }
    }

    /** Resolved input column lists for [node] (in port order) — from staged/Load/CTE sources. */
    private fun inputCols(
        node: Node,
        container: Container,
        outCols: Map<String, List<EmitColumn>>,
    ): List<List<EmitColumn>> =
        inputsOf(node, container, emptyMap(), outCols).map { input ->
            when (input) {
                is EmitInput.BaseTable -> input.columns
                is EmitInput.Cte -> input.columns
                is EmitInput.Model -> input.columns
                is EmitInput.Upstream -> input.columns
            }
        }

    /** [cols] tagged as the [tag] side of a join — whatever side of an earlier join they were forgotten. */
    private fun sided(
        cols: List<EmitColumn>,
        tag: String,
    ) = cols.map { it.copy(qualifiers = it.qualifiers - PortNames.LEFT - PortNames.RIGHT + tag) }

    private fun joinColumns(
        node: Join,
        ins: List<List<EmitColumn>>,
    ): List<EmitColumn> {
        if (ins.size != 2) return ins.flatten()
        val left = ins[0]
        val right = ins[1]
        // An existence test (semi/anti) keeps the LEFT row type only.
        if (node.type == org.tatrman.ttrp.graph.model.JoinType.SEMI ||
            node.type == org.tatrman.ttrp.graph.model.JoinType.ANTI
        ) {
            return sided(left, PortNames.LEFT)
        }
        val rightKeys = node.on?.let { JoinDedup.rightEquiKeys(it) }.orEmpty()
        // Each side's columns are `left.`/`right.` to the nodes after the join (a decomposed fragment's `cp.price`
        // reaches them as `left.price`) — the side of THIS join, an earlier join's side forgotten.
        val all = sided(left, PortNames.LEFT) + sided(right, PortNames.RIGHT)
        // The same survivors, names and order the plan builder projects ([PlanNodeBuilder]) — a repeated right
        // name made unique, a right key kept when a qualified reference reads it (AU-core-P2).
        return JoinDedup
            .survivors(left.map { it.name }, right.map { it.name }, rightKeys, joinKeep(node, right))
            .map { (ordinal, name) -> all[ordinal].copy(name = name) }
    }

    private fun aggregateColumns(
        node: Aggregate,
        input: List<EmitColumn>,
    ): List<EmitColumn> {
        val byName = input.associateBy { it.name }
        val keys = node.groupBy.map { EmitColumn(it, byName[it]?.type ?: "text") }
        val aggs =
            node.aggregations.map { agg ->
                val fn = (agg.value as? AggregateCall)?.function?.name
                val argType =
                    ((agg.value as? AggregateCall)?.args?.firstOrNull() as? ColumnRef)?.let {
                        byName[it.column]
                            ?.type
                    }
                EmitColumn(agg.name, aggType(fn, argType))
            }
        return keys + aggs
    }

    /** sum/avg → float; count → int; min/max → the argument's type; unknown → float. */
    private fun aggType(
        fn: String?,
        argType: String?,
    ): String =
        when (fn) {
            "count" -> "int"
            "min", "max" -> argType ?: "float"
            else -> "float" // sum, avg
        }

    private fun projectColumns(
        node: Project,
        input: List<EmitColumn>,
    ): List<EmitColumn> {
        val byName = input.associateBy { it.name }
        val computed =
            node.columns.mapIndexed { i, c ->
                val name = node.aliasOf(i) ?: (c as? ColumnRef)?.column ?: "_expr$i"
                // A plain column carried under its own name keeps what qualifies it.
                val carried = (c as? ColumnRef)?.takeIf { it.column == name }?.let { byName[it.column] }
                EmitColumn(name, computedType(c, byName), carried?.qualifiers.orEmpty(), carried?.base ?: name)
            }
        if (!node.passthrough) return computed
        // calc add-semantics: input columns first, minus any overridden by a computed alias and
        // minus internal `_ttrp_v*` validity flags (never part of an output schema, contracts §1).
        val overridden = computed.map { it.name }.toSet()
        return input.filterNot { it.name in overridden || RejectGuardSql.isValidityFlag(it.name) } + computed
    }

    /** Best-effort SQL type of a computed projection column (cast target / ref passthrough / bool predicate). */
    private fun computedType(
        e: Expression,
        byName: Map<String, EmitColumn>,
    ): String =
        when (e) {
            is ColumnRef -> byName[e.column]?.type ?: "text"
            is Cast -> e.target.canonical
            is IsNull -> "bool"
            is FunctionCall ->
                when (e.function.name) {
                    "is_castable", "is_nonzero", "is_parseable_dt",
                    "eq", "ne", "lt", "le", "gt", "ge", "and", "or", "not",
                    -> "bool"
                    // AG-P0: value-preserving functions keep their first argument's type; arithmetic is
                    // numeric (a sql-text output's declared row type feeds the host's typed displays).
                    "coalesce", "abs", "round", "ceil" ->
                        e.args.firstOrNull()?.let { computedType(it, byName) }
                            ?: "text"
                    "add", "sub", "mul", "div", "neg" -> numericOf(e.args.map { computedType(it, byName) })
                    else -> "text"
                }
            is org.tatrman.ttrp.expr.Literal ->
                when (val v = e.value) {
                    is org.tatrman.ttrp.expr.LiteralValue.Num ->
                        if (v.raw.any {
                                it == '.' || it == 'e' || it == 'E'
                            }
                        ) {
                            "decimal"
                        } else {
                            "int"
                        }
                    is org.tatrman.ttrp.expr.LiteralValue.Bool -> "bool"
                    else -> "text"
                }
            // A NULL branch says nothing of the type (AU-core-P2: `CASE WHEN a IS NULL THEN NULL ELSE … END`).
            is org.tatrman.ttrp.expr.CaseWhen ->
                (e.branches.map { it.second } + listOfNotNull(e.elseExpr))
                    .firstOrNull {
                        (it as? org.tatrman.ttrp.expr.Literal)?.value !=
                            org.tatrman.ttrp.expr.LiteralValue.Null
                    }?.let { computedType(it, byName) }
                    ?: "text"
            else -> "text"
        }

    /** The numeric result type of an arithmetic over [types]: int only when every operand is int. */
    private fun numericOf(types: List<String>): String =
        when {
            types.isNotEmpty() && types.all { it == "int" || it == "integer" || it == "bigint" } -> "int"
            types.any { it == "float" || it == "double" } -> "float"
            else -> "decimal"
        }

    // --- world schema resolution (mirrors PolarsGraphEmitter) --------------------------

    /** Columns of the world-declared staging-boundary schema whose name matches the [port]. */
    private fun stagingSchema(
        port: String,
        container: Container,
    ): List<EmitColumn> =
        schemaCols(port).ifEmpty {
            throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail =
                    "PG island '${container.label}' reads IN port '$port' but no staging-boundary " +
                        "schema named '$port' is declared in the world (S3.5 T3.5.1)",
                location = container.location,
            )
        }

    private fun resolveSchemaName(load: Load): String? = load.schemaRef

    /** World-declared schema (by name, last-segment-tolerant) → emit columns; empty if absent. */
    private fun schemaCols(schemaName: String?): List<EmitColumn> {
        if (schemaName == null) return emptyList()

        fun matches(name: String) = name == schemaName || name.substringAfterLast('.') == schemaName
        val storage =
            world.world.storages.firstOrNull { s -> s.schemas.any { matches(it.qname.name) } } ?: return emptyList()
        val schema = storage.schemas.first { matches(it.qname.name) }
        return schema.fields.entries.map { EmitColumn(it.key, it.value) }
    }

    private companion object {
        /**
         * Namespace for session-local relations (staged IN-port relations + CSV temp tables). Reuses
         * [CtePlanner.CTE_NAMESPACE] so they resolve in the translator model *and* render bare
         * (`FROM sales_2026`, not `FROM "db"."sales_2026"`) — the correct SQL for a `pg_temp` table.
         */
        val LOCAL_NS = CtePlanner.CTE_NAMESPACE
    }
}
