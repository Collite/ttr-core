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
 * One left-to-right scan shares literal, quoted-identifier and comment boundaries with the other
 * pre-parse rails. Quoted names are decoded as whole tokens, comments do not interrupt a table
 * reference, and `WHERE note = 'use WITH (NOLOCK)'` is never matched.
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
        val references = mutableListOf<ReferenceToken>()
        var continuesReference = false

        fun reference(
            text: String,
            quoted: Boolean,
        ) {
            if (!quoted && text.lowercase() in CLAUSE_BOUNDARIES) {
                references.clear()
                continuesReference = false
                return
            }
            val decoded =
                if (quoted) {
                    text.substring(1, text.length - 1).replace("\"\"", "\"")
                } else {
                    text.split('.').joinToString(".") { it.trim('`', '[', ']') }
                }
            val previous = references.lastOrNull()
            if (previous != null && (continuesReference || (!quoted && text.startsWith('.')))) {
                references[references.lastIndex] = ReferenceToken(previous.name + decoded, isAs = false)
            } else {
                references += ReferenceToken(decoded, isAs = !quoted && text.equals("AS", ignoreCase = true))
            }
            continuesReference = !quoted && text.endsWith('.')
        }

        var i = 0
        while (i < sql.length) {
            val protectedEnd = SqlTextScanner.protectedEnd(sql, i)
            if (protectedEnd > i) {
                when (sql[i]) {
                    '"' -> reference(sql.substring(i, protectedEnd), quoted = true)
                    '\'' -> {
                        references.clear()
                        continuesReference = false
                    }
                    // Comments are invisible to the table-reference token run.
                }
                out.append(sql, i, protectedEnd)
                i = protectedEnd
                continue
            }
            if (isHintWithAt(sql, i)) {
                val parenStart = nextNonWs(sql, i + WITH.length)
                val parenEnd = matchingParen(sql, parenStart)
                if (parenEnd >= 0) {
                    val tableKey =
                        references
                            .firstOrNull { !it.isAs }
                            ?.name
                            ?.substringAfterLast('.')
                            ?.lowercase()
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
            if (isRefChar(sql[i])) {
                val start = i++
                while (i < sql.length && isRefChar(sql[i])) i++
                reference(sql.substring(start, i), quoted = false)
                out.append(sql, start, i)
            } else {
                if (!sql[i].isWhitespace()) {
                    references.clear()
                    continuesReference = false
                }
                out.append(sql[i++])
            }
        }
        return ExtractedHints(cleanedSql = out.toString(), byTable = byTable)
    }

    private data class ReferenceToken(
        val name: String,
        val isAs: Boolean,
    )

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
     * protected spans inside the hint body. Returns -1 if unbalanced.
     */
    private fun matchingParen(
        sql: String,
        open: Int,
    ): Int {
        if (open >= sql.length || sql[open] != '(') return -1
        var depth = 0
        var j = open
        while (j < sql.length) {
            val end = SqlTextScanner.protectedEnd(sql, j)
            if (end > j) {
                j = end
                continue
            }
            when (sql[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        return -1
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
                    TableHintSpec(name = bracketHintIdentifiers(name), options = options.map(::bracketHintIdentifiers))
                } else {
                    TableHintSpec(name = bracketHintIdentifiers(item))
                }
            }

    /** Split on commas at paren-depth 0 (so `INDEX(0, 1)` stays one item). */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var i = 0
        while (i < s.length) {
            val end = SqlTextScanner.protectedEnd(s, i)
            if (end > i) {
                cur.append(s, i, end)
                i = end
                continue
            }
            val c = s[i++]
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

    /** Hint text is emitted raw by MSSQL; restore its identifier quotes after splitting options. */
    private fun bracketHintIdentifiers(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val end = SqlTextScanner.protectedEnd(text, i)
            if (end > i) {
                if (text[i] == '"') {
                    val name = text.substring(i + 1, end - 1).replace("\"\"", "\"")
                    out.append('[').append(name.replace("]", "]]")).append(']')
                } else {
                    out.append(text, i, end)
                }
                i = end
            } else {
                out.append(text[i++])
            }
        }
        return out.toString()
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

    /** Bare reference characters; double-quoted names are tokens from the shared scanner. */
    private fun isRefChar(c: Char): Boolean = isIdentChar(c) || c == '`' || c == '[' || c == ']'
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
