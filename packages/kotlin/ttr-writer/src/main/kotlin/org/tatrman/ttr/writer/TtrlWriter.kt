// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import org.tatrman.ttr.parser.TtrStrings

import org.tatrman.ttr.parser.model.TtrlCanvas
import org.tatrman.ttr.parser.model.TtrlDocument
import org.tatrman.ttr.parser.model.TtrlMode

/**
 * Canonical, byte-stable `.ttrl` emission (C1-c-i / v1.1 §15 writer isolation — the
 * sidecar is rewritten **wholesale**, never surgically). Family-wide asset (H-2c/C1-f:
 * written once, Kotlin-side; the TTR-M `.ttrl` migration reuses it).
 *
 * Determinism (P2): canvases in canonical order (`program` first, then keys sorted),
 * node entries sorted by ζ key, integral coordinates emitted without a decimal, fixed
 * 4-space indentation, LF newlines. Equal layout ⇒ byte-identical output, so
 * `parse(write(x)) == x` for any canonically-ordered `x` and `write` is idempotent.
 *
 * Emission rules (a canvas omits what it does not carry, so the output is minimal):
 *  - `skin` line only when set;
 *  - `mode` always;
 *  - `nodes { … }` only when non-empty (auto canvases never carry nodes);
 *  - `collapsed [ … ]` only when non-empty.
 */
object TtrlWriter {
    private const val INDENT = "    "

    fun write(doc: TtrlDocument): String {
        val sb = StringBuilder()
        sb.append("ttrl ").append(doc.version).append('\n')
        for (canvas in canonicalOrder(doc.canvases)) {
            sb.append('\n')
            writeCanvas(sb, canvas)
        }
        return sb.toString()
    }

    private fun canonicalOrder(canvases: List<TtrlCanvas>): List<TtrlCanvas> {
        val program = canvases.filter { it.key == "program" }
        val rest = canvases.filter { it.key != "program" }.sortedBy { it.key }
        return program + rest
    }

    private fun writeCanvas(
        sb: StringBuilder,
        canvas: TtrlCanvas,
    ) {
        sb.append("canvas ").append(canvas.key).append(" {\n")
        canvas.skin?.let {
            sb
                .append(INDENT)
                .append("skin: ")
                .append(quote(it))
                .append('\n')
        }
        sb
            .append(INDENT)
            .append("mode: ")
            .append(if (canvas.mode == TtrlMode.MANUAL) "manual" else "auto")
            .append('\n')
        if (canvas.nodes.isNotEmpty()) {
            sb.append(INDENT).append("nodes: {\n")
            for (n in canvas.nodes.sortedBy { it.zeta }) {
                sb
                    .append(INDENT)
                    .append(INDENT)
                    .append(quote(n.zeta))
                    .append(": { x: ")
                    .append(num(n.x))
                    .append(", y: ")
                    .append(num(n.y))
                    .append(" }\n")
            }
            sb.append(INDENT).append("}\n")
        }
        if (canvas.collapsed.isNotEmpty()) {
            sb.append(INDENT).append("collapsed: [")
            sb.append(canvas.collapsed.sorted().joinToString(", ") { quote(it) })
            sb.append("]\n")
        }
        if (canvas.chains.isNotEmpty()) {
            sb.append(INDENT).append("chains: {\n")
            for ((name, len) in canvas.chains.entries.sortedBy { it.key }) {
                sb
                    .append(INDENT)
                    .append(INDENT)
                    .append(quote(name))
                    .append(": ")
                    .append(len)
                    .append('\n')
            }
            sb.append(INDENT).append("}\n")
        }
        sb.append("}\n")
    }

    private fun quote(s: String): String = TtrStrings.encode(s)

    /** Integral coordinates drop the decimal (`120`, not `120.0`); non-integral keep it. */
    private fun num(v: Double): String =
        if (v == Math.floor(v) &&
            !v.isInfinite()
        ) {
            v.toLong().toString()
        } else {
            v.toString()
        }
}
