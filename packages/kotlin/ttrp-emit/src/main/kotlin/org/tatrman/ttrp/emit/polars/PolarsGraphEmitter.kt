// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.polars

import org.tatrman.ttrp.emit.core.RejectSites
import org.tatrman.ttrp.emit.core.SsaNames
import org.tatrman.ttrp.graph.capability.BoundWorld
import org.tatrman.ttrp.graph.model.Container
import org.tatrman.ttrp.graph.model.Display
import org.tatrman.ttrp.graph.model.DisplayLeaves
import org.tatrman.ttrp.graph.model.EdgeKind
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.Node
import org.tatrman.ttrp.graph.model.Store
import org.tatrman.ttrp.graph.model.TtrpGraph

/**
 * Walks a normalized Polars container in the graph and produces the ordered [PolarsStep] list
 * [PolarsIslandEmitter] consumes. Container IN ports become staged Arrow reads (materialized by
 * the upstream transfer, F-c-i β); member Loads read their world storage (CSV via the declared
 * schema, D-c/T7); each container OUT port that maps to a member is written to its sink
 * (Display → `out/`, Store → `staging/`) after the mainline statements.
 *
 * **Rejects (RJ-P4):** a **wired** `rejects` port is elaborated by the RJ-P1 stratum into a real
 * reject terminal (guard-calc → branch → reject-project) and re-wired onto a normal `.out`
 * producer, so it flows through the member walk like any output — its guard-calc renders the
 * canonical validity mask via [RejectGuardPolars] in [PolarsIslandEmitter]. An OUT port **still**
 * mapped literally to a node's `rejects` is a *dead wire* (a node that can never reject, RJ-101)
 * and is skipped — an empty erroneous-rows stream is not emitted, matching the SQL emitter.
 */
