// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

/** Converts T-SQL bracket identifiers to Calcite's double quotes, outside literals and comments. */
object BracketQuoteRewriter {
    fun rewrite(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val protectedEnd = SqlTextScanner.protectedEnd(sql, i)
            if (protectedEnd > i) {
                out.append(sql, i, protectedEnd)
                i = protectedEnd
            } else if (sql[i] == '[') {
                val start = i++
                val name = StringBuilder()
                var closed = false
                while (i < sql.length) {
                    val ch = sql[i++]
                    if (ch == ']' && i < sql.length && sql[i] == ']') {
                        name.append(']')
                        i++
                    } else if (ch == ']') {
                        closed = true
                        break
                    } else {
                        name.append(ch)
                    }
                }
                if (closed) {
                    out.append('"').append(name.toString().replace("\"", "\"\"")).append('"')
                } else {
                    // Preserve the malformed suffix; the parser owns its diagnostic.
                    out.append(sql, start, sql.length)
                }
            } else {
                out.append(sql[i++])
            }
        }
        return out.toString()
    }
}
