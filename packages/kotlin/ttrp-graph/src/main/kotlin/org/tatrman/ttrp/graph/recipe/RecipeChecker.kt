// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.AutomationService
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RoleContainer
import org.tatrman.ttrp.ast.RouteRef
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.WiringArg
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.build.GraphBuilder
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.RecipeGraph
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.recipe.RecipeCatalog
import org.tatrman.ttrp.resolve.ImportScope
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.TtrpChecker

/**
 * The recipe checker (AU-core-P1; AU contracts §4) — a pass over the built graph and the resolved model, after
 * `StructureValidator`: the role wiring (REC-000), the action wiring typed against `def action` (REC-001…003),
 * the candidates (REC-004/005), key lineage and the mode verdict (REC-010…012), parameters (REC-020/021),
 * transitions against `states:` (REC-030), the turn rule (REC-040) and the time typing ([RecipeTime], REC-050).
 * The document-level shape (one recipe, one candidates role, DML, the decision body) is checked at parse time
 * (`TtrpChecks`), where a DML island's TTRP-SQL-001 would otherwise stop the compile before this pass runs.
 */
class RecipeChecker(
    private val report: TtrpChecker.Report,
    private val build: GraphBuilder.BuildResult,
    private val fileName: String,
) {
    data class Result(
        val model: RecipeModel?,
        val diagnostics: List<TtrpDiagnostic>,
    )

    private val diags = mutableListOf<TtrpDiagnostic>()
    private val model: ModelIndex? = report.modelIndex
    private val graph: TtrpGraph = build.graph
    private val imports: List<ImportScope> =
        report.document.statements
            .filterIsInstance<ImportDecl>()
            .map { ModelIndex.importScope(it.qname.parts, it.qname.text) }

    fun check(): Result {
        val rg = build.recipe ?: return Result(null, emptyList())
        val recipe = rg.recipe
        val name = RecipeCatalog.recipeName(recipe, fileName)
        val keyColumns = recipe.key.map { it.parts.last() }
        val keyEntity =
            recipe.key
                .firstOrNull()
                ?.parts
                ?.let { if (it.size >= 2) it[it.size - 2] else null }

        val forms =
            rg.roles
                .mapNotNull { (n, id) ->
                    recipe.roleContainers.firstOrNull { it.name == n }?.let {
                        id to
                            it.form
                    }
                }.toMap()
        val opaqueOut: (Container) -> Row? = { c -> forms[c.id]?.let { declaredRow(it) } }
        val rows = RecipeRows(graph, report.loadSchemas, opaqueOut)
        val candidatesId = recipe.candidates.firstOrNull()?.let { rg.roles[it.name] }
        val lineage =
            KeyLineage(
                graph,
                rows,
                candidatesId,
                keyColumns,
                rg.decision,
                forms,
                opaqueOut,
                relationKey = { rel -> relationKey(rel, keyEntity, keyColumns) },
                relationJoins = rg.relationJoins,
            )

        checkShape(recipe)
        checkRoleWiring(rg)
        val candidatesRow = candidatesId?.let { id -> container(id)?.let { outRow(rows, it) } }
        checkCandidates(recipe, rg, candidatesRow, keyColumns)

        val programs = automation<AutomationProgram>().filter { it.recipe == name }
        val contexts = recipe.contexts.map { classify(it, rg, lineage) }
        val mode = modeOf(contexts)
        checkMode(recipe, contexts, mode, programs)
        checkWindowBy(recipe, rg, rows, keyColumns)
        checkDecisionLineage(rg, lineage, keyColumns)

        val routeRow = routeRows(rg, rows)
        val turnFed = turnFedRoutes(recipe, rg, keyColumns)
        checkWiring(recipe, rg, routeRow, turnFed)
        checkParameters(recipe, rg, programs, keyColumns)
        diags += RecipeTime(report, build, rg, rows, imports, name).check()

        val sw = recipe.decisions.firstOrNull()?.switch
        val recipeModel =
            RecipeModel(
                name = name,
                keyAttributes = recipe.key.map { it.text },
                keyColumns = keyColumns,
                candidates = recipe.candidates.firstOrNull()?.name,
                contexts = contexts,
                decision = recipe.decisions.firstOrNull()?.name,
                routes =
                    sw?.routes.orEmpty().map {
                        RouteInfo(it.name, it.because, it.detail.map { d -> d.text }, it.name in turnFed)
                    } +
                        listOfNotNull(
                            sw?.elseRoute?.name?.let {
                                RouteInfo(
                                    it,
                                    sw.elseRoute!!.because,
                                    sw.elseRoute!!.detail.map { d ->
                                        d.text
                                    },
                                    false,
                                    isElse = true,
                                )
                            },
                        ),
                switchMode = sw?.mode?.spelling,
                wirings =
                    recipe.wirings.map { w ->
                        WiringInfo(
                            w.routes.map { "${it.decision}.${it.route}" },
                            w.action,
                            w.args.associate { it.name to render(it.value) },
                            w.whenCondition != null,
                        )
                    },
                parameters = recipe.parameters.map { it.text },
                mode = mode,
                programs = programs.map { it.qname.name },
            )
        return Result(recipeModel, diags.toList())
    }

    // ---- roles -------------------------------------------------------------------------------------------

    /** 06 §1.2: exactly one `candidates` (no `in` port), at most one `decision`, and the `actions` wiring. */
    private fun checkShape(recipe: RecipeDecl) {
        when (recipe.candidates.size) {
            1 -> Unit
            0 -> diags += err(TtrpDiagnosticId.REC_000, "the recipe has no `candidates` island", recipe.location)
            else ->
                recipe.candidates.drop(1).forEach {
                    diags +=
                        err(TtrpDiagnosticId.REC_000, "a second `candidates` island — a recipe has one", it.location)
                }
        }
        recipe.candidates.firstOrNull()?.ports?.firstOrNull { it.kind == org.tatrman.ttrp.ast.PortKind.IN }?.let {
            diags +=
                err(TtrpDiagnosticId.REC_000, "the candidates read nothing of the recipe — no `in` port", it.location)
        }
        recipe.decisions.drop(1).forEach {
            diags += err(TtrpDiagnosticId.REC_000, "a second `decision` — a recipe decides once", it.location)
        }
        if (recipe.members.none { it is org.tatrman.ttrp.ast.ActionsBlock }) {
            diags +=
                err(
                    TtrpDiagnosticId.REC_000,
                    "the recipe wires no actions — add an `actions { }` block",
                    recipe.location,
                )
        }
    }

    /** Every `in` port of a context or the decision is fed — by the candidates' `out` of that name, or a context. */
    private fun checkRoleWiring(rg: RecipeGraph) {
        val consumers = rg.roles.values + listOfNotNull(rg.decision)
        for (id in consumers) {
            val c = container(id) ?: continue
            for (p in c.declaredPorts.filter { it.direction == PortDirection.IN && it.kind == PortKind.DATA }) {
                if (graph.edges.none { it.to == PortRef(c.id, p.name) }) {
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_000,
                            "`${c.label}` reads `${p.name}`, which neither the candidates' out port nor a context provides",
                            c.location,
                        )
                }
            }
        }
    }

    // ---- candidates (REC-004 compile half, REC-005) ------------------------------------------------------

    private fun checkCandidates(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        row: Row?,
        keyColumns: List<String>,
    ) {
        val cand = recipe.candidates.firstOrNull() ?: return
        if (row != null) {
            val missing = keyColumns.filter { it !in row.keys }
            if (missing.isNotEmpty()) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_004,
                        "the candidates `${cand.name}` do not project the key `${missing.joinToString(", ")}`",
                        cand.location,
                    )
            }
        }
        val c = rg.roles[cand.name]?.let { container(it) } ?: return
        for (id in c.memberIds) {
            val f = graph.nodes[id] as? Filter ?: continue
            val pred = f.predicate ?: continue
            for (e in walk(pred)) {
                val keyCompare =
                    when (e) {
                        is FunctionCall ->
                            e.function == CatalogId.EQ &&
                                e.args.any { it is ColumnRef && it.column in keyColumns } &&
                                e.args.any { it is ParamRef }
                        is InList -> (e.expr as? ColumnRef)?.column in keyColumns && e.items.any { it is ParamRef }
                        else -> false
                    }
                if (keyCompare) {
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_005,
                            "the candidates `${cand.name}` filter on the key `${keyColumns.joinToString(", ")}` — " +
                                "a recipe selects by state; the program's port picks single keys",
                            e.location,
                        )
                }
            }
        }
    }

    // ---- key lineage + the mode verdict (REC-010…012) -----------------------------------------------------

    private fun classify(
        role: RoleContainer,
        rg: RecipeGraph,
        lineage: KeyLineage,
    ): ContextInfo {
        val kind =
            when (val f = role.form) {
                is IslandForm.Target ->
                    when (val b = role.body) {
                        is org.tatrman.ttrp.ast.FragmentBody -> b.tag
                        else -> "canonical"
                    }
                is IslandForm.Builtin -> "builtin"
                is IslandForm.Foreign -> "foreign:${f.language}"
                is IslandForm.Call -> "call"
                is IslandForm.Turn -> "turn"
            }
        val c = rg.roles[role.name]?.let { container(it) }
        val out = c?.let { outPort(it) }?.let { lineage.of(PortRef(c.id, it)) } ?: Lineage.LOOKUP
        val cls =
            when {
                out.keyed -> ContextClass.PER_CANDIDATE
                out.readsCandidates -> ContextClass.SET_LEVEL
                else -> ContextClass.LOOKUP
            }
        return ContextInfo(role.name, kind, cls, role.window, out.lostAt?.what, out.lostAt?.location)
    }

    private fun modeOf(contexts: List<ContextInfo>): RecipeMode {
        val setLevel = contexts.filter { it.contextClass == ContextClass.SET_LEVEL }
        return when {
            setLevel.isEmpty() -> RecipeMode.PER_CANDIDATE
            setLevel.all { it.window != null } -> RecipeMode.SET_LEVEL_WINDOWED
            else -> RecipeMode.SET_LEVEL_BATCH_ONLY
        }
    }

    private fun checkMode(
        recipe: RecipeDecl,
        contexts: List<ContextInfo>,
        mode: RecipeMode,
        programs: List<AutomationProgram>,
    ) {
        val eventPrograms = programs.filter { it.mode == "on_event" || it.port == "key" }
        for (ctx in contexts) {
            val role = recipe.contexts.first { it.name == ctx.name }
            when (ctx.contextClass) {
                ContextClass.SET_LEVEL ->
                    if (ctx.window == null) {
                        val loc = ctx.lostKeyLocation ?: role.location
                        val why = ctx.lostKeyAt ?: "an op loses the key"
                        val base = "context `${ctx.name}` reads the candidates but $why → set-level"
                        diags +=
                            if (eventPrograms.isNotEmpty()) {
                                err(
                                    TtrpDiagnosticId.REC_010,
                                    "$base, and program `${eventPrograms.first().qname.name}` runs it per event — " +
                                        "declare `window` or restate",
                                    loc,
                                )
                            } else {
                                warn(
                                    TtrpDiagnosticId.REC_010,
                                    "$base, no `window` → batch only; declare `window` or restate",
                                    loc,
                                )
                            }
                    } else {
                        checkWindow(ctx, programs)
                    }
                else ->
                    if (ctx.window != null) {
                        diags +=
                            warn(
                                TtrpDiagnosticId.REC_010,
                                "context `${ctx.name}` is ${ctx.contextClass.spelling}; its `window` is unnecessary",
                                ctx.window.location,
                            )
                    }
            }
        }
        if (mode != RecipeMode.PER_CANDIDATE) {
            for (p in programs.filter { it.port == "key" }) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_012,
                        "program `${p.qname.name}` runs the recipe for one key (`port: key`), but the recipe is ${mode.spelling}",
                        recipe.location,
                    )
            }
        }
    }

    /** D-104's compile-time half: a window's `by:` columns are columns of the context's rows (or the key). */
    private fun checkWindowBy(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        rows: RecipeRows,
        keyColumns: List<String>,
    ) {
        for (role in recipe.contexts) {
            val window = role.window ?: continue
            val row = rg.roles[role.name]?.let { container(it) }?.let { outRow(rows, it) } ?: continue
            for (by in window.by) {
                if (by.text !in row.keys && by.text !in keyColumns) {
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_010,
                            "context `${role.name}`'s window groups `by: ${by.text}`, which its rows do not carry " +
                                "(${row.keys.joinToString(", ")})",
                            by.location,
                        )
                }
            }
        }
    }

    /** REC-011: a batch program's schedule period must cover whole windows (a period `over:` only — D-28). */
    private fun checkWindow(
        ctx: ContextInfo,
        programs: List<AutomationProgram>,
    ) {
        val window = ctx.window ?: return
        val over = window.over
        for (p in programs.filter { it.mode == "batch" }) {
            val period = schedulePeriod(p.schedule)
            val ok =
                when (over) {
                    is org.tatrman.ttrp.ast.WindowOver.Calendar ->
                        period != null &&
                            PERIODS.indexOf(period) >= PERIODS.indexOf(over.unit)
                    else -> false
                }
            if (!ok) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_011,
                        "batch program `${p.qname.name}` (schedule ${p.schedule ?: "none"}) does not cover whole " +
                            "windows of " +
                            "context `${ctx.name}`",
                        window.location,
                    )
            }
        }
    }

    /** A schedule string's period: `06:00 Europe/Prague` is daily; cron forms are the Server's to read. */
    private fun schedulePeriod(schedule: String?): String? =
        when {
            schedule == null -> null
            Regex("^\\d{1,2}:\\d{2}( .*)?$").matches(schedule.trim()) -> "day"
            schedule.trim().startsWith("weekly") -> "week"
            schedule.trim().startsWith("monthly") -> "month"
            else -> null
        }

    /** T5: the decision joins per-candidate contexts on the key, and its switch reads keyed rows. */
    private fun checkDecisionLineage(
        rg: RecipeGraph,
        lineage: KeyLineage,
        keyColumns: List<String>,
    ) {
        val sid = rg.switch ?: return
        val sw = graph.nodes[sid] as? Switch ?: return
        val inp = lineage.input(sid)
        if (!inp.keyed) {
            val where = inp.lostAt
            diags +=
                err(
                    TtrpDiagnosticId.REC_010,
                    "the decision's switch reads rows not keyed by `${keyColumns.joinToString(", ")}`" +
                        (where?.let { " — ${it.what}" } ?: ""),
                    where?.location ?: sw.location,
                )
        }
    }

    // ---- the action wiring (REC-001…003, REC-030, REC-040) -------------------------------------------------

    private fun routeRows(
        rg: RecipeGraph,
        rows: RecipeRows,
    ): Row? =
        rg.switch?.let { rows.input(it) }
            ?: rg.recipe.candidates
                .firstOrNull()
                ?.let { rg.roles[it.name] }
                ?.let {
                    container(
                        it,
                    )
                }?.let { outRow(rows, it) }

    private fun checkWiring(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        routeRow: Row?,
        turnFed: Set<String>,
    ) {
        val decision = recipe.decisions.firstOrNull()
        val sw = decision?.switch
        val routeNames = sw?.routeNames.orEmpty()
        val consumed = mutableSetOf<String>()
        for (w in recipe.wirings) {
            for (r in w.routes) {
                if (!knownRoute(r, decision?.name, routeNames, recipe)) {
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_003,
                            "`${r.decision}.${r.route}` is no route of " +
                                (
                                    decision?.let { "decision `${it.name}` (${routeNames.joinToString(", ")})" }
                                        ?: "this recipe"
                                ),
                            r.location,
                        )
                }
                if (r.isAll) consumed += routeNames else consumed += r.route
            }
            val action = action(w.action)
            if (action == null) {
                diags +=
                    err(TtrpDiagnosticId.REC_001, "no action `${w.action}` in the imported packages", w.actionLocation)
                continue
            }
            checkArgs(action, w.args, routeRow, rg, w.actionLocation)
            checkTransition(action, w.args, routeRow, w.actionLocation)
            val fed = w.routes.filter { it.isAll && turnFed.isNotEmpty() || it.route in turnFed }
            if (fed.isNotEmpty() && action.approval != "required") {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_040,
                        "route `${fed.first().route}` decides on an agent's answer and fires `${w.action}`, " +
                            "which needs no approval",
                        w.actionLocation,
                    )
            }
        }
        for (r in routeNames - consumed) {
            val loc =
                sw?.routes?.firstOrNull { it.name == r }?.nameLocation ?: sw?.elseRoute?.location ?: recipe.location
            diags += err(TtrpDiagnosticId.REC_003, "route `$r` is wired to no action", loc)
        }
    }

    private fun knownRoute(
        r: RouteRef,
        decision: String?,
        routeNames: List<String>,
        recipe: RecipeDecl,
    ): Boolean =
        if (decision != null) {
            r.decision == decision && (r.isAll || r.route in routeNames)
        } else {
            // A decision-less recipe has the identity verdict only: `<candidates>.all` (06 §1.2).
            r.isAll && recipe.candidates.any { it.name == r.decision }
        }

    private fun checkArgs(
        action: AutomationAction,
        args: List<WiringArg>,
        row: Row?,
        rg: RecipeGraph,
        at: SourceLocation,
    ) {
        val columns = action.columns.associateBy { it.name }
        for (a in args) {
            val col = columns[a.name]
            if (col == null) {
                if (a.name in WIRING_PROPERTIES) {
                    (a.value as? WiringValue.Call)?.let { call -> checkCall(call, row, rg) }
                    continue
                }
                diags +=
                    err(
                        TtrpDiagnosticId.REC_001,
                        "action `${action.qname.name}` has no column `${a.name}` (columns: " +
                            "${action.columns.joinToString(", ") { it.name }})",
                        a.location,
                    )
                continue
            }
            checkType(action, col, a, row, rg)
        }
        for (c in action.columns.filter { !it.optional }) {
            if (args.none { it.name == c.name }) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_001,
                        "action `${action.qname.name}` needs its column `${c.name}`",
                        at,
                    )
            }
        }
    }

    /** `on_due: hold { order: order_number, … }` — an action call nested in a wiring (D-98). */
    private fun checkCall(
        call: WiringValue.Call,
        row: Row?,
        rg: RecipeGraph,
    ) {
        val action = action(call.action)
        if (action == null) {
            diags += err(TtrpDiagnosticId.REC_001, "no action `${call.action}` in the imported packages", call.location)
            return
        }
        checkArgs(action, call.args, row, rg, call.location)
        checkTransition(action, call.args, row, call.location)
    }

    private fun checkType(
        action: AutomationAction,
        col: AutomationColumn,
        arg: WiringArg,
        row: Row?,
        rg: RecipeGraph,
    ) {
        val v = arg.value
        val expr = (v as? WiringValue.Expr)?.expression
        val ref = expr as? ColumnRef

        fun mismatch(why: String) {
            diags +=
                err(
                    TtrpDiagnosticId.REC_002,
                    "`${action.qname.name}.${col.name}` (${col.type}) $why",
                    arg.location,
                )
        }
        when (col.type) {
            "role" -> {
                val role = ref?.takeIf { it.port == "role" }?.column
                when {
                    role == null -> mismatch("takes a role, written `role.<name>`")
                    automation<AutomationRole>().none {
                        it.qname.name == role
                    } -> mismatch("names `role.$role`, which no `def role` defines")
                }
            }
            "rows" -> {
                val items =
                    when (v) {
                        is WiringValue.Values -> v.items
                        is WiringValue.Expr -> listOf(v.expression)
                        is WiringValue.Call -> emptyList()
                    }
                val sources =
                    rg.recipe.roleContainers
                        .map { it.name }
                        .toSet()
                val bad =
                    items.filterNot {
                        (it as? ColumnRef)?.let { r -> r.port == null && r.column in sources } ==
                            true
                    }
                if (items.isEmpty() ||
                    bad.isNotEmpty()
                ) {
                    mismatch("takes rows — the name of a context or the candidates")
                }
            }
            "action" ->
                when {
                    // `action: start { process: "purchasing" }` — the action, with its arguments (H-3's ask).
                    v is WiringValue.Call -> checkCall(v, row, rg)
                    ref == null || ref.port != null || action(ref.column) == null ->
                        mismatch("takes an action the imports define")
                }
            else -> {
                if (v is WiringValue.Call || v is WiringValue.Values) {
                    mismatch("takes one value")
                    return
                }
                val want = columnType(col.type) ?: return
                val got: TtrpType? =
                    when {
                        expr is Literal -> literalType(expr.value)
                        expr is DurationExpr || (expr is FunctionCall && expr.function.name in DURATION_FUNCTIONS) ->
                            if (want.kind == TtrpType.Kind.TEMPORAL) return else TtrpType.Named("duration")
                        ref != null && ref.port == null && row != null && ref.column in row -> row[ref.column]
                        ref != null && ref.port == null && row != null && ref.column !in row -> {
                            // A bare name the rows do not carry is a symbol (`to: rejected`, `kind: inbound`) — text only.
                            if (want.kind == TtrpType.Kind.STRING) return
                            mismatch("reads `${ref.column}`, which the route's rows do not carry")
                            return
                        }
                        else -> null
                    }
                if (got != null && !compatible(want, got)) mismatch("gets a ${got.canonical} value")
            }
        }
    }

    /** REC-030: the action's transition target ∈ `states:`, its `from:` ⊆ `states:`, consistent with `transitions:`. */
    private fun checkTransition(
        action: AutomationAction,
        args: List<WiringArg>,
        row: Row?,
        at: SourceLocation,
    ) {
        val t = action.transition ?: return
        val attr = attribute(t.attribute) ?: return
        if (attr.states.isEmpty()) return
        val to =
            t.to ?: t.toColumn?.let { c ->
                val v = (args.firstOrNull { it.name == c }?.value as? WiringValue.Expr)?.expression
                when (v) {
                    is ColumnRef -> v.column.takeIf { v.port == null && (row == null || it !in row) }
                    is Literal -> (v.value as? LiteralValue.Str)?.value
                    else -> null
                }
            }
        if (to != null && to !in attr.states) {
            diags +=
                err(
                    TtrpDiagnosticId.REC_030,
                    "`${action.qname.name}` moves `${t.attribute}` to `$to`, which is not one of its states " +
                        "(${attr.states.joinToString(", ")})",
                    at,
                )
            return
        }
        for (f in t.from) {
            when {
                f !in attr.states ->
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_030,
                            "`${action.qname.name}` moves `${t.attribute}` from `$f`, which is not one of its states",
                            at,
                        )
                to != null && f != to && attr.transitions.isNotEmpty() && to !in attr.transitions[f].orEmpty() ->
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_030,
                            "`${t.attribute}` has no transition `$f → $to` (its transitions: " +
                                "${attr.transitions.entries.joinToString(
                                    "; ",
                                ) { "${it.key} → ${it.value.joinToString("|")}" }})",
                            at,
                        )
            }
        }
    }

    /** The routes whose condition reads a column a `turn` island produced — transitively through the decision. */
    private fun turnFedRoutes(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        keyColumns: List<String>,
    ): Set<String> {
        val turns = recipe.contexts.filter { it.form is IslandForm.Turn }
        if (turns.isEmpty()) return emptySet()
        val tainted = mutableSetOf<String>()
        for (t in turns) declaredRow(t.form)?.keys?.let { tainted += it - keyColumns.toSet() }
        val decision = rg.decision?.let { container(it) } ?: return emptySet()
        for (id in decision.memberIds) {
            when (val n = graph.nodes[id]) {
                is org.tatrman.ttrp.graph.model.Calc ->
                    n.assignments.forEach { a -> if (reads(a.value, tainted)) tainted += a.name }
                is org.tatrman.ttrp.graph.model.Aggregate ->
                    n.aggregations.forEach { a -> if (reads(a.value, tainted)) tainted += a.name }
                is org.tatrman.ttrp.graph.model.Project ->
                    n.columns.forEachIndexed { i, e -> if (reads(e, tainted)) n.aliasOf(i)?.let { tainted += it } }
                else -> Unit
            }
        }
        val sw = recipe.decisions.firstOrNull()?.switch ?: return emptySet()
        return sw.routes
            .filter { reads(it.condition, tainted) }
            .map { it.name }
            .toSet()
    }

    private fun reads(
        e: Expression,
        cols: Set<String>,
    ): Boolean = walk(e).any { it is ColumnRef && it.column in cols }

    // ---- parameters (REC-020/021) --------------------------------------------------------------------------

    private fun checkParameters(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        programs: List<AutomationProgram>,
        keyColumns: List<String>,
    ) {
        val declared = recipe.parameters.map { it.text }.toSet()
        val runtime = mutableSetOf(AS_OF)
        for (p in programs) {
            when (p.port) {
                "event" -> p.event?.let { e -> rowSchema(e)?.let { runtime += it.keys } }
                "key", "keys" -> runtime += keyColumns
            }
        }
        val refs = paramRefs(recipe, rg)
        for (r in refs) {
            if (r.name !in declared && r.name !in runtime) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_020,
                        "`:${r.name}` is neither a parameter of the recipe (`parameters:`) nor a value the run binds" +
                            if (programs.isEmpty()) " — declare the parameter, or the program that supplies it" else "",
                        r.location,
                    )
            }
        }
        val used = refs.map { it.name }.toSet() + becauseFills(recipe)
        val defined = automation<AutomationParameter>().map { it.qname.name }.toSet()
        for (p in recipe.parameters) {
            when {
                p.text !in defined ->
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_021,
                            "parameter `${p.text}` is no `def parameter` of the imports",
                            p.location,
                        )
                p.text !in used ->
                    diags +=
                        warn(
                            TtrpDiagnosticId.REC_021,
                            "parameter `${p.text}` is declared but no island uses it",
                            p.location,
                        )
            }
        }
    }

    /** Every `:name` the recipe's islands, routes and wirings read (a `days(:p)` duration included). */
    private fun paramRefs(
        recipe: RecipeDecl,
        rg: RecipeGraph,
    ): List<ParamRef> {
        val exprs = mutableListOf<Expression>()
        val ids = (rg.roles.values + listOfNotNull(rg.decision)).flatMap { container(it)?.memberIds.orEmpty() }
        for (id in ids) exprs += nodeExpressions(graph.nodes[id])
        for (w in recipe.wirings) {
            w.whenCondition?.let { exprs += it }
            w.args.forEach { exprs += wiringExprs(it.value) }
        }
        return exprs.flatMap { walk(it) }.mapNotNull { e ->
            when (e) {
                is ParamRef -> e
                is org.tatrman.ttrp.expr.DurationParam -> ParamRef(e.param, e.location)
                else -> null
            }
        }
    }

    private fun wiringExprs(v: WiringValue): List<Expression> =
        when (v) {
            is WiringValue.Expr -> listOf(v.expression)
            is WiringValue.Values -> v.items
            is WiringValue.Call -> v.args.flatMap { wiringExprs(it.value) }
        }

    /** `because: "… {return_window} …"` names a parameter (or a column) by its `{fill}`. */
    private fun becauseFills(recipe: RecipeDecl): Set<String> {
        val sw = recipe.decisions.firstOrNull()?.switch ?: return emptySet()
        val texts = sw.routes.mapNotNull { it.because } + listOfNotNull(sw.elseRoute?.because)
        return texts.flatMap { t -> Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}").findAll(t).map { it.groupValues[1] } }.toSet()
    }

    // ---- the model -----------------------------------------------------------------------------------------

    private inline fun <reified T> automation(): List<T> =
        model?.automationInScope(imports)?.filterIsInstance<T>().orEmpty()

    private fun action(name: String): AutomationAction? =
        automation<AutomationAction>().firstOrNull {
            it.qname.name ==
                name
        }

    /** `order.state` → the attribute (the entity found anywhere in the model, then the stdlib). */
    private fun attribute(path: String): Attribute? {
        val parts = path.split('.')
        if (parts.size != 2) return null
        val entity = model?.entitiesNamed(parts[0])?.firstOrNull() ?: return null
        return model.attributesOf(entity).firstOrNull { it.qname.name.substringAfterLast('.') == parts[1] }
    }

    private fun entityAttributeType(path: String): TtrpType? {
        val parts = path.split('.')
        if (parts.size != 2) return null
        val entity: Entity = model?.entitiesNamed(parts[0])?.firstOrNull() ?: return null
        return model.entityAttributes(entity).firstOrNull { it.first == parts[1] }?.let { TtrpType.parse(it.second) }
    }

    /** A `def column`'s type spelling as a type: a scalar, or an `<entity>.<attr>` reference's type. */
    private fun columnType(spelling: String): TtrpType? =
        if ('.' in
            spelling
        ) {
            entityAttributeType(spelling)
        } else {
            TtrpType.parse(spelling).takeUnless { it is TtrpType.Named }
        }

    private fun rowSchema(name: String): Row? =
        model
            ?.findRowSchemas(name.removePrefix("schema."), imports)
            ?.firstOrNull()
            ?.columns
            ?.associate { it.name to TtrpType.parse(it.type) }

    /** An opaque island's declared out row: `foreign … schema s`, `turn … answer s`, a `call`'s service response. */
    private fun declaredRow(form: IslandForm): Row? =
        when (form) {
            is IslandForm.Foreign -> rowSchema(form.schema)
            is IslandForm.Turn -> rowSchema(form.answerSchema)
            is IslandForm.Call ->
                automation<AutomationService>()
                    .firstOrNull { it.qname.name == form.service.parts.last() }
                    ?.response
                    ?.let { rowSchema(it) }
            else -> null
        }

    /**
     * For `on: relation r` (D-103): when `r` runs from the key's entity to a side whose rows each have exactly one
     * of it (`from: "1"`), the joined side's join columns equate to the key — per key component, that column.
     */
    private fun relationKey(
        name: String,
        keyEntity: String?,
        keyColumns: List<String>,
    ): List<String>? {
        if (keyEntity == null) return null
        val rel: Relation = model?.findRelations(name, imports)?.firstOrNull() ?: return null
        val pairs =
            when {
                rel.fromEntity.name == keyEntity && rel.cardinality.fromMax == 1 ->
                    rel.joinPairs.map {
                        it.fromAttr.name.substringAfterLast('.') to
                            it.toAttr.name.substringAfterLast('.')
                    }
                rel.toEntity.name == keyEntity && rel.cardinality.toMax == 1 ->
                    rel.joinPairs.map {
                        it.toAttr.name.substringAfterLast('.') to
                            it.fromAttr.name.substringAfterLast('.')
                    }
                else -> return null
            }
        return keyColumns.map { k -> pairs.firstOrNull { it.first == k }?.second ?: return null }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun container(id: String): Container? = graph.containers[id]

    private fun outPort(c: Container): String? = c.defaultOut()

    private fun outRow(
        rows: RecipeRows,
        c: Container,
    ): Row? = outPort(c)?.let { rows.of(PortRef(c.id, it)) }

    private fun render(v: WiringValue): String =
        when (v) {
            is WiringValue.Expr ->
                when (val e = v.expression) {
                    is ColumnRef -> (e.port?.let { "$it." } ?: "") + e.column
                    is Literal ->
                        when (val l = e.value) {
                            is LiteralValue.Str -> "\"${l.value}\""
                            is LiteralValue.Num -> l.raw
                            is LiteralValue.Bool -> l.value.toString()
                            LiteralValue.Null -> "null"
                        }
                    is org.tatrman.ttrp.expr.DurationLiteral -> "${e.amount} ${e.unit.spelling}"
                    is org.tatrman.ttrp.expr.DurationParam -> "${e.unit.spelling}(:${e.param})"
                    else -> "…"
                }
            is WiringValue.Values -> v.items.joinToString(", ") { render(WiringValue.Expr(it, it.location)) }
            is WiringValue.Call -> "${v.action} { … }"
        }

    private fun literalType(v: LiteralValue): TtrpType? =
        when (v) {
            is LiteralValue.Str -> TtrpType.Str
            is LiteralValue.Num -> if ('.' in v.raw) TtrpType.Decimal() else TtrpType.Integer
            is LiteralValue.Bool -> TtrpType.Bool
            LiteralValue.Null -> null
        }

    /** A text column takes any scalar (an action renders it — `about: order_number`); otherwise kinds must agree. */
    private fun compatible(
        want: TtrpType,
        got: TtrpType,
    ): Boolean =
        want.kind == got.kind ||
            want.kind == TtrpType.Kind.STRING ||
            want.kind == TtrpType.Kind.OTHER ||
            got.kind == TtrpType.Kind.OTHER

    private fun err(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
    ) = TtrpDiagnostic(id, Severity.ERROR, message, loc)

    private fun warn(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
    ) = TtrpDiagnostic(id, Severity.WARNING, message, loc)

    companion object {
        /** The run's instant — always bound (D-29); never a parameter. */
        const val AS_OF = "as_of"

        /** Wiring arguments that set an action's properties rather than a column (D-98, the `ask` grouping). */
        val WIRING_PROPERTIES = setOf("due", "on_due", "group_by")

        val DURATION_FUNCTIONS = setOf("days", "working_days", "weeks", "months")

        private val PERIODS = listOf("day", "week", "month")

        /** Every expression of a node the checker reads for `:name` and taint. */
        fun nodeExpressions(n: org.tatrman.ttrp.graph.model.Node?): List<Expression> =
            when (n) {
                is Filter -> listOfNotNull(n.predicate)
                is org.tatrman.ttrp.graph.model.Branch -> listOfNotNull(n.predicate)
                is org.tatrman.ttrp.graph.model.Project -> n.columns
                is org.tatrman.ttrp.graph.model.Calc -> n.assignments.map { it.value }
                is org.tatrman.ttrp.graph.model.Aggregate -> n.aggregations.map { it.value } + listOfNotNull(n.having)
                is org.tatrman.ttrp.graph.model.Join -> listOfNotNull(n.on)
                is Switch -> n.cases.mapNotNull { it.second }
                is org.tatrman.ttrp.graph.model.TimeSugar -> n.args.map { it.second }
                else -> emptyList()
            }

        /** An expression and every sub-expression, depth first. */
        fun walk(e: Expression): Sequence<Expression> =
            sequence {
                yield(e)
                when (e) {
                    is FunctionCall -> e.args.forEach { yieldAll(walk(it)) }
                    is org.tatrman.ttrp.expr.AggregateCall -> e.args.forEach { yieldAll(walk(it)) }
                    is org.tatrman.ttrp.expr.Cast -> yieldAll(walk(e.expr))
                    is org.tatrman.ttrp.expr.CaseWhen -> {
                        e.branches.forEach { (c, v) ->
                            yieldAll(walk(c))
                            yieldAll(walk(v))
                        }
                        e.elseExpr?.let { yieldAll(walk(it)) }
                    }
                    is InList -> {
                        yieldAll(walk(e.expr))
                        e.items.forEach { yieldAll(walk(it)) }
                    }
                    is org.tatrman.ttrp.expr.IsNull -> yieldAll(walk(e.expr))
                    else -> Unit
                }
            }
    }
}
