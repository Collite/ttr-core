// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

/** XM C-9: stable ids shared by library, CLI and service. Empty means lossless at the parsed-AST tier. */
object LossinessLedger {
    data class Entry(
        val id: String,
        val drops: String,
    )

    private val entries =
        mapOf(
            "graph.json" to
                listOf(
                    Entry("L-GRAPH-1", "expression bodies"),
                    Entry("L-GRAPH-2", "definition text"),
                    Entry("L-GRAPH-3", "security"),
                    Entry("L-GRAPH-4", "test/authoring keys"),
                    Entry("L-GRAPH-5", "layouts"),
                ),
            "ttl" to
                listOf(
                    Entry("L-TTL-1", "md/er/db beyond grounding targets"),
                    Entry("L-TTL-2", "definition text carried opaque"),
                ),
            "xmi" to
                listOf(
                    Entry("L-XMI-1", "diagrams"),
                    Entry("L-XMI-2", "lexicon carried as synonyms tagged values"),
                ),
            "dot" to listOf(Entry("L-DOT-1", "everything but nodes/edges")),
        )

    fun entriesFor(format: String): List<Entry> = entries[format].orEmpty()

    fun cannotCarry(
        model: String,
        format: String,
    ): String? =
        when {
            format == "ttl" && model !in setOf("cn", "world") -> "L-TTL-1"
            format == "xmi" && model == "lexicon" -> "L-XMI-2"
            format == "dot" && model != "world" -> "L-DOT-1"
            else -> null
        }
}
