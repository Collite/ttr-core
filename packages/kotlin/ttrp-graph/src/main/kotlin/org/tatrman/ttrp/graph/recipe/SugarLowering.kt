// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Aggregation
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Edge
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.RecipeGraph
import org.tatrman.ttrp.graph.model.TimeSugar
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.rewrite.GraphOps
import org.tatrman.ttrp.resolve.LoadSource
import org.tatrman.ttrp.resolve.LoadSourceResolver
import org.tatrman.ttrp.resolve.ModelIndex

/**
 * The recipe's time sugar lowered to the node set (AU-core-P2 · S2.2; D-33 sugar is no built-in, D-82, 06 A-6) —
 * run after the checker passed and the switch was lowered, before normalize. Everything it writes is what the door
 * runs: comparisons, joins, aggregates — the door has **no date arithmetic** (no `INTERVAL`, no `date ± n`, no
 * `ts − ts`), so neither does any statement:
 *
 *  - **`:as_of − days(365)`**, `:as_of − :return_window` — a bound value minus (or plus) a duration — becomes a
 *    *derived* run-bound value, `:as_of_minus_365_days`, which the runner computes and binds ([DerivedParam]);
 *    date arithmetic on a column is refused (TTRP-TYP-001);
 *  - **`latest(by: d, per: a, b)`** → `aggregate { group_by: a, b  m = max(d) }` and a semi join of the rows back on
 *    `a, b, d = m`: the rows at the latest `d` of their group (an sk orders like its date);
 *  - **`within(c, last: n)`** → `filter(c >= :as_of_minus_<n> and c <= :as_of)`; `from:`/`to:` bound it so. When `c`
 *    is an sk of the calendar (D-82) the rows are semi-joined to the calendar's days in that range on the sk — the
 *    row is unchanged;
 *  - **`working_days(between: a, and: b)`** and **`age(since: t)`** (`b` = `:as_of`) in a `calc` → a count of the
 *    calendar's days in `(a, b]` — its working days only for `working_days` — looked up per distinct `(a, b)` and
 *    left-joined back (NULL when `a` or `b` is): exact whatever the rows' multiplicity, and no date subtraction.
 *
 * The calendar is the model's `semantics { role: event_date }` entity; `working_days` needs its `working_day`
 * attribute. The helper columns are `_ttrp_`-named (reserved, RJ-103); the recipe's statements leave them out.
 */
