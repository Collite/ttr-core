// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.SwitchMode
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.model.Aggregation
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Port
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.RecipeGraph
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.rewrite.GraphOps

/**
 * The decision's `switch` lowered to the node set (AU-core-P2 · S2.1 T2–T3; D-31, D-102, D-77, D-108) — run after the
 * recipe checker passed, before normalize, so every engine and emitter sees only filters, calcs and joins:
 *
 *  - **`overlapping`** — one filter per route on its condition (a row may take several routes); a named `else` takes
 *    the rows no condition took (`not coalesce(c1 or c2 …, false)`, a NULL condition counting as not taken);
 *  - **`exclusive`** — one calc writes `route = CASE WHEN c1 THEN 'r1' WHEN c2 THEN 'r2' … ELSE '<else>' END` (the
 *    first route that matches wins; the `ELSE` is NULL for `else: none`), then one filter per route on `route = '<r>'`;
 *  - every route's rows = the switch's input row + `route` + `verdict_reason` (the `because:` text, `{fill}`s
 *    concatenated in — a column of the row, else a parameter — each cast to text and a NULL fill read as empty);
 *  - **`detail: c`** on a route → the context's rows the decision reads on `in c`, semi-joined to the route's rows on
 *    the key, + `route` and `context` — the decision's OUT port `<route>.<c>` (its island `verdict.<route>.<c>`).
 *
 * The route names stay the decision container's OUT ports, now mapped onto the route chains; an `else: none` route
 * has no port and no rows (D-80). The switch node is gone afterwards.
 */
object SwitchLowering {
    /** The verdict columns every route row carries (contracts §5.3). */
    const val ROUTE = "route"
    const val VERDICT_REASON = "verdict_reason"

    /** The column a detail row names its context by. */
    const val CONTEXT = "context"

    /** The OUT port of the detail rows of [route] for the context [context]. */
    fun detailPort(
        route: String,
        context: String,
    ): String = "$route.$context"

    data class Result(
        val graph: TtrpGraph,
        val diagnostics: List<TtrpDiagnostic>,
    )

    fun lower(
        graph: TtrpGraph,
        rg: RecipeGraph,
        model: RecipeModel,
    ): Result {
        val swId = rg.switch ?: return Result(graph, emptyList())
        val decisionId = rg.decision ?: return Result(graph, emptyList())
        val sw = graph.nodes[swId] as? Switch ?: return Result(graph, emptyList())
        val stmt =
            rg.recipe.decisions
                .firstOrNull()
                ?.switch ?: return Result(graph, emptyList())
        val src =
            graph.edges.firstOrNull { it.kind == EdgeKind.DATA && it.to == PortRef(swId, PortNames.IN) }?.from
                ?: return Result(graph, emptyList())
        val row = model.routeRow.orEmpty()
        val loc = sw.location
        val diags = mutableListOf<TtrpDiagnostic>()
        for (reserved in listOf(ROUTE, VERDICT_REASON).filter { it in row }) {
            diags += error("the decision's rows carry a `$reserved` column — the name is the verdict's", loc)
        }
        if (diags.isNotEmpty()) return Result(graph, diags)

        var g = graph
        val portMap = LinkedHashMap<String, PortRef>()
        val exclusive = stmt.mode == SwitchMode.EXCLUSIVE

        // exclusive: the CASE ladder writes `route` once; each route filters on it.
        val feed =
            if (exclusive) {
                val ladder =
                    CaseWhen(
                        stmt.routes.map { it.condition to str(it.name, loc) },
                        stmt.elseRoute?.name?.let { str(it, loc) } ?: Literal(LiteralValue.Null, loc),
                        loc,
                    )
                val id = "$swId~$ROUTE"
                g =
                    GraphOps.addNode(
                        g,
                        Calc(id, "${sw.label}~$ROUTE", loc, listOf(Aggregation(ROUTE, ladder))),
                        decisionId,
                    )
                g = GraphOps.addEdges(g, listOf(Edge(src, PortRef(id, PortNames.IN), EdgeKind.DATA, loc)))
                PortRef(id, PortNames.OUT)
            } else {
                src
            }

        val elseRoute = stmt.elseRoute
        val routes =
            stmt.routes.map { Triple(it.name, it.because, it.condition as Expression?) } +
                listOfNotNull(elseRoute?.name?.let { Triple(it, elseRoute.because, null) })
        for ((name, because, condition) in routes) {
            val predicate =
                when {
                    exclusive -> eq(ColumnRef(null, ROUTE, loc), str(name, loc), loc)
                    condition != null -> condition
                    else -> notTaken(stmt.routes.map { it.condition }, loc)
                }
            val filterId = "$swId~$name~filter"
            val calcId = "$swId~$name"
            val verdict =
                listOfNotNull(
                    Aggregation(ROUTE, str(name, loc)).takeUnless { exclusive },
                    Aggregation(VERDICT_REASON, reason(because, row, loc)),
                )
            g = GraphOps.addNode(g, Filter(filterId, "${sw.label}~$name~filter", loc, predicate), decisionId)
            g = GraphOps.addNode(g, Calc(calcId, "${sw.label}~$name", loc, verdict), decisionId)
            g =
                GraphOps.addEdges(
                    g,
                    listOf(
                        Edge(feed, PortRef(filterId, PortNames.IN), EdgeKind.DATA, loc),
                        Edge(PortRef(filterId, PortNames.OUT), PortRef(calcId, PortNames.IN), EdgeKind.DATA, loc),
                    ),
                )
            portMap[name] = PortRef(calcId, PortNames.OUT)
        }

        // detail: the context's rows for the route's keys (D-77).
        val detailPorts = mutableListOf<Port>()
        val decision = g.containers.getValue(decisionId)
        val details =
            stmt.routes.map { it.name to it.detail } +
                listOfNotNull(elseRoute?.name?.let { it to elseRoute.detail })
        for ((name, detail) in details) {
            for (context in detail.map { it.text }) {
                if (decision.declaredPorts.none { it.direction == PortDirection.IN && it.name == context }) {
                    diags +=
                        error(
                            "route `$name`'s `detail: $context` — the decision reads no `in $context`, so it has no " +
                                "rows of it to attach",
                            loc,
                        )
                    continue
                }
                val joinId = "$swId~$name~$context~join"
                val calcId = "$swId~$name~$context"
                val on =
                    model.keyColumns
                        .map { k -> eq(ColumnRef(PortNames.LEFT, k, loc), ColumnRef(PortNames.RIGHT, k, loc), loc) }
                        .reduce { a, b -> FunctionCall(CatalogId.AND, listOf(a, b), loc) }
                g = GraphOps.addNode(g, Join(joinId, "${sw.label}~$name~$context", loc, JoinType.SEMI, on), decisionId)
                g =
                    GraphOps.addNode(
                        g,
                        Calc(
                            calcId,
                            "${sw.label}~$name~$context",
                            loc,
                            listOf(Aggregation(ROUTE, str(name, loc)), Aggregation(CONTEXT, str(context, loc))),
                        ),
                        decisionId,
                    )
                g =
                    GraphOps.addEdges(
                        g,
                        listOf(
                            Edge(PortRef(decisionId, context), PortRef(joinId, PortNames.LEFT), EdgeKind.DATA, loc),
                            Edge(portMap.getValue(name), PortRef(joinId, PortNames.RIGHT), EdgeKind.DATA, loc),
                            Edge(PortRef(joinId, PortNames.OUT), PortRef(calcId, PortNames.IN), EdgeKind.DATA, loc),
                        ),
                    )
                val port = detailPort(name, context)
                portMap[port] = PortRef(calcId, PortNames.OUT)
                detailPorts += Port(port, PortKind.DATA, PortDirection.OUT)
            }
        }

        // The decision's ports: the routes (minus any the switch maps to nowhere) + the detail ports, onto the chains.
        g = GraphOps.removeNode(g, swId)
        val updated =
            g.containers.getValue(decisionId).let { c ->
                c.copy(
                    declaredPorts = c.declaredPorts + detailPorts,
                    portMapping = c.portMapping.filterValues { it.nodeId != swId } + portMap,
                )
            }
        g =
            g.copy(
                nodes = LinkedHashMap(g.nodes).apply { put(decisionId, updated) },
                containers = LinkedHashMap(g.containers).apply { put(decisionId, updated) },
            )
        return Result(g, diags)
    }

