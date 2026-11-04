// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

/** MF F-8: gitignore subset, rooted paths, no re-inclusion. Also tests every ancestor directory. */
class FileSelector(patterns: List<String>) {
    private data class Rule(val regex: Regex, val directory: Boolean)
    private val rules = patterns.mapNotNull { raw ->
        val pattern = raw.trimEnd()
        require(!pattern.startsWith('!')) { "'!' (re-include) is not supported" }
        if (pattern.isBlank() || pattern.startsWith('#')) return@mapNotNull null
        val directory = pattern.endsWith('/')
        val body = pattern.removePrefix("/").removeSuffix("/")
        val anchored = pattern.startsWith('/') || '/' in body
        Rule(Regex((if (anchored) "^" else "^(?:.*/)?") + glob(body) + "$"), directory)
    }
    fun matches(path: String): Boolean {
        val parts = path.split('/')
        return rules.any { rule ->
            (1 until parts.size).any { rule.regex.matches(parts.take(it).joinToString("/")) } ||
                (!rule.directory && rule.regex.matches(path))
        }
    }
    companion object {
        private fun glob(pattern: String): String = buildString {
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i++]) {
                    '*' -> if (i < pattern.length && pattern[i] == '*') {
                        i++
                        if (i < pattern.length && pattern[i] == '/') {
                            i++
                            append("(?:.*/)?")
                        } else append(".*")
                    } else append("[^/]*")
                    '?' -> append("[^/]")
                    '[' -> {
                        val end = pattern.indexOf(']', i)
                        require(end >= i && end != i) { "Unclosed or empty glob character class" }
                        val body = pattern.substring(i, end)
                        append("(?=[^/])[")
                        if (body.startsWith('!') || body.startsWith('^')) append('^')
                        append(body.removePrefix("!").removePrefix("^").replace("\\", "\\\\").replace("&", "\\&"))
                        append(']')
                        i = end + 1
                    }
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
    }
}
