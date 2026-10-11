// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.identity

/**
 * Graph identity MODULO GENERATED NAMES. Two surfaces lower to the same graph but name its
 * intermediate values differently (a TTR-B fragment synthesizes `_b0`, a hand-written program says
 * `big_orders`; an unnamed chain step is `~7` or `~9` depending on statement shape). This helper
 * renames every SSA label of a [org.tatrman.ttrp.graph.explain.NormalizedGraphJson] rendering to a
 * STRUCTURAL name — derived from what the node is and how it is wired (a few rounds of neighbour
 * refinement over the edge list), never from what the author called it — and applies the same
 * renaming to any other text that cites the labels (`ttrp explain`). Container names, ports,
 * display names and every expression stay as they are.
 */
object ModuloNames {
    data class Normalized(
        val graph: String,
        private val rename: Map<String, String>,
    ) {
        /** [text] with every label of the graph replaced by its structural name. */
        fun apply(text: String): String = replaceLabels(text, rename)
    }

    private val nodeLine = Regex("^  (\\S+) = (.*)$")
    private val edgeLine = Regex("^  (\\S+)\\.(\\S+) -(\\w+)-> (\\S+)\\.(\\S+)$")

    fun normalize(normalizedGraphJson: String): Normalized {
        val lines = normalizedGraphJson.lines()
        val nodesAt = lines.indexOf("nodes:")
        val containersAt = lines.indexOf("containers:")
        val edgesAt = lines.indexOf("edges:")
        val render = LinkedHashMap<String, String>()
        for (l in lines.subList(nodesAt + 1, containersAt)) {
            nodeLine.find(l)?.let { render[it.groupValues[1]] = it.groupValues[2] }
        }

        data class E(
            val from: String,
            val fromPort: String,
            val kind: String,
            val to: String,
            val toPort: String,
        )
        val edges =
            lines.subList(edgesAt + 1, lines.size).mapNotNull { l ->
                edgeLine.find(l)?.groupValues?.let { E(it[1], it[2], it[3], it[4], it[5]) }
            }
        // Containers (and any non-node endpoint) keep their author-given name — it is the same on both sides.
        var sig: Map<String, String> = render.toMap()

        fun of(label: String): String = sig[label] ?: "C:$label"
        repeat(6) {
            sig =
                render.keys.associateWith { n ->
                    val ins =
                        edges
                            .filter { it.to == n }
                            .map {
                                "${it.toPort}<${it.kind}<${of(
                                    it.from,
                                )}.${it.fromPort}"
                            }.sorted()
                    val outs =
                        edges
                            .filter { it.from == n }
                            .map {
                                "${it.fromPort}>${it.kind}>${of(
                                    it.to,
                                )}.${it.toPort}"
                            }.sorted()
                    (render.getValue(n) + "|" + ins + "|" + outs).hashCode().toUInt().toString(36)
                }
        }
        // Structural name = rank of the final signature; true twins (same signature) share a stem and are
        // numbered in their order of appearance, which then carries no meaning either way.
        val ranks =
            sig.values
                .toSortedSet()
                .withIndex()
                .associate { (i, s) -> s to i }
        val seen = HashMap<String, Int>()
        val rename =
            render.keys.associateWith { n ->
                val stem = "n${ranks.getValue(sig.getValue(n))}"
                val k = (seen[stem] ?: 0) + 1
                seen[stem] = k
                if (k == 1) stem else "$stem/$k"
            }
        val renamed = replaceLabels(normalizedGraphJson, rename)
        return Normalized(sortSections(renamed), rename)
    }

    /** Graph rendering modulo names, ready to compare. */
    fun graph(normalizedGraphJson: String): String = normalize(normalizedGraphJson).graph

    private fun replaceLabels(
        text: String,
        rename: Map<String, String>,
    ): String {
        if (rename.isEmpty()) return text
        val alternatives = rename.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val re = Regex("(?<![\\p{L}\\p{N}_#~])(?:$alternatives)(?![\\p{L}\\p{N}_#])")
        return re.replace(text) { rename.getValue(it.value) }
    }

    private val members = Regex("members=\\[([^\\]]*)]")

    /** NormalizedGraphJson sorts by label; after renaming, re-sort each section's lines (and member lists). */
    private fun sortSections(text: String): String {
        val out = mutableListOf<String>()
        var block = mutableListOf<String>()
        for (raw in text.lines()) {
            val l =
                members.replace(raw) { m ->
                    "members=[" +
                        m.groupValues[1]
                            .split(",")
                            .sorted()
                            .joinToString(",") +
                        "]"
                }
            if (l.startsWith("  ")) {
                block += l
            } else {
                out += block.sorted()
                block = mutableListOf()
                out += l
            }
        }
        out += block.sorted()
        return out.joinToString("\n")
    }
}
