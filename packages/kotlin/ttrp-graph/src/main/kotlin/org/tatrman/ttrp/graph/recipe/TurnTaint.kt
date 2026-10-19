// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Calc
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Distinct
import org.tatrman.ttrp.graph.model.Except
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.graph.model.Intersect
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortKind
import org.tatrman.ttrp.graph.model.PortNames
import org.tatrman.ttrp.graph.model.PortRef
import org.tatrman.ttrp.graph.model.Project
import org.tatrman.ttrp.graph.model.Select
import org.tatrman.ttrp.graph.model.Sort
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TimeSugar
import org.tatrman.ttrp.graph.model.TtrpGraph
import org.tatrman.ttrp.graph.model.Union

/**
 * The turn rule's taint (D-32, REC-040), over the dataflow rather than by name. For each port: the columns that
 * carry an agent's answer, or [ALL] when the port's rows themselves were chosen by one.
 * - A `turn` island's out columns are tainted (all but the key); when its answer schema is unknown, so are all of
 *   its rows.
 * - A value computed from a tainted column is tainted, through every container and every op.
 * - A filter, join condition, grouping or sort that reads a tainted column makes the rows it passes turn-chosen.
 */
class TurnTaint(
    private val graph: TtrpGraph,
    private val rows: RecipeRows,
    /** The `turn` role containers' ids. */
    private val turns: Set<String>,
    private val keyColumns: List<String>,
) {
    private val memo = HashMap<PortRef, Set<String>>()
    private val visiting = HashSet<PortRef>()

    fun of(ref: PortRef): Set<String> {
        memo[ref]?.let { return it }
        if (!visiting.add(ref)) return emptySet()
        val t = compute(ref)
        visiting.remove(ref)
        memo[ref] = t
        return t
    }

    fun input(
        nodeId: String,
        port: String = PortNames.IN,
    ): Set<String> = rows.feed(PortRef(nodeId, port))?.let { of(it) } ?: emptySet()

    /** True when [e] reads a column of [taint] (or the rows are turn-chosen). */
    fun reads(
        e: Expression,
        taint: Set<String>,
    ): Boolean = ALL in taint || RecipeChecker.walk(e).any { it is ColumnRef && it.column in taint }

    private fun compute(ref: PortRef): Set<String> {
        val node = graph.nodes[ref.nodeId] ?: return emptySet()
        if (node is Container) {
            val port = node.declaredPorts.firstOrNull { it.name == ref.port }
            if (port?.direction == PortDirection.IN) return rows.feed(ref)?.let { of(it) } ?: emptySet()
            if (node.id in turns) return rows.of(ref)?.keys?.minus(keyColumns.toSet()) ?: setOf(ALL)
            node.portMapping[ref.port]?.let { return of(it) }
            // Another opaque island: whatever it reads of an answer may shape all it returns.
            val ins =
                node.declaredPorts
                    .filter { it.direction == PortDirection.IN && it.kind == PortKind.DATA }
                    .flatMap { of(PortRef(node.id, it.name)) }
            return if (ins.isEmpty()) emptySet() else setOf(ALL)
        }
        return when (node) {
            is Filter -> chosen(input(node.id), node.predicate)
            is Branch -> chosen(input(node.id), node.predicate)
            is Sort -> input(node.id).let { t -> if (node.keys.any { it in t }) t + ALL else t }
            is Limit, is Distinct, is Switch -> input(node.id)
            is TimeSugar -> input(node.id).let { t -> if (node.args.any { reads(it.second, t) }) t + ALL else t }
            is Calc -> {
                val t = input(node.id).toMutableSet()
                for (a in node.assignments) if (reads(a.value, t)) t += a.name else t -= a.name
                t
            }
            is Project -> {
                val inp = input(node.id)
                val out = if (node.passthrough) inp.toMutableSet() else mutableSetOf()
                if (ALL in inp) out += ALL
                node.columns.forEachIndexed { i, e ->
                    val name = node.aliasOf(i)
                    if (name != null && reads(e, inp)) out += name
                }
                out
            }
            is Select -> {
                val inp = input(node.id)
                node.columns
                    .filter { it in inp }
                    .map { node.renames[it] ?: it }
                    .toSet() + inp.intersect(setOf(ALL))
            }
            is Aggregate -> {
                val inp = input(node.id)
                val out = node.groupBy.filter { it in inp }.toMutableSet()
                node.aggregations.forEach { if (reads(it.value, inp)) out += it.name }
                if (ALL in inp ||
                    node.groupBy.any { it in inp } ||
                    node.having?.let { reads(it, inp) } == true
                ) {
                    out += ALL
                }
                out
            }
            is Join -> {
                val l = input(node.id, PortNames.LEFT)
                val r = input(node.id, PortNames.RIGHT)
                val both = l + r
                val condition = node.on?.let { reads(it, both) } == true
                val semi = node.type == JoinType.SEMI || node.type == JoinType.ANTI
                val out = if (semi) l.toMutableSet() else both.toMutableSet()
                // A semi/anti join keeps or drops rows by what the other side holds.
                if (condition || (semi && r.isNotEmpty())) out += ALL
                out
            }
            is Union -> (1..node.arity).flatMap { input(node.id, "in$it") }.toSet()
            is Intersect, is Except ->
                input(node.id, "in1") +
                    input(node.id, "in2").let { if (it.isEmpty()) it else setOf(ALL) }
            else -> input(node.id)
        }
    }

    private fun chosen(
        inp: Set<String>,
        predicate: Expression?,
    ): Set<String> = if (predicate != null && reads(predicate, inp)) inp + ALL else inp

    companion object {
        /** Every row of the port was chosen by an agent's answer. */
        const val ALL = "*"
    }
}
