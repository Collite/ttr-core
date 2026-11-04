// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

/**
 * Extracts T-SQL table hints (`FROM tbl WITH (NOLOCK)`, `FROM tbl m WITH (NOLOCK, ROWLOCK)`) out
 * of a SQL string *before* it reaches Calcite's parser, recording which table each hint set
 * belongs to. (NX-A.S4 — ported from ai-platform's query-translator, decision D9.)
 *
 * Why extract rather than parse the hint in the grammar: T-SQL places the hint **after** the
 * (optional) alias, a position Calcite's `TableRef3` production can't reach without forking the
 * parser grammar. The cleaned SQL parses with the stock parser; the hints are reattached
 * structurally during proto encoding ([org.tatrman.translator.wire.PlanNodeEncoder]) by matching the
 * table name, and re-emitted by the MSSQL unparse
 * ([org.tatrman.translator.dialects.MssqlSqlDialectWithFloatCast.unparseTableScanHints]).
 *
 * The scan is literal-aware: a single left-to-right pass tracking whether it sits inside a
 * single-quoted string literal (doubled `''` stays inside), so `WHERE note = 'use WITH (NOLOCK)'`
 * is never matched.
 *
 * A `WITH` token counts as a table hint **only** when the next non-whitespace character is `(`.
 * The CTE form `WITH cte AS (…)` has an identifier between `WITH` and `(`, so it is never matched —
 * which also means a leading statement-level `WITH (` can't false-positive (it's not valid SQL).
 *
 * **Limitation (accepted, D9):** association is by table *name* (last dotted segment, lowercased),
 * not alias. If the same table appears twice with different hint sets, both scans receive the
 * union. Fine for NOLOCK (the dominant case); alias-precise matching is a documented later
 * refinement.
 */
object TableHintExtractor {
    /** SQL keywords that bound a table reference on the left — the table run never crosses these. */
    private val CLAUSE_BOUNDARIES =
        setOf(
            "from",
            "join",
            "apply",
            "on",
            "where",
            "select",
            "group",
            "order",
            "having",
            "union",
            "inner",
            "left",
            "right",
            "full",
            "outer",
            "cross",
        )

    fun extract(sql: String): ExtractedHints {
        val out = StringBuilder(sql.length)
        val byTable = linkedMapOf<String, MutableList<TableHintSpec>>()
        var i = 0
        while (i < sql.length) {
            val protectedEnd = SqlTextScanner.protectedEnd(sql, i)
            if (protectedEnd > i) {
                out.append(sql, i, protectedEnd)
                i = protectedEnd
                continue
            }
            if (isHintWithAt(sql, i)) {
                val parenStart = nextNonWs(sql, i + WITH.length)
                val parenEnd = matchingParen(sql, parenStart)
                if (parenEnd >= 0) {
                    val tableKey = tableKeyBefore(sql, i)
                    val specs = parseHintBody(sql.substring(parenStart + 1, parenEnd))
                    if (tableKey != null && specs.isNotEmpty()) {
                        byTable.getOrPut(tableKey) { mutableListOf() }.addAll(specs)
                    }
                    // Replace the whole `WITH ( … )` span with a single space so tokens don't fuse.
                    out.append(' ')
                    i = parenEnd + 1
                    continue
                }
            }
            out.append(sql[i])
            i++
        }
        return ExtractedHints(cleanedSql = out.toString(), byTable = byTable)
    }

    private const val WITH = "WITH"

    /**
     * True when a `WITH` table-hint keyword starts at [pos]: a whole-word `WITH` (case-insensitive,
     * not part of a larger identifier) whose next non-whitespace character is `(`.
     */
    private fun isHintWithAt(
        sql: String,
        pos: Int,
    ): Boolean {
        if (pos + WITH.length > sql.length) return false
        if (!sql.regionMatches(pos, WITH, 0, WITH.length, ignoreCase = true)) return false
        if (pos > 0 && isIdentChar(sql[pos - 1])) return false
        val after = pos + WITH.length
        if (after < sql.length && isIdentChar(sql[after])) return false
        val np = nextNonWs(sql, after)
        return np < sql.length && sql[np] == '('
    }

