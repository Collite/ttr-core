// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.Column
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Except
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Intersect
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Select
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TimeSugar
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.model.Union
import org.tatrman.ttrp.graph.model.Values

/** A row's columns, in order: name → type (null = the type is not known). */
typealias Row = Map<String, TtrpType?>

/**
 * The row each port of a recipe's graph carries, by name — what the recipe checker needs to know whether the key
 * survives, which columns a wiring can name and what type they have. Inferred from the resolved loads
 * ([loadSchemas], the front-half's) through the op roster; `null` means not known (an opaque island without a
 * declared schema, a `*` over an unknown row), and a check that needs the row then stays silent.
 */
class RecipeRows(
    private val graph: TtrpGraph,
    private val loadSchemas: Map<SourceLocation, List<Column>>,
    /** The declared out row of an opaque role container (`foreign … schema s`, `turn … answer s`), if known. */
    private val opaqueOut: (Container) -> Row? = { null },
) {
    private val memo = HashMap<PortRef, Row?>()
    private val visiting = HashSet<PortRef>()

    /** The row [ref] carries; for a container IN port, the row fed into it. */
    fun of(ref: PortRef): Row? {
        if (ref in memo) return memo[ref]
        if (!visiting.add(ref)) return null
        val row = compute(ref)
        visiting.remove(ref)
        memo[ref] = row
        return row
    }

    /** The row entering [nodeId]'s in-port [port] (null when nothing is wired to it). */
    fun input(
        nodeId: String,
        port: String = PortNames.IN,
    ): Row? = feed(PortRef(nodeId, port))?.let { of(it) }

    /** The out port wired into [to], if any. */
    fun feed(to: PortRef): PortRef? = graph.edges.firstOrNull { it.kind == EdgeKind.DATA && it.to == to }?.from

    private fun compute(ref: PortRef): Row? {
        val node = graph.nodes[ref.nodeId] ?: return null
        if (node is Container) {
            val port = node.declaredPorts.firstOrNull { it.name == ref.port }
            if (port?.direction == PortDirection.IN) return feed(ref)?.let { of(it) }
            node.portMapping[ref.port]?.let { return of(it) }
            return opaqueOut(node)
        }
        return when (node) {
            is Load -> loadSchemas[node.location]?.associate { it.name to it.type }
            is Values -> node.schema.associate { it.name to TtrpType.parse(it.type) }
            is Filter, is Sort, is Limit, is Distinct, is Branch, is Switch, is TimeSugar -> input(node.id)
            is Project -> project(node)
            is Select -> select(node)
            is Calc ->
                input(node.id)?.let { row ->
                    row +
                        node.assignments.associate { it.name to typeOf(it.value, row) }
                }
            is Aggregate -> aggregate(node)
            is Join -> join(node)
            is Union -> input(node.id, "in1")
            is Intersect, is Except -> input(node.id, "in1")
            else -> null
        }
    }

    private fun project(node: Project): Row? {
        val inRow = input(node.id)
        val out = LinkedHashMap<String, TtrpType?>()
        if (node.passthrough) out.putAll(inRow ?: return null)
        for ((i, col) in node.columns.withIndex()) {
            if (col is ColumnRef && col.column == "*") {
                out.putAll(inRow ?: return null)
                continue
            }
            val name = node.aliasOf(i) ?: continue
            out[name] = typeOf(col, inRow)
        }
        return out
    }

    private fun select(node: Select): Row? {
        val inRow = input(node.id)
        return node.columns.associate { c -> (node.renames[c] ?: c) to inRow?.get(c) }
    }

    private fun aggregate(node: Aggregate): Row? {
        val inRow = input(node.id)
        if (node.distinctAllColumns) return inRow
        return node.groupBy.associateWith { inRow?.get(it) } +
            node.aggregations.associate { a -> a.name to typeOf(a.value, inRow) }
    }

    private fun join(node: Join): Row? {
        val left = input(node.id, PortNames.LEFT)
        if (node.type == JoinType.SEMI || node.type == JoinType.ANTI) return left
        val right = input(node.id, PortNames.RIGHT)
        if (left == null || right == null) return null
        return right + left
    }

    /** A column reference's type from [row]; a count is an integer; anything else is not known here. */
    private fun typeOf(
        e: org.tatrman.ttrp.expr.Expression,
        row: Row?,
    ): TtrpType? =
        when {
            e is ColumnRef -> row?.get(e.column)
            e is AggregateCall && e.function.name == "count" -> TtrpType.Integer
            else -> e.type
        }
}