class SugarLowering(
    private val rg: RecipeGraph,
    private val model: RecipeModel,
    private val index: ModelIndex?,
) {
    data class Result(
        val graph: TtrpGraph,
        val derived: List<DerivedParam>,
        val diagnostics: List<TtrpDiagnostic>,
    )

    private val diags = mutableListOf<TtrpDiagnostic>()
    private val derived = LinkedHashMap<String, DerivedParam>()
    private val parameterTypes = model.parameterDecls.associate { it.name to it.type }
    private lateinit var g: TtrpGraph

    fun lower(graph: TtrpGraph): Result {
        g = graph
        val members =
            (rg.roles.values + listOfNotNull(rg.decision))
                .flatMap { g.containers[it]?.memberIds.orEmpty() }
                .toSet()
        // 1. Date arithmetic → derived run-bound values, in every expression of the recipe's nodes.
        for (id in members) {
            g.nodes[id]?.let { n ->
                g = GraphOps.swapNode(g, id, NodeExpressions.map(n) { derive(it) })
            }
        }
        g = g.copy(logicalJoins = g.logicalJoins.mapValues { (id, e) -> if (id in members) derive(e) else e })
        // A bound date minus a date column in a calc (`:as_of - placed_date`) is the days between — the calendar
        // count `age(since: placed_date, and: :as_of)` (the door subtracts no dates).
        for (id in members) {
            val c = g.nodes[id] as? Calc ?: continue
            g =
                GraphOps.swapNode(
                    g,
                    id,
                    c.copy(assignments = c.assignments.map { it.copy(value = daysBetween(it.value)) }),
                )
        }
        // 2. The sugar ops.
        for (id in members) {
            when (val n = g.nodes[id]) {
                is TimeSugar -> if (n.kind == "latest") latest(n) else within(n)
                is Calc -> calendarCounts(n)
                else -> Unit
            }
        }
        // 3. Anything left over cannot run at the door.
        for (id in members) {
            val n = g.nodes[id] ?: continue
            NodeExpressions.of(n).flatMap { NodeExpressions.walk(it) }.forEach { e ->
                when {
                    e is DurationExpr || (e is FunctionCall && DurationUnit.of(e.function.name) != null) ->
                        diags +=
                            error(
                                TtrpDiagnosticId.TYP_001,
                                "date arithmetic is lowered on a bound value only (`:as_of - days(30)`) — the door " +
                                    "computes none; derive the date in a parameter",
                                e.location,
                            )
                    e is FunctionCall && e.function.name in CALENDAR_FUNCTIONS ->
                        diags +=
                            error(
                                TtrpDiagnosticId.FN_002,
                                "`${e.function.name}(…)` is lowered in a `calc` only — compute it there first",
                                e.location,
                            )
                }
            }
        }
        return Result(g, derived.values.toList(), diags.toList())
    }

    // ---- date arithmetic ------------------------------------------------------------------------------------

    /** `<bound value> ± <duration>` → the derived bound value's name. */
    private fun derive(e: Expression): Expression =
        NodeExpressions.rewrite(e) { x ->
            val call = x as? FunctionCall ?: return@rewrite null
            val op =
                when (call.function) {
                    CatalogId.SUB -> "minus"
                    CatalogId.ADD -> "plus"
                    else -> return@rewrite null
                }
            val base = call.args.getOrNull(0) as? ParamRef ?: return@rewrite null
            val (amount, unit) = duration(call.args.getOrNull(1) ?: return@rewrite null) ?: return@rewrite null
            val name = "${base.name}_${op}_${amount}_$unit"
            derived.getOrPut(name) { DerivedParam(name, base.name, op, amount, unit) }
            ParamRef(name, call.location)
        }

    /** `:p - col`, `p` a bound date (`:as_of`, a derived one, a `date` parameter) → `age(since: col, and: :p)`. */
    private fun daysBetween(e: Expression): Expression =
        NodeExpressions.rewrite(e) { x ->
            val call = x as? FunctionCall ?: return@rewrite null
            if (call.function != CatalogId.SUB) return@rewrite null
            val p = call.args.getOrNull(0) as? ParamRef ?: return@rewrite null
            val col = call.args.getOrNull(1) as? ColumnRef ?: return@rewrite null
            val date =
                p.name == RecipeChecker.AS_OF ||
                    p.name in derived ||
                    parameterTypes[p.name] in setOf("date", "timestamp")
            if (!date) return@rewrite null
            FunctionCall(AGE, listOf(col, p), call.location, argNames = listOf("since", "and"))
        }

    /** A duration operand: `days(30)` · `days(:p)` · `:p` of a `days`/`int` parameter — (amount, unit). */
    private fun duration(e: Expression): Pair<String, String>? =
        when (e) {
            is DurationLiteral ->
                if (e.unit ==
                    DurationUnit.WORKING_DAYS
                ) {
                    null
                } else {
                    e.amount.toString() to e.unit.spelling
                }
            is DurationParam -> if (e.unit == DurationUnit.WORKING_DAYS) null else e.param to e.unit.spelling
            is ParamRef ->
                when (parameterTypes[e.name]) {
                    "days", "int", "integer" -> e.name to DurationUnit.DAYS.spelling
                    else -> null
                }
            else -> null
        }

    // ---- latest ---------------------------------------------------------------------------------------------

    private fun latest(n: TimeSugar) {
        val by = n.columnsOf("by").singleOrNull() ?: return badArgs(n)
        val per = n.columnsOf("per")
        val loc = n.location
        val src = feed(n.id) ?: return
        val container = GraphOps.containerIdOf(g, n.id)
        val m = "_ttrp_latest_${token(n.id)}"
        val aggId = "${n.id}~max"
        add(
            Aggregate(
                aggId,
                "${n.label}~max",
                loc,
                groupBy = per,
                aggregations = listOf(Aggregation(m, AggregateCall(MAX, listOf(ColumnRef(null, by, loc)), false, loc))),
            ),
            container,
        )
        val on =
            and(
                (per.map { it to it } + (by to m)).map { (l, r) ->
                    eq(side(PortNames.LEFT, l, loc), side(PortNames.RIGHT, r, loc), loc)
                },
                loc,
            )
        g = GraphOps.swapNode(g, n.id, Join(n.id, n.label, loc, JoinType.SEMI, on, provenance = n.provenance))
        retargetIn(n.id, PortNames.IN, PortNames.LEFT)
        edges(src to PortRef(aggId, PortNames.IN), PortRef(aggId, PortNames.OUT) to PortRef(n.id, PortNames.RIGHT))
    }

    // ---- within ---------------------------------------------------------------------------------------------

    private fun within(n: TimeSugar) {
        val column = n.args.firstOrNull { it.first == null }?.second as? ColumnRef ?: return badArgs(n)
        val loc = n.location
        val named = n.args.filter { it.first != null }.associate { it.first!! to it.second }
        val (lo, hi) =
            when {
                "last" in named -> {
                    val (amount, unit) = duration(named.getValue("last")) ?: return badArgs(n)
                    val name = "${RecipeChecker.AS_OF}_minus_${amount}_$unit"
                    derived.getOrPut(name) { DerivedParam(name, RecipeChecker.AS_OF, "minus", amount, unit) }
                    ParamRef(name, loc) to ParamRef(RecipeChecker.AS_OF, loc)
                }
                "from" in named && "to" in named -> derive(named.getValue("from")) to derive(named.getValue("to"))
                else -> return badArgs(n)
            }
        if (n.id !in model.calendarSkWithins) {
            val range = and(listOf(call(CatalogId.GTE, column, lo, loc), call(CatalogId.LTE, column, hi, loc)), loc)
            g = GraphOps.swapNode(g, n.id, Filter(n.id, n.label, loc, range, provenance = n.provenance))
            return
        }
        // An sk of the calendar: the rows whose day is in range, by the sk (the row itself unchanged).
        val cal = calendar(n) ?: return
        val container = GraphOps.containerIdOf(g, n.id)
        val t = token(n.id)
        val days = calendarDays(n, cal, t, working = false, withSk = true) ?: return
        val inRange = "${n.id}~days"
        val d = ColumnRef(null, "_ttrp_d_$t", loc)
        add(
            Filter(
                inRange,
                "${n.label}~days",
                loc,
                and(listOf(call(CatalogId.GTE, d, lo, loc), call(CatalogId.LTE, d, hi, loc)), loc),
            ),
            container,
        )
        edges(days to PortRef(inRange, PortNames.IN))
        val on = eq(side(PortNames.LEFT, column.column, loc), side(PortNames.RIGHT, "_ttrp_sk_$t", loc), loc)
        g = GraphOps.swapNode(g, n.id, Join(n.id, n.label, loc, JoinType.SEMI, on, provenance = n.provenance))
        retargetIn(n.id, PortNames.IN, PortNames.LEFT)
        edges(PortRef(inRange, PortNames.OUT) to PortRef(n.id, PortNames.RIGHT))
    }

    // ---- working_days / age ---------------------------------------------------------------------------------

    private fun calendarCounts(c: Calc) {
        val calls =
            c.assignments
                .flatMap { a -> NodeExpressions.walk(a.value).filterIsInstance<FunctionCall>() }
                .filter {
                    it.function.name in
                        CALENDAR_FUNCTIONS
                }.distinct()
        if (calls.isEmpty()) return
        val cal = calendar(c) ?: return
        val container = GraphOps.containerIdOf(g, c.id)
        var head = feed(c.id) ?: return
        val replaced = HashMap<FunctionCall, Expression>()
        for ((i, fn) in calls.withIndex()) {
            val loc = fn.location
            val args = fn.argNames.zip(fn.args).toMap()
            val working = fn.function.name == "working_days"
            val a = (if (working) args["between"] else args["since"]) ?: return badCall(fn)
            val b =
                if (working) {
                    args["and"] ?: return badCall(fn)
                } else {
                    args["and"]
                        ?: ParamRef(RecipeChecker.AS_OF, loc)
                }
            if (listOf(a, b).any { it !is ColumnRef && it !is ParamRef }) return badCall(fn)
            if (working && cal.workingDay == null) {
                diags +=
                    error(
                        TtrpDiagnosticId.FN_002,
                        "working_days needs a `working_day` attribute on the calendar `${cal.entity}`",
                        loc,
                    )
                return
            }
            val keys = listOf(a, b).filterIsInstance<ColumnRef>().map { it.column }.distinct()
            if (keys.isEmpty()) return badCall(fn)
            val t = "${token(c.id)}_$i"
            val pairs = "${c.id}~cal$i~pairs"
            add(Aggregate(pairs, "${c.label}~cal$i~pairs", loc, groupBy = keys), container)
            edges(head to PortRef(pairs, PortNames.IN))
            val days = calendarDays(c, cal, t, working, withSk = false) ?: return
            val d = side(PortNames.RIGHT, "_ttrp_d_$t", loc)

            fun bound(e: Expression) = if (e is ColumnRef) side(PortNames.LEFT, e.column, loc) else e
            val join = "${c.id}~cal$i~join"
            add(
                Join(
                    join,
                    "${c.label}~cal$i~join",
                    loc,
                    JoinType.INNER,
                    and(listOf(call(CatalogId.GT, d, bound(a), loc), call(CatalogId.LTE, d, bound(b), loc)), loc),
                ),
                container,
            )
            edges(
                PortRef(pairs, PortNames.OUT) to PortRef(join, PortNames.LEFT),
                days to PortRef(join, PortNames.RIGHT),
            )
            val count = "_ttrp_n_$t"
            val cnt = "${c.id}~cal$i~count"
            add(
                Aggregate(
                    cnt,
                    "${c.label}~cal$i~count",
                    loc,
                    groupBy = keys,
                    aggregations =
                        listOf(
                            Aggregation(
                                count,
                                AggregateCall(COUNT, listOf(ColumnRef(null, "_ttrp_d_$t", loc)), false, loc),
                            ),
                        ),
                ),
                container,
            )
            edges(PortRef(join, PortNames.OUT) to PortRef(cnt, PortNames.IN))
            val ren = "${c.id}~cal$i~keys"
            add(
                Project(
                    ren,
                    "${c.label}~cal$i~keys",
                    loc,
                    columns = (keys + count).map { ColumnRef(null, it, loc) },
                    aliases = keys.map { "_ttrp_k_${t}_$it" } + count,
                ),
                container,
            )
            edges(PortRef(cnt, PortNames.OUT) to PortRef(ren, PortNames.IN))
            val back = "${c.id}~cal$i~back"
            add(
                Join(
                    back,
                    "${c.label}~cal$i~back",
                    loc,
                    JoinType.LEFT,
                    and(
                        keys.map {
                            eq(side(PortNames.LEFT, it, loc), side(PortNames.RIGHT, "_ttrp_k_${t}_$it", loc), loc)
                        },
                        loc,
                    ),
                ),
                container,
            )
            edges(head to PortRef(back, PortNames.LEFT), PortRef(ren, PortNames.OUT) to PortRef(back, PortNames.RIGHT))
            head = PortRef(back, PortNames.OUT)
            val missing =
                listOf(a, b).map { IsNull(it, false, loc) }.reduce<Expression, Expression> {
                    x,
                    y,
                    ->
                    call(CatalogId.OR, x, y, loc)
                }
            replaced[fn] =
                CaseWhen(
                    listOf(missing to Literal(LiteralValue.Null, loc)),
                    FunctionCall(
                        COALESCE,
                        listOf(ColumnRef(null, count, loc), Literal(LiteralValue.Num("0"), loc)),
                        loc,
                    ),
                    loc,
                )
        }
        // The calc reads the joined rows now.
        g = g.copy(edges = g.edges.map { if (it.to == PortRef(c.id, PortNames.IN)) it.copy(from = head) else it })
        g =
            GraphOps.swapNode(
                g,
                c.id,
                c.copy(
                    assignments =
                        c.assignments.map { a ->
                            a.copy(value = NodeExpressions.rewrite(a.value) { replaced[it] })
                        },
                ),
            )
    }

    // ---- the calendar ---------------------------------------------------------------------------------------

    private data class Calendar(
        val entity: String,
        val date: String,
        val sk: String?,
        val workingDay: String?,
        val source: LoadSource,
    )

    private var calendarMemo: Calendar? = null

    private fun calendar(at: Node): Calendar? {
        calendarMemo?.let { return it }
        val idx = index
        val entity = idx?.calendars()?.singleOrNull()
        if (idx == null || entity == null) {
            diags +=
                error(
                    TtrpDiagnosticId.FN_002,
                    "the time sugar needs the model's calendar — one entity with `semantics { role: event_date }`",
                    at.location,
                )
            return null
        }
        val name = { a: org.tatrman.ttr.metadata.model.Attribute -> a.qname.name.substringAfterLast('.') }
        val source = LoadSourceResolver.resolve(entity, idx)
        if (source == null) {
            diags +=
                error(
                    TtrpDiagnosticId.RES_005,
                    "the calendar `${entity.qname.name}` has no er2db binding reachable",
                    at.location,
                )
            return null
        }
        return Calendar(
            entity = entity.qname.name.substringAfterLast('.'),
            date = entity.attributes.first { it.semantics?.role == "event_date" }.let(name),
            sk = entity.attributes.firstOrNull { it.isKey }?.let(name),
            workingDay = entity.attributes.firstOrNull { name(it) == WORKING_DAY }?.let(name),
            source = source,
        ).also { calendarMemo = it }
    }

    /**
     * The calendar's days as `_ttrp_d_<t>` (+ `_ttrp_sk_<t>` with [withSk]) — its working days only when [working]:
     * a load of the calendar, a projection naming the columns, a filter on the working-day flag. Its out port.
     */
    private fun calendarDays(
        at: Node,
        cal: Calendar,
        t: String,
        working: Boolean,
        withSk: Boolean,
    ): PortRef? {
        val loc = at.location
        val container = GraphOps.containerIdOf(g, at.id)
        val load = "${at.id}~cal~$t"
        add(Load(load, "${at.label}~calendar", loc, source = cal.source.objectQname), container)
        g = g.copy(loadSources = g.loadSources + (load to cal.source))
        val cols =
            listOfNotNull(
                cal.date to "_ttrp_d_$t",
                cal.sk?.takeIf { withSk }?.let { it to "_ttrp_sk_$t" },
                cal.workingDay?.takeIf { working }?.let {
                    it to
                        "_ttrp_w_$t"
                },
            )
        if (withSk && cal.sk == null) {
            diags += error(TtrpDiagnosticId.FN_002, "the calendar `${cal.entity}` declares no key attribute", loc)
            return null
        }
        val proj = "$load~cols"
        add(
            Project(
                proj,
                "${at.label}~calendar~cols",
                loc,
                columns =
                    cols.map {
                        ColumnRef(null, it.first, loc)
                    },
                aliases = cols.map { it.second },
            ),
            container,
        )
        edges(PortRef(load, PortNames.OUT) to PortRef(proj, PortNames.IN))
        if (!working) return PortRef(proj, PortNames.OUT)
        val wd = "$load~working"
        add(
            Filter(
                wd,
                "${at.label}~calendar~working",
                loc,
                eq(ColumnRef(null, "_ttrp_w_$t", loc), Literal(LiteralValue.Bool(true), loc), loc),
            ),
            container,
        )
        edges(PortRef(proj, PortNames.OUT) to PortRef(wd, PortNames.IN))
        return PortRef(wd, PortNames.OUT)
    }

    // ---- graph edits ----------------------------------------------------------------------------------------

    private fun add(
        node: Node,
        container: String?,
    ) {
        g = GraphOps.addNode(g, node, container)
    }

    private fun edges(vararg pairs: Pair<PortRef, PortRef>) {
        g = GraphOps.addEdges(g, pairs.map { (from, to) -> Edge(from, to, EdgeKind.DATA) })
    }

    /** The DATA port feeding [nodeId]'s `in`. */
    private fun feed(nodeId: String): PortRef? =
        g.edges.firstOrNull { it.kind == EdgeKind.DATA && it.to == PortRef(nodeId, PortNames.IN) }?.from

    private fun retargetIn(
        nodeId: String,
        from: String,
        to: String,
    ) {
        g =
            g.copy(
                edges =
                    g.edges.map {
                        if (it.to ==
                            PortRef(nodeId, from)
                        ) {
                            it.copy(to = PortRef(nodeId, to))
                        } else {
                            it
                        }
                    },
            )
    }

    private fun badArgs(n: TimeSugar) {
        diags += error(TtrpDiagnosticId.FN_002, "`${n.kind}` cannot be lowered with these arguments", n.location)
    }

    private fun badCall(fn: FunctionCall) {
        diags +=
            error(
                TtrpDiagnosticId.FN_002,
                "`${fn.function.name}` is lowered over columns and bound values (`:as_of`) only",
                fn.location,
            )
    }

    private fun error(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
    ) = TtrpDiagnostic(id, Severity.ERROR, message, loc)

    companion object {
        /** The calendar attribute `working_days` counts (D-82; hartland `date_dim.working_day`). */
        const val WORKING_DAY = "working_day"

        private val CALENDAR_FUNCTIONS = setOf("working_days", "age")
        private val AGE = CatalogId("age")
        private val MAX = CatalogId("agg.max")
        private val COUNT = CatalogId("agg.count")
        private val COALESCE = CatalogId("fn.coalesce")

        /** A node id as a column-name fragment (`n21~x` → `n21_x`). */
        private fun token(id: String) = id.replace(Regex("[^A-Za-z0-9_]"), "_")

        private fun side(
            port: String,
            column: String,
            loc: SourceLocation,
        ) = ColumnRef(port, column, loc)

        private fun eq(
            a: Expression,
            b: Expression,
            loc: SourceLocation,
        ) = call(CatalogId.EQ, a, b, loc)

        private fun call(
            fn: CatalogId,
            a: Expression,
            b: Expression,
            loc: SourceLocation,
        ) = FunctionCall(fn, listOf(a, b), loc)

        private fun and(
            parts: List<Expression>,
            loc: SourceLocation,
        ): Expression = parts.reduce { a, b -> call(CatalogId.AND, a, b, loc) }
    }
}
