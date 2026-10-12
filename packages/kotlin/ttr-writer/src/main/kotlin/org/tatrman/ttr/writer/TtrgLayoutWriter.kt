// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import org.tatrman.ttr.parser.loader.TtrId
import org.tatrman.ttr.parser.model.LayoutDocument

/**
 * Canonical, byte-stable `.ttrg` emission of a layout (VL contracts §3, V-5, V-10…V-13) — the JVM twin
 * of `writeTtrg` (`@tatrman/parser/layout`, `packages/parser/src/layout/layout-ttr.ts`); both reproduce the shared
 * vectors' `layout.ttrg` byte for byte (`packages/package-sdk/schemas/layout-vectors/`).
 *
 * One graph block: `model`, the sorted `objects`, and — only when there are positions — the in-file
 * `layout: { nodes: { … } }` block (the v1.1 D4 shape). Two-space indentation, commas between
 * entries, integral coordinates, LF, a trailing newline. Every ref is bare: `TTR.g4` admits only an
 * `id` in `objects` and as a `nodes` key, so a document holding any other ref has no TTR form and
 * [write] refuses it (VL A-VL-5; [unwritable] names them). Never written: `skin` / `collapsed` (the
 * block cannot carry them, GQ-1), a viewport (C1-b), edges (reserved).
 */
object TtrgLayoutWriter {
    /** The refs of [doc] a `.ttrg` cannot spell — not a grammar `id` ([TtrId]) — sorted (A-VL-5). */
    fun unwritable(doc: LayoutDocument): List<String> =
        (doc.objects + doc.positions.keys)
            .distinct()
            .filterNot { TtrId.isId(it) }
            .sortedWith(LayoutDocument.ALPHABETICAL_ORDER)

    /** The canonical `.ttrg` of [doc]; throws [IllegalArgumentException] when a ref is not an `id` (A-VL-5). */
    fun write(doc: LayoutDocument): String {
        val bad = unwritable(doc)
        require(bad.isEmpty()) {
            "${doc.name} has no TTR form: ${bad.joinToString(
                ", ",
            ) { quoted(it) }} ${if (bad.size == 1) "is" else "are"} not a TTR id"
        }
        val d = doc.canonical()
        val sb = StringBuilder()
        sb.append("graph ").append(d.name).append(" {\n")
        sb.append("  model: ").append(d.model).append(",\n")
        val objectsEnd = if (d.positions.isNotEmpty()) "," else ""
        if (d.objects.isEmpty()) {
            sb.append("  objects: []").append(objectsEnd).append('\n')
        } else {
            sb.append("  objects: [\n")
            d.objects.forEachIndexed { i, o ->
                sb
                    .append("    ")
                    .append(o)
                    .append(if (i < d.objects.size - 1) "," else "")
                    .append('\n')
            }
            sb.append("  ]").append(objectsEnd).append('\n')
        }
        if (d.positions.isNotEmpty()) {
            sb.append("  layout: {\n    nodes: {\n")
            val keys = d.positions.keys.toList()
            keys.forEachIndexed { i, k ->
                val p = d.positions.getValue(k)
                sb
                    .append("      ")
                    .append(k)
                    .append(": { x: ")
                    .append(p.x.toLong())
                    .append(", y: ")
                    .append(p.y.toLong())
                    .append(" }")
                    .append(if (i < keys.size - 1) "," else "")
                    .append('\n')
            }
            sb.append("    }\n  }\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    /** A ref as JSON spells it — the refusal names refs the way the viewer's message does. */
    private fun quoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