    /** Index of the next non-whitespace char at or after [from] (may be [sql].length). */
    private fun nextNonWs(
        sql: String,
        from: Int,
    ): Int {
        var j = from
        while (j < sql.length && sql[j].isWhitespace()) j++
        return j
    }

    /**
     * Index of the `)` matching the `(` at [open], honouring nested parens (e.g. `INDEX(0)`) and
     * string literals inside the hint body. Returns -1 if unbalanced.
     */
    private fun matchingParen(
        sql: String,
        open: Int,
    ): Int {
        if (open >= sql.length || sql[open] != '(') return -1
        var depth = 0
        var inLit = false
        var j = open
        while (j < sql.length) {
            val c = sql[j]
            when {
                c == '\'' && inLit && j + 1 < sql.length && sql[j + 1] == '\'' -> j++
                c == '\'' -> inLit = !inLit
                !inLit && c == '(' -> depth++
                !inLit && c == ')' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        return -1
    }

    /**
     * The table key for a hint whose `WITH` starts at [withStart]: walk backwards over the
     * whitespace-separated identifier run immediately preceding `WITH` (`table [AS] [alias]`),
     * stopping at a clause keyword or a `,` / `(` delimiter, and return the *leftmost* identifier
     * (the table) reduced to its last dotted segment, unquoted and lowercased. Null when no
     * identifier precedes.
     *
     * Quoted identifiers (`"mu"`, `` `mu` ``, `[mu]`) are captured as part of the run and the
     * surrounding quote chars stripped from the final key, so `FROM "dbo"."mu" WITH (NOLOCK)`
     * keys on `mu` — matching the unquoted name the encoded scan carries.
     */
    private fun tableKeyBefore(
        sql: String,
        withStart: Int,
    ): String? {
        var j = withStart - 1
        val idents = mutableListOf<String>()
        while (j >= 0) {
            while (j >= 0 && sql[j].isWhitespace()) j--
            if (j < 0) break
            val c = sql[j]
            if (c == ',' || c == '(' || c == ')') break
            if (!isRefChar(c)) break
            val end = j + 1
            while (j >= 0 && isRefChar(sql[j])) j--
            val token = sql.substring(j + 1, end)
            if (token.lowercase() in CLAUSE_BOUNDARIES) break
            idents.add(token)
            // continue scanning further-left tokens (alias / AS / table)
        }
        // `idents` is right-to-left; the table is the leftmost non-`AS` identifier.
        val table = idents.lastOrNull { !it.equals("AS", ignoreCase = true) } ?: return null
        return table.substringAfterLast('.').trim('"', '`', '[', ']').lowercase()
    }

    /** Parse the comma-separated hint list (the text between the outer parens). */
    private fun parseHintBody(body: String): List<TableHintSpec> =
        splitTopLevel(body)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { item ->
                val paren = item.indexOf('(')
                if (paren >= 0 && item.endsWith(")")) {
                    val name = item.substring(0, paren).trim()
                    val options =
                        splitTopLevel(item.substring(paren + 1, item.length - 1))
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    TableHintSpec(name = name, options = options)
                } else {
                    TableHintSpec(name = item)
                }
            }

    /** Split on commas at paren-depth 0 (so `INDEX(0, 1)` stays one item). */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' -> {
                    depth++
                    cur.append(c)
                }
                c == ')' -> {
                    depth--
                    cur.append(c)
                }
                c == ',' && depth == 0 -> {
                    parts.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        return parts
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

    /** [isIdentChar] plus the identifier-quote chars, so a quoted table name scans as one run. */
    private fun isRefChar(c: Char): Boolean = isIdentChar(c) || c == '"' || c == '`' || c == '[' || c == ']'
}

/** Result of [TableHintExtractor.extract]. */
data class ExtractedHints(
    /** The SQL with every `WITH ( … )` table-hint span replaced by a single space. */
    val cleanedSql: String,
    /** Lowercased table name (last dotted segment) → hints applied to it. */
    val byTable: Map<String, List<TableHintSpec>>,
)

/** One T-SQL table hint: a bare `name` (`NOLOCK`) or `name(options)` (`INDEX(0)`). */
data class TableHintSpec(
    val name: String,
    val options: List<String> = emptyList(),
)
