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
) {
    /** OUT port name → the ordered [EmitNode] plan (terminal last) producing that port's rows. */
    fun plansByOutput(container: Container): LinkedHashMap<String, List<EmitNode>> {
        val members = container.memberIds.mapNotNull { graph.nodes[it] }.map { logical(it) }
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
                    .map { n ->
                        EmitNode(
                            cteNames.getValue(n.id),
                            n,
                            inputsOf(n, container, cteNames, outCols),
                            outCols.getValue(n.id),
                        )
                    }
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
        val transforms = members.filter { it !is Load }
        val terminal = transforms.firstOrNull { it.id == nodeId } ?: return null
        val ordered = topoOrder(transforms)
        val cteNames = SsaNames.assign(ordered)
        val outCols = LinkedHashMap<String, List<EmitColumn>>()
        ordered.forEach { n -> outCols[n.id] = outputColumns(n, container, outCols) }
        val cone = ancestorsAndSelf(terminal, transforms)
        return ordered.filter { it.id in cone }.map { n ->
            EmitNode(
                cteNames.getValue(n.id),
                n,
                inputsOf(n, container, cteNames, outCols),
                outCols.getValue(n.id),
            )
        }
    }

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
    ): EmitInput.Model = EmitInput.Model(loadId, source, source.columns.map { EmitColumn(it.name, it.type) })

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
            if (producer.verbatimFragment != null) {
                stagingSchema(port, container)
            } else {
                SqlGraphEmitter(graph, world, sqlText = true)
                    .plansByOutput(producer)[feed.from.port]
                    ?.lastOrNull()
                    ?.outputColumns
                    ?: throw TtrpEmitException(
                        EmitDiagnosticId.UNSUPPORTED_NODE,
                        detail = "island '${producer.label}' has no OUT port '${feed.from.port}'",
                        location = producer.location,
                    )
            }
        return EmitInput.Upstream(producer.id, feed.from.port, columns)
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
        return when (node) {
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
            return left
        }
        val rightKeys = node.on?.let { JoinDedup.rightEquiKeys(it) }
        if (rightKeys.isNullOrEmpty()) return left + right
        val byName = (left + right).associateBy { it.name }
        return JoinDedup
            .survivors(left.map { it.name }, right.map { it.name }, rightKeys)
            .map { (_, name) -> byName.getValue(name) }
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
                val name = node.aliasOf(i) ?: "_expr$i"
                EmitColumn(name, computedType(c, byName))
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
                    "coalesce", "abs", "round" -> e.args.firstOrNull()?.let { computedType(it, byName) } ?: "text"
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
            is org.tatrman.ttrp.expr.CaseWhen ->
                e.branches.firstOrNull()?.let { computedType(it.second, byName) }
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
