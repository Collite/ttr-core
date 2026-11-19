// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Wiring
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
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.resolve.ActionResolution
import org.tatrman.ttrp.resolve.TtrpChecker

/**
 * Action-time counts need the same model calendar as island time sugar, without adding a graph Load or JOIN.
 * So does an obligation action's `due` in working days (D-249/D-251): the Server counts that date over the calendar.
 * Read the parsed direct arguments/when and the checked action's expression defaults; never scan rendered text
 * or checkout paths. Nested action calls and their defaults are a separate, gated dispatch contract.
 */
internal class ActionCalendarReads(
    private val report: TtrpChecker.Report,
    private val time: RecipeTime,
    private val row: Row?,
    private val diagnostics: MutableList<TtrpDiagnostic>,
) {
    fun collect(recipe: RecipeDecl): List<String> {
        var need: Need? = null
        val defaults = mutableMapOf<String, List<Expression>>()
        for (wiring in recipe.wirings) {
            val action =
                (report.automationScope?.action(wiring.action) as? ActionResolution.Resolved)?.action ?: continue
            // Every wiring's defaults are parsed, so a malformed one is reported even after the calendar is needed.
            val expressions =
                wiring.args.flatMap { arg ->
                    when (val value = arg.value) {
                        is WiringValue.Expr -> listOf(value.expression)
                        is WiringValue.Values -> value.items
                        is WiringValue.Call -> emptyList()
                    }
                } + listOfNotNull(wiring.whenCondition) +
                    defaults.getOrPut(wiring.action) { expressions(action, wiring.actionLocation) }
            if (need != null) continue
            need =
                if (expressions.any(::needsCalendar)) {
                    Need(wiring.actionLocation, due = false)
                } else {
                    workingDaysDue(wiring, action)?.let { Need(it, due = true) }
                }
        }
        val (location, due) = need ?: return emptyList()
        val calendar = report.modelIndex?.calendars()?.singleOrNull()
        if (calendar == null) {
            val what = if (due) "a `due` in working days needs" else "action time expressions need"
            error("$what the model's calendar — one entity with `semantics { role: event_date }`", location)
            return emptyList()
        }
        return listOf("er.entity.${calendar.qname.name}")
    }

    /** Why the calendar is read, and where to say so when the model has no unique one. */
    private data class Need(
        val at: SourceLocation,
        val due: Boolean,
    )

    private fun expressions(
        action: AutomationAction,
        at: SourceLocation,
    ): List<Expression> =
        action.properties.filter { it.first == "writer_defaults" }.flatMap { (_, value) ->
            (value as? AutoValue.Obj)?.entries.orEmpty().flatMap { entry ->
                (entry.value as? AutoValue.Obj)?.entries.orEmpty().filter { it.key == "expression" }.mapNotNull {
                    val source = (it.value as? AutoValue.Str)?.raw ?: return@mapNotNull null
                    val parsed = TtrpParser.parseExpression(source, at.file)
                    if (parsed.diagnostics.any { d -> d.severity == Severity.ERROR }) {
                        error("invalid writer default expression `${action.qname.name}.${entry.key}`", at)
                        null
                    } else {
                        parsed.expression
                    }
                }
            }
        }

    /**
     * AU S2.4f (D-249/D-251): an obligation action's `due` in working days is a date the Server counts over the
     * governed calendar, so the recipe declares that read. The wiring's `due:` counts when it types as working days
     * (a literal, `working_days(:p)`, a working-days parameter, a `case`); the action's `due:` default counts as
     * well, even when the wiring sets its own `due` — as the writer defaults do. An approval action's `due` is the
     * engine's user-task date and reads nothing here. Returns where to point at, or null.
     */
    private fun workingDaysDue(
        wiring: Wiring,
        action: AutomationAction,
    ): SourceLocation? {
        if (action.sinks["native"] != "obligation") return null
        val due = wiring.args.lastOrNull { it.name == "due" }
        val value = (due?.value as? WiringValue.Expr)?.expression
        if (due != null && value != null && time.durationUnit(value, row) == DurationUnit.WORKING_DAYS) {
            return due.location
        }
        return wiring.actionLocation.takeIf { action.due?.unit == DurationUnit.WORKING_DAYS.spelling }
    }

    private fun needsCalendar(expression: Expression): Boolean =
        NodeExpressions.walk(expression).filterIsInstance<FunctionCall>().any { call ->
            call.function.name in setOf("working_days", "age") ||
                (
                    call.function == CatalogId.SUB &&
                        call.args.size == 2 &&
                        boundDate(call.args[0]) &&
                        call.args[1] is ColumnRef
                )
        }

    private fun boundDate(expression: Expression): Boolean =
        when (expression) {
            is ParamRef -> time.scalarType(expression, row)?.kind == TtrpType.Kind.TEMPORAL
            is FunctionCall ->
                expression.function in setOf(CatalogId.ADD, CatalogId.SUB) &&
                    expression.args.size == 2 &&
                    boundDate(expression.args[0]) &&
                    (expression.args[1] is DurationExpr || expression.args[1] is ParamRef)
            else -> false
        }

    private fun error(
        message: String,
        at: SourceLocation,
    ) {
        diagnostics += TtrpDiagnostic(TtrpDiagnosticId.FN_002, Severity.ERROR, message, at)
    }
}
