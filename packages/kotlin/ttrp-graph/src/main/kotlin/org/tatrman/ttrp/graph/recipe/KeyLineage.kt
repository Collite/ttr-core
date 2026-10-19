// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.Grain
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
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
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
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
import org.tatrman.ttrp.graph.model.Values

/**
 * One port's key lineage (06 A-5): [keyed] — every row carries the candidates' key and descends from exactly one
 * candidate row; [keyColumns] — per key component, the column names that carry it; [readsCandidates] — the rows
 * depend on the candidates at all (a lookup's do not). [lostAt] names the first op that lost the key.
 */
data class Lineage(
    val keyed: Boolean,
    val keyColumns: List<Set<String>> = emptyList(),
    val readsCandidates: Boolean = keyed,
    val lostAt: LostKey? = null,
) {
    companion object {
        /** A row of the model, read at `as of` — no candidate in it. */
        val LOOKUP = Lineage(keyed = false, readsCandidates = false)
    }
}

/** The op that lost the key: what it did, and where. */
data class LostKey(
    val what: String,
    val location: SourceLocation,
)

/**
 * The forward key-lineage pass over a recipe's graph (AU-core-P1 · S1.2; 06 A-5 + D-103), one bit per port, the
 * op table as a `when`. Conservative by design (D-28): what it cannot prove keyed is not keyed.
 *
 * - The candidates container's OUT port is keyed, by definition.
 * - `filter`, `calc`, `sort`, `limit`, `distinct`, `latest`/`within`, the `switch`/`branch` outputs keep the input's
 *   lineage (a `calc` that overwrites a key column loses that column).
 * - `project`/`select` keep it when every key component survives as a plain column (renamed or not).
 * - `aggregate` keeps it when every key component is in `group_by`.
 * - `union`/`intersect`/`except` keep it when every input is keyed.
 * - `join`: both sides keyed is a candidate ↔ candidate join — not keyed in a context; in the decision it is the
 *   point (the decision joins its contexts back on the key), so keyed iff the `on:` equates every key component.
 *   Exactly one side keyed: `inner` is keyed; `left` / `semi` / `anti` with the keyed side left are keyed, `right`
 *   with it right; and (D-103) a `left`/`right`/`semi` join whose keyed side is the other one is keyed when the
 *   unkeyed side's columns equate to the key — by an equi-`on:`, or by an `on: relation` from the candidates'
 *   entity to the many side ([relationKey]). `full` and `cross` are not keyed.
 * - An opaque island (`foreign`, `call`, `turn`) is keyed iff it reads the candidates, runs `per candidate`, and
 *   its declared out row carries the key; a `builtin` never is.
 */
