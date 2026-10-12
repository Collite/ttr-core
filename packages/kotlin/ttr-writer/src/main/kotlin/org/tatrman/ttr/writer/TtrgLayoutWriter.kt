// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import org.tatrman.ttr.parser.loader.TtrId
import org.tatrman.ttr.parser.model.LayoutDocument

/**
 * Canonical, byte-stable `.ttrg` emission of a layout (VL contracts §3, V-5, V-10…V-13) — the JVM twin
 * of the viewer's `writeTtrg` (`packages/designer/src/layout/layout-ttr.ts`); both reproduce the shared
 * vectors' `layout.ttrg` byte for byte (`packages/package-sdk/schemas/layout-vectors/`).
 *
 * One graph block: `model`, the sorted `objects`, and — only when there are positions — the in-file
 * `layout: { nodes: { … } }` block (the v1.1 D4 shape). Two-space indentation, commas between
 * entries, integral coordinates, LF, a trailing newline. Refs are bare when they are a grammar `id`,
 * quoted otherwise ([TtrId]). Never written: `skin` / `collapsed` (the block cannot carry them, GQ-1),
 * a viewport (C1-b), edges (reserved).
 */
object TtrgLayoutWriter {
    fun write(doc: LayoutDocument): String {
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
                    .append(TtrId.token(o))
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
                    .append(TtrId.token(k))
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
}
