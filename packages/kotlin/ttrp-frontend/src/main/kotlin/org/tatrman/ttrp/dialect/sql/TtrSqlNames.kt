// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.sql

/**
 * The name a TTR-SQL identifier token spells: a double-quoted identifier (`"Customer"`, `""` for a quote)
 * loses its quotes; a bare one is itself. Every place the dialect reads a name goes through here
 * (AU-core-P0 review-114 M3 — before, the lexer silently dropped the `"` and the bare text was read).
 */
internal fun sqlName(text: String): String =
    if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
        text.substring(1, text.length - 1).replace("\"\"", "\"")
    } else {
        text
    }
