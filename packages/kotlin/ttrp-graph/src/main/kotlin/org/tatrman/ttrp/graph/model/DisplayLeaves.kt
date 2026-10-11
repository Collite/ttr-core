// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.model

/**
 * The display leaves of a graph in bundle-manifest order, each with its file stem.
 *
 * Several [Display] nodes may share a name — an **action display** with several sources (grammar 0.14
 * `def schema`; the frontend rejects a shared name that is not a declared schema, TTRP-DSP-004). Leaves sort
 * by name (the order `displays[]` always had), then by program position — the wiring order the host
 * concatenates the rows of one action display in. The first leaf of a name keeps the stem `<name>` (so a
 * program with unique display names is byte-identical to before); the k-th (k ≥ 2) gets `<name>~<k>` —
 * `~` never appears in a TTR-P identifier, so a stem cannot collide with another display's name — and a
 * file-writing executor never overwrites one display with another.
 */
object DisplayLeaves {
    data class Leaf(
        val display: Display,
        /** 1-based position among the leaves sharing [Display.name]. */
        val ordinal: Int,
        /** The file stem: `out/<stem>.<fmt>`. */
        val stem: String,
    )

    fun of(graph: TtrpGraph): List<Leaf> =
        graph.nodes.values
            .filterIsInstance<Display>()
            .sortedWith(
                compareBy<Display>(
                    { it.name },
                    { it.location.line },
                    { it.location.column },
                    { it.location.offsetStart },
                ),
            ).groupBy { it.name }
            .flatMap { (name, group) ->
                group.mapIndexed { i, d -> Leaf(d, i + 1, if (i == 0) name else "$name~${i + 1}") }
            }

    /** The file stem of [display] (see [of]); its bare name when it is not in [graph]. */
    fun stemOf(
        graph: TtrpGraph,
        display: Display,
    ): String = of(graph).firstOrNull { it.display.id == display.id }?.stem ?: display.name
}
