// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.yaml.snakeyaml.Yaml

/** One row of a TTR-B reject table: the id, its message and suggested sentence in the table's language. */
data class TtrbReject(
    val id: String,
    val form: String,
    val example: String,
    /** The message; `{word}` stands for the offending word. */
    val message: String,
    /** A suggested correct sentence (the assist repair vocabulary). */
    val suggest: String,
    val decision: String,
    /** Folded sentence-initial words that name this row (`update`, `aktualizuj`). */
    val triggers: Set<String> = emptySet(),
) {
    /** The message with `{word}` filled in. */
    fun message(word: String?): String = if (word == null) message else message.replace("{word}", word)
}

/**
 * A per-skin TTR-B reject table (AG B6): `ttrb/rejects.<lang>.yaml` — the same ids in every skin, each
 * with a message in that language and a suggested correct sentence. THE single source of the TTR-B
 * reject wording: the scanner, the reject specs and `ttrp/authoringContext` read it, never a
 * hard-coded string.
 */
class TtrbRejectTable(
    val lang: String,
    val rows: List<TtrbReject>,
) {
    private val byId = rows.associateBy { it.id }

    fun entry(id: String): TtrbReject = byId[id] ?: error("no TTR-B reject row $id in rejects.$lang.yaml")

    fun ids(): Set<String> = byId.keys

    /** The row a sentence-initial [word] names (case- and diacritic-insensitive), or null. */
    fun triggeredBy(word: String): TtrbReject? {
        val folded = TtrbSkin.fold(word)
        return rows.firstOrNull { folded in it.triggers }
    }

    companion object {
        fun load(lang: String): TtrbRejectTable {
            val path = "/ttrb/rejects.$lang.yaml"
            val text =
                TtrbRejectTable::class.java
                    .getResourceAsStream(path)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("TTR-B reject table not found: $path")
            val root = Yaml().load<Any?>(text) as? Map<*, *> ?: error("$path: not a mapping")
            val rows =
                (root["rejects"] as? List<*> ?: error("$path: no `rejects` list")).mapIndexed { i, r ->
                    val m = r as? Map<*, *> ?: error("$path: row $i is not a mapping")

                    fun req(k: String): String = m[k]?.toString() ?: error("$path: row $i misses `$k`")
                    TtrbReject(
                        id = req("id"),
                        form = req("form"),
                        example = req("example"),
                        message = req("message"),
                        suggest = req("suggest"),
                        decision = req("decision"),
                        triggers = (m["triggers"] as? List<*>).orEmpty().map { TtrbSkin.fold(it.toString()) }.toSet(),
                    )
                }
            return TtrbRejectTable(root["lang"]?.toString() ?: lang, rows)
        }
    }
}
