// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.model

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.resolve.Provenance

/**
 * The one internal graph node set (T10 roster + T9 movement/IO + B-T2 routing).
 * Every node carries a stable [id] (document order), an SSA [label] (Q7-γ: the
 * surviving variable name, or `~n` anonymous per C1-c-i), a [location], and an
 * optional [provenance] slot (E-d er origin). Port signatures are per-kind:
 * [ports] lists them, [defaultIn]/[defaultOut] name the default port (B-T2).
 *
 * Authoring-sugar forms (Select/Calc/Distinct) are [SugarNode]s — representable at
 * construction; Stage 2.3a's sugar stratum expands them. `Materialize` is NOT a
 * surface/graph node (S13) — it exists only as a 2.3 rewrite-output pattern.
 */
sealed interface Node {
    val id: String
    val label: String
    val location: SourceLocation
    val provenance: Provenance?

    fun ports(): List<Port>

    /** The default in-port name (for `a -> b` default-port elision), or null if none. */
    fun defaultIn(): String? = PortNames.IN

    /** The default out-port name, or null if the node has no single default out (sinks, Branch/Switch). */
    fun defaultOut(): String? = PortNames.OUT
}

/** Marker for authoring-sugar nodes the 2.3a sugar stratum expands (B-T10). */
sealed interface SugarNode : Node

/** Join kinds (B-T10 sweep: semi/anti are Join types, not separate nodes). */
enum class JoinType { INNER, LEFT, RIGHT, FULL, SEMI, ANTI, CROSS }

/** One `name = <agg-or-scalar>` binding inside an `aggregate { … }` / `pivot` config. */
data class Aggregation(
    val name: String,
    val value: Expression,
)

// ---- shared port shapes ----

private fun unaryIn() = Port(PortNames.IN, PortKind.DATA, PortDirection.IN)

private fun dataOut() = Port(PortNames.OUT, PortKind.DATA, PortDirection.OUT)

private fun unaryDataPorts(): List<Port> = listOf(unaryIn(), dataOut()) + PortNames.errorPorts()

private fun sourcePorts(): List<Port> = listOf(dataOut()) + PortNames.errorPorts()

// ---- transform nodes (T10) ----

data class Project(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val columns: List<Expression> = emptyList(),
    /**
     * Optional output name per column (parallel to [columns]); a null entry (or a short/empty list)
     * ⇒ derive the name (a [ColumnRef]'s own column, else the engine default). Carries the names a
     * `calc { name = expr }` assigns, which the bare-[Expression] [columns] list cannot (RJ-P3).
     */
    val aliases: List<String?> = emptyList(),
    /**
     * `calc` add-semantics (language-design §): pass through every input column **not** overridden
     * by an alias in this projection, before the listed computed columns. False = replace-semantics
     * (`project`/`select`: exactly the listed columns). RJ-P3 guard/reject/cast calcs set this.
     */
    val passthrough: Boolean = false,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()

    /** The output alias for column [i] (explicit alias, else the column's own name if it is a ref). */
    fun aliasOf(i: Int): String? = aliases.getOrNull(i) ?: (columns.getOrNull(i) as? ColumnRef)?.column
}

data class Select(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val columns: List<String> = emptyList(),
    val renames: Map<String, String> = emptyMap(),
    override val provenance: Provenance? = null,
) : SugarNode {
    override fun ports() = unaryDataPorts()
}

data class Calc(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val assignments: List<Aggregation> = emptyList(),
    override val provenance: Provenance? = null,
) : SugarNode {
    override fun ports() = unaryDataPorts()
}

