// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationLiteral
import org.tatrman.ttrp.expr.DurationParam
import org.tatrman.ttrp.expr.DurationUnit
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
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
    }
}

/**
 * Time in a recipe (AU-core-P1 · S1.3; D-17, D-29, D-33, D-82, D-108): every expression of every island, route
 * and wiring typed against its node's input row, with the recipe's time words —
 *
 * - `:as_of` is the run's instant, `timestamp`, never a parameter; a `:name` is a declared parameter (typed by its
 *   `def parameter`) or a value the program binds (an event's or the port's column) — else REC-020 ([RecipeChecker]);
 * - a duration (`days(30)`, `days(:p)`, a wiring's `2 working_days`): `date ± days|weeks|months → date`,
 *   `timestamp ± duration → timestamp`, `date − date → int`; working days need the calendar — only
 *   `working_days(between:, and:)` and a wiring's `due:` take them; `date − 365` reads the integer as days, with a
 *   warning to write `days(365)`; `days(:p)` takes a `days`, `working_days` or `int` parameter;
 * - `working_days(between: a, and: b)` and `age(since: t)` are integers over two / one temporal value;
 * - the sugar ops (D-33, lowered at P2): `latest(by: d, per: k…)` keeps its input's row — `by:` orderable (a date,
 *   or an sk — the snapshot's surrogate date), `per:` columns of the row; `within(attr, last: <duration>)` /
 *   `within(attr, from: <t>, to: <t>)` keeps the row — `attr` a date, or an sk the model leads to a calendar
 *   (an `event_date`-roled entity), which the P2 lowering joins;
 * - REC-050 (warning): an `aggregate` that sums or averages a snapshot measure (`aggregation: last`) across its
 *   snapshot date without a `latest` before it.
 *
 * Type errors are TTRP-TYP-001 (no implicit coercion across kinds); the int-as-days reading is its warning.
 */
