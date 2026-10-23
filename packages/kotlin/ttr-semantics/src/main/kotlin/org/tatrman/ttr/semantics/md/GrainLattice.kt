// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics.md

/**
 * Grain lattice over the MD map graph (contracts §6.1–6.3) — a faithful Kotlin port of the TS
 * `md-lattice.ts` algorithms (md stage 2E). Pure functions over a **domain**-level edge graph:
 * the model's maps lowered to `from-domain → to-domain` edges with the 2B4 attribute→domain sugar
 * already applied (a map `from:` may name a dimension attribute; it reduces to that attr's domain).
 *
 * Invariants (from md-lattice.ts):
 *  - **Leaf** = a domain with **no incoming N:1 map**. A 1:1 map does NOT demote leaf-ness.
 *  - Partial order = the transitive closure of the N:1 edges (a calc map is implicitly N:1).
 *  - Co-leaves = domains connected by a chain of 1:1 maps (union-find).
 */
class GrainLattice private constructor(
    val nodes: Set<String>,
    val edges: List<MapEdge>,
) {
    private val n1Adjacency: Map<String, Set<String>> =
        edges
            .filterNot { it.oneToOne }
            .groupBy({ it.from }, { it.to })
            .mapValues { (_, v) -> v.toSet() }

    /** Domains with no incoming N:1 map (a 1:1 map does not demote). */
    val leaves: Set<String> =
        run {
            val coarsened = edges.filterNot { it.oneToOne }.map { it.to }.toSet()
            nodes.filterNot { it in coarsened }.toSet()
        }

    /** Does `lower` coarsen (eventually N:1-map) to `upper`? `lower == upper` is trivially true. */
    fun grainReachable(
        lower: String,
        upper: String,
    ): Boolean {
        if (lower == upper) return true
        val seen = mutableSetOf(lower)
        val stack = ArrayDeque(listOf(lower))
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            for (y in n1Adjacency[x].orEmpty()) {
                if (y == upper) return true
                if (seen.add(y)) stack.addLast(y)
            }
        }
        return false
    }

    /** All domains reachable from `domain` via the N:1 closure (excludes `domain` itself). */
    fun reachableFrom(domain: String): Set<String> {
        val out = mutableSetOf<String>()
        val stack = ArrayDeque(listOf(domain))
        while (stack.isNotEmpty()) {
            for (y in n1Adjacency[stack.removeLast()].orEmpty()) {
                if (out.add(y)) stack.addLast(y)
            }
        }
        return out
    }

    /** Each domain's co-leaf representative (its 1:1-connected class root); computed once, union-find. */
    private val coLeafRoot: Map<String, String> by lazy {
        val parent = nodes.associateWith { it }.toMutableMap()

        fun find(x: String): String {
            var r = x
            while (parent[r] != r) r = parent.getValue(r)
            return r
        }
        for (e in edges) {
            if (!e.oneToOne) continue
            if (e.from !in parent || e.to !in parent) continue
            val ra = find(e.from)
            val rb = find(e.to)
            if (ra != rb) parent[ra] = rb
        }
        nodes.associateWith { find(it) }
    }

    /** Partition domains into co-leaf classes via 1:1 maps (union-find). */
    fun coLeafClasses(): List<List<String>> = nodes.groupBy { coLeafRoot.getValue(it) }.values.toList()

    /**
     * Are `a` and `b` co-leaves — the same domain, or connected by a chain of 1:1 maps? A 1:1 hop is
     * a legal grain coordinate per R8 ("N:1 **or 1:1**"), so the resolver consults this alongside
     * [grainReachable] when validating a coordinate against a cubelet's grain domain.
     */
    fun sameCoLeaf(
        a: String,
        b: String,
    ): Boolean = a == b || (a in coLeafRoot && coLeafRoot[a] == coLeafRoot[b])

    /** The N:1 maps connecting `lower → upper` directly (hierarchy-step candidates). */
    fun connectingMaps(
        lower: String,
        upper: String,
    ): List<MapEdge> = edges.filter { !it.oneToOne && it.from == lower && it.to == upper }

    /**
     * Infer the connecting map for one consecutive `(lower, upper)` hierarchy step, without a
     * `via:` override: a unique N:1 map is used; zero → [StepResult.None]; >1 → [StepResult.Ambiguous].
     */
    fun inferStep(
        lower: String,
        upper: String,
    ): StepResult {
        val conn = connectingMaps(lower, upper)
        return when {
            conn.isEmpty() -> StepResult.None
            conn.size > 1 -> StepResult.Ambiguous
            else -> StepResult.Ok(conn[0].mapName)
        }
    }

    companion object {
        /** Lower a model's maps to the domain-edge graph and build the lattice (md-graph.ts). */
        fun of(model: MdModel): GrainLattice {
            val edges =
                model.maps.values.flatMap { m ->
                    if (m.kind == MapKind.MANY_MANY) return@flatMap emptyList()
                    val toDom = m.to.firstOrNull()?.let { model.underlyingDomain(it) } ?: return@flatMap emptyList()
                    m.from.mapNotNull { f ->
                        model.underlyingDomain(f)?.let { fromDom ->
                            MapEdge(fromDom, toDom, m.kind == MapKind.ONE_ONE, m.name)
                        }
                    }
                }
            val nodes = model.domains.keys + edges.flatMap { listOf(it.from, it.to) }
            return GrainLattice(nodes.toSet(), edges)
        }
    }
}

/** A directed map edge between two domains (attribute refs already reduced to domains). */
data class MapEdge(
    val from: String,
    val to: String,
    /** A 1:1 map connects co-leaves; an N:1 map coarsens (demotes leaf-ness). */
    val oneToOne: Boolean,
    val mapName: String?,
)

/** The outcome of inferring a single hierarchy step (md-lattice.ts `StepResult`). */
sealed interface StepResult {
    data class Ok(
        val mapName: String?,
    ) : StepResult

    data object None : StepResult

    data object Ambiguous : StepResult
}