class PolarsGraphEmitter(
    private val graph: TtrpGraph,
    @Suppress("unused") private val world: BoundWorld,
) {
    /** True if the container OUT port [portName] maps to a member node's `rejects` port. */
    fun isRejectsPort(
        container: Container,
        portName: String,
    ): Boolean = container.portMapping[portName]?.port == "rejects"

    fun steps(container: Container): List<PolarsStep> {
        val members = container.memberIds.mapNotNull { graph.nodes[it] }
        val ordered = topoOrder(container, members)
        val names = SsaNames.assign(ordered)

        // Container IN ports → staged reads. Var name = the port name.
        val inPorts = container.declaredPorts.filter { it.direction.name == "IN" }.map { it.name }
        val portVar = inPorts.associateWith { it }

        val steps = mutableListOf<PolarsStep>()
        // 1. Staged reads for each IN port (fed by an upstream transfer).
        inPorts.forEach { port ->
            steps +=
                PolarsStep(varName = port, node = stagedReadNode(container, port), source = PolarsSource.Staged(port))
        }
        // 2. Mainline member statements.
        ordered.forEach { node ->
            steps +=
                PolarsStep(
                    varName = names.getValue(node.id),
                    node = node,
                    inputVars = inputVarsOf(node, container, names, portVar),
                    source = (node as? Load)?.let { loadSource(it) },
                )
        }
        // 3. Sinks for each mapped OUT port. An elaborated rejects producer is re-wired to a
        //    normal `.out` (RJ-P1), so it flows here; a port still literally mapped to `.rejects`
        //    is a dead wire (RJ-101) — an empty erroneous-rows stream is not emitted (matches SQL).
        container.portMapping.forEach { (port, ref) ->
            if (ref.port == "rejects") return@forEach // dead wire (RJ-101)
            val producer = graph.nodes[ref.nodeId] ?: return@forEach
            val producerVar = names[producer.id] ?: return@forEach
            sinksFor(container, port).forEach { steps += it.copy(inputVars = listOf(producerVar)) }
        }
        return steps
    }

    /**
     * The elaborated reject sites' partition frames (RJ-P5), by SSA var — mirrors
     * [org.tatrman.ttrp.emit.bundle.BundleAssembler]'s `rejectSites` derivation: a portMapping target
     * in [TtrpGraph.synthProvenance] marks a rejects port. For each, the `rejects` frame is that
     * producer's var, `in` is the site guard's input frame, and `processed` is the guard's **clean
     * output** (the branch-true child — the authored op applied to the rows that passed the guard).
     *
     * `processed` is counted at the split's clean output, **not** at the terminal OUT ports: any
     * row-dropping/-collapsing op downstream of the split (the hero's `join`+`aggregate`) makes the
     * OUT-port counts diverge from `in − rejects`. The clean frame is the split's honest witness —
     * `in == processed + rejects` holds at the guard regardless of what follows (RH-1's guard flows
     * straight into a row-preserving branch, so its OUT ports coincidentally agreed; the hero does
     * not). See the seal note in the design control room (R-D3).
     */
    fun partitions(container: Container): List<PolarsPartition> {
        val members = container.memberIds.mapNotNull { graph.nodes[it] }
        val ordered = topoOrder(container, members)
        val names = SsaNames.assign(ordered)
        return RejectSites.of(graph, container).mapNotNull { site ->
            val inVar =
                if (site.inFrom.nodeId == container.id) site.inFrom.port else names[site.inFrom.nodeId]
            val cleanVar = names[site.cleanNodeId]
            val rejectsVar = names[site.rejectsNodeId]
            if (inVar == null || cleanVar == null || rejectsVar == null) return@mapNotNull null
            PolarsPartition(
                site = site.site,
                inVar = inVar,
                processedVars = listOf(cleanVar),
                rejectsVar = rejectsVar,
            )
        }
    }

    /** Order members by internal DATA edges (Kahn's); container-port inputs count as external. */
    private fun topoOrder(
        container: Container,
        members: List<Node>,
    ): List<Node> {
        val memberIds = members.map { it.id }.toSet()
        val incoming = members.associate { m -> m.id to mutableSetOf<String>() }
        graph.edges.filter { it.kind == EdgeKind.DATA }.forEach { e ->
            if (e.from.nodeId in memberIds && e.to.nodeId in memberIds) {
                incoming.getValue(e.to.nodeId).add(e.from.nodeId)
            }
        }
        val ready = ArrayDeque(members.filter { incoming.getValue(it.id).isEmpty() }.map { it.id })
        val out = mutableListOf<Node>()
        val remaining = incoming.mapValues { it.value.toMutableSet() }.toMutableMap()
        val byId = members.associateBy { it.id }
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            out += byId.getValue(id)
            remaining.forEach { (n, deps) ->
                if (deps.remove(id) && deps.isEmpty() && out.none { it.id == n } && ready.none { it == n }) {
                    ready.addLast(n)
                }
            }
        }
        // Any cycle-residue (shouldn't happen post-validate) appended deterministically.
        members.filter { m -> out.none { it.id == m.id } }.forEach { out += it }
        return out
    }

    private fun inputVarsOf(
        node: Node,
        container: Container,
        names: Map<String, String>,
        portVar: Map<String, String>,
    ): List<String> {
        // Data edges into this node, ordered by the node's declared in-port order (left before right).
        val portOrder = node.ports().filter { it.direction.name == "IN" }.map { it.name }
        val edges =
            graph.edges
                .filter { it.kind == EdgeKind.DATA && it.to.nodeId == node.id }
                .sortedBy { portOrder.indexOf(it.to.port).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return edges.map { e ->
            when (e.from.nodeId) {
                container.id -> portVar.getValue(e.from.port) // fed by a container IN port
                else -> names[e.from.nodeId] ?: e.from.nodeId
            }
        }
    }

    private fun loadSource(load: Load): PolarsSource {
        // A member Load reads its world storage. Resolve the declared schema (D-c) for read_csv.
        val schema = resolveSchema(load)
        val path = load.source.replace('.', '/') + ".csv"
        return if (schema !=
            null
        ) {
            PolarsSource.Csv(path, schema)
        } else {
            PolarsSource.Staged(load.source.substringAfterLast('.'))
        }
    }

    private fun resolveSchema(load: Load): List<Pair<String, String>>? {
        val ref = load.schemaRef ?: return null

        fun matches(name: String) = name == ref || name.substringAfterLast('.') == ref
        val storage = world.world.storages.firstOrNull { s -> s.schemas.any { matches(it.qname.name) } } ?: return null
        val schema = storage.schemas.first { matches(it.qname.name) }
        return schema.fields.entries.map { it.key to it.value }
    }

    /**
     * The external leaves consuming this OUT port — every one of them (a port may feed several displays, e.g.
     * an evidence display and an action display). A display writes `out/<stem>.arrow`, its stem unique among
     * same-named displays ([DisplayLeaves]); a store stages `staging/<port>.arrow`.
     */
    private fun sinksFor(
        container: Container,
        port: String,
    ): List<PolarsStep> =
        graph.edges
            .filter { it.from.nodeId == container.id && it.from.port == port }
            .mapNotNull { graph.nodes[it.to.nodeId] }
            .mapNotNull { leaf ->
                when (leaf) {
                    is Display ->
                        PolarsStep(
                            varName = "_",
                            node = leaf,
                            sinkPath = "out/${DisplayLeaves.stemOf(graph, leaf)}.arrow",
                        )
                    is Store -> PolarsStep(varName = "_", node = leaf, sinkPath = "staging/$port.arrow")
                    else -> null
                }
            }.distinctBy { it.sinkPath }

    private fun stagedReadNode(
        container: Container,
        port: String,
    ): Load = Load("${container.id}~$port", port, container.location, source = port)
}
