// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

/** Shared literal, quoted-identifier and comment boundaries for the pre-parse text passes. */
internal object SqlTextScanner {
    /** The name inside a `"…"` span from [protectedEnd]; the parser reports unterminated spans, including a lone `"`. */
    fun quotedName(span: String): String = span.drop(1).dropLast(1).replace("\"\"", "\"")

    /** End of the protected span starting at [start], or [start] when this is ordinary SQL. */
    fun protectedEnd(
        sql: String,
        start: Int,
    ): Int {
        val ch = sql[start]
        if (ch == '\'' || ch == '"') {
            var i = start + 1
            while (i < sql.length) {
                if (sql[i] != ch) {
                    i++
                } else if (i + 1 < sql.length && sql[i + 1] == ch) {
                    i += 2
                } else {
                    return i + 1
                }
            }
            return sql.length
        }
        if (sql.startsWith("--", start) || sql.startsWith("//", start)) {
            val newline = sql.indexOfAny(charArrayOf('\n', '\r'), start + 2)
            return if (newline < 0) sql.length else newline
        }
        if (sql.startsWith("/*", start)) {
            val close = sql.indexOf("*/", start + 2)
            return if (close < 0) sql.length else close + 2
        }
        return start
    }
}
