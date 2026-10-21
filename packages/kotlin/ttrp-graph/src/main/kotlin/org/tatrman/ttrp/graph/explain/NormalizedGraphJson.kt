// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.explain

import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Display
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Select
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.Store
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.model.Union

/**
 * The 6.3 KEY-GATE serializer: a canonical, byte-stable rendering of a normalized graph
 * for the identity comparison "bare ≡ embedded ≡ canonical → byte-identical graphs".
 *
 * Excluded (incidental to the surface): node ids, all source locations/spans, the fragment
 * `sourceText`, and the container `derived` flag. Included (semantic): node kinds + params,
 * SSA labels, ports/edges, container names + targets + port mappings, and expression trees
 * (structural, by catalogue id). Nodes are keyed by SSA label — which is surface-independent
 * (the decomposers lower to the SAME canonical statements) — and everything is sorted, so a
 * structural divergence shows up as a readable line diff.
 */
object NormalizedGraphJson {
    fun write(graph: TtrpGraph): String {
        val labelOf: (String) -> String = { id -> graph.nodes[id]?.label ?: id }
        val sb = StringBuilder()

        sb.append("nodes:\n")
        for (node in graph.nodes.values
            .filter { it !is Container }
            .sortedBy { it.label }) {
            sb
                .append("  ")
                .append(node.label)
                .append(" = ")
                .append(renderNode(node))
                .append('\n')
        }

        sb.append("containers:\n")
        for (c in graph.containers.values.sortedBy { it.label }) {
            sb
                .append("  ")
                .append(c.label)
                .append(" target=")
                .append(c.target)
            sb
                .append(" members=[")
                .append(
                    c.memberIds
                        .map(labelOf)
                        .sorted()
                        .joinToString(","),
                ).append(']')
            val pm =
                c.portMapping.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}->${labelOf(it.value.nodeId)}.${it.value.port}" }
            sb.append(" ports={").append(pm).append("}\n")
        }

        sb.append("edges:\n")
        val edges =
            graph.edges
                .map {
                    "${labelOf(
                        it.from.nodeId,
                    )}.${it.from.port} -${it.kind}-> ${labelOf(it.to.nodeId)}.${it.to.port}"
                }.sorted()
        for (e in edges) sb.append("  ").append(e).append('\n')

        return sb.toString()
    }

    private fun renderNode(node: Node): String =
        when (node) {
            is Join -> "Join(type=${node.type}, on=${expr(node.on)}, allCols=${node.onAllColumns})"
            is Filter -> "Filter(${expr(node.predicate)})"
            is Branch -> "Branch(${expr(node.predicate)})"
            is Switch -> "Switch(cases=${node.cases.map { "${it.first}=${expr(it.second)}" }}, else=${node.hasElse})"
            is Project -> "Project(${node.columns.joinToString(",") { expr(it) }})"
            is Select -> "Select(cols=${node.columns}, renames=${node.renames.toSortedMap()})"
            is Calc -> "Calc(${node.assignments.joinToString(",") { "${it.name}=${expr(it.value)}" }})"
            is Aggregate ->
                "Aggregate(by=${node.groupBy}, aggs=${node.aggregations.joinToString(
                    ",",
                ) { "${it.name}=${expr(it.value)}" }}, " +
                    "having=${expr(node.having)}, distinctAll=${node.distinctAllColumns})"
            is Sort -> "Sort(${node.keys})"
            is Limit -> "Limit(${node.count})"
            is Union -> "Union(arity=${node.arity})"
            is Load -> "Load(source=${node.source}, schemaRef=${node.schemaRef})"
            is Store -> "Store(target=${node.target})"
            is Display -> "Display(name=${node.name})"
            else -> node::class.simpleName ?: "Node"
        }

    private fun expr(e: Expression?): String =
        when (e) {
            null -> "-"
            is ColumnRef -> "col(${e.port?.plus(".") ?: ""}${e.column})"
            is Literal -> "lit(${e.value})"
            is FunctionCall -> "${e.function.value}(${e.args.joinToString(",") { expr(it) }})"
            is AggregateCall -> "${e.function.value}${if (e.distinct) "!d" else ""}(${e.args.joinToString(
                ",",
            ) { expr(it) }})"
            is Cast -> "cast(${expr(e.expr)} as ${e.target})"
            is CaseWhen ->
                "case(${e.branches.joinToString(
                    ",",
                ) { "${expr(it.first)}=>${expr(it.second)}" }}${e.elseExpr?.let { ",else=>${expr(it)}" } ?: ""})"
            is InList -> "in${if (e.negated) "!n" else ""}(${expr(e.expr)};${e.items.joinToString(",") { expr(it) }})"
            is IsNull -> "isnull${if (e.negated) "!n" else ""}(${expr(e.expr)})"
            is MdPath -> "mdpath(${e.components.size})" // MD lowering is S4; opaque leaf for now
            is org.tatrman.ttrp.expr.ParamRef -> "param(${e.name})"
            is org.tatrman.ttrp.expr.DurationLiteral -> "duration(${e.amount} ${e.unit.spelling})"
            is org.tatrman.ttrp.expr.DurationParam -> "duration(:${e.param} ${e.unit.spelling})"
            // TTRP 0.5: refused at check (TTRP-CN-001) — named, never rendered.
            is org.tatrman.ttrp.expr.ConceptForm -> "cn(${e::class.simpleName})"
        }
}
