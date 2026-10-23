// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

fun markdownEscape(s: String): String =
    buildString {
        s.forEachIndexed { i, ch ->
            val inWord = ch == '_' && s.getOrNull(i - 1).isWordChar() && s.getOrNull(i + 1).isWordChar()
            if (ch == '\n' || ch == '\r') {
                append(' ')
                return@forEachIndexed
            }
            if (ch in MD_SPECIAL && !inWord) append('\\')
            append(ch)
        }
    }

private fun Char?.isWordChar() = this != null && isLetterOrDigit()

private const val MD_SPECIAL = "\\`*_[]<>|~&#"