class RecipeTime(
    private val report: TtrpChecker.Report,
    private val build: GraphBuilder.BuildResult,
    private val rg: RecipeGraph,
    private val rows: RecipeRows,
    private val imports: List<ImportScope>,
    /** The recipe's name (its programs are found by it). */
    private val recipeName: String,
) {
    private val diags = mutableListOf<TtrpDiagnostic>()
    private val model = report.modelIndex
    private val graph = build.graph
    private val automation = model?.automationInScope(imports).orEmpty()
    private val parameters: Map<String, AutomationParameter> =
        automation.filterIsInstance<AutomationParameter>().associateBy { it.qname.name }

    /** `:name` → its type: `as_of`, the recipe's parameters, the bound event / port columns. */
    private val bindings: Map<String, RecipeType> by lazy {
        val out = LinkedHashMap<String, RecipeType>()
        out[RecipeChecker.AS_OF] = RecipeType.Scalar(TtrpType.Timestamp)
        for (p in automation.filterIsInstance<AutomationProgram>().filter { it.recipe == recipeName }) {
            if (p.port == "event") {
                p.event?.let { e ->
                    model
                        ?.findRowSchemas(e.removePrefix("schema."), imports)
                        ?.firstOrNull()
                        ?.columns
                        ?.forEach { c -> out.putIfAbsent(c.name, RecipeType.Scalar(columnType(c.type))) }
                }
            }
        }
        for (p in rg.recipe.parameters) parameters[p.text]?.let { out[p.text] = parameterType(it) }
        out
    }

    fun check(): List<TtrpDiagnostic> {
        val ids = (rg.roles.values + listOfNotNull(rg.decision)).flatMap { graph.containers[it]?.memberIds.orEmpty() }
        for (id in ids) {
            val node = graph.nodes[id] ?: continue
            when (node) {
                is TimeSugar -> sugar(node)
                is Aggregate -> {
                    val row = rows.input(node.id)
                    node.aggregations.forEach { type(it.value, row) }
                    node.having?.let { type(it, row) }
                    semiAdditive(node)
                }
                is Join -> node.on?.let { on -> type(on, joinRow(node)) }
                else -> {
                    val row = rows.input(node.id)
                    RecipeChecker.nodeExpressions(node).forEach { type(it, row) }
                }
            }
        }
        val routeRow = rg.switch?.let { rows.input(it) }
        for (w in rg.recipe.wirings) w.whenCondition?.let { type(it, routeRow) }
        return diags.toList()
    }

    // ---- expressions ------------------------------------------------------------------------------------

    /** Types [e] against [row]; reports what does not type. */
    fun type(
        e: Expression,
        row: Row?,
    ): RecipeType =
        when (e) {
            is ColumnRef -> RecipeType.Scalar(row?.get(e.column))
            is Literal ->
                RecipeType.Scalar(
                    when (val v = e.value) {
                        is LiteralValue.Str -> TtrpType.Str
                        is LiteralValue.Num -> if ('.' in v.raw) TtrpType.Decimal() else TtrpType.Integer
                        is LiteralValue.Bool -> TtrpType.Bool
                        LiteralValue.Null -> null
                    },
                )
            is ParamRef -> bindings[e.name] ?: RecipeType.UNKNOWN
            is DurationLiteral -> RecipeType.Duration(e.unit)
            is DurationParam -> durationParam(e)
            is FunctionCall -> call(e, row)
            is AggregateCall -> {
                e.args.forEach { type(it, row) }
                RecipeType.Scalar(if (e.function.name == "count") TtrpType.Integer else null)
            }
            else -> {
                RecipeChecker.walk(e).drop(1).forEach { if (it is FunctionCall || it is DurationParam) type(it, row) }
                RecipeType.UNKNOWN
            }
        }

    private fun call(
        e: FunctionCall,
        row: Row?,
    ): RecipeType {
        val args = e.args.map { type(it, row) }
        return when (e.function) {
            CatalogId.ADD, CatalogId.SUB -> arithmetic(e, args)
            CatalogId.EQ, CatalogId.NEQ, CatalogId.LT, CatalogId.LTE, CatalogId.GT, CatalogId.GTE,
            CatalogId.AND, CatalogId.OR, CatalogId.NOT,
            -> {
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
            else ->
                when (e.function.name) {
                    "working_days" -> temporalFunction(e, args, listOf("between", "and"))
                    "age" -> temporalFunction(e, args, listOf("since"))
                    // `minutes(2 * period_minutes)` — a duration whose amount is computed (the walker folds a
                    // literal or a `:param` amount; anything else stays this call).
                    in DURATION_UNITS -> {
                        val amount = (args.singleOrNull() as? RecipeType.Scalar)?.type
                        if (args.size != 1 || (amount != null && amount.kind != TtrpType.Kind.NUMERIC)) {
                            err(TtrpDiagnosticId.TYP_001, "`${e.function.name}(…)` takes one number", e.location)
                        }
                        RecipeType.Duration(DurationUnit.of(e.function.name)!!)
                    }
                    else -> {
                        args.firstOrNull { it is RecipeType.Duration }?.let {
                            err(TtrpDiagnosticId.TYP_001, "`${e.function.name}` takes no duration", e.location)
                        }
                        RecipeType.UNKNOWN
                    }
                }
        }
    }

    /** `working_days(between: a, and: b)` · `age(since: t)` — integers over temporal arguments. */
    private fun temporalFunction(
        e: FunctionCall,
        args: List<RecipeType>,
        names: List<String>,
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
            val st = (t as? RecipeType.Scalar)?.type
            if (t is RecipeType.Duration || (st != null && !temporalOrSk(st))) {
                err(
                    TtrpDiagnosticId.TYP_001,
                    "`${e.function.name}(${names.getOrElse(i) { "?" }}: …)` takes a date or a timestamp",
                    e.args[i].location,
                )
            }
        }
        return RecipeType.Scalar(TtrpType.Integer)
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
        if (a is RecipeType.Duration || b is RecipeType.Duration) {
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
            if (a is RecipeType.Duration && b is RecipeType.Duration) return d
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
        if (ta == null || tb == null) return RecipeType.UNKNOWN
        val aTemporal = ta.kind == TtrpType.Kind.TEMPORAL
        val bTemporal = tb.kind == TtrpType.Kind.TEMPORAL
        return when {
            aTemporal && bTemporal && sub -> RecipeType.Scalar(TtrpType.Integer)
            aTemporal && tb.kind == TtrpType.Kind.NUMERIC -> {
                diags +=
                    TtrpDiagnostic(
                        TtrpDiagnosticId.TYP_001,
                        Severity.WARNING,
                        "an integer next to a date is read as days — write `days(${(e.args[1] as? Literal)?.let { lit ->
                            (lit.value as? LiteralValue.Num)?.raw
                        } ?: "n"})`",
                        e.location,
                    )
                RecipeType.Scalar(ta)
            }
            aTemporal || bTemporal -> {
                err(TtrpDiagnosticId.TYP_001, "`${e.function.name}` on ${ta.canonical} and ${tb.canonical}", e.location)
                RecipeType.UNKNOWN
            }
            else ->
                RecipeType.Scalar(
                    if (ta.kind == TtrpType.Kind.NUMERIC &&
                        tb.kind == TtrpType.Kind.NUMERIC
                    ) {
                        ta
                    } else {
                        null
                    },
                )
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

    private fun sugar(node: TimeSugar) {
        val row = rows.input(node.id)
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
                by.forEach { c ->
                    column(row, c, node, "latest(by:)")?.let { t ->
                        if (!temporalOrSk(
                                t,
                            )
                        ) {
                            err(
                                TtrpDiagnosticId.TYP_001,
                                "`latest(by: $c)` orders by a date or an sk; `$c` is ${t.canonical}",
                                node.location,
                            )
                        }
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
            "within" -> {
                val attr = node.args.firstOrNull { it.first == null }?.second as? ColumnRef
                val named = node.args.mapNotNull { it.first }.toSet()
                val shapeOk = attr != null && (named == setOf("last") || named == setOf("from", "to"))
                if (!shapeOk) {
                    err(
                        TtrpDiagnosticId.FN_002,
                        "`within` is called `within(<date>, last: <duration>)` or `within(<date>, from: …, to: …)`",
                        node.location,
                    )
                }
                node.args.filter { it.first != null }.forEach { (n, e) ->
                    val t = type(e, row)
                    if (n == "last" && t !is RecipeType.Duration && t != RecipeType.UNKNOWN) {
                        err(TtrpDiagnosticId.TYP_001, "`within(…, last:)` takes a duration — `weeks(8)`", e.location)
                    }
                }
                if (attr != null) {
                    val t = column(row, attr.column, node, "within") ?: return
                    if (t.kind != TtrpType.Kind.TEMPORAL && !reachesCalendar(node, attr.column)) {
                        err(
                            TtrpDiagnosticId.TYP_001,
                            "`within` reads a date: `${attr.column}` is ${t.canonical}, and no relation of its " +
                                "entity leads " +
                                "to a calendar (an `event_date` entity)",
                            attr.location,
                        )
                    }
                }
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

    /** A date, a timestamp — or an integer, which a recipe reads as an sk the model maps to its date (D-82). */
    private fun temporalOrSk(t: TtrpType): Boolean = t.kind == TtrpType.Kind.TEMPORAL || t is TtrpType.Integer

    /**
     * D-82 / S1.3 T5 (`TemporalResolution`): the sk attribute [column] of the entity loaded upstream of [node] has a
     * relation to an entity whose `semantics { role: event_date }` marks it the calendar (hartland's `date_dim`).
     */
    private fun reachesCalendar(
        node: Node,
        column: String,
    ): Boolean {
        val entity = sourceEntity(node, column) ?: return false
        val model = model ?: return false
        return model
            .relationsOf(entity)
            .any { rel ->
                val other = if (rel.fromEntity.name == entity.qname.name) rel.toEntity.name else rel.fromEntity.name
                // An FK-only relation (`binding: { fk: … }`, no `join:` pairs — hartland's `rel_inventory_date`) names
                // no column at the er level: it leads to the calendar from the entity as a whole.
                val pair =
                    rel.joinPairs.isEmpty() ||
                        rel.joinPairs.any { p ->
                            p.fromAttr.name.substringAfterLast('.') == column ||
                                p.toAttr.name.substringAfterLast('.') == column
                        }
                pair && model.entitiesNamed(other).any { model.isCalendar(it) }
            }
    }

    // ---- REC-050 ------------------------------------------------------------------------------------------

    /** An aggregate summing/averaging a snapshot measure across its snapshot date with no `latest` before it. */
    private fun semiAdditive(node: Aggregate) {
        for (a in node.aggregations) {
            val call = a.value as? AggregateCall ?: continue
            if (call.function.name !in setOf("sum", "avg")) continue
            val col = (call.args.firstOrNull() as? ColumnRef)?.column ?: continue
            val trace = trace(node, col) ?: continue
            if (trace.throughLatest) continue
            val entity = trace.entity
            val measure =
                entity.mentionSemantics?.measures?.firstOrNull {
                    it.attribute.path == col && it.aggregation == "last"
                } ?: continue
            val snapshotDates =
                model
                    ?.attributesOf(entity)
                    .orEmpty()
                    .filter { it.isKey }
                    .map { it.qname.name.substringAfterLast('.') }
                    .filter { it.endsWith("date") }
            if (snapshotDates.any { it in node.groupBy }) continue
            diags +=
                TtrpDiagnostic(
                    TtrpDiagnosticId.REC_050,
                    Severity.WARNING,
                    "`${call.function.name}(${measure.attribute.path})` adds the snapshot measure of " +
                        "`${entity.qname.name}` " +
                        "across its snapshot dates (${snapshotDates.joinToString(
                            ", ",
                        ).ifEmpty { "the snapshot date" }})",
                    a.value.location,
                )
        }
    }

    private data class Trace(
        val entity: Entity,
        val throughLatest: Boolean,
    )

    /** Follows [column] upstream from [node]'s input to the entity load it comes from, noting a `latest` on the way. */
    private fun trace(
        node: Node,
        column: String,
    ): Trace? {
        var ref: PortRef = rows.feed(PortRef(node.id, PortNames.IN)) ?: return null
        var latest = false
        repeat(MAX_TRACE) {
            val n = graph.nodes[ref.nodeId] ?: return null
            when {
                n is Container -> {
                    val port = n.declaredPorts.firstOrNull { it.name == ref.port }
                    ref =
                        if (port?.direction == PortDirection.IN) {
                            rows.feed(ref) ?: return null
                        } else {
                            n.portMapping[ref.port] ?: return null
                        }
                }
                n is Load -> {
                    val name = n.source.substringAfterLast('.')
                    val entity = model?.entitiesNamed(name)?.firstOrNull() ?: return null
                    return Trace(entity, latest)
                }
                n is Join -> {
                    val side =
                        listOf(PortNames.LEFT, PortNames.RIGHT).firstOrNull {
                            rows.input(n.id, it)?.containsKey(column) ==
                                true
                        }
                            ?: return null
                    ref = rows.feed(PortRef(n.id, side)) ?: return null
                }
                n is Calc && n.assignments.any { it.name == column } -> return null
                n is Project &&
                    n.columns.indices.any {
                        n.aliasOf(
                            it,
                        ) == column &&
                            n.columns[it] !is ColumnRef
                    } -> return null
                n is Aggregate -> return null
                else -> {
                    if (n is TimeSugar && n.kind == "latest") latest = true
                    ref = rows.feed(PortRef(n.id, PortNames.IN)) ?: return null
                }
            }
        }
        return null
    }

    /** The entity loaded upstream of [node] whose row carries [column]. */
    private fun sourceEntity(
        node: Node,
        column: String,
    ): Entity? = trace(node, column)?.entity

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun joinRow(node: Join): Row? {
        val l = rows.input(node.id, PortNames.LEFT) ?: return null
        val r = rows.input(node.id, PortNames.RIGHT) ?: return null
        return r + l
    }

    private fun columnType(spelling: String): TtrpType? =
        if ('.' in spelling) {
            val (e, a) = spelling.split('.', limit = 2)
            model?.entitiesNamed(e)?.firstOrNull()?.let { ent ->
                model.entityAttributes(ent).firstOrNull { it.first == a }?.let { TtrpType.parse(it.second) }
            }
        } else {
            TtrpType.parse(spelling)
        }

    private fun err(
        id: TtrpDiagnosticId,
        message: String,
        loc: SourceLocation,
    ) {
        diags += TtrpDiagnostic(id, Severity.ERROR, message, loc)
    }

    private companion object {
        const val MAX_TRACE = 200

        val DURATION_UNITS = DurationUnit.entries.map { it.spelling }.toSet()
    }
}
