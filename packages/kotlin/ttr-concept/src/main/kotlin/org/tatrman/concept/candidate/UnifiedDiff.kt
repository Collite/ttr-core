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

    /**
     * The edit script: common prefix and suffix, then Myers' greedy diff over what lies between — O((N+M)·D) time and
     * O(D²) memory, D the number of changed lines (review-133 F13: an LCS table was quadratic in the file, and a
     * relation edits near the top AND appends at the end, so nothing common trims it). Within a change, deletions come
     * before insertions, as git prints a changed line.
     */
    private fun script(
        a: List<String>,
        b: List<String>,
    ): List<Op> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val ops = mutableListOf<Op>()
        for (k in 0 until pre) ops += Op.Same(k, k)
        ops += grouped(myers(a.subList(pre, a.size - suf), b.subList(pre, b.size - suf), pre))
        for (k in 0 until suf) ops += Op.Same(a.size - suf + k, b.size - suf + k)
        return ops
    }

    /** Myers' shortest edit script of [a] → [b]; indices shifted by [offset]. */
    private fun myers(
        a: List<String>,
        b: List<String>,
        offset: Int,
    ): List<Op> {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val off = max + 1
        val v = IntArray(2 * max + 3)
        // trace[d] = v over k ∈ [-d-1, d+1] BEFORE step d — all the backtrack reads.
        val trace = mutableListOf<IntArray>()
        var end = -1
        search@ for (d in 0..max) {
            trace += v.copyOfRange(off - d - 1, off + d + 2)
            for (k in -d..d step 2) {
                var x =
                    if (k == -d ||
                        (k != d && v[off + k - 1] < v[off + k + 1])
                    ) {
                        v[off + k + 1]
                    } else {
                        v[off + k - 1] + 1
                    }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[off + k] = x
                if (x >= n && y >= m) {
                    end = d
                    break@search
                }
            }
        }
        val ops = ArrayDeque<Op>()
        var x = n
        var y = m
        for (d in end downTo 0) {
            val before = trace[d]

            fun at(k: Int) = before[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                ops.addFirst(Op.Same(offset + x - 1, offset + y - 1))
                x--
                y--
            }
            if (d > 0) {
                if (x == prevX) ops.addFirst(Op.Add(offset + y - 1)) else ops.addFirst(Op.Del(offset + x - 1))
            }
            x = prevX
            y = prevY
        }
        return ops
    }

    /** Each run of changes reordered deletions-first (a stable partition; the run means the same). */
    private fun grouped(ops: List<Op>): List<Op> {
        val out = mutableListOf<Op>()
        var run = mutableListOf<Op>()

        fun flush() {
            out += run.filterIsInstance<Op.Del>() + run.filterIsInstance<Op.Add>()
            run = mutableListOf()
        }
        for (op in ops) {
            if (op is Op.Same) {
                flush()
                out += op
            } else {
                run += op
            }
        }
        flush()
        return out
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
