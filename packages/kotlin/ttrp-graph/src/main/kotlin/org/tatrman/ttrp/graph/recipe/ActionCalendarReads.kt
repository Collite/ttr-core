// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.WiringValue
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.DurationExpr
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
        var at: SourceLocation? = null
        val defaults = mutableMapOf<String, List<Expression>>()
        for (wiring in recipe.wirings) {
            val action =
                (report.automationScope?.action(wiring.action) as? ActionResolution.Resolved)?.action ?: continue
            val expressions =
                wiring.args.flatMap { arg ->
                    when (val value = arg.value) {
                        is WiringValue.Expr -> listOf(value.expression)
                        is WiringValue.Values -> value.items
                        is WiringValue.Call -> emptyList()
                    }
                } + listOfNotNull(wiring.whenCondition) +
                    defaults.getOrPut(wiring.action) { expressions(action, wiring.actionLocation) }
            if (expressions.any(::needsCalendar) && at == null) at = wiring.actionLocation
        }
        val location = at ?: return emptyList()
        val calendar = report.modelIndex?.calendars()?.singleOrNull()
        if (calendar == null) {
            error(
                "action time expressions need the model's calendar — one entity with `semantics { role: event_date }`",
                location,
            )
            return emptyList()
        }
        return listOf("er.entity.${calendar.qname.name}")
    }

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
