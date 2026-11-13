// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.LoadResult
import org.tatrman.ttr.metadata.MetadataLoader
import java.nio.file.Path

/**
 * MW S3.4 — a dump-level view of a load, to compare two sources of the same model: every typed object in qname order
 * plus the load issues, with what names a file removed (`sourceFile`, the root, `.ttrm`/`.yaml` suffixes).
 */
internal object SnapshotDump {
    fun load(source: ModelSource): LoadResult =
        MetadataLoader(listOf(BuiltinStockSource(), source)).load(LoadOptions(strict = true))

    /** Map entries (`{k=v, …}`) in sorted order: a map's equality ignores order, its toString does not. */
    fun unordered(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '{') {
                out.append(c)
                i++
                continue
            }
            var depth = 0
            var j = i
            val items = mutableListOf<String>()
            var start = i + 1
            while (j < text.length) {
                when (text[j]) {
                    '{', '(', '[' -> depth++
                    '}', ')', ']' -> depth--
                }
                if (depth == 1 && text.startsWith(", ", j)) {
                    items += text.substring(start, j)
                    start = j + 2
                }
                if (depth == 0) break
                j++
            }
            items += text.substring(start, j.coerceAtMost(text.length))
            out
                .append(
                    '{',
                ).append(
                    items
                        .filter { it.isNotEmpty() }
                        .map(::unordered)
                        .sorted()
                        .joinToString(", "),
                ).append('}')
            i = j + 1
        }
        return out.toString()
    }

    fun dump(
        result: LoadResult,
        root: Path,
    ): String {
        val model = requireNotNull(result.model) { "load failed: ${result.issues}" }
        val prefixes = listOf(root.toAbsolutePath().toString(), root.toRealPath().toString()).map { "$it/" }

        fun scrub(text: String): String {
            var out =
                text
                    .replace(
                        Regex("(sourceFile|sourceLocation|source|textSource|valueSource|tagSource)=[^,)]*"),
                        "$1=_",
                    ).replace(
                        Regex("\\b(line|column|endLine|endColumn|offsetStart|offsetEnd|indentWidth)=-?\\d+"),
                        "$1=_",
                    ).replace(Regex("lineOffsets=\\[[^]]*]"), "lineOffsets=_")
            prefixes.forEach { out = out.replace(it, "") }
            out = out.replace(Regex("([\\w.-]*/[\\w./-]+):\\d+(:\\d+)?"), "$1:_")
            return unordered(out.replace(Regex("\\.(ttrm|ttr|yaml|yml)\\b"), ""))
        }
        val objects =
            model
                .objectByQname()
                .entries
                .sortedBy { it.key.toString() }
                .map { (qname, value) -> "$qname = ${scrub(value.toString())}" }
        val areas = model.areas.toSortedMap().map { (name, area) -> "area $name = ${scrub(area.toString())}" }
        val schemas =
            model.rowSchemas.toSortedMap().map { (name, schema) ->
                "schema $name = ${scrub(schema.toString())}"
            }
        val issues =
            result.issues
                .map { "${it.severity} ${it.category} ${scrub(it.message)}" }
                .sorted()
        return (objects + areas + schemas + issues).joinToString("\n")
    }
}
