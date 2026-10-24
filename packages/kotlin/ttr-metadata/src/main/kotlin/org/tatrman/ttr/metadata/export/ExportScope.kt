// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

/** Null means unrestricted; an empty set selects nothing. Package and area selectors form a union. */
data class ExportScope(
    val packages: Set<String>? = null,
    val areas: Set<String>? = null,
) {
    val header: Map<String, List<String>>?
        get() =
            if (packages == null && areas == null) {
                null
            } else {
                buildMap {
                    packages?.let { put("packages", it.sortedWith(bytewise)) }
                    areas?.let { put("areas", it.sortedWith(bytewise)) }
                }
            }

    internal fun definitionIds(
        set: DefinitionSet,
        graph: DefinitionGraph,
    ): Set<String> {
        if (packages == null && areas == null) return set.entries.map { it.qname }.toSet()
        val selectedAreas = areas.orEmpty().mapNotNull { set.areas[it] }
        val pkgs = packages.orEmpty() + selectedAreas.flatMap { it.packages }
        val selected = apply(graph, set)
        val nodeIds =
            selected.nodes
                .filterNot { it.external }
                .map { it.id }
                .toSet()
        val bindingIds = selected.edges.mapNotNull { it.id }.toSet()
        val areaIds = areas.orEmpty().map { "area.$it" }.toSet()
        return set.entries
            .filter { entry ->
                pkgs.any { entry.pkg == it || entry.pkg.startsWith("$it.") } ||
                    entry.qname in areaIds ||
                    entry.qname in nodeIds ||
                    entry.parentQname in nodeIds ||
                    entry.kind in ExportKinds.bindings &&
                    entry.qname in bindingIds
            }.map { it.qname }
            .toSet()
    }

    fun apply(
        graph: DefinitionGraph,
        set: DefinitionSet,
    ): DefinitionGraph {
        if (packages == null && areas == null) return graph
        val selectedAreas = areas.orEmpty().mapNotNull { set.areas[it] }
        val pkgs = packages.orEmpty() + selectedAreas.flatMap { it.packages }
        val entityRefs = selectedAreas.flatMap { it.entities }.toSet()
        val entities =
            graph.nodes
                .filter { node ->
                    node.kind == "entity" &&
                        entityRefs.any { ref ->
                            ref == node.id ||
                                ref == node.label ||
                                ref == "er.${node.label}" ||
                                ref == "${node.pkg}.${node.label}" ||
                                ref == "${node.pkg}.${node.id}"
                        }
                }.map { it.id }
                .toSet()
        val inside =
            graph.nodes
                .filter { n ->
                    pkgs.any { n.pkg == it || n.pkg?.startsWith("$it.") == true } ||
                        entities.any { n.id == it || n.id.startsWith("$it.") } ||
                        n.id in areas.orEmpty().map { "area.$it" }
                }.map { it.id }
                .toSet()
        val edges = graph.edges.filter { it.from in inside || it.to in inside }
        val ids = inside + edges.flatMap { listOf(it.from, it.to) }
        return DefinitionGraph(
            graph.nodes.filter { it.id in ids }.map {
                if (it.id in inside) it else Node(it.id, kind = it.kind, external = true)
            },
            edges,
        )
    }
}
