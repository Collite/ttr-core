// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttrp.emit.sql.EmitColumn
import org.tatrman.ttrp.emit.sql.PlanParam
import org.tatrman.ttrp.emit.sql.SqlIslandEmitter
import org.tatrman.ttrp.emit.sql.SqlTextPlanner
import org.tatrman.ttrp.emit.sql.SqlTier
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.collapse.Island
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.recipe.RecipeModel

/**
 * One statement of a recipe bundle (AU-core-P2; contracts §5.2): the self-contained SELECT of one island output —
 * `islands/<island>.<port>.sql` — in the engine's dialect, with every `:name` it binds left as a placeholder the
 * runner fills (`:as_of`, the parameters, a value the run binds).
 */
data class RecipeStatement(
    val island: String,
    val port: String,
    val sql: String,
    val columns: List<EmitColumn>,
    val params: List<String>,
) {
    val file: String get() = "islands/$island.$port.sql"
}

/**
 * Plans every output of a recipe (AU-core-P2 · S2.1): each role container's OUT ports — the candidates', each
 * context's, the decision's routes and their `detail` outputs — as ONE self-contained statement through the
 * [SqlTextPlanner] (the output's whole cone inlined: a statement runs through the door on its own). The program's key
 * filter is not in any of them: the runner applies the port to every keyed output it runs (06 A-2).
 */
object RecipeStatements {
    /** The reserved `:as_of` (06 A-6): the instant the run binds — a timestamp. */
    const val AS_OF = "as_of"

    /**
     * Every output's statement. [tier] [SqlTier.ER] is the bundle's (the door translates it); [SqlTier.DB] renders the
     * same plan over the physical tables — what a test runs on a database of the model's shape.
     */
    fun plan(
        plan: TtrpPipeline.PlanResult,
        tier: SqlTier = SqlTier.ER,
    ): List<RecipeStatement> {
        val graph = requireNotNull(plan.graph) { "a recipe compiles only from a checked plan" }
        val exec = requireNotNull(plan.exec)
        val bound = requireNotNull(plan.bound)
        val model = requireNotNull(plan.recipe) { "${plan.fileName} holds no recipe" }
        val params = params(model, graph)
        return exec.islands.flatMap { island -> islandStatements(island, graph, bound, params, tier) }
    }

    private fun islandStatements(
        island: Island,
        graph: TtrpGraph,
        bound: org.tatrman.ttrp.graph.capability.BoundWorld,
        params: Map<String, PlanParam>,
        tier: SqlTier,
    ): List<RecipeStatement> {
        val container = graph.containers.getValue(island.id)
        val planner =
            SqlTextPlanner(
                graph,
                bound,
                SqlIslandEmitter(bound).dialect(island),
                params,
                fragmentsAsMembers = true,
                tier = tier,
                dropSynthesized = true,
            )
        return container.declaredPorts
            .filter { it.direction == PortDirection.OUT && it.kind == PortKind.DATA && it.name != "rejects" }
            .map { port ->
                val out = planner.emit(container, port.name)
                RecipeStatement(island.name, port.name, out.sql, out.columns, out.params)
            }
    }

    /**
     * Every `:name` the recipe's statements may bind, typed (the plan.v1 surface tag) and indexed: `:as_of` first,
     * then the parameters in `parameters:` order, then any other name the islands read (a value the run binds), by
     * name. A `days`/`working_days` parameter is its count of days.
     */
    fun params(
        model: RecipeModel,
        graph: TtrpGraph,
    ): Map<String, PlanParam> {
        val declared = model.parameterDecls.associateBy { it.name }
        val derived = model.derivedParams.map { it.name }
        val names =
            listOf(AS_OF) + model.parameters + derived +
                graph.nodes.values
                    .flatMap { expressionsOf(it) }
                    .flatMap { paramNames(it) }
                    .filter { it != AS_OF && it !in model.parameters && it !in derived }
                    .distinct()
                    .sorted()
        return names.distinct().withIndex().associate { (i, n) ->
            val tag =
                when {
                    n == AS_OF -> "datetime"
                    n in derived -> "datetime"
                    else -> declared[n]?.type?.let { typeTag(it) } ?: "text"
                }
            n to PlanParam(i, tag)
        }
    }

    /** A `def parameter` type → the plan.v1 surface tag; `days`/`working_days` are counts. */
    fun typeTag(type: String): String =
        when (type.lowercase()) {
            "days", "working_days", "int", "integer" -> "int"
            "decimal" -> "decimal"
            "date" -> "date"
            "bool", "boolean" -> "bool"
            "timestamp", "datetime" -> "datetime"
            else -> "text"
        }

    private fun expressionsOf(n: Node): List<Expression> =
        when (n) {
            is Filter -> listOfNotNull(n.predicate)
            is Calc -> n.assignments.map { it.value }
            is Project -> n.columns
            is Join -> listOfNotNull(n.on)
            is Aggregate -> n.aggregations.map { it.value } + listOfNotNull(n.having)
            is org.tatrman.ttrp.graph.model.TimeSugar -> n.args.map { it.second }
            else -> emptyList()
        }

    private fun paramNames(e: Expression): List<String> =
        when (e) {
            is ParamRef -> listOf(e.name)
            is org.tatrman.ttrp.expr.DurationParam -> listOf(e.param)
            is FunctionCall -> e.args.flatMap { paramNames(it) }
            is org.tatrman.ttrp.expr.AggregateCall -> e.args.flatMap { paramNames(it) }
            is Cast -> paramNames(e.expr)
            is CaseWhen ->
                e.branches.flatMap { paramNames(it.first) + paramNames(it.second) } +
                    (e.elseExpr?.let { paramNames(it) } ?: emptyList())
            is InList -> paramNames(e.expr) + e.items.flatMap { paramNames(it) }
            is IsNull -> paramNames(e.expr)
            else -> emptyList()
        }
}
