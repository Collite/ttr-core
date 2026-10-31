// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser

/** STRING_LITERAL rules; triple strings and tagged blocks stay raw. */
object TtrStrings {
    private val escapes = mapOf('\\' to '\\', '"' to '"', 'n' to '\n', 't' to '\t', 'r' to '\r')

    /** One pass; unknown escapes keep both characters. Callback positions count Unicode code points. */
    fun decode(
        raw: String,
        onEscape: (Int) -> Unit = {},
    ): String {
        val input = raw.substring(1, raw.length - 1).codePoints().toArray()
        val out = StringBuilder()
        var offset = 0
        var i = 0
        while (i < input.size) {
            val decoded =
                if (input[i] == '\\'.code &&
                    i + 1 < input.size &&
                    input[i + 1] <= Char.MAX_VALUE.code
                ) {
                    escapes[input[i + 1].toChar()]
                } else {
                    null
                }
            if (decoded != null) {
                onEscape(offset)
                out.append(decoded)
                i += 2
            } else {
                out.appendCodePoint(input[i++])
            }
            offset++
        }
        return out.toString()
    }

    fun encode(value: String): String =
        buildString {
            append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('"')
        }

    /** Physical position before a decoded code point, accounting for encoded LF/TAB/CR. */
    fun sourcePosition(
        text: String,
        escapes: List<Int>,
        offset: Int,
        line: Int,
        column: Int,
    ): Pair<Int, Int> {
        var row = line
        var col = column
        val escaped = escapes.toSet()
        text.codePoints().limit(offset.toLong()).toArray().forEachIndexed { i, ch ->
            when {
                i in escaped -> col += 2
                ch == '\n'.code -> {
                    row++
                    col = 0
                }
                else -> col++
            }
        }
        return row to col
    }
}
