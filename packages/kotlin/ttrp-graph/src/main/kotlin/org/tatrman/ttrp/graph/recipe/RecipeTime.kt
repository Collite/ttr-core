// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.build.GraphBuilder
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.RecipeGraph
import org.tatrman.ttrp.graph.model.TimeSugar
import org.tatrman.ttrp.resolve.ImportScope
import org.tatrman.ttrp.resolve.TtrpChecker

/** A recipe expression's type: a scalar (null = not known here), or a duration in a unit (D-108). */
sealed interface RecipeType {
    data class Scalar(
        val type: TtrpType?,
    ) : RecipeType

    data class Duration(
        val unit: DurationUnit,
    ) : RecipeType

    companion object {
        val UNKNOWN = Scalar(null)

        /** How a row carries a duration-valued column (a row's types are [TtrpType]s). */
        fun asColumnType(t: RecipeType): TtrpType? =
            when (t) {
                is Scalar -> t.type
                is Duration -> TtrpType.Named(DURATION_PREFIX + t.unit.spelling)
            }

        fun ofColumnType(t: TtrpType?): RecipeType {
            val name = (t as? TtrpType.Named)?.name ?: return Scalar(t)
            if (!name.startsWith(DURATION_PREFIX)) return Scalar(t)
            return DurationUnit.of(name.removePrefix(DURATION_PREFIX))?.let { Duration(it) } ?: Scalar(t)
        }

        private const val DURATION_PREFIX = "duration:"
    }
}

/**
 * Time in a recipe (AU-core-P1 · S1.3; D-17, D-29, D-33, D-82, D-108). Every expression of every island, route and
 * `when:` is typed against its node's input row. The same typer types the rows' computed columns ([scalarType], for
 * [RecipeRows]), so a computed date, number or duration keeps its type downstream.
 *
 * - **`:name`.** `:as_of` is the run's instant (`timestamp`), never a parameter. A `:name` is a declared parameter
 *   (typed by its `def parameter`) or a value the program binds (an event's or the port's column); anything else is
 *   REC-020 ([RecipeChecker]).
 * - **Durations and date arithmetic** (`days(30)`, `days(:p)`, a wiring's `2 working_days`):
 *   - `date ± days|weeks|months` → date; `timestamp ± duration` → timestamp; `date − date` → int;
 *   - durations add only in one unit;
 *   - a duration compares only with a number (a count of its unit);
 *   - working days need the calendar: only `working_days(between:, and:)` and a wiring's `due:` take them;
 *   - an integer next to a date reads as days, with a warning to write `days(n)`; a fraction there is an error;
 *   - `days(:p)` takes a `days`, `working_days` or `int` parameter.
 * - **Time functions.** `working_days(between: a, and: b)` and `age(since: t)` are integers over dates.
 * - **The sugar ops** (D-33, lowered at P2) keep their input's row:
 *   - `latest(by: d, per: k…)`: `by:` a date, `per:` columns of the row;
 *   - `within(attr, last: <duration>)` / `within(attr, from: <date>, to: <date>)`.
 * - **Dates.** A date is a `date` / `timestamp`, or an int **sk** the model leads to a calendar (an entity with
 *   `semantics { role: event_date }`) — through an `on:` pair of a relation, or an FK from the attribute's bound
 *   column to the calendar's table (D-82). Any other int is not a date.
 * - **REC-050** (a warning): an `aggregate` that sums or averages a snapshot measure (`aggregation: last`) across
 *   its snapshot date without a `latest` before it.
 *
 * Type errors are TTRP-TYP-001 (no implicit coercion across kinds); the int-as-days reading is its warning.
 */