    /**
     * The `because:` text as an expression: literal pieces and `{fill}`s concatenated, a fill read from the route's
     * row when it has the column, else the parameter of that name; each fill cast to text, NULL read as empty. No
     * `because:` gives a NULL `verdict_reason`.
     */
    fun reason(
        because: String?,
        row: Map<String, TtrpType?>,
        loc: SourceLocation,
    ): Expression {
        if (because == null) return Cast(Literal(LiteralValue.Null, loc), TtrpType.Str, loc)
        val pieces = mutableListOf<Expression>()
        var at = 0
        for (m in RecipeChecker.FILL.findAll(because)) {
            if (m.range.first > at) pieces += str(because.substring(at, m.range.first), loc)
            val fill = m.groupValues[1]
            val value: Expression = if (fill in row) ColumnRef(null, fill, loc) else ParamRef(fill, loc)
            val text = if (fill in row && row[fill] == TtrpType.Str) value else Cast(value, TtrpType.Str, loc)
            pieces += FunctionCall(COALESCE, listOf(text, str("", loc)), loc)
            at = m.range.last + 1
        }
        if (at < because.length || pieces.isEmpty()) pieces += str(because.substring(at), loc)
        return pieces.reduce { a, b -> FunctionCall(CONCAT, listOf(a, b), loc) }
    }

    /** `concat` (`||`) — the catalogue's string concatenation (AU-core-P2: a `because:` text). */
    val CONCAT = CatalogId("op.concat")
    private val COALESCE = CatalogId("fn.coalesce")

    /** The else of an `overlapping` switch: the rows no condition took — a NULL condition is not taken. */
    private fun notTaken(
        conditions: List<Expression>,
        loc: SourceLocation,
    ): Expression {
        val any = conditions.reduce { a, b -> FunctionCall(CatalogId.OR, listOf(a, b), loc) }
        val taken = FunctionCall(COALESCE, listOf(any, Literal(LiteralValue.Bool(false), loc)), loc)
        return FunctionCall(CatalogId.NOT, listOf(taken), loc)
    }

    private fun str(
        value: String,
        loc: SourceLocation,
    ) = Literal(LiteralValue.Str(value), loc)

    private fun eq(
        a: Expression,
        b: Expression,
        loc: SourceLocation,
    ) = FunctionCall(CatalogId.EQ, listOf(a, b), loc)

    private fun error(
        message: String,
        loc: SourceLocation,
    ) = TtrpDiagnostic(TtrpDiagnosticId.REC_000, Severity.ERROR, message, loc)
}