class KeyLineage(
    private val graph: TtrpGraph,
    private val rows: RecipeRows,
    private val candidates: String?,
    private val keyColumns: List<String>,
    /** The decision container's id — joins inside it follow the decision's rule. */
    private val decision: String?,
    /** Role container id → its island form (opaque forms are classified by it). */
    private val forms: Map<String, IslandForm>,
    /** The declared out row of an opaque role container, if known. */
    private val opaqueOut: (Container) -> Row?,
    /**
     * For an `on: relation r` join: per key component, the column of the joined (non-candidates) side that equates
     * to it — when `r` runs from the candidates' entity to the many side; else null.
     */
    private val relationKey: (relation: String) -> List<String>?,
    /** Join id → the relation its `on: relation` names. */
    private val relationJoins: Map<String, String>,
) {
    private val memo = HashMap<PortRef, Lineage>()
    private val visiting = HashSet<PortRef>()
    private val memberOf: Map<String, String> =
        graph.containers.values
            .flatMap { c -> c.memberIds.map { it to c.id } }
            .toMap()

    fun of(ref: PortRef): Lineage {
        memo[ref]?.let { return it }
        if (!visiting.add(ref)) return Lineage.LOOKUP
        val l = compute(ref)
        visiting.remove(ref)
        memo[ref] = l
        return l
    }

    /** The lineage entering [nodeId]'s in-port [port]. */
    fun input(
        nodeId: String,
        port: String = PortNames.IN,
    ): Lineage = rows.feed(PortRef(nodeId, port))?.let { of(it) } ?: Lineage.LOOKUP

    private fun keyedSeed(): Lineage = Lineage(keyed = true, keyColumns = keyColumns.map { setOf(it) })

    private fun compute(ref: PortRef): Lineage {
        val node = graph.nodes[ref.nodeId] ?: return Lineage.LOOKUP
        if (node is Container) {
            val port = node.declaredPorts.firstOrNull { it.name == ref.port }
            if (port?.direction == PortDirection.IN) return rows.feed(ref)?.let { of(it) } ?: Lineage.LOOKUP
            if (node.id == candidates) return keyedSeed()
            node.portMapping[ref.port]?.let { return of(it) }
            return opaque(node)
        }
        return when (node) {
            is Load, is Values -> Lineage.LOOKUP
            is Filter, is Sort, is Limit, is Distinct, is Branch, is Switch, is TimeSugar -> input(node.id)
            is Calc -> calc(node)
            is Project ->
                keep(
                    node,
                    input(node.id),
                    node.columns.indices.mapNotNull { i ->
                        projected(node, i)
                    },
                    "projects",
                )
            is Select ->
                keep(
                    node,
                    input(node.id),
                    node.columns.map { c ->
                        ColumnRef(null, c, node.location) to
                            (node.renames[c] ?: c)
                    },
                    "selects",
                )
            is Aggregate -> aggregate(node)
            is Join -> join(node)
            is Union -> all(node, (1..node.arity).map { input(node.id, "in$it") }, "union")
            is Intersect, is Except -> all(node, listOf(input(node.id, "in1"), input(node.id, "in2")), "set operation")
            else -> {
                val inp = input(node.id)
                if (inp.readsCandidates) lose(inp, "`${opName(node)}` does not keep the key", node) else Lineage.LOOKUP
            }
        }
    }

    private fun lose(
        input: Lineage,
        what: String,
        node: Node,
    ): Lineage =
        Lineage(
            false,
            emptyList(),
            readsCandidates = true,
            lostAt =
                input.lostAt ?: LostKey(what, node.location),
        )

    private fun keyedWith(
        columns: List<Set<String>>,
        inputs: List<Lineage>,
    ): Lineage = Lineage(true, columns, readsCandidates = true, lostAt = inputs.firstNotNullOfOrNull { it.lostAt })

    private fun calc(node: Calc): Lineage {
        val inp = input(node.id)
        if (!inp.keyed) return inp
        val overwritten = node.assignments.map { it.name }.toSet()
        val cols = inp.keyColumns.map { it - overwritten }
        return if (cols.any { it.isEmpty() }) {
            lose(inp, "calc overwrites the key column `${keyColumns.first()}`", node)
        } else {
            inp.copy(keyColumns = cols)
        }
    }

    /** Project column [i] as (source reference, output name) when it is a plain column reference. */
    private fun projected(
        node: Project,
        i: Int,
    ): Pair<ColumnRef, String>? {
        val ref = node.columns[i] as? ColumnRef ?: return null
        return ref to (node.aliasOf(i) ?: ref.column)
    }

    private fun keep(
        node: Node,
        inp: Lineage,
        kept: List<Pair<ColumnRef, String>>,
        verb: String,
    ): Lineage {
        if (!inp.keyed) return inp
        if (kept.any { it.first.column == "*" }) return inp
        val cols = inp.keyColumns.map { comp -> kept.filter { carries(comp, it.first) }.map { it.second }.toSet() }
        return if (cols.any { it.isEmpty() }) {
            lose(inp, "`${opName(node)}` $verb without the key `${keyLabel(inp, cols)}`", node)
        } else {
            inp.copy(keyColumns = cols)
        }
    }

    private fun aggregate(node: Aggregate): Lineage {
        val inp = input(node.id)
        if (!inp.keyed || node.distinctAllColumns) return inp
        val cols = inp.keyColumns.map { comp -> comp.intersect(node.groupBy.toSet()) }
        return if (cols.any { it.isEmpty() }) {
            lose(inp, "aggregates the candidates without the key `${keyLabel(inp, cols)}` in `group_by`", node)
        } else {
            inp.copy(keyColumns = cols)
        }
    }

    private fun all(
        node: Node,
        inputs: List<Lineage>,
        what: String,
    ): Lineage {
        if (inputs.none { it.readsCandidates }) return Lineage.LOOKUP
        if (inputs.all { it.keyed }) {
            val cols = keyColumns.indices.map { i -> inputs.map { it.keyColumns[i] }.reduce { a, b -> a intersect b } }
            if (cols.none { it.isEmpty() }) return keyedWith(cols, inputs)
        }
        return lose(inputs.first { it.readsCandidates }, "a $what over rows not all keyed", node)
    }

    private fun join(node: Join): Lineage {
        val l = input(node.id, PortNames.LEFT)
        val r = input(node.id, PortNames.RIGHT)
        val inputs = listOf(l, r)
        if (!l.keyed && !r.keyed) {
            val reading = inputs.firstOrNull { it.readsCandidates } ?: return Lineage.LOOKUP
            return lose(reading, "joins rows not keyed", node)
        }
        if (l.keyed && r.keyed) {
            // Both sides descend from candidates. Joined ON the key, a row pairs a candidate with itself (the key is
            // unique, REC-004) — the `17` CTE pattern, and the decision joining its contexts back. On anything else
            // it pairs one candidate with another: the verdict of one depends on the others (06 A-5).
            val eq = equated(node, keyedLeft = true, keyed = l)
            if (eq != null &&
                keyColumns.indices.all { i ->
                    eq[i].any { u -> r.keyColumns[i].any { it.substringAfter('.') == u } }
                }
            ) {
                return keyedWith(
                    keyColumns.indices.map { i ->
                        joined(listOf(l.keyColumns[i]), PortNames.LEFT, null, null)[0] +
                            joined(listOf(r.keyColumns[i]), PortNames.RIGHT, null, null)[0]
                    },
                    inputs,
                )
            }
            val who = if (memberOf[node.id] == decision && decision != null) "the decision joins" else "joins"
            return lose(l, "$who keyed rows with keyed rows not on the key — one candidate's rows meet another's", node)
        }
        val keyedLeft = l.keyed
        val k = if (keyedLeft) l else r
        val eq = equated(node, keyedLeft, k)
        val kSide = if (keyedLeft) PortNames.LEFT else PortNames.RIGHT
        val uSide = if (keyedLeft) PortNames.RIGHT else PortNames.LEFT
        val uRow = rows.input(node.id, uSide)
        // A semi/anti join outputs the left side only.
        val leftOnly = node.type == JoinType.SEMI || node.type == JoinType.ANTI
        val fromKeyed = joined(k.keyColumns, kSide, uRow, eq)
        val fromEquated = eq?.let { joined(it, uSide, null, null) }
        return when (node.type) {
            JoinType.INNER ->
                keyedWith(fromKeyed.mapIndexed { i, c -> c + (fromEquated?.get(i) ?: emptySet()) }, inputs)
            JoinType.LEFT, JoinType.SEMI ->
                when {
                    keyedLeft -> keyedWith(if (leftOnly) k.keyColumns else fromKeyed, inputs)
                    fromEquated != null -> keyedWith(if (leftOnly) eq else fromEquated, inputs)
                    else -> lose(k, joinLoss(node), node)
                }
            JoinType.ANTI -> if (keyedLeft) keyedWith(k.keyColumns, inputs) else lose(k, joinLoss(node), node)
            JoinType.RIGHT ->
                when {
                    !keyedLeft -> keyedWith(fromKeyed, inputs)
                    fromEquated != null -> keyedWith(fromEquated, inputs)
                    else -> lose(k, joinLoss(node), node)
                }
            JoinType.FULL, JoinType.CROSS -> lose(k, joinLoss(node), node)
        }
    }

    /**
     * The key columns as a join output names them: qualified by their side (`left.order_number`), and unqualified
     * unless the other side has a column of that name the join does not equate to the key (then a bare name
     * would be ambiguous — it might read the other side's value).
     */
    private fun joined(
        cols: List<Set<String>>,
        side: String,
        otherRow: Row?,
        eq: List<Set<String>>?,
    ): List<Set<String>> =
        cols.mapIndexed { i, comp ->
            val bare = comp.map { it.substringAfter('.') }.toSet()
            bare.map { "$side.$it" }.toSet() +
                bare.filter { otherRow?.containsKey(it) != true || eq?.get(i)?.contains(it) == true }
        }

    /** True when [ref] reads a column that carries key component [comp]. */
    private fun carries(
        comp: Set<String>,
        ref: ColumnRef,
    ): Boolean = if (ref.port == null) ref.column in comp else "${ref.port}.${ref.column}" in comp

    private fun joinLoss(node: Join): String =
        "a ${node.type.name.lowercase()} join keeps rows of the unkeyed side that no single candidate owns"

    /**
     * Per key component, the unkeyed side's columns the join equates to it (D-103) — from an `on: relation` from
     * the candidates' entity to the many side, or from the equi-conjuncts of `on:`. Null unless every component
     * is equated.
     */
    private fun equated(
        node: Join,
        keyedLeft: Boolean,
        keyed: Lineage,
    ): List<Set<String>>? {
        relationJoins[node.id]?.let { rel -> return relationKey(rel)?.map { setOf(it) } }
        val on = node.on ?: return null
        val leftRow = rows.input(node.id, PortNames.LEFT)
        val rightRow = rows.input(node.id, PortNames.RIGHT)
        val out = keyColumns.indices.map { mutableSetOf<String>() }
        for (c in conjuncts(on)) {
            val eq = c as? FunctionCall ?: continue
            if (eq.function != CatalogId.EQ || eq.args.size != 2) continue
            val a = eq.args[0] as? ColumnRef ?: continue
            val b = eq.args[1] as? ColumnRef ?: continue
            val sa = side(a, leftRow, rightRow) ?: continue
            val sb = side(b, leftRow, rightRow) ?: continue
            if (sa == sb) continue
            val (kRef, uRef) = if ((sa == PortNames.LEFT) == keyedLeft) a to b else b to a
            // `kRef` reads the keyed side (by its qualifier or its row), so its bare name is what to match.
            keyed.keyColumns.forEachIndexed { i, comp ->
                if (comp.any { it.substringAfter('.') == kRef.column }) out[i] += uRef.column
            }
        }
        return out.takeIf { cols -> cols.none { it.isEmpty() } }
    }

    /** Which join input a column reference reads: its port qualifier, else the one side whose row has the name. */
    private fun side(
        ref: ColumnRef,
        left: Row?,
        right: Row?,
    ): String? {
        if (ref.port == PortNames.LEFT || ref.port == PortNames.RIGHT) return ref.port
        val inLeft = left?.containsKey(ref.column) == true
        val inRight = right?.containsKey(ref.column) == true
        return when {
            inLeft && !inRight -> PortNames.LEFT
            inRight && !inLeft -> PortNames.RIGHT
            else -> null
        }
    }

    private fun conjuncts(e: Expression): List<Expression> =
        if (e is FunctionCall && e.function == CatalogId.AND) e.args.flatMap { conjuncts(it) } else listOf(e)

    /** An opaque role container's out port (06 A-5: `per candidate` with the key in its declared schema). */
    private fun opaque(node: Container): Lineage {
        val ins =
            node.declaredPorts
                .filter { it.direction == PortDirection.IN && it.kind == PortKind.DATA }
                .map { of(PortRef(node.id, it.name)) }
        val reading = ins.firstOrNull { it.readsCandidates } ?: return Lineage.LOOKUP
        val form = forms[node.id]
        val grain =
            when (form) {
                is IslandForm.Foreign -> form.grain
                is IslandForm.Call -> form.grain
                is IslandForm.Turn -> Grain.PER_CANDIDATE
                else -> null
            }
        val out = opaqueOut(node)
        if (grain == Grain.PER_CANDIDATE && out != null && keyColumns.all { it in out.keys }) {
            return keyedWith(keyColumns.map { setOf(it) }, ins)
        }
        val what =
            when {
                form is IslandForm.Builtin -> "a `builtin` island reads the candidates as a set"
                grain != Grain.PER_CANDIDATE -> "a `${formName(
                    form,
                )}` island runs for the whole set (not `per candidate`)"
                else -> "a `${formName(form)}` island's declared out row does not carry the key"
            }
        return lose(reading, what, node)
    }

    private fun formName(form: IslandForm?): String =
        when (form) {
            is IslandForm.Foreign -> "foreign"
            is IslandForm.Call -> "call"
            is IslandForm.Turn -> "turn"
            is IslandForm.Builtin -> "builtin"
            else -> "target"
        }

    private fun keyLabel(
        inp: Lineage,
        cols: List<Set<String>>,
    ): String =
        keyColumns
            .filterIndexed {
                i,
                _,
                ->
                cols[i].isEmpty() || inp.keyColumns.getOrNull(i) == null
            }.joinToString(", ")

    private fun opName(node: Node): String =
        when (node) {
            is Project -> "project"
            is Select -> "select"
            is Aggregate -> "aggregate"
            is Join -> "join"
            is TimeSugar -> node.kind
            else -> node::class.simpleName?.lowercase() ?: "op"
        }
}