class RecipeTime(
    private val report: TtrpChecker.Report,
    private val build: GraphBuilder.BuildResult,
    private val rg: RecipeGraph,
    private val imports: List<ImportScope>,
    /** The programs that run this recipe (found across the model). */
    private val programs: List<AutomationProgram>,
) {
    private val diags = mutableListOf<TtrpDiagnostic>()

    /**
     * AU-core-P2: the `within` nodes whose column is an int sk the model leads to the calendar (D-82) — the sugar
     * lowering joins the calendar for them, and filters the column itself for a date.
     */
    val calendarSkWithins = mutableSetOf<String>()

    /** Null while typing a row's columns: nothing is reported then. */
    private var sink: MutableList<TtrpDiagnostic>? = diags
    private val model = report.modelIndex
    private val graph = build.graph
    private val automation = model?.automationInScope(imports).orEmpty()
    private val parameters: Map<String, AutomationParameter> =
        automation.filterIsInstance<AutomationParameter>().associateBy { it.qname.name }
    private lateinit var rows: RecipeRows

    /** `:name` → its type: `as_of`, the recipe's parameters, the bound event / port columns. */
    private val bindings: Map<String, RecipeType> by lazy {
        val out = LinkedHashMap<String, RecipeType>()
        out[RecipeChecker.AS_OF] = RecipeType.Scalar(TtrpType.Timestamp)
        for (p in programs) {
            if (p.port == "event") {
                p.event?.let { e ->
                    model
                        ?.findRowSchemas(e.removePrefix("schema."), imports)
                        ?.firstOrNull()
                        ?.columns
                        ?.forEach { c -> out.putIfAbsent(c.name, RecipeType.Scalar(attributeType(c.type))) }
                }
            }
        }
        for (p in rg.recipe.parameters) parameters[p.text]?.let { out[p.text] = parameterType(it) }
        out
    }

    /** The type a computed column carries in a row — silent (the checking pass reports). */
    fun scalarType(
        e: Expression,
        row: Row?,
    ): TtrpType? {
        val saved = sink
        sink = null
        try {
            return RecipeType.asColumnType(type(e, row, null))
        } finally {
            sink = saved
        }
    }

    fun check(rows: RecipeRows): List<TtrpDiagnostic> {
        this.rows = rows
        val ids = (rg.roles.values + listOfNotNull(rg.decision)).flatMap { graph.containers[it]?.memberIds.orEmpty() }
        for (id in ids) {
            val node = graph.nodes[id] ?: continue
            val row = rows.input(node.id)
            val origin = rows.inputOrigin(node.id)
            when (node) {
                is TimeSugar -> sugar(node, row, origin)
                is Aggregate -> {
                    node.aggregations.forEach { type(it.value, row, origin) }
                    node.having?.let { type(it, row, origin) }
                    semiAdditive(node)
                }
                is Join -> {
                    node.on?.let { on ->
                        val l = rows.input(node.id, PortNames.LEFT)
                        val r = rows.input(node.id, PortNames.RIGHT)
                        val o = rows.inputOrigin(node.id, PortNames.RIGHT) + rows.inputOrigin(node.id, PortNames.LEFT)
                        type(on, if (l != null && r != null) r + l else null, o)
                    }
                }
                else -> RecipeChecker.nodeExpressions(node).forEach { type(it, row, origin) }
            }
        }
        rg.switch?.let { sw ->
            val routeRow = rows.input(sw)
            val origin = rows.inputOrigin(sw)
            for (w in rg.recipe.wirings) w.whenCondition?.let { type(it, routeRow, origin) }
        }
        return diags.toList()
    }

    // ---- expressions ------------------------------------------------------------------------------------

    /** Types [e] against [row] (with its columns' provenance [origin], when known); reports what does not type. */
    fun type(
        e: Expression,
        row: Row?,
        origin: Map<String, String>?,
    ): RecipeType =
        when (e) {
            is ColumnRef -> RecipeType.ofColumnType(row?.get(e.column))
            is Literal -> RecipeType.Scalar(literalType(e.value))
            is ParamRef -> bindings[e.name] ?: RecipeType.UNKNOWN
            is DurationLiteral -> RecipeType.Duration(e.unit)
            is DurationParam -> durationParam(e)
            is FunctionCall -> call(e, row, origin)
            is AggregateCall -> aggregateCall(e, row, origin)
            is Cast -> {
                type(e.expr, row, origin)
                RecipeType.Scalar(e.target)
            }
            is CaseWhen -> {
                e.branches.forEach { (c, _) -> type(c, row, origin) }
                val values =
                    e.branches.map { type(it.second, row, origin) } +
                        listOfNotNull(e.elseExpr?.let { type(it, row, origin) })
                values.firstOrNull { it != RecipeType.UNKNOWN } ?: RecipeType.UNKNOWN
            }
            is InList -> {
                type(e.expr, row, origin)
                e.items.forEach { type(it, row, origin) }
                RecipeType.Scalar(TtrpType.Bool)
            }
            is IsNull -> {
                type(e.expr, row, origin)
                RecipeType.Scalar(TtrpType.Bool)
            }
            else -> RecipeType.UNKNOWN
        }

    private fun aggregateCall(
        e: AggregateCall,
        row: Row?,
        origin: Map<String, String>?,
    ): RecipeType {
        val args = e.args.map { type(it, row, origin) }
        val arg = (args.firstOrNull() as? RecipeType.Scalar)?.type
        return RecipeType.Scalar(
            when (e.function.name) {
                "count" -> TtrpType.Integer
                "min", "max" -> arg
                "sum" -> arg?.takeIf { it.kind == TtrpType.Kind.NUMERIC }
                "avg" -> arg?.takeIf { it.kind == TtrpType.Kind.NUMERIC }?.let { TtrpType.Decimal() }
                else -> null
            },
        )
    }

    private fun call(
        e: FunctionCall,
        row: Row?,
        origin: Map<String, String>?,
    ): RecipeType {
        val args = e.args.map { type(it, row, origin) }
        return when (e.function) {
            CatalogId.ADD, CatalogId.SUB -> arithmetic(e, args)
            CatalogId.MUL, CatalogId.DIV, CatalogId.NEG ->
                if (args.any { it is RecipeType.Duration }) {
                    err(
                        TtrpDiagnosticId.TYP_001,
                        "a duration is no factor — compute the number first (`days(2 * n)`)",
                        e.location,
                    )
                    RecipeType.UNKNOWN
                } else {
                    numeric(args)
                }
            CatalogId.EQ, CatalogId.NEQ, CatalogId.LT, CatalogId.LTE, CatalogId.GT, CatalogId.GTE -> {
                // A duration compares with a count in its unit (`days_open > :inbound_close`, 17 §4.2) — never with a
                // date, a text or a truth value.
                if (args.any { it is RecipeType.Duration } &&
                    args.any { (it as? RecipeType.Scalar)?.type?.let { t -> t.kind != TtrpType.Kind.NUMERIC } == true }
                ) {
                    err(
                        TtrpDiagnosticId.TYP_001,
                        "a duration compares with a number of its unit — add it to a date instead",
                        e.location,
                    )
                }
                RecipeType.Scalar(TtrpType.Bool)
            }
            CatalogId.AND, CatalogId.OR, CatalogId.NOT -> RecipeType.Scalar(TtrpType.Bool)
            else ->
                when (e.function.name) {
                    "working_days" -> temporalFunction(e, args, listOf("between", "and"), origin)
                    "age" -> temporalFunction(e, args, listOf("since"), origin)
                    // `minutes(2 * period_minutes)` — a duration whose amount is computed (the walker folds a
                    // literal or a `:param` amount; anything else stays this call).
                    in DURATION_UNITS -> {
                        val amount = (args.singleOrNull() as? RecipeType.Scalar)?.type
                        if (args.size != 1 || (amount != null && amount.kind != TtrpType.Kind.NUMERIC)) {
                            err(TtrpDiagnosticId.TYP_001, "`${e.function.name}(…)` takes one number", e.location)
                        }
                        RecipeType.Duration(DurationUnit.of(e.function.name)!!)
                    }
                    "coalesce", "abs", "round", "greatest", "least" ->
                        args.firstOrNull { it != RecipeType.UNKNOWN }
                            ?: RecipeType.UNKNOWN
                    else -> {
                        args.firstOrNull { it is RecipeType.Duration }?.let {
                            err(TtrpDiagnosticId.TYP_001, "`${e.function.name}` takes no duration", e.location)
                        }
                        RecipeType.UNKNOWN
                    }
                }
        }
    }

    private fun numeric(args: List<RecipeType>): RecipeType {
        val types = args.map { (it as? RecipeType.Scalar)?.type }
        if (types.any { it == null || it.kind != TtrpType.Kind.NUMERIC }) return RecipeType.UNKNOWN
        return RecipeType.Scalar(if (types.all { it is TtrpType.Integer }) TtrpType.Integer else TtrpType.Decimal())
    }

    /** `working_days(between: a, and: b)` · `age(since: t)` — integers over dates. */
    private fun temporalFunction(
        e: FunctionCall,
        args: List<RecipeType>,
        names: List<String>,
        origin: Map<String, String>?,
    ): RecipeType {
        val given = e.argNames
        if (given.size != names.size || given.zip(names).any { (g, n) -> g != n }) {
            err(
                TtrpDiagnosticId.FN_002,
                "`${e.function.name}` is called `${e.function.name}(${names.joinToString(", ") { "$it: …" }})`",
                e.location,
            )
        }
        args.forEachIndexed { i, t ->
            if (!isDate(t, e.args[i], origin)) {
                err(
                    TtrpDiagnosticId.TYP_001,
                    "`${e.function.name}(${names.getOrElse(i) { "?" }}: …)` takes a date or a timestamp",
                    e.args[i].location,
                )
            }
        }
        return RecipeType.Scalar(TtrpType.Integer)
    }

    /**
     * True unless [t] is known not to be a date: a date or timestamp, an int column the model leads to a calendar
     * (D-82), or a value whose type is not known here (silence, not a guess). An int literal is no date.
     */
    private fun isDate(
        t: RecipeType,
        e: Expression,
        origin: Map<String, String>?,
    ): Boolean {
        if (t is RecipeType.Duration) return false
        val st = (t as RecipeType.Scalar).type ?: return true
        if (st.kind == TtrpType.Kind.TEMPORAL) return true
        if (st !is TtrpType.Integer) return false
        if (origin == null) return true
        val attr = (e as? ColumnRef)?.let { origin[it.column] } ?: return false
        return skReachesCalendar(attr)
    }

    private fun arithmetic(
        e: FunctionCall,
        args: List<RecipeType>,
    ): RecipeType {
        if (args.size != 2) return RecipeType.UNKNOWN
        val (a, b) = args
        val sub = e.function == CatalogId.SUB
        val ta = (a as? RecipeType.Scalar)?.type
        val tb = (b as? RecipeType.Scalar)?.type
        if (a is RecipeType.Duration || b is RecipeType.Duration) return durationArithmetic(e, a, b, ta, tb, sub)
        if (ta == null || tb == null) return RecipeType.UNKNOWN
        val aTemporal = ta.kind == TtrpType.Kind.TEMPORAL
        val bTemporal = tb.kind == TtrpType.Kind.TEMPORAL
        return when {
            aTemporal && bTemporal ->
                if (sub) {
                    RecipeType.Scalar(TtrpType.Integer)
                } else {
                    err(TtrpDiagnosticId.TYP_001, "two dates do not add", e.location)
                    RecipeType.UNKNOWN
                }
            // An integer next to a date reads as days (either order for `+`; only `date - n` for `-`).
            (aTemporal && tb.kind == TtrpType.Kind.NUMERIC) ||
                (bTemporal && ta.kind == TtrpType.Kind.NUMERIC && !sub) -> {
                val n = if (aTemporal) e.args[1] else e.args[0]
                val num = if (aTemporal) tb else ta
                if (num !is TtrpType.Integer) {
                    err(
                        TtrpDiagnosticId.TYP_001,
                        "a fraction of a day next to a date — use whole days, or hours",
                        e.location,
                    )
                    return RecipeType.UNKNOWN
                }
                val raw = ((n as? Literal)?.value as? LiteralValue.Num)?.raw ?: "n"
                warn(
                    TtrpDiagnosticId.TYP_001,
                    "an integer next to a date is read as days — write `days($raw)`",
                    e.location,
                )
                RecipeType.Scalar(if (aTemporal) ta else tb)
            }
            aTemporal || bTemporal -> {
                err(TtrpDiagnosticId.TYP_001, "`${e.function.name}` on ${ta.canonical} and ${tb.canonical}", e.location)
                RecipeType.UNKNOWN
            }
            else -> numeric(args)
        }
    }

    private fun durationArithmetic(
        e: FunctionCall,
        a: RecipeType,
        b: RecipeType,
        ta: TtrpType?,
        tb: TtrpType?,
        sub: Boolean,
    ): RecipeType {
        if (a is RecipeType.Duration && b is RecipeType.Duration) {
            if (a.unit != b.unit) {
                err(
                    TtrpDiagnosticId.TYP_001,
                    "durations in ${a.unit.spelling} and ${b.unit.spelling} do not add — use one unit",
                    e.location,
                )
                return RecipeType.UNKNOWN
            }
            return a
        }
        val d = (if (b is RecipeType.Duration) b else a) as RecipeType.Duration
        val other = if (b is RecipeType.Duration) ta else tb
        if (d.unit == DurationUnit.WORKING_DAYS) {
            err(
                TtrpDiagnosticId.TYP_001,
                "working days need the calendar — use `working_days(between: …, and: …)`, or a wiring's `due:`",
                e.location,
            )
            return RecipeType.UNKNOWN
        }
        if (a is RecipeType.Duration && sub) {
            err(
                TtrpDiagnosticId.TYP_001,
                "a duration minus a date is no value — subtract the duration from the date",
                e.location,
            )
            return RecipeType.UNKNOWN
        }
        return when {
            other == null -> RecipeType.UNKNOWN
            other is TtrpType.Date ->
                if (d.unit == DurationUnit.MINUTES || d.unit == DurationUnit.HOURS) {
                    RecipeType.Scalar(TtrpType.Timestamp)
                } else {
                    RecipeType.Scalar(TtrpType.Date)
                }
            other.kind == TtrpType.Kind.TEMPORAL -> RecipeType.Scalar(other)
            else -> {
                err(
                    TtrpDiagnosticId.TYP_001,
                    "a duration adds to a date or a timestamp, not to ${other.canonical}",
                    e.location,
                )
                RecipeType.UNKNOWN
            }
        }
    }

    /** `days(:p)` / `working_days(:p)` — the parameter must be a `days`, `working_days` or `int` one. */
    private fun durationParam(e: DurationParam): RecipeType {
        val p = parameters[e.param]
        if (p != null && p.type !in setOf("days", "working_days", "int")) {
            err(
                TtrpDiagnosticId.TYP_001,
                "`${e.unit.spelling}(:${e.param})` takes a `days`, `working_days` or `int` parameter; `${e.param}` is ${p.type}",
                e.location,
            )
        }
        return RecipeType.Duration(e.unit)
    }

    private fun parameterType(p: AutomationParameter): RecipeType =
        when (p.type) {
            "days" -> RecipeType.Duration(DurationUnit.DAYS)
            "working_days" -> RecipeType.Duration(DurationUnit.WORKING_DAYS)
            "decimal" -> RecipeType.Scalar(TtrpType.Decimal())
            "int" -> RecipeType.Scalar(TtrpType.Integer)
            "text" -> RecipeType.Scalar(TtrpType.Str)
            "date" -> RecipeType.Scalar(TtrpType.Date)
            "bool" -> RecipeType.Scalar(TtrpType.Bool)
            else -> RecipeType.UNKNOWN
        }

    // ---- the sugar ops -----------------------------------------------------------------------------------

    private fun sugar(
        node: TimeSugar,
        row: Row?,
        origin: Map<String, String>,
    ) {
        when (node.kind) {
            "latest" -> {
                val by = node.columnsOf("by")
                if (by.size != 1) {
                    err(
                        TtrpDiagnosticId.FN_002,
                        "`latest` is called `latest(by: <date>, per: <columns>)`",
                        node.location,
                    )
                }
                for (c in by) {
                    val t = column(row, c, node, "latest(by:)") ?: continue
                    if (!isDate(RecipeType.Scalar(t), ColumnRef(null, c, node.location), origin)) {
                        err(
                            TtrpDiagnosticId.TYP_001,
                            "`latest(by: $c)` orders by a date (or an sk the model leads to the calendar); `$c` is not one",
                            node.location,
                        )
                    }
                }
                if (node.columnsOf("per").isEmpty()) {
                    err(
                        TtrpDiagnosticId.FN_002,
                        "`latest` needs `per:` — the columns one snapshot is the latest of",
                        node.location,
                    )
                }
                node.columnsOf("per").forEach { column(row, it, node, "latest(per:)") }
            }
            "within" -> within(node, row, origin)
        }
    }

    private fun within(
        node: TimeSugar,
        row: Row?,
        origin: Map<String, String>,
    ) {
        val attr = node.args.firstOrNull { it.first == null }?.second as? ColumnRef
        val named = node.args.mapNotNull { it.first }.toSet()
        if (attr == null || (named != setOf("last") && named != setOf("from", "to"))) {
            err(
                TtrpDiagnosticId.FN_002,
                "`within` is called `within(<date>, last: <duration>)` or `within(<date>, from: …, to: …)`",
                node.location,
            )
        }
        node.args.filter { it.first != null }.forEach { (n, e) ->
            val t = type(e, row, origin)
            when {
                n == "last" && t !is RecipeType.Duration && t != RecipeType.UNKNOWN ->
                    err(TtrpDiagnosticId.TYP_001, "`within(…, last:)` takes a duration — `weeks(8)`", e.location)
                (n == "from" || n == "to") && !isDate(t, e, origin) ->
                    err(TtrpDiagnosticId.TYP_001, "`within(…, $n:)` takes a date — `:as_of - months(12)`", e.location)
            }
        }
        if (attr != null) {
            val t = column(row, attr.column, node, "within") ?: return
            if (t.kind == TtrpType.Kind.NUMERIC &&
                isDate(RecipeType.Scalar(t), attr, origin)
            ) {
                calendarSkWithins += node.id
            }
            if (!isDate(RecipeType.Scalar(t), attr, origin)) {
                err(
                    TtrpDiagnosticId.TYP_001,
                    "`within` reads a date: `${attr.column}` is ${t.canonical}, and the model leads it to no " +
                        "calendar " +
                        "(an `event_date` entity)",
                    attr.location,
                )
            }
        }
    }

    /** [name]'s type in [row]; an unknown column is TTRP-EXP-001 (only input columns are in scope). */
    private fun column(
        row: Row?,
        name: String,
        node: Node,
        where: String,
    ): TtrpType? {
        if (row == null) return null
        if (name !in row) {
            err(TtrpDiagnosticId.EXP_001, "`$where` names `$name`, which its input does not carry", node.location)
            return null
        }
        return row[name]
    }

    /**
     * D-82 / S1.3 T5: the attribute [attr] (`entity.attribute`) is an sk of the calendar — a relation's `on:` pair
     * names it toward an entity with `semantics { role: event_date }`, or an FK runs from its bound column to that
     * entity's table.
     */
    private fun skReachesCalendar(attr: String): Boolean {
        val model = model ?: return false
        val parts = attr.split('.', limit = 2)
        if (parts.size != 2) return false
        val (entityName, attrName) = parts
        val entity = model.entityInScope(entityName, imports) ?: return false
        val attribute =
            model.attributesOf(entity).firstOrNull { it.qname.name.substringAfterLast('.') == attrName } ?: return false
        val calendars = model.calendars()
        val calendarNames = calendars.map { it.qname.name }.toSet()
        val byPair =
            model.relationsOf(entity).any { rel ->
                val fromHere = rel.fromEntity.name == entity.qname.name
                val other = if (fromHere) rel.toEntity.name else rel.fromEntity.name
                other in calendarNames &&
                    rel.joinPairs.any { p ->
                        (if (fromHere) p.fromAttr else p.toAttr).name.substringAfterLast('.') == attrName
                    }
            }
        if (byPair) return true
        val column = model.erToDb(attribute.qname).dbQname ?: return false
        val calendarTables =
            calendars
                .mapNotNull {
                    model
                        .erToDb(
                            it.qname,
                        ).dbQname
                        ?.name
                        ?.substringAfterLast('.')
                }.toSet()
        return model.foreignKeys().any { fk ->
            fk.fromColumns.any {
                it.name.substringAfterLast('.') == column.name.substringAfterLast('.') &&
                    sameTable(it.name, column.name)
            } &&
                fk.toColumns.any { it.name.substringBeforeLast('.').substringAfterLast('.') in calendarTables }
        }
    }

    /** Two column names (`table.col`, `schema.table.col` …) on the same table. */
    private fun sameTable(
        a: String,
        b: String,
    ): Boolean =
        a.substringBeforeLast('.').substringAfterLast('.') == b.substringBeforeLast('.').substringAfterLast('.')

    // ---- REC-050 ------------------------------------------------------------------------------------------

    /** An aggregate summing/averaging a snapshot measure across its snapshot date with no `latest` before it. */
    private fun semiAdditive(node: Aggregate) {
        val model = model ?: return
        val origin = rows.inputOrigin(node.id)
        for (a in node.aggregations) {
            val call = a.value as? AggregateCall ?: continue
            if (call.function.name !in setOf("sum", "avg")) continue
            val col = (call.args.firstOrNull() as? ColumnRef)?.column ?: continue
            val attr = origin[col] ?: continue
            if (throughLatest(node, col)) continue
            val entity = model.entityInScope(attr.substringBefore('.'), imports) ?: continue
            val measure =
                entity.mentionSemantics?.measures?.firstOrNull {
                    it.attribute.path == attr.substringAfter('.') && it.aggregation == "last"
                } ?: continue
            // The snapshot date: the entity's key attributes that are dates (or calendar sks).
            val snapshotDates =
                model
                    .entityAttributes(entity)
                    .filter { (name, type) ->
                        model.attributesOf(entity).any { it.isKey && it.qname.name.substringAfterLast('.') == name } &&
                            (
                                TtrpType.parse(type).kind == TtrpType.Kind.TEMPORAL ||
                                    skReachesCalendar("${entity.qname.name}.$name")
                            )
                    }.map { it.first }
            if (snapshotDates.isEmpty() || snapshotDates.any { it in node.groupBy }) continue
            diags +=
                TtrpDiagnostic(
                    TtrpDiagnosticId.REC_050,
                    Severity.WARNING,
                    "`${call.function.name}(${measure.attribute.path})` adds the snapshot measure of " +
                        "`${entity.qname.name}` across its snapshot dates (${snapshotDates.joinToString(", ")})",
                    a.value.location,
                )
        }
    }

    /** True when a `latest` stands between [node] and the load [column] comes from. */
    private fun throughLatest(
        node: Node,
        column: String,
    ): Boolean {
        var ref: PortRef = rows.feed(PortRef(node.id, PortNames.IN)) ?: return false
        repeat(MAX_TRACE) {
            val n = graph.nodes[ref.nodeId] ?: return false
            ref =
                when {
                    n is TimeSugar && n.kind == "latest" -> return true
                    n is Load || n is Aggregate -> return false
                    n is Container -> {
                        val port = n.declaredPorts.firstOrNull { it.name == ref.port }
                        if (port?.direction == PortDirection.IN) rows.feed(ref) else n.portMapping[ref.port]
                    }
                    n is Join ->
                        listOf(PortNames.LEFT, PortNames.RIGHT)
                            .firstOrNull { rows.input(n.id, it)?.containsKey(column) == true }
                            ?.let { rows.feed(PortRef(n.id, it)) }
                    n is Calc && n.assignments.any { it.name == column } -> return false
                    n is Project &&
                        n.columns.indices.any {
                            n.aliasOf(
                                it,
                            ) == column &&
                                n.columns[it] !is ColumnRef
                        } -> return false
                    else -> rows.feed(PortRef(n.id, PortNames.IN))
                } ?: return false
        }
        return false
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun attributeType(spelling: String): TtrpType? =
        if ('.' in spelling) {
            val (e, a) = spelling.split('.', limit = 2)
            model?.entityInScope(e, imports)?.let { ent ->
                model.entityAttributes(ent).firstOrNull { it.first == a }?.let { TtrpType.parse(it.second) }
            }
        } else {
            TtrpType.parse(spelling)
        }

    private fun literalType(v: LiteralValue): TtrpType? =
        when (v) {
            is LiteralValue.Str -> TtrpType.Str
            is LiteralValue.Num -> if ('.' in v.raw) TtrpType.Decimal() else TtrpType.Integer
            is LiteralValue.Bool -> TtrpType.Bool
            LiteralValue.Null -> null
        }

    private fun err(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
    ) {
        sink?.add(TtrpDiagnostic(id, Severity.ERROR, message, loc))
    }

    private fun warn(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
    ) {
        sink?.add(TtrpDiagnostic(id, Severity.WARNING, message, loc))
    }

    private companion object {
        const val MAX_TRACE = 200

        val DURATION_UNITS = DurationUnit.entries.map { it.spelling }.toSet()
    }
}
