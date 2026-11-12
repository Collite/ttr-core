// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

/**
 * Rewrites T-SQL `SELECT [ALL | DISTINCT] TOP <int> …` into standard `… FETCH FIRST <int> ROWS ONLY`
 * *before* the SQL reaches Calcite's parser.
 *
 * Why rewrite rather than parse `TOP` in the grammar: `TOP` is a SELECT-list prefix modifier that
 * Calcite's `SqlSelect` production can't host without forking the parser grammar — the same decision
 * that makes [TableHintExtractor] a pre-parse text pass rather than a grammar change. The entire
 * downstream limit path already exists: a Calcite `Sort` with `fetch = n` encodes to a
 * `LimitOffsetNode` ([org.tatrman.translator.wire.PlanNodeEncoder]) and the MSSQL dialect renders it
 * back. So the only missing piece is turning the `TOP` surface into a `FETCH` clause the parser
 * accepts. (NX-A.S4 — ported from ai-platform's query-translator, decision D9.)
 *
 * The scan shares [SqlTextScanner] with [TableHintExtractor]: a left-to-right pass aware of quoted
 * identifiers, single-quoted string literals (doubled `''` stays inside) and `-- …` / `/* … */` comments, so a `TOP` inside a
 * literal or comment is never matched.
 *
 * Scope-correct placement: the `FETCH` clause is inserted at the end of the **same** query scope the
 * `TOP` belongs to — tracked by paren depth. For the outer query it lands at end-of-string (after any
 * `ORDER BY`); for a `(SELECT TOP 1 … )` scalar subquery it lands just before the `)` that closes the
 * subquery. This is why nested `TOP` (e.g. `TOP 1` inside a `WHERE` scalar subquery) is handled
 * correctly.
 *
 * **Only** the bare `TOP <integer-literal>` form is rewritten. `TOP (expr)`, `TOP n PERCENT`, and
 * `WITH TIES` are left verbatim, so they surface an ordinary parse error rather than a silently-wrong
 * rewrite.
 */
object TopClauseExtractor {
    private const val SELECT = "SELECT"
    private const val TOP = "TOP"

    /** One `TOP <n>` occurrence: the `[topStart, topEnd)` span to strip and where to insert `FETCH`. */
    private data class TopEdit(
        val topStart: Int,
        val topEnd: Int,
        val insertAt: Int,
        val n: String,
    )

    /** A single string mutation. `text != null` → insert at [at]; `text == null` → replace `[at, end)`. */
    private data class Op(
        val at: Int,
        val end: Int,
        val text: String?,
    )

    fun rewrite(sql: String): String {
        val edits = mutableListOf<TopEdit>()
        var i = 0
        while (i < sql.length) {
            val protectedEnd = SqlTextScanner.protectedEnd(sql, i)
            when {
                protectedEnd > i -> i = protectedEnd
                isKeywordAt(sql, i, SELECT) -> {
                    topEditForSelectAt(sql, i)?.let { edits.add(it) }
                    i += SELECT.length
                }
                else -> i++
            }
        }
        if (edits.isEmpty()) return sql

        // Flatten each edit into its two mutations (insert FETCH; strip TOP) and apply ALL of them
        // strictly highest-index-first, so every mutation leaves lower indices — including those of
        // other, nested edits — valid. (Applying an edit's own strip before a later edit's positions
        // is what corrupted nested rewrites.)
        val ops =
            edits.flatMap {
                listOf(
                    Op(at = it.insertAt, end = it.insertAt, text = " FETCH FIRST ${it.n} ROWS ONLY"),
                    Op(at = it.topStart, end = it.topEnd, text = null),
                )
            }
        val sb = StringBuilder(sql)
        for (op in ops.sortedByDescending { it.at }) {
            if (op.text != null) sb.insert(op.at, op.text) else sb.replace(op.at, op.end, " ")
        }
        return sb.toString()
    }

    /**
     * If the `SELECT` at [selectPos] is followed (past an optional `ALL`/`DISTINCT`) by a bare
     * `TOP <integer>` — not `TOP (…)` or `TOP n PERCENT` — return the edit to apply. Null otherwise.
     */
    private fun topEditForSelectAt(
        sql: String,
        selectPos: Int,
    ): TopEdit? {
        var p = nextNonWs(sql, selectPos + SELECT.length)
        if (isKeywordAt(sql, p, "ALL")) {
            p = nextNonWs(sql, p + 3)
        } else if (isKeywordAt(sql, p, "DISTINCT")) {
            p = nextNonWs(sql, p + "DISTINCT".length)
        }
        if (!isKeywordAt(sql, p, TOP)) return null

        val topStart = p
        val numStart = nextNonWs(sql, p + TOP.length)
        if (numStart >= sql.length || !sql[numStart].isDigit()) return null // TOP (expr) / non-literal
        var q = numStart
        while (q < sql.length && sql[q].isDigit()) q++
        val n = sql.substring(numStart, q)
        // Reject the unsupported `TOP n PERCENT` form — leave it verbatim to fail loudly.
        if (isKeywordAt(sql, nextNonWs(sql, q), "PERCENT")) return null
        return TopEdit(topStart = topStart, topEnd = q, insertAt = scopeEnd(sql, q), n = n)
    }

    /**
     * End of the query scope owning a `TOP` whose number ends at [fromPos]: scan forward,
     * literal/comment-aware, tracking paren depth from 0; the scope ends at the first `)` seen at
     * depth 0 (which closes an enclosing subquery) or at end-of-string.
     */
    private fun scopeEnd(
        sql: String,
        fromPos: Int,
    ): Int {
        var depth = 0
        var j = fromPos
        while (j < sql.length) {
            val protectedEnd = SqlTextScanner.protectedEnd(sql, j)
            if (protectedEnd > j) {
                j = protectedEnd
                continue
            }
            val c = sql[j]
            when {
                c == '(' -> depth++
                c == ')' -> {
                    if (depth == 0) return j
                    depth--
                }
            }
            j++
        }
        return sql.length
    }

    private fun isKeywordAt(
        sql: String,
        pos: Int,
        kw: String,
    ): Boolean {
        if (pos + kw.length > sql.length) return false
        if (!sql.regionMatches(pos, kw, 0, kw.length, ignoreCase = true)) return false
        if (pos > 0 && isIdentChar(sql[pos - 1])) return false
        val after = pos + kw.length
        if (after < sql.length && isIdentChar(sql[after])) return false
        return true
    }

    private fun nextNonWs(
        sql: String,
        from: Int,
    ): Int {
        var j = from
        while (j < sql.length && sql[j].isWhitespace()) j++
        return j
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
