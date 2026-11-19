// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.parser.model.LayoutDocument
import org.tatrman.ttr.parser.model.LayoutPoint

/**
 * Project an already resolved tag [closure] onto one schema's cards (MW D-108). The caller runs
 * ResolveTags against [snapshot]; this function neither rechecks tags nor adds neighbours to that scope.
 * Packages come from this snapshot's definitions, never from a newer checkout or registry snapshot.
 *
 * There is no model-card auto-layout in metadata (the TTR-P LSP's layout operates on process nodes).
 * Place cards in a three-column grid per package, with a blank row between packages. Both package and
 * card ordering use the same canonical alphabetical order as authored layouts. Computed coordinates
 * have no authored carrier, so positionsFrom is null; source names the computation rather than a file.
 */
fun closureLayout(
    snapshot: RegistrySnapshot,
    closure: Collection<ModelObject>,
    schema: String = "er",
): ResolvedLayout {
    require(schema in setOf("er", "db")) { "layout schema must be er or db" }
    val declarations = snapshot.definitions.entries.groupBy { Triple(it.def.source.file, it.kind, it.qname) }

    fun packageOf(obj: ModelObject): String =
        declarations[Triple(obj.sourceFile, obj.kind, obj.qname.dotted())]
            ?.map { it.pkg }
            ?.distinct()
            ?.singleOrNull()
            ?: obj.qname.`package`

    val selected = closure.map { it.qname }.toSet()
    val cardsInScope =
        snapshot.model
            .objectByQname()
            .values
            .filter { it.qname in selected }
            .map { packageOf(it) to it.qname.dotted() }
            .toSet()
    val cards =
        LayoutResolver
            .indexOf(snapshot, ::packageOf)
            .cards
            .filter { it.schema == schema && (it.pkg to it.qname) in cardsInScope }
    val order = LayoutDocument.ALPHABETICAL_ORDER
    val positions = linkedMapOf<String, LayoutPoint>()
    var top = 40.0
    for ((_, group) in cards.groupBy { it.pkg }.toSortedMap(order)) {
        group.sortedWith(compareBy(order) { it.fullQname }).forEachIndexed { i, card ->
            positions[card.fullQname] = LayoutPoint(40.0 + (i % 3) * 320, top + (i / 3) * 220)
        }
        top += ((group.size + 2) / 3 + 1) * 220
    }
    return ResolvedLayout(
        name = "closure_$schema",
        schema = schema,
        objects = positions.keys.sortedWith(order),
        positions = positions.toSortedMap(order),
        skin = null,
        collapsed = emptyList(),
        missing = emptyList(),
        ambiguous = emptyList(),
        ignored = emptyList(),
        positionsFrom = null,
        source = "computed:tags",
    )
}
