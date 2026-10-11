// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.model

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.expr.MdResolution
import org.tatrman.ttrp.resolve.Provenance

/**
 * An opaque fragment island (`"""sql` / `"""pandas` / `"""ttrb`) inside a container.
 * Phase 1 keeps fragment interiors VERBATIM (C2-f); clause→node decomposition is
 * Phase 6 (the dialect grammars). In Phase 2 a fragment container therefore has NO
 * decomposed members — it carries the raw fragment and exposes a single default out.
 */
data class FragmentSource(
    val tag: String,
    val sourceText: String,
) {
    /**
     * True when the interior is ENGINE text an emitter may run as written (`"""sql`, `"""pandas`). A TTR-B
     * interior (`"""ttrb`, `"""ttrb-cs`) is sentences, never engine text: its container emits from the
     * decomposed members like a canonical body (AG B4).
     */
    val verbatim: Boolean get() = !(tag == "ttrb" || tag.startsWith("ttrb-"))
}

/**
 * A container (B-T9): a closed function grouping ops, no processing value, whose
 * ports map onto internal node ports. In v1 it bears the author-assigned execution
 * target (engine-instance qname string; resolved against the world in Stage 2.2).
 * A fragment container carries its raw fragment ([fragment]) and is opaque in Phase 2.
 */
data class Container(
    override val id: String,
    override val label: String,
    override val location: SourceLocation,
    /** The author-assigned execution target (engine instance qname, e.g. `erp_pg`). */
    val target: String,
    /** Member node ids belonging to this container. */
    val memberIds: List<String>,
    /** Declared container ports (`in …, out …, err …`). */
    val declaredPorts: List<Port>,
    /** Container port name → the internal node port it maps to (B-T9 encapsulation). */
    val portMapping: Map<String, PortRef>,
    /** Non-null for a fragment container (opaque until Phase-6 decomposition). */
    val fragment: FragmentSource? = null,
    /** PL-P2.S1 (F-4-iv): the source island this container runs on-failure-of (manifest `island.onFailureOf`). */
    val onFailureOf: String? = null,
    /** PL-P2.S1 (F-4-ii): manifest-declared per-island retry count (`retries N`). */
    val retries: Int? = null,
    override val provenance: Provenance? = null,
) : Node {
    override fun ports(): List<Port> = declaredPorts

    /** The fragment an emitter runs as written, or null — a TTR-B fragment emits from its members. */
    val verbatimFragment: FragmentSource? get() = fragment?.takeIf { it.verbatim }

    override fun defaultIn(): String? = declaredPorts.firstOrNull { it.direction == PortDirection.IN }?.name

    override fun defaultOut(): String? =
        declaredPorts.firstOrNull { it.direction == PortDirection.OUT && it.kind == PortKind.DATA }?.name
}

/**
 * Where a Polars-placed MD read's staged scalar lives (S4-B4): the container IN [port] the hoist
 * added (read as `staging/<port>.arrow`) and the [column] of that 1-row frame carrying this read's
 * value. Keyed on the `mdPath` node's source location in [TtrpGraph.mdStaging] so the Polars emitter
 * can render the read as `pl.lit(<port>.item(0, "<column>"))` instead of an unsupported inline scan.
 */
data class MdStageRef(
    val port: String,
    val column: String,
)

/** Edge kinds — DATA flow or the two v1 control constraints (FS/SS). NO FF arm (B-T2-as-amended; FF ⇒ CTL-001). */
enum class EdgeKind { DATA, CONTROL_FS, CONTROL_SS }

/**
 * A connection from an out-port to an in-port. Control edges (`CONTROL_FS`/`_SS`)
 * are hard on their effect (B-T2) — the 2.3 rewriter must preserve them.
 */
data class Edge(
    val from: PortRef,
    val to: PortRef,
    val kind: EdgeKind,
    val location: SourceLocation = SourceLocation.UNKNOWN,
)

/**
 * The one internal graph (B-T4: one document = one program = one acyclic graph).
 * Insertion-ordered collections only (determinism groundwork for Stage 2.3). All
 * nodes — including container members — live in the flat [nodes] map; [containers]
 * records grouping + port mapping. Program-level leaves (Load/Store/Display) are
 * ordinary [nodes] not inside any container.
 */
