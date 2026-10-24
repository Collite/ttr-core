// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.AutomationService
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RoleContainer
import org.tatrman.ttrp.ast.RouteRef
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Wiring
import org.tatrman.ttrp.ast.WiringArg
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.DurationUnit
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
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.RecipeGraph
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.recipe.RecipeCatalog
import org.tatrman.ttrp.resolve.ImportScope
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.TtrpChecker
import org.tatrman.ttrp.resolve.ActionResolution
import org.tatrman.ttrp.resolve.AutomationScope
import org.tatrman.ttrp.resolve.TargetResolution

/**
 * The recipe checker (AU-core-P1; AU contracts §4): a pass over the built graph and the resolved model, run after
 * `StructureValidator`. It checks:
 * - the role shape and wiring (REC-000);
 * - the action wiring, typed against `def action` (REC-001…003);
 * - the candidates (REC-004 / 005);
 * - key lineage and the mode verdict (REC-010…012);
 * - parameters (REC-020 / 021);
 * - transitions against `states:` (REC-030);
 * - the turn rule (REC-040);
 * - time typing ([RecipeTime], REC-050).
 *
 * The document-level shape (one recipe, DML, the decision body) is checked at parse time (`TtrpChecks`): a DML
 * island's TTRP-SQL-001 stops the compile before this pass would run.
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
    private val imports: List<ImportScope> = report.imports
    private val actionScope = report.automationScope

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
        // A program names its recipe; the recipe imports nothing of it (review-115 M2) — so the whole model.
        val programs =
            model
                ?.automationAll()
                .orEmpty()
                .filterIsInstance<AutomationProgram>()
                .filter { it.recipe == name }

        val forms =
            rg.roles
                .mapNotNull { (n, id) -> recipe.roleContainers.firstOrNull { it.name == n }?.let { id to it.form } }
                .toMap()
        val opaqueOut: (Container) -> Row? = { c -> forms[c.id]?.let { declaredRow(it) } }
        val time = RecipeTime(report, build, rg, imports, programs)
        val rows = RecipeRows(graph, report.loadSchemas, opaqueOut) { e, row -> time.scalarType(e, row) }
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
        checkIslandSchemas(recipe)
        checkRoleWiring(rg)
        val candidatesRow = candidatesId?.let { id -> container(id)?.let { outRow(rows, it) } }
        checkCandidates(recipe, rg, candidatesRow, keyColumns)

        val contexts = recipe.contexts.map { classify(it, rg, lineage, rows) }
        val mode = modeOf(contexts)
        checkMode(recipe, contexts, mode, programs)
        checkWindowBy(recipe, rg, rows, keyColumns)
        checkDecisionLineage(rg, lineage, keyColumns)

        val routeRow = routeRows(rg, rows)
        checkKeyCarried(recipe, rg, rows, contexts, routeRow, keyColumns)
        checkRelationJoins(rg, rows)
        val routeOrigin =
            rg.switch?.let { rows.inputOrigin(it) }
                ?: candidatesId?.takeIf { rg.decision == null }?.let { id ->
                    container(id)?.let { outOrigin(rows, it) }
                }
                ?: emptyMap()
        val turns =
            rg.roles
                .filter { (n, _) ->
                    recipe.contexts.any { it.name == n && it.form is IslandForm.Turn }
                }.values
                .toSet()
        val taint = TurnTaint(graph, rows, turns, keyColumns)
        val turnFed = turnFedRoutes(recipe, rg, taint)
        checkWiring(recipe, routeRow, routeOrigin, turnFed, rg.switch?.let { taint.input(it) }.orEmpty(), taint)
        checkRouteAttributes(recipe, routeRow)
        checkParameters(recipe, rg, programs, keyColumns)
        diags += time.check(rows)

        val sw = recipe.decisions.firstOrNull()?.switch
        val recipeModel =
            RecipeModel(
                name = name,
                keyAttributes = recipe.key.map { it.text },
                keyColumns = keyColumns,
                candidates = recipe.candidates.firstOrNull()?.name,
                candidateKind = recipe.candidates.firstOrNull()?.let(::roleKind),
                contexts = contexts,
                decision = recipe.decisions.firstOrNull()?.name,
                routes =
                    sw?.routes.orEmpty().map {
                        RouteInfo(it.name, it.because, it.detail.map { d -> d.text }, it.name in turnFed)
                    } +
                        listOfNotNull(
                            sw?.elseRoute?.let { e ->
                                e.name?.let { n ->
                                    RouteInfo(
                                        n,
                                        e.because,
                                        e.detail.map { d ->
                                            d.text
                                        },
                                        n in turnFed,
                                        isElse = true,
                                    )
                                }
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
                            w.whenCondition?.let {
                                org.tatrman.ttrp.expr.ExprText
                                    .render(it)
                            },
                        )
                    },
                parameters = recipe.parameters.map { it.text },
                mode = mode,
                programs = programs.map { it.qname.name },
                routeRow = routeRow,
                calendarSkWithins = time.calendarSkWithins.toSet(),
                implicitLatestDates = time.implicitLatestDates.toMap(),
                dateArithmetic = time.dateArithmetic.toMap(),
                elseNone = sw?.elseRoute?.let { it.name == null } ?: false,
                kinds =
                    recipe.roleContainers
                        .map {
                            when (it.form) {
                                is IslandForm.Target -> "sql"
                                is IslandForm.Builtin -> "builtin"
                                is IslandForm.Foreign -> "foreign"
                                is IslandForm.Call -> "call"
                                is IslandForm.Turn -> "turn"
                            }
                        }.distinct()
                        .sorted(),
                programPackage =
                    programs
                        .firstOrNull()
                        ?.qname
                        ?.`package`
                        ?.ifEmpty { null },
                document = recipe.document,
                asOf =
                    when (val a = recipe.asOf) {
                        org.tatrman.ttrp.ast.AsOf.Now -> "now"
                        is org.tatrman.ttrp.ast.AsOf.Instant -> a.text
                    },
                protocol = recipe.protocol?.spelling ?: "always",
                parameterDecls =
                    recipe.parameters.map { p ->
                        val decl = automation<AutomationParameter>().firstOrNull { it.qname.name == p.text }
                        ParameterInfo(p.text, decl?.type, decl?.value?.let { autoValueText(it) })
                    },
                dryRuns =
                    recipe.wirings
                        .map { it.action }
                        .distinct()
                        .mapNotNull { a -> action(a)?.let { a to it.dryRun.byLanguage } }
                        .filter { it.second.isNotEmpty() }
                        .toMap(),
            )
        return Result(recipeModel, diags.toList())
    }

    // ---- roles -------------------------------------------------------------------------------------------

    /**
     * 06 §1.2's shape:
     * - exactly one `candidates`, with no `in` port and one `out` (the one that carries the key);
     * - at most one `decision`;
     * - an `actions` block;
     * - every role named once, and one out port per context.
     */
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
        recipe.candidates.firstOrNull()?.let { c ->
            c.ports.firstOrNull { it.kind == org.tatrman.ttrp.ast.PortKind.IN }?.let {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_000,
                        "the candidates read nothing of the recipe — no `in` port",
                        it.location,
                    )
            }
            c.ports.filter { it.kind == org.tatrman.ttrp.ast.PortKind.OUT }.drop(1).forEach {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_000,
                        "a second `out` port on the candidates — one port carries the key; a computation over the " +
                            "candidates is a `context`",
                        it.location,
                    )
            }
        }
        recipe.contexts.forEach { c ->
            c.ports.filter { it.kind == org.tatrman.ttrp.ast.PortKind.OUT }.drop(1).forEach {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_000,
                        "context `${c.name}` has a second `out` port — a context has one",
                        it.location,
                    )
            }
        }
        recipe.roleContainers.groupBy { it.name }.filterValues { it.size > 1 }.forEach { (n, cs) ->
            cs.drop(1).forEach {
                diags +=
                    err(TtrpDiagnosticId.REC_000, "a second role container named `$n`", it.location)
            }
        }
        val candidatesOut =
            recipe.candidates.flatMap { c ->
                c.ports
                    .filter {
                        it.kind ==
                            org.tatrman.ttrp.ast.PortKind.OUT
                    }.map { it.name }
            }
        recipe.contexts.filter { it.name in candidatesOut }.forEach {
            diags +=
                err(
                    TtrpDiagnosticId.REC_000,
                    "context `${it.name}` has the name of the candidates' out port",
                    it.location,
                )
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

    /** A `foreign … schema s`, `turn … answer s` or `call` service must resolve — an island's declared rows. */
    private fun checkIslandSchemas(recipe: RecipeDecl) {
        for (role in recipe.roleContainers) {
            val f = role.form
            val missing =
                when (f) {
                    is IslandForm.Foreign ->
                        f.schema
                            .takeIf {
                                rowSchema(
                                    it,
                                ) == null
                            }?.let { "no `def schema $it` in the imports" }
                    is IslandForm.Turn ->
                        f.answerSchema
                            .takeIf {
                                rowSchema(
                                    it,
                                ) == null
                            }?.let { "no `def schema $it` in the imports for the answer" }
                    is IslandForm.Call -> {
                        val service =
                            automation<AutomationService>().firstOrNull {
                                it.qname.name ==
                                    f.service.parts.last()
                            }
                        when {
                            service == null -> "no `def service ${f.service.text}` in the imports"
                            service.response != null && rowSchema(service.response!!) == null ->
                                "service `${f.service.text}`'s response `${service.response}` is no `def schema` of the imports"
                            else -> null
                        }
                    }
                    else -> null
                }
            missing?.let { diags += err(TtrpDiagnosticId.RES_001, "`${role.name}`: $it", f.location) }
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

    // ---- the key on every keyed output (REC-004, review-119 B4) ------------------------------------------

    /**
     * A per-candidate context's rows and the routes' rows are filtered on the run's key by the runner (D-135,
     * AU-C-3), so each carries every key column under its own name — key lineage follows a rename (`order_number AS
     * onum` stays per candidate), the runner's filter does not.
     */
    private fun checkKeyCarried(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        rows: RecipeRows,
        contexts: List<ContextInfo>,
        routeRow: Row?,
        keyColumns: List<String>,
    ) {
        for (info in contexts.filter { it.contextClass == ContextClass.PER_CANDIDATE }) {
            val role = recipe.contexts.firstOrNull { it.name == info.name } ?: continue
            val row = rg.roles[info.name]?.let { container(it) }?.let { outRow(rows, it) } ?: continue
            val missing = keyColumns.filter { it !in row.keys }
            if (missing.isNotEmpty()) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_004,
                        "the context `${info.name}` is per candidate but its rows do not carry the key " +
                            "`${missing.joinToString(", ")}` under that name — the runner filters them on it",
                        role.location,
                    )
            }
        }
        val decision = recipe.decisions.firstOrNull() ?: return
        val missing = keyColumns.filter { routeRow != null && it !in routeRow.keys }
        if (missing.isNotEmpty()) {
            diags +=
                err(
                    TtrpDiagnosticId.REC_004,
                    "the rows entering `${decision.name}`'s switch do not carry the key `${missing.joinToString(
                        ", ",
                    )}` " +
                        "under that name — every route's rows must",
                    decision.location,
                )
        }
    }

    // ---- `on: relation R` over a port's rows (RES-004, review-119 m5) -----------------------------------------

    /**
     * A join whose condition is inferred from a relation reads the relation's attribute names on the side a port
     * feeds (the other side is a `load` of the entity, read by its bound columns), so that side's rows must carry
     * them — else the condition names a column no row has. The front half cannot tell: it does not know a role's
     * input rows. Only the names are checked, not that the rows ARE the relation's entity: 17's return lines carry
     * the request's `customer` for `rel_line_customer`.
     */
    private fun checkRelationJoins(
        rg: RecipeGraph,
        rows: RecipeRows,
    ) {
        for ((joinId, relation) in rg.relationJoins) {
            val join = graph.nodes[joinId] as? org.tatrman.ttrp.graph.model.Join ?: continue
            val on = join.on ?: continue
            for (side in listOf(PortNames.LEFT, PortNames.RIGHT)) {
                val feed = rows.feed(PortRef(joinId, side)) ?: continue
                if (graph.nodes[feed.nodeId] is org.tatrman.ttrp.graph.model.Load) continue
                val row = rows.of(feed) ?: continue
                val missing =
                    NodeExpressions
                        .walk(on)
                        .filterIsInstance<ColumnRef>()
                        .filter { it.port == side && it.column !in row.keys }
                        .map { it.column }
                        .distinct()
                if (missing.isNotEmpty()) {
                    diags +=
                        err(
                            TtrpDiagnosticId.RES_004,
                            "`on: relation $relation` joins on `${missing.joinToString(
                                "`, `",
                            )}`, which the $side rows " +
                                "do not carry — they are not that relation's rows; write the `on:` condition",
                            join.location,
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

    private fun roleKind(role: RoleContainer): String =
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

    private fun classify(
        role: RoleContainer,
        rg: RecipeGraph,
        lineage: KeyLineage,
        rows: RecipeRows,
    ): ContextInfo {
        val kind = roleKind(role)
        val c = rg.roles[role.name]?.let { container(it) }
        val out = c?.let { outPort(it) }?.let { lineage.of(PortRef(c.id, it)) } ?: Lineage.LOOKUP
        val cls =
            when {
                out.keyed -> ContextClass.PER_CANDIDATE
                out.readsCandidates -> ContextClass.SET_LEVEL
                else -> ContextClass.LOOKUP
            }
        val lost = out.lostAt.takeIf { cls == ContextClass.SET_LEVEL }
        return ContextInfo(role.name, kind, cls, role.window, lost?.what, lost?.location, c?.let { outRow(rows, it) })
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
        val perKey = programs.filter { it.mode == "on_event" || it.port == "key" || it.port == "keys" }
        for (ctx in contexts) {
            val role = recipe.contexts.first { it.name == ctx.name }
            when (ctx.contextClass) {
                ContextClass.SET_LEVEL ->
                    if (ctx.window == null) {
                        val loc = ctx.lostKeyLocation ?: role.location
                        val why = ctx.lostKeyAt ?: "an op loses the key"
                        val base = "context `${ctx.name}` reads the candidates but $why → set-level"
                        diags +=
                            if (perKey.isNotEmpty()) {
                                err(
                                    TtrpDiagnosticId.REC_010,
                                    "$base, and program `${perKey.first().qname.name}` runs it per event or per " +
                                        "key — " +
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
            // 06 A-5: a set-level recipe cannot run for one key, nor for an arbitrary set of keys (only an unwindowed
            // one; a windowed recipe's key-set batch is REC-011's).
            val refused =
                programs.filter { it.port == "key" || (it.port == "keys" && mode == RecipeMode.SET_LEVEL_BATCH_ONLY) }
            for (p in refused) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_012,
                        "program `${p.qname.name}` runs the recipe for " +
                            "${if (p.port == "key") "one key" else "a set of keys"} " +
                            "(`port: ${p.port}`), but the recipe is ${mode.spelling}",
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
                p.port != "keys" &&
                    when (over) {
                        is org.tatrman.ttrp.ast.WindowOver.Calendar ->
                            period != null && PERIODS.indexOf(period) >= PERIODS.indexOf(over.unit)
                        else -> false
                    }
            if (!ok) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_011,
                        "batch program `${p.qname.name}` (schedule " +
                            "${p.schedule ?: "none"}${if (p.port == "keys") ", `port: keys`" else ""}) " +
                            "does not cover whole windows of context `${ctx.name}`",
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

    /** T5: the decision's switch reads keyed rows — one per candidate, never one built from several. */
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

    /** The rows a route carries: the switch's input, or — for a decision-less recipe — the candidates'. */
    private fun routeRows(
        rg: RecipeGraph,
        rows: RecipeRows,
    ): Row? =
        if (rg.switch != null) {
            rows.input(rg.switch)
        } else if (rg.decision == null) {
            rg.recipe.candidates
                .firstOrNull()
                ?.let { rg.roles[it.name] }
                ?.let {
                    container(
                        it,
                    )
                }?.let { outRow(rows, it) }
        } else {
            null
        }

    private fun checkWiring(
        recipe: RecipeDecl,
        routeRow: Row?,
        routeOrigin: Map<String, String>,
        turnFed: Set<String>,
        switchTaint: Set<String>,
        taint: TurnTaint,
    ) {
        val decision = recipe.decisions.firstOrNull()
        val sw = decision?.switch
        val routeNames = if (decision == null) listOf(IDENTITY) else sw?.routeNames.orEmpty()
        val consumed = mutableSetOf<String>()
        val covered = recipe.wirings.any { action(it.action)?.let(AutomationScope::hasDefaults) == true }
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
            val action = resolveAction(w.action, w.actionLocation)
            if (action == null) {
                continue
            }
            if (covered) checkActionTargets(action, w.actionLocation)
            val ctx = WiringContext(routeRow, routeOrigin)
            checkArgs(action, w.args, ctx, w.actionLocation)
            checkTransition(action, w.args, routeRow, w.actionLocation)
            checkTurnRule(w, action, turnFed, switchTaint, taint)
        }
        for (r in routeNames - consumed) {
            val loc =
                sw?.routes?.firstOrNull { it.name == r }?.nameLocation ?: sw?.elseRoute?.location ?: recipe.location
            val what = if (r == IDENTITY) "the recipe's identity verdict (`<candidates>.all`)" else "route `$r`"
            diags += err(TtrpDiagnosticId.REC_003, "$what is wired to no action", loc)
        }
    }

    private fun resolveAction(
        name: String,
        at: SourceLocation,
    ): AutomationAction? =
        when (val resolved = actionScope?.action(name)) {
            is ActionResolution.Resolved -> resolved.action
            is ActionResolution.Ambiguous -> {
                diags += err(TtrpDiagnosticId.REC_007, "ambiguous action `$name` in the imported packages", at)
                null
            }
            else -> {
                diags += err(TtrpDiagnosticId.REC_001, "no action `$name` in the imported packages", at)
                null
            }
        }

    private fun checkActionTargets(
        action: AutomationAction,
        at: SourceLocation,
    ) {
        val targets = mutableMapOf<String, org.tatrman.ttr.metadata.model.QualifiedName>()
        for (ref in AutomationScope.targetRefs(action)) {
            val result = actionScope?.target(ref)
            val reason =
                when (result) {
                    is TargetResolution.Resolved -> {
                        val previous = targets.put(result.entity.qname.name, result.entity.qname)
                        if (previous != null && previous != result.entity.qname) "ambiguous" else null
                    }
                    is TargetResolution.Ambiguous -> "ambiguous"
                    else -> "unresolved"
                }
            if (reason != null) {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_006,
                        "$reason default-covered action target `$ref` on `${action.qname.name}` in the recipe scope",
                        at,
                    )
            }
        }
    }

    /**
     * REC-040 (D-32): a wiring decided on an agent's answer — its route's condition, the rows the route was chosen
     * from, or its own `when:` — fires only actions that need approval, and no unapproved `on_due:` either.
     */
    private fun checkTurnRule(
        w: Wiring,
        action: AutomationAction,
        turnFed: Set<String>,
        switchTaint: Set<String>,
        taint: TurnTaint,
    ) {
        val fedRoute = w.routes.firstOrNull { (it.isAll && turnFed.isNotEmpty()) || it.route in turnFed }
        val fedWhen = w.whenCondition?.takeIf { taint.reads(it, switchTaint) }
        if (fedRoute == null && fedWhen == null) return
        val why =
            fedRoute?.let { "route `${it.route}` decides on an agent's answer" }
                ?: "its `when:` reads an agent's answer"
        if (action.approval != "required") {
            diags +=
                err(TtrpDiagnosticId.REC_040, "$why and fires `${w.action}`, which needs no approval", w.actionLocation)
        }
        for (a in w.args.filter { it.name == "on_due" }) {
            val call = a.value as? WiringValue.Call ?: continue
            val onDue = action(call.action) ?: continue
            if (onDue.approval != "required") {
                diags +=
                    err(
                        TtrpDiagnosticId.REC_040,
                        "$why, and its `on_due:` fires `${call.action}` unapproved when the ask runs out",
                        a.location,
                    )
            }
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

    /** What a wiring's values are read against: the route's rows and their provenance. */
    private data class WiringContext(
        val row: Row?,
        val origin: Map<String, String>,
    )

    private fun checkArgs(
        action: AutomationAction,
        args: List<WiringArg>,
        ctx: WiringContext,
        at: SourceLocation,
        partial: Boolean = false,
    ) {
        val columns = action.columns.associateBy { it.name }
        for (a in args) {
            val col = columns[a.name]
            if (col == null) {
                if (a.name in WIRING_PROPERTIES) {
                    (a.value as? WiringValue.Call)?.let { call -> checkCall(call, ctx) }
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
            checkType(action, col, a, ctx)
        }
        if (partial) return
        for (c in action.columns.filter { !it.optional }) {
            if (args.none { it.name == c.name }) {
                diags += err(TtrpDiagnosticId.REC_001, "action `${action.qname.name}` needs its column `${c.name}`", at)
            }
        }
    }

    /** An action call nested in a wiring — `on_due: hold { … }` (D-98), or an ask's `action: start { … }`. */
    private fun checkCall(
        call: WiringValue.Call,
        ctx: WiringContext,
        partial: Boolean = false,
    ) {
        val action = resolveAction(call.action, call.location)
        if (action == null) {
            return
        }
        checkArgs(action, call.args, ctx, call.location, partial)
        checkTransition(action, call.args, ctx.row, call.location)
    }

    private fun checkType(
        action: AutomationAction,
        col: AutomationColumn,
        arg: WiringArg,
        ctx: WiringContext,
    ) {
        val v = arg.value
        val expr = (v as? WiringValue.Expr)?.expression
        val ref = expr as? ColumnRef
        val row = ctx.row

        fun mismatch(why: String) {
            diags +=
                err(TtrpDiagnosticId.REC_002, "`${action.qname.name}.${col.name}` (${col.type}) $why", arg.location)
        }
        when (col.type) {
            "role" -> {
                val role = ref?.takeIf { it.port == "role" }?.column
                when {
                    role == null -> mismatch("takes a role, written `role.<name>`")
                    automation<AutomationRole>().none { it.qname.name == role } ->
                        mismatch("names `role.$role`, which no `def role` defines")
                }
            }
            "rows" -> {
                val items =
                    when (v) {
                        is WiringValue.Values -> v.items
                        is WiringValue.Expr -> listOf(v.expression)
                        is WiringValue.Call -> emptyList()
                    }
                val bad =
                    items.filterNot {
                        (it as? ColumnRef)?.let { r -> r.port == null && r.column in roleNames } ==
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
                    // `action: start { process: "purchasing" }` — the asked action, partly applied: the answer to
                    // the ask completes it, so a column it leaves out is not missing (H-3's ask).
                    v is WiringValue.Call -> checkCall(v, ctx, partial = true)
                    ref == null ||
                        ref.port != null ||
                        action(
                            ref.column,
                        ) == null -> mismatch("takes an action the imports define")
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
                        ref != null && ref.port == null && row != null && ref.column in row -> {
                            // An attribute-typed column takes that attribute (or one an `on:` pair equates to it).
                            if ('.' in col.type) {
                                val src = ctx.origin[ref.column]
                                if (src != null && !sameAttribute(src, col.type)) {
                                    mismatch("takes `${col.type}`; `${ref.column}` is `$src`")
                                    return
                                }
                            }
                            row[ref.column]
                        }
                        ref != null && ref.port == null && row != null && ref.column !in row -> {
                            symbol(action, col, ref, row, ::mismatch)
                            return
                        }
                        else -> null
                    }
                if (got != null && !compatible(want, got)) mismatch("gets a ${got.canonical} value")
            }
        }
    }

    /**
     * A bare name the route's rows do not carry is a **symbol** — 17 writes `to: rejected`, `kind: inbound`. A
     * symbol is text only. It is checked against the vocabulary when the model has one:
     * - a transition's target column → REC-030;
     * - the column a `set:` writes → that attribute's `states:` / value labels.
     * A symbol one edit away from a row column is more likely a misspelt column: a warning.
     */
    private fun symbol(
        action: AutomationAction,
        col: AutomationColumn,
        ref: ColumnRef,
        row: Row,
        mismatch: (String) -> Unit,
    ) {
        val want = columnType(col.type)
        if (want != null && want.kind != TtrpType.Kind.STRING) {
            mismatch("reads `${ref.column}`, which the route's rows do not carry")
            return
        }
        if (action.transition?.toColumn == col.name) return
        val set = action.set?.takeIf { it.column == col.name }
        val vocabulary =
            set?.let { s -> attribute(s.attribute) }?.let { a -> a.states + a.valueLabels.keys }.orEmpty()
        if (vocabulary.isNotEmpty()) {
            if (ref.column !in vocabulary) {
                mismatch(
                    "is `${ref.column}`, which `${set!!.attribute}` does not list (${vocabulary.joinToString(", ")})",
                )
            }
            return
        }
        row.keys.firstOrNull { editDistance(it, ref.column) <= 2 }?.let { near ->
            diags +=
                warn(
                    TtrpDiagnosticId.REC_002,
                    "`${action.qname.name}.${col.name}` reads `${ref.column}` as the text \"${ref.column}\" — " +
                        "did you " +
                        "mean the column `$near`? Quote a text: `\"${ref.column}\"`",
                    ref.location,
                )
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
                when (val v = (args.firstOrNull { it.name == c }?.value as? WiringValue.Expr)?.expression) {
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

    /** The routes decided on an agent's answer: the switch's rows are turn-chosen, or the condition reads an answer. */
    private fun turnFedRoutes(
        recipe: RecipeDecl,
        rg: RecipeGraph,
        taint: TurnTaint,
    ): Set<String> {
        val sw = recipe.decisions.firstOrNull()?.switch ?: return emptySet()
        val inp = rg.switch?.let { taint.input(it) }.orEmpty()
        val fed =
            sw.routes
                .filter { taint.reads(it.condition, inp) }
                .map { it.name }
                .toMutableSet()
        // The else route takes what no condition took — decided on the answer when any condition was.
        sw.elseRoute?.name?.let { if (fed.isNotEmpty() || TurnTaint.ALL in inp) fed += it }
        return fed
    }

    /** A route's `detail:` names a role container; a `because:` fill names a column of its rows or a parameter. */
    private fun checkRouteAttributes(
        recipe: RecipeDecl,
        routeRow: Row?,
    ) {
        val sw = recipe.decisions.firstOrNull()?.switch ?: return
        val attrs =
            sw.routes.map { Triple(it.name, it.detail, it.because) } +
                listOfNotNull(sw.elseRoute?.let { Triple(it.name ?: "else", it.detail, it.because) })
        for ((route, detail, because) in attrs) {
            detail.filter { it.text !in roleNames }.forEach {
                diags +=
                    err(TtrpDiagnosticId.REC_000, "route `$route`'s `detail: ${it.text}` names no context", it.location)
            }
            if (because == null || routeRow == null) continue
            for (fill in FILL.findAll(because).map { it.groupValues[1] }) {
                if (fill !in routeRow.keys && recipe.parameters.none { it.text == fill }) {
                    diags +=
                        err(
                            TtrpDiagnosticId.REC_020,
                            "route `$route`'s `because:` fills `{$fill}`, which is neither a column of its rows nor a parameter",
                            sw.routes.firstOrNull { it.name == route }?.location ?: sw.location,
                        )
                }
            }
        }
    }

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
        return texts.flatMap { t -> FILL.findAll(t).map { it.groupValues[1] } }.toSet()
    }

    // ---- the model -----------------------------------------------------------------------------------------

    private val roleNames: Set<String> by lazy {
        build.recipe
            ?.recipe
            ?.roleContainers
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
    }

    private inline fun <reified T> automation(): List<T> =
        model?.automationInScope(imports)?.filterIsInstance<T>().orEmpty()

    private fun action(name: String): AutomationAction? =
        (actionScope?.action(name) as? ActionResolution.Resolved)?.action

    /** `order.state` → the attribute of the entity the recipe sees by that name (imports, then the stdlib). */
    private fun attribute(path: String): Attribute? =
        (actionScope?.target(path) as? TargetResolution.Resolved)?.attribute

    /** A `def column`'s type spelling as a type: a scalar, or an `<entity>.<attr>` reference's type. */
    private fun columnType(spelling: String): TtrpType? =
        if ('.' in spelling) {
            attribute(spelling)?.type?.let { TtrpType.parse(it) }
        } else {
            TtrpType.parse(spelling).takeUnless { it is TtrpType.Named }
        }

    /**
     * True when the attribute [src] is [want], or names the same thing: `on:` pairs of the model's relations connect
     * them, directly (an FK and its key: `return_request.customer` → `customer.sk`) or through a shared key
     * (`sales_line.item` → `item.sk` ← `reorder_proposal.item`).
     */
    private fun sameAttribute(
        src: String,
        want: String,
    ): Boolean {
        fun canonical(path: String): String =
            (actionScope?.target(path) as? TargetResolution.Resolved)?.let {
                "${it.entity.qname.name}.${it.attribute.qname.name.substringAfterLast('.')}"
            } ?: path
        val from = canonical(src)
        val to = canonical(want)
        if (from == to) return true
        val links = attributeLinks
        val seen = mutableSetOf(from)
        var frontier = setOf(from)
        repeat(3) {
            frontier = frontier.flatMap { links[it].orEmpty() }.filter { seen.add(it) }.toSet()
            if (to in frontier) return true
        }
        return false
    }

    /** The model's `on:` pairs as an undirected graph of attributes (`entity.attribute`). */
    private val attributeLinks: Map<String, Set<String>> by lazy {
        val out = HashMap<String, MutableSet<String>>()
        model?.allRelations().orEmpty().forEach { rel ->
            rel.joinPairs.forEach { p ->
                out.getOrPut(p.fromAttr.name) { mutableSetOf() } += p.toAttr.name
                out.getOrPut(p.toAttr.name) { mutableSetOf() } += p.fromAttr.name
            }
        }
        out
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

    private fun outOrigin(
        rows: RecipeRows,
        c: Container,
    ): Map<String, String>? = outPort(c)?.let { rows.origin(PortRef(c.id, it)) }

    /** A `def parameter`'s value as written — what manifest v3 records and the `values` fingerprint hashes (D-13). */
    private fun autoValueText(v: org.tatrman.ttr.parser.model.AutoValue): String? =
        when (v) {
            is org.tatrman.ttr.parser.model.AutoValue.Str -> v.raw
            is org.tatrman.ttr.parser.model.AutoValue.Num -> v.text
            is org.tatrman.ttr.parser.model.AutoValue.Bool -> v.raw.toString()
            // the amount as written (`2.5 days` stays 2.5 — review-119 m7)
            is org.tatrman.ttr.parser.model.AutoValue.Duration ->
                (if (v.amount % 1.0 == 0.0) v.amount.toLong().toString() else v.amount.toString()) + " ${v.unit}"
            is org.tatrman.ttr.parser.model.AutoValue.Id -> v.parts.joinToString(".")
            else -> null
        }

    /** A wiring value in TTR-P's spelling, a call with its arguments (manifest v3 `wiring[].columns`). */
    private fun render(v: WiringValue): String =
        when (v) {
            is WiringValue.Expr ->
                org.tatrman.ttrp.expr.ExprText
                    .render(v.expression)
            is WiringValue.Values ->
                v.items.joinToString(", ") {
                    org.tatrman.ttrp.expr.ExprText
                        .render(it)
                }
            is WiringValue.Call -> "${v.action} { ${v.args.joinToString(", ") { "${it.name}: ${render(it.value)}" }} }"
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

        /** The route of a decision-less recipe: every candidate (06 §1.2's identity verdict). */
        private const val IDENTITY = "all"

        /** Wiring arguments that set an action's properties rather than a column (D-98, the `ask` grouping). */
        val WIRING_PROPERTIES = setOf("due", "on_due", "group_by")

        val DURATION_FUNCTIONS = DurationUnit.entries.map { it.spelling }.toSet()

        private val PERIODS = listOf("day", "week", "month")

        /** A `because:` fill — `{column}` or `{parameter}` (D-108); shared with the switch lowering (AU-core-P2). */
        internal val FILL = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

        /** Every expression of a node the checker reads for `:name` and types. */
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

        /** Levenshtein distance — a misspelt column's tell. */
        fun editDistance(
            a: String,
            b: String,
        ): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val t = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = t
                }
            }
            return d[b.length]
        }
    }
}
