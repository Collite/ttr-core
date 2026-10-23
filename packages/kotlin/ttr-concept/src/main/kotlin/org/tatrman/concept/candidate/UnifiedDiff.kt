// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

/**
 * A git-style unified diff of one file (`diff --git a/<path> b/<path>`, three lines of context, `/dev/null` for a new
 * file, `\ No newline at end of file` where it applies) — what `git apply` takes. Lines are compared with their line
 * ends, so a last line that gains its newline is a change, as git sees it.
 */
internal object UnifiedDiff {
    private const val CONTEXT = 3

    fun of(
        path: String,
        old: String?,
        new: String,
    ): String {
        val a = old?.let(::lines).orEmpty()
        val b = lines(new)
        val out = StringBuilder("diff --git a/$path b/$path\n")
        if (old == null) out.append("new file mode 100644\n--- /dev/null\n") else out.append("--- a/$path\n")
        out.append("+++ b/$path\n")
        for (hunk in hunks(script(a, b))) {
            val (oldStart, oldLen) = range(hunk, a = true)
            val (newStart, newLen) = range(hunk, a = false)
            out
                .append(
                    "@@ -",
                ).append(span(oldStart, oldLen))
                .append(" +")
                .append(span(newStart, newLen))
                .append(" @@\n")
            for (op in hunk) {
                val (mark, line) =
                    when (op) {
                        is Op.Same -> ' ' to a[op.i]
                        is Op.Del -> '-' to a[op.i]
                        is Op.Add -> '+' to b[op.j]
                    }
                out.append(mark).append(line.removeSuffix("\n")).append('\n')
                if (!line.endsWith("\n")) out.append("\\ No newline at end of file\n")
            }
        }
        return out.toString()
    }

    private sealed interface Op {
        data class Same(
            val i: Int,
            val j: Int,
        ) : Op

        data class Del(
            val i: Int,
        ) : Op

        data class Add(
            val j: Int,
        ) : Op
    }

    /** Each line with its `\n`; a last line without one stays without. */
    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                out += text.substring(start)
                break
            }
            out += text.substring(start, nl + 1)
            start = nl + 1
        }
        return out
    }

    /** The edit script: common prefix and suffix, an LCS over what lies between. */
    private fun script(
        a: List<String>,
        b: List<String>,
    ): List<Op> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val n = a.size - pre - suf
        val m = b.size - pre - suf
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] =
                    if (a[pre + i] == b[pre + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val ops = mutableListOf<Op>()
        for (k in 0 until pre) ops += Op.Same(k, k)
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[pre + i] == b[pre + j] -> ops += Op.Same(pre + i++, pre + j++)
                // A tie deletes first: `-old` before `+new`, as git prints a changed line.
                i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> ops += Op.Del(pre + i++)
                else -> ops += Op.Add(pre + j++)
            }
        }
        for (k in 0 until suf) ops += Op.Same(a.size - suf + k, b.size - suf + k)
        return ops
    }

    /** Changes with [CONTEXT] lines around them; hunks whose context would touch are merged. */
    private fun hunks(ops: List<Op>): List<List<Op>> {
        val changed = ops.indices.filter { ops[it] !is Op.Same }
        if (changed.isEmpty()) return emptyList()
        val out = mutableListOf<List<Op>>()
        var start = maxOf(0, changed.first() - CONTEXT)
        var end = minOf(ops.size, changed.first() + CONTEXT + 1)
        for (c in changed.drop(1)) {
            if (c - CONTEXT <= end) {
                end = minOf(ops.size, c + CONTEXT + 1)
            } else {
                out += ops.subList(start, end)
                start = c - CONTEXT
                end = minOf(ops.size, c + CONTEXT + 1)
            }
        }
        out += ops.subList(start, end)
        return out
    }

    /** A hunk's 1-based start and length on one side. */
    private fun range(
        hunk: List<Op>,
        a: Boolean,
    ): Pair<Int, Int> {
        val idx =
            hunk.mapNotNull {
                when (it) {
                    is Op.Same -> if (a) it.i else it.j
                    is Op.Del -> if (a) it.i else null
                    is Op.Add -> if (a) null else it.j
                }
            }
        // A side with no line in a context-carrying hunk is an empty file (a new one): `-0,0`.
        return if (idx.isEmpty()) 0 to 0 else idx.first() + 1 to idx.size
    }

    private fun span(
        start: Int,
        len: Int,
    ): String = if (len == 1) "$start" else "$start,$len"
}