data class TtrpGraph(
    val nodes: Map<String, Node>,
    val edges: List<Edge>,
    val containers: Map<String, Container>,
    /**
     * RJ-P1 rejects-elaboration provenance (contracts §5 / RS-5): synthesized-node id →
     * authored-node id. Populated only by the reject-elaboration stratum; empty for every
     * un-elaborated (fail-fast) graph, so it never appears in [org.tatrman.ttrp.graph.explain.NormalizedGraphJson]
     * and the byte-identity pin is unaffected. A side table (not a per-node field) keeps the
     * node roster and its serialization untouched, and is what `CapabilityChecker` consumes for
     * whole-cluster escalation (task 1.3.5).
     */
    val synthProvenance: Map<String, String> = emptyMap(),
    /**
     * MD dot-path resolutions (S3), keyed by the `mdPath` node's source location — the graph-side
     * annotation the S4 read lowering consumes (decision: carry the resolution on the IR, not
     * re-resolve in emit). Empty for programs with no MD paths. See [org.tatrman.ttrp.expr.MdResolution].
     */
    val mdResolutions: Map<SourceLocation, MdResolution> = emptyMap(),
    /**
     * MD dot-path staging (S4-B4), keyed by the `mdPath` node's source location — where each MD read
     * in a non-SQL (Polars) container gets its value from, once the hoist stratum
     * ([org.tatrman.ttrp.graph.movement.MdReadHoist]) has moved the read into its own db island and
     * staged the 1-row result. Empty for SQL-placed programs (MD reads lower inline there) and for
     * programs with no MD paths. Consumed only by the Polars emitter; a side table like
     * [mdResolutions], so the node roster + [org.tatrman.ttrp.graph.explain.NormalizedGraphJson] are
     * untouched.
     */
    val mdStaging: Map<SourceLocation, MdStageRef> = emptyMap(),
    /**
     * The synthesized md-source container ids (S4-B4) — the db islands the hoist stratum created to
     * compute the staged MD scalars. The bundle emitter routes these to a `.sql` island whose text is
     * the MD read SELECT ([org.tatrman.ttrp.emit.sql.MdSourceIslandEmitter]), so the existing
     * fragment→transfer path stages the result. Empty unless the hoist fired.
     */
    val mdSourceContainers: Set<String> = emptySet(),
    /**
     * AG-P0: the physical source of each model-object [Load] (node id → the frontend-resolved er→db
     * binding: a db table, or an inline query/view text, with logical→physical columns). Consumed by
     * the SQL emitter to scan real tables; a side table like [mdResolutions], so the node roster and
     * [org.tatrman.ttrp.graph.explain.NormalizedGraphJson] are untouched. Empty without a model repo.
     */
    val loadSources: Map<String, org.tatrman.ttrp.resolve.LoadSource> = emptyMap(),
    /**
     * AG-P0: Join node id → its `on: relation X` condition in the **logical** (er attribute) spelling.
     * [Join.on] keeps the db-spelled synthesis for the existing emit paths; the `sql-text` emitter,
     * whose loads keep logical names, reads this instead. Empty when no relation join is authored.
     */
    val logicalJoins: Map<String, org.tatrman.ttrp.expr.Expression> = emptyMap(),
) {
    fun node(id: String): Node? = nodes[id]

    /** The container that owns [nodeId], or null for a program-level node. */
    fun containerOf(nodeId: String): Container? = containers.values.firstOrNull { nodeId in it.memberIds }

    /** Edges leaving any port of [nodeId]. */
    fun edgesFrom(nodeId: String): List<Edge> = edges.filter { it.from.nodeId == nodeId }

    /** Edges entering any port of [nodeId]. */
    fun edgesTo(nodeId: String): List<Edge> = edges.filter { it.to.nodeId == nodeId }

    companion object {
        val EMPTY = TtrpGraph(emptyMap(), emptyList(), emptyMap())
    }
}