data class Filter(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val predicate: Expression?,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

data class Branch(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val predicate: Expression?,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() =
        listOf(
            unaryIn(),
            Port(PortNames.TRUE, PortKind.DATA, PortDirection.OUT),
            Port(PortNames.FALSE, PortKind.DATA, PortDirection.OUT),
        ) + PortNames.errorPorts()

    override fun defaultOut(): String? = null
}

data class Switch(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val cases: List<Pair<String, Expression?>> = emptyList(),
    val hasElse: Boolean = false,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports(): List<Port> {
        val outs = cases.map { Port(it.first, PortKind.DATA, PortDirection.OUT) }
        val elsePort = if (hasElse) listOf(Port(PortNames.ELSE, PortKind.DATA, PortDirection.OUT)) else emptyList()
        return listOf(unaryIn()) + outs + elsePort + PortNames.errorPorts()
    }

    override fun defaultOut(): String? = null
}

data class Join(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val type: JoinType,
    val on: Expression? = null,
    /**
     * Set-semantics marker (B-T10 sweep): when true the join matches on ALL columns of
     * both inputs (the `intersect`/`except` → SEMI/ANTI lowering). It is DISTINCT from
     * `on == null`, which is an unconditioned/cross match — the P3 emitter expands an
     * `onAllColumns` join to a full-row equality over the input schema. A plain equijoin
     * carries its condition in [on] and leaves this false.
     */
    val onAllColumns: Boolean = false,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() =
        listOf(
            Port(PortNames.LEFT, PortKind.DATA, PortDirection.IN),
            Port(PortNames.RIGHT, PortKind.DATA, PortDirection.IN),
            dataOut(),
        ) + PortNames.errorPorts()

    override fun defaultIn(): String? = null
}

data class Aggregate(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val groupBy: List<String> = emptyList(),
    val aggregations: List<Aggregation> = emptyList(),
    val having: Expression? = null,
    /**
     * Distinct-dedup marker (B-T10 sweep): when true this aggregate groups by ALL columns
     * of its input with no aggregate calls (the `distinct` → Aggregate lowering). It is
     * DISTINCT from an empty [groupBy], which is a scalar `GROUP BY ()` collapsing every
     * row to one — the P3 emitter enumerates the input schema for an `distinctAllColumns`
     * aggregate.
     */
    val distinctAllColumns: Boolean = false,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

data class Sort(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val keys: List<String> = emptyList(),
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

/** N-ary set union (S11: list form → internal ports `in1..inN`). */
data class Union(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val arity: Int,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() =
        (1..arity).map { Port("in$it", PortKind.DATA, PortDirection.IN) } + dataOut() + PortNames.errorPorts()

    override fun defaultIn(): String? = null
}

data class Intersect(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() =
        listOf(
            Port("in1", PortKind.DATA, PortDirection.IN),
            Port("in2", PortKind.DATA, PortDirection.IN),
            dataOut(),
        ) + PortNames.errorPorts()

    override fun defaultIn(): String? = null
}

data class Except(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() =
        listOf(
            Port("in1", PortKind.DATA, PortDirection.IN),
            Port("in2", PortKind.DATA, PortDirection.IN),
            dataOut(),
        ) + PortNames.errorPorts()

    override fun defaultIn(): String? = null
}

data class Values(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val schema: List<PortColumn> = emptyList(),
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = sourcePorts()

    override fun defaultIn(): String? = null
}

data class Limit(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val count: Long? = null,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

/** Static/declared-value Pivot (T10; native or CASE per dialect — a 2.3 lowering decision). */
data class Pivot(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val declaredValues: List<String> = emptyList(),
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

data class Distinct(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    override val provenance: Provenance? = null,
) : SugarNode {
    override fun ports() = unaryDataPorts()
}

// ---- movement / IO (T9) ----

/** Physical → engine memory. A source: no data in-port. */
data class Load(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val source: String,
    /** The named/inline declared schema reference (`schema: sales_csv`), if any (D-c). */
    val schemaRef: String? = null,
    val schema: List<PortColumn>? = null,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = sourcePorts()

    override fun defaultIn(): String? = null
}

/** Engine memory → physical (the engine "write"). A sink: no data out-port. */
data class Store(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val target: String,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = listOf(unaryIn()) + PortNames.errorPorts()

    override fun defaultOut(): String? = null
}

/** A Charon call: physical → physical (may convert format). Synthesized in 2.3b. */
data class Transfer(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val via: String? = null,
    val format: String = "arrow-ipc",
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

data class Index(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports() = unaryDataPorts()
}

/**
 * Sink-only leaf (Q11): dynamic schema exception — its input port accepts any schema. An **action display**
 * ([schema] non-null — its name is a `def schema` row schema an import brings into scope, grammar 0.14) is the
 * exception to the exception: the frontend held its rows to [schema] (TTRP-DSP-*), and the emitters project
 * the rows to the schema's columns, in schema order. Several Display nodes may share an action display's name.
 */
data class Display(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    val name: String,
    override val provenance: Provenance? = null,
    val schema: org.tatrman.ttrp.resolve.DisplaySchema? = null,
) : Node {
    override fun ports() =
        listOf(Port(PortNames.IN, PortKind.DATA, PortDirection.IN, schema = null)) + PortNames.errorPorts()

    override fun defaultOut(): String? = null
}
