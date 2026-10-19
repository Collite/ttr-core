// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.Grain
import org.tatrman.ttrp.ast.IslandForm
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
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
 * One port's key lineage (06 A-5).
 * - [keyed]: every row carries the candidates' key and descends from exactly one candidate row.
 * - [keyColumns]: per key component, the column names that carry it. A bare name is unambiguous in the row; a
 *   `left.` / `right.` name is valid only for the op reading a join's output directly.
 * - [readsCandidates]: the rows depend on the candidates at all (a lookup's do not).
 * - [lostAt]: the first op that lost the key, for an unkeyed port that reads the candidates.
 * - [viaContext]: inside the decision, the rows came in through an `in` port a context feeds. That context is
 *   classified on its own, and its class is already in the mode verdict.
 */
data class Lineage(
    val keyed: Boolean,
    val keyColumns: List<Set<String>> = emptyList(),
    val readsCandidates: Boolean = keyed,
    val lostAt: LostKey? = null,
    val viaContext: Boolean = false,
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
 * The forward key-lineage pass over a recipe's graph (AU-core-P1 · S1.2; 06 A-5 + D-103): one bit per port, with the
 * op table as a `when`. It is conservative by design (D-28): what it cannot prove keyed is not keyed.
 *
 * - **Seeds.** The candidates' (default) out port is keyed by definition.
 * - **Ops that keep the input's lineage:** `filter`, `sort` (06 A-5's table), `distinct`, `within`, and the
 *   `switch` / `branch` outputs.
 *   - A `limit` over keyed rows loses the key (D-133, amending A-5): whether a candidate's row is among the first N
 *     depends on the others.
 *   - A `filter` whose fragment held an `IN` / `EXISTS` subquery the decomposition could not follow loses the key,
 *     when its island reads the candidates.
 *   - A `calc` that overwrites a key column loses it.
 *   - `latest` keeps the key when every key component is among its `per:` columns.
 *   - `project` / `select` keep it when every key component survives as a plain column (renamed or not).
 *   - `aggregate` keeps it when every key component is in `group_by`.
 * - **Set operations** (`union` / `intersect` / `except`) combine columns by position. They keep the key when every
 *   input is keyed with a key column at the same position.
 * - **`join`:**
 *   - Both sides keyed and joined on the key pairs a candidate with itself — the `17` CTE pattern, and the decision
 *     joining its contexts back. On anything else, one candidate meets another, and the key is lost.
 *   - One side keyed, the other a lookup: `inner` is keyed; `left` / `semi` / `anti` are keyed with the keyed side
 *     on the left, and `right` with it on the right.
 *   - D-103 extends that: a `left` / `right` / `semi` join whose keyed side is the other one is keyed when the
 *     unkeyed side's columns equate to the key — by an equi-`on:`, or by an `on: relation` from the key's entity to
 *     the many side ([relationKey]).
 *   - One side keyed, the other unkeyed rows built from the candidates (an aggregate of them, a set-level
 *     context's rows): one candidate's row meets the others'. The key is lost.
 *     - The exception is in the decision, for the rows of a classified set-level context: the mode verdict already
 *       holds that context's class.
 *   - `full` and `cross` are not keyed.
 * - **Opaque islands** (`foreign`, `call`, `turn`) are keyed iff:
 *   - every input that reads the candidates is keyed;
 *   - the island runs `per candidate`;
 *   - its declared out row carries the key.
 *   A `builtin` never is.
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
            if (port?.direction == PortDirection.IN) {
                val feed = rows.feed(ref) ?: return Lineage.LOOKUP
                val fed = of(feed)
                // Inside the decision, rows a context feeds were classified with that context.
                return if (node.id == decision && feed.nodeId != candidates) fed.copy(viaContext = true) else fed
            }
            if (node.id == candidates) {
                // 06 §1.2: one out port carries the key. A second one is REC-000; it is no seed.
                return if (ref.port == node.defaultOut()) {
                    keyedSeed()
                } else {
                    Lineage(
                        false,
                        readsCandidates = true,
                        lostAt = LostKey("a second out port of the candidates", node.location),
                    )
                }
            }
            node.portMapping[ref.port]?.let { return of(it) }
            return opaque(node)
        }
        return when (node) {
            is Load, is Values -> Lineage.LOOKUP
            is Filter -> filter(node)
            is Sort, is Distinct, is Branch, is Switch -> input(node.id)
            is Limit -> limit(node)
            is TimeSugar -> if (node.kind == "latest") latest(node) else input(node.id)
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
                    node.columns.map { c -> ColumnRef(null, c, node.location) to (node.renames[c] ?: c) },
                    "selects",
                )
            is Aggregate -> aggregate(node)
            is Join -> join(node)
            is Union -> setOperation(node, (1..node.arity).map { "in$it" }, "union")
            is Intersect, is Except -> setOperation(node, listOf("in1", "in2"), "set operation")
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

    /** A keyed result; one whose key no column carries unambiguously any more is not keyed ([what] says why). */
    private fun keyedWith(
        columns: List<Set<String>>,
        inputs: List<Lineage>,
        node: Node,
        what: String = "the key column is ambiguous after the join — the joined rows carry another of that name",
    ): Lineage =
        if (columns.any { it.isEmpty() }) {
            lose(inputs.firstOrNull { it.keyed } ?: inputs.first(), what, node)
        } else {
            Lineage(true, columns, readsCandidates = true, viaContext = inputs.any { it.viaContext })
        }

    /**
     * A `filter` keeps the key — unless its predicate held a subquery the TTR-SQL decomposition could not follow
     * (`x IN (SELECT …)` inside a larger WHERE folds to an empty `IN` list, `EXISTS (…)` to a bare `op.exists`): what
     * the subquery reads is invisible here, so an island that reads the candidates is set-level until it is restated.
     */
    private fun filter(node: Filter): Lineage {
        val inp = input(node.id)
        val pred = node.predicate ?: return inp
        val blind =
            RecipeChecker.walk(pred).any {
                (it is InList && it.items.isEmpty()) ||
                    (it is FunctionCall && it.function == EXISTS && it.args.isEmpty())
            }
        if (!blind) return inp
        val container = memberOf[node.id]?.let { graph.containers[it] } ?: return inp
        val readsCandidates =
            container.declaredPorts
                .filter { it.direction == PortDirection.IN && it.kind == PortKind.DATA }
                .any { of(PortRef(container.id, it.name)).readsCandidates }
        if (!readsCandidates) return inp
        return lose(
            inp.copy(lostAt = null),
            "an `IN` / `EXISTS` subquery the checker cannot follow — restate it as a join",
            node,
        )
    }

    /**
     * D-133: a `limit` keeps the first N rows of the whole stream. Over the candidates' rows, a candidate's row survives
     * only while fewer than N others come before it, so its verdict would change with the candidates a run holds — a
     * batch of all of them or one key on an event. Over a lookup it reads no candidate and changes nothing.
     */
    private fun limit(node: Limit): Lineage {
        val inp = input(node.id)
        if (!inp.keyed) return inp
        val op = node.count?.let { "`limit($it)`" } ?: "`limit`"
        return lose(
            inp,
            "$op keeps the first rows of all the candidates taken together — whether one survives depends on the others",
            node,
        )
    }

    /** `latest(by:, per:)` keeps one row per `per:` key: the candidates' key survives only if it is among them. */
    private fun latest(node: TimeSugar): Lineage {
        val inp = input(node.id)
        if (!inp.keyed) return inp
        val per = node.columnsOf("per").toSet()
        val cols = inp.keyColumns.map { comp -> bare(comp).intersect(per) }
        return if (cols.any { it.isEmpty() }) {
            lose(inp, "`latest` keeps one row per `per: ${per.joinToString(", ")}` — not per candidate", node)
        } else {
            inp.copy(keyColumns = cols)
        }
    }

    private fun calc(node: Calc): Lineage {
        val inp = input(node.id)
        if (!inp.keyed) return inp
        val overwritten = node.assignments.map { it.name }.toSet()
        val cols = inp.keyColumns.map { comp -> comp.filterNot { it.substringAfter('.') in overwritten }.toSet() }
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
            lose(inp, "`${opName(node)}` $verb without the key `${missing(cols)}`", node)
        } else {
            inp.copy(keyColumns = cols)
        }
    }

    private fun aggregate(node: Aggregate): Lineage {
        val inp = input(node.id)
        if (!inp.keyed || node.distinctAllColumns) return inp
        val cols = inp.keyColumns.map { comp -> bare(comp).intersect(node.groupBy.toSet()) }
        return if (cols.any { it.isEmpty() }) {
            lose(inp, "aggregates the candidates without the key `${missing(cols)}` in `group_by`", node)
        } else {
            inp.copy(keyColumns = cols)
        }
    }

    /**
     * A set operation combines its inputs' columns **by position**, named as in the first (SQL, plan.v1): key
     * component i survives at a position where every input carries it.
     */
    private fun setOperation(
        node: Node,
        ports: List<String>,
        what: String,
    ): Lineage {
        val inputs = ports.map { input(node.id, it) }
        if (inputs.none { it.readsCandidates }) return Lineage.LOOKUP
        val first = inputs.firstOrNull { it.readsCandidates }!!
        if (!inputs.all { it.keyed }) return lose(first, "a $what over rows not all keyed", node)
        val columnLists = ports.map { p -> rows.input(node.id, p)?.keys?.toList() }
        if (columnLists.any {
                it == null
            }
        ) {
            return lose(first.copy(lostAt = null), "a $what over rows the checker cannot read", node)
        }
        val lists = columnLists.map { it!! }
        val cols =
            keyColumns.indices.map { i ->
                lists[0]
                    .indices
                    .filter { pos ->
                        lists.withIndex().all { (k, cs) -> pos < cs.size && cs[pos] in bare(inputs[k].keyColumns[i]) }
                    }.map { lists[0][it] }
                    .toSet()
            }
        return keyedWith(
            cols,
            inputs,
            node,
            "a $what does not line the key up — its inputs carry it at different positions",
        )
    }

    private fun join(node: Join): Lineage {
        val l = input(node.id, PortNames.LEFT)
        val r = input(node.id, PortNames.RIGHT)
        val inputs = listOf(l, r)
        val inDecision = decision != null && memberOf[node.id] == decision
        if (!l.keyed && !r.keyed) {
            val reading = inputs.firstOrNull { it.readsCandidates } ?: return Lineage.LOOKUP
            return lose(reading, "joins rows not keyed", node)
        }
        if (l.keyed && r.keyed) {
            // Both sides descend from candidates. Joined ON the key, a row pairs a candidate with itself (the key is
            // unique, REC-004). On anything else it pairs one candidate with another (06 A-5).
            val eq = equated(node, keyedLeft = true, keyed = l)
            if (eq != null && keyColumns.indices.all { i -> eq[i].any { u -> u in bare(r.keyColumns[i]) } }) {
                return keyedWith(
                    keyColumns.indices.map { i ->
                        joined(listOf(l.keyColumns[i]), PortNames.LEFT, null, null)[0] +
                            joined(listOf(r.keyColumns[i]), PortNames.RIGHT, null, null)[0]
                    },
                    inputs,
                    node,
                )
            }
            val who = if (inDecision) "the decision joins" else "joins"
            return lose(l, "$who keyed rows with keyed rows not on the key — one candidate's rows meet another's", node)
        }
        val keyedLeft = l.keyed
        val k = if (keyedLeft) l else r
        val u = if (keyedLeft) r else l
        // The other side is built from the candidates but carries no key (an aggregate of them, a set-level
        // context): a candidate's row then meets the others'. In the decision, a classified context's rows are fine.
        if (u.readsCandidates && !(inDecision && u.viaContext)) {
            val where = u.lostAt?.let { " (${it.what})" } ?: ""
            return lose(
                k,
                "joins the candidates with rows built from the candidates$where — one candidate meets the others",
                node,
            )
        }
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
                keyedWith(fromKeyed.mapIndexed { i, c -> c + (fromEquated?.get(i) ?: emptySet()) }, inputs, node)
            JoinType.LEFT, JoinType.SEMI ->
                when {
                    keyedLeft -> keyedWith(if (leftOnly) bareOnly(k.keyColumns) else fromKeyed, inputs, node)
                    fromEquated != null -> keyedWith(if (leftOnly) eq else fromEquated, inputs, node)
                    else -> lose(k, joinLoss(node), node)
                }
            JoinType.ANTI ->
                if (keyedLeft) {
                    keyedWith(
                        bareOnly(k.keyColumns),
                        inputs,
                        node,
                    )
                } else {
                    lose(k, joinLoss(node), node)
                }
            JoinType.RIGHT ->
                when {
                    !keyedLeft -> keyedWith(fromKeyed, inputs, node)
                    fromEquated != null -> keyedWith(fromEquated, inputs, node)
                    else -> lose(k, joinLoss(node), node)
                }
            JoinType.FULL, JoinType.CROSS -> lose(k, joinLoss(node), node)
        }
    }

    /**
     * The key columns as a join output names them: qualified by their side (`left.order_number`), and bare unless
     * the other side has a column of that name the join does not equate to the key (a bare name would then be
     * ambiguous). Only the input's **bare** names go in: a qualified one named a side of an earlier join.
     */
    private fun joined(
        cols: List<Set<String>>,
        side: String,
        otherRow: Row?,
        eq: List<Set<String>>?,
    ): List<Set<String>> =
        cols.mapIndexed { i, comp ->
            val names = bare(comp)
            names.map { "$side.$it" }.toSet() +
                names.filter { otherRow?.containsKey(it) != true || eq?.get(i)?.contains(it) == true }
        }

    /**
     * True when [ref] reads a column that carries key component [comp]. A `left.` / `right.` qualifier names a side
     * of the join right before — when [comp] holds side-qualified names; otherwise (no join before, as in a
     * single-table TTR-SQL select `o.order_number`) it names the input's own column.
     */
    private fun carries(
        comp: Set<String>,
        ref: ColumnRef,
    ): Boolean =
        when {
            ref.port == null -> ref.column in comp
            comp.any { '.' in it } -> "${ref.port}.${ref.column}" in comp
            ref.port == PortNames.LEFT || ref.port == PortNames.RIGHT -> ref.column in comp
            else -> false
        }

    private fun joinLoss(node: Join): String =
        "a ${node.type.name.lowercase()} join keeps rows of the unkeyed side that no single candidate owns"

    /**
     * Per key component, the unkeyed side's columns the join equates to it (D-103) — from an `on: relation` from
     * the candidates' entity to the many side, or from the equi-conjuncts of `on:`. A keyed-side reference counts
     * only when it names an unambiguous (bare) key column of that side. Null unless every component is equated.
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
            keyed.keyColumns.forEachIndexed { i, comp -> if (kRef.column in bare(comp)) out[i] += uRef.column }
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
        val reading = ins.filter { it.readsCandidates }
        if (reading.isEmpty()) return Lineage.LOOKUP
        val form = forms[node.id]
        val grain =
            when (form) {
                is IslandForm.Foreign -> form.grain
                is IslandForm.Call -> form.grain
                is IslandForm.Turn -> Grain.PER_CANDIDATE
                else -> null
            }
        val out = opaqueOut(node)
        val what =
            when {
                form is IslandForm.Builtin -> "a `builtin` island reads the candidates as a set"
                reading.any { !it.keyed } -> "a `${formName(
                    form,
                )}` island reads rows built from the candidates without the key"
                grain != Grain.PER_CANDIDATE -> "a `${formName(
                    form,
                )}` island runs for the whole set (not `per candidate`)"
                out == null || !keyColumns.all { it in out.keys } ->
                    "a `${formName(form)}` island's declared out row does not carry the key"
                else -> return keyedWith(keyColumns.map { setOf(it) }, ins, node)
            }
        return lose(reading.first().copy(lostAt = reading.firstOrNull { !it.keyed }?.lostAt), what, node)
    }

    private fun formName(form: IslandForm?): String =
        when (form) {
            is IslandForm.Foreign -> "foreign"
            is IslandForm.Call -> "call"
            is IslandForm.Turn -> "turn"
            is IslandForm.Builtin -> "builtin"
            else -> "target"
        }

    private fun missing(cols: List<Set<String>>): String =
        keyColumns.filterIndexed { i, _ -> cols[i].isEmpty() }.joinToString(", ")

    private fun opName(node: Node): String =
        when (node) {
            is Project -> "project"
            is Select -> "select"
            is Aggregate -> "aggregate"
            is Join -> "join"
            is TimeSugar -> node.kind
            else -> node::class.simpleName?.lowercase() ?: "op"
        }

    private companion object {
        val EXISTS = CatalogId("op.exists")

        /** A component's unambiguous names — the bare ones. */
        fun bare(comp: Set<String>): Set<String> = comp.filterNot { '.' in it }.toSet()

        fun bareOnly(cols: List<Set<String>>): List<Set<String>> = cols.map { bare(it) }
    }
}
