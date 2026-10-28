// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

/**
 * The er renderer's SQL (CN-P3 S3.4): a small tree over entities — what a rendering builds — and its one printer, the
 * **formatting contract** (contracts §3, A-CN-42). Names are written by [ErNames]; a placeholder by the
 * [PlaceholderStyle] of the rendering.
 */
internal sealed interface SqlExpr {
    /** `<alias>.<column>` — always qualified (S0.3 §3 rule 1). */
    data class Col(
        val alias: String,
        val name: String,
    ) : SqlExpr

    /** A literal, already spelled (`'placed'`, `2`, `NULL`). */
    data class Lit(
        val text: String,
    ) : SqlExpr

    /** A placeholder the consumer binds: `{name}` or `:name`. */
    data class Param(
        val name: String,
    ) : SqlExpr

    /** A comparison or an arithmetic operator (`=`, `<=`, `-`, …). */
    data class Bin(
        val op: String,
        val l: SqlExpr,
        val r: SqlExpr,
    ) : SqlExpr

    /** `AND` / `OR` over two or more parts (flattened — [and] / [or]). */
    data class Junction(
        val op: String,
        val parts: List<SqlExpr>,
    ) : SqlExpr

    data class Not(
        val e: SqlExpr,
    ) : SqlExpr

    /** `NAME(args)`; `COUNT(*)` is `Fn("COUNT", [Star])`. */
    data class Fn(
        val name: String,
        val args: List<SqlExpr>,
    ) : SqlExpr

    data object Star : SqlExpr

    data class IsNull(
        val e: SqlExpr,
        val negated: Boolean,
    ) : SqlExpr

    data class InList(
        val e: SqlExpr,
        val items: List<SqlExpr>,
        val negated: Boolean,
    ) : SqlExpr

    data class InSelect(
        val e: SqlExpr,
        val select: Select,
        val negated: Boolean,
    ) : SqlExpr

    data class Exists(
        val select: Select,
        val negated: Boolean,
    ) : SqlExpr

    data class Between(
        val e: SqlExpr,
        val lo: SqlExpr,
        val hi: SqlExpr,
    ) : SqlExpr

    /** `ROW_NUMBER() OVER (PARTITION BY … ORDER BY <order> DESC)` — `latest(…, by:)` (DC-9). */
    data class RowNumber(
        val partition: List<SqlExpr>,
        val order: SqlExpr,
    ) : SqlExpr

    data class Case(
        val branches: List<Pair<SqlExpr, SqlExpr>>,
        val otherwise: SqlExpr?,
    ) : SqlExpr

    companion object {
        fun and(parts: List<SqlExpr>): SqlExpr? = junction("AND", parts)

        fun or(parts: List<SqlExpr>): SqlExpr? = junction("OR", parts)

        private fun junction(
            op: String,
            parts: List<SqlExpr>,
        ): SqlExpr? {
            val flat = parts.flatMap { if (it is Junction && it.op == op) it.parts else listOf(it) }
            return when (flat.size) {
                0 -> null
                1 -> flat.single()
                else -> Junction(op, flat)
            }
        }
    }
}

/** A row source: an entity (under an alias — the entity's own name, a variable) or a derived table. */
internal sealed interface Source {
    val alias: String

    data class Entity(
        val name: String,
        override val alias: String,
    ) : Source

    data class Derived(
        val select: Select,
        override val alias: String,
    ) : Source
}

internal data class Join(
    val left: Boolean,
    val source: Source,
    val on: SqlExpr,
)

internal data class Item(
    val expr: SqlExpr,
    val alias: String? = null,
)

/**
 * One `SELECT` — built while a rendering walks its definition: a sub-query stays open until the walk leaves it, and a
 * hop read deeper in may still add a join to it, so the lists are mutable and the text is printed at the end.
 */
internal class Select(
    var from: Source,
    /** How deep it nests: the rendering's own `SELECT` is 0; a sub-query of a depth-n query is n + 1. */
    val depth: Int = 0,
) {
    val items = mutableListOf<Item>()
    val joins = mutableListOf<Join>()
    val where = mutableListOf<SqlExpr>()
    val groupBy = mutableListOf<SqlExpr>()
    var having: SqlExpr? = null

    /** A statement read's order (CN-P6 ⚑S62-1): each key and whether it is descending; a definition never orders. */
    val orderBy = mutableListOf<Pair<SqlExpr, Boolean>>()

    /** A statement read's row limit; a definition never limits. */
    var limit: Int? = null
}

/**
 * The formatting contract (contracts §3, A-CN-42): keywords upper case; one clause per line — `SELECT`, `FROM`, each
 * `[LEFT] JOIN … ON`, `WHERE`, `GROUP BY`, `HAVING`; a sub-query (a derived table, `EXISTS (…)`, `IN (…)`) opens its
 * parenthesis at the end of the line, its clauses indented two spaces deeper, and closes it on a line of its own at the
 * outer indent; within a clause, single spaces. `AND` binds tighter than `OR` and is parenthesized inside it, an `OR`
 * inside an `AND` too. [compact] prints the same tree on one line (`EXISTS (SELECT 1 …)`) — the spelling S3.7 authored
 * the goldens in, kept for a reader and the tests.
 *
 * The placeholders the text reads are collected in [params], in reading order. A [binder] writes each as its value
 * instead, asked as it is printed — a statement read is self-contained (CN-P6 ⚑S62-1) and printed once (review-154
 * E13); a definition's rendering binds nothing.
 */
internal class SqlPrinter(
    private val style: PlaceholderStyle,
    private val compact: Boolean = false,
    private val binder: ((String) -> String)? = null,
) {
    val params = linkedSetOf<String>()

    fun print(s: Select): String = select(s, 0)

    private fun pad(indent: Int) = " ".repeat(indent)

    private fun select(
        s: Select,
        indent: Int,
    ): String {
        val lines = mutableListOf<String>()
        lines += "SELECT " + s.items.joinToString(", ") { item(it, indent) }
        lines += "FROM " + source(s.from, indent)
        for (j in s.joins) {
            lines +=
                (if (j.left) "LEFT JOIN " else "JOIN ") + source(j.source, indent) + " ON " + expr(j.on, indent, 0)
        }
        SqlExpr.and(s.where)?.let { lines += "WHERE " + expr(it, indent, 0) }
        if (s.groupBy.isNotEmpty()) lines += "GROUP BY " + s.groupBy.joinToString(", ") { expr(it, indent, 0) }
        s.having?.let { lines += "HAVING " + expr(it, indent, 0) }
        if (s.orderBy.isNotEmpty()) {
            lines +=
                "ORDER BY " +
                s.orderBy.joinToString(", ") { (e, desc) -> expr(e, indent, 0) + if (desc) " DESC" else "" }
        }
        s.limit?.let { lines += "LIMIT $it" }
        return if (compact) lines.joinToString(" ") else lines.joinToString("\n") { pad(indent) + it }
    }

    private fun item(
        i: Item,
        indent: Int,
    ): String = expr(i.expr, indent, 0) + (i.alias?.let { " AS " + ErNames.sql(it) } ?: "")

    private fun source(
        s: Source,
        indent: Int,
    ): String =
        when (s) {
            is Source.Entity -> ErNames.sql(s.name) + (if (s.alias != s.name) " " + ErNames.sql(s.alias) else "")
            is Source.Derived -> sub(s.select, indent) + " " + ErNames.sql(s.alias)
        }

    /** A parenthesized sub-query: inline when [compact], else its clauses on their own lines, two spaces deeper. */
    private fun sub(
        s: Select,
        indent: Int,
    ): String = if (compact) "(" + select(s, 0) + ")" else "(\n" + select(s, indent + 2) + "\n" + pad(indent) + ")"

    private fun precedence(e: SqlExpr): Int =
        when (e) {
            is SqlExpr.Junction -> if (e.op == "OR") 1 else 2
            is SqlExpr.Not -> 3
            is SqlExpr.Bin -> if (e.op in ARITHMETIC) (if (e.op == "+" || e.op == "-") 5 else 6) else 4
            is SqlExpr.IsNull, is SqlExpr.InList, is SqlExpr.InSelect, is SqlExpr.Between -> 4
            else -> 7
        }

    /** [e] printed inside an operator of precedence [outer]: parenthesized when it binds looser. */
    private fun expr(
        e: SqlExpr,
        indent: Int,
        outer: Int,
    ): String {
        val text = bare(e, indent)
        val p = precedence(e)
        // `AND` inside `OR` reads plainer parenthesized; anything looser than its context needs it
        val wrap = p < outer || (outer == 1 && p == 2)
        return if (wrap) "($text)" else text
    }

    private fun bare(
        e: SqlExpr,
        indent: Int,
    ): String =
        when (e) {
            is SqlExpr.Col -> ErNames.sql(e.alias) + "." + ErNames.sql(e.name)
            is SqlExpr.Lit -> e.text
            is SqlExpr.Param -> {
                params += e.name
                binder?.invoke(e.name) ?: if (style == PlaceholderStyle.COLON) ":${e.name}" else "{${e.name}}"
            }
            is SqlExpr.Bin -> {
                val p = precedence(e)
                // `a - (b - c)`: the right operand of a non-associative operator at the same level keeps its parentheses
                val right = if (e.op == "-" || e.op == "/") p + 1 else p
                expr(e.l, indent, p) + " " + e.op + " " + expr(e.r, indent, right)
            }
            is SqlExpr.Junction -> e.parts.joinToString(" ${e.op} ") { expr(it, indent, precedence(e)) }
            is SqlExpr.Not -> "NOT " + expr(e.e, indent, 4)
            is SqlExpr.Fn -> e.name + "(" + e.args.joinToString(", ") { expr(it, indent, 0) } + ")"
            SqlExpr.Star -> "*"
            is SqlExpr.IsNull -> expr(e.e, indent, 5) + if (e.negated) " IS NOT NULL" else " IS NULL"
            is SqlExpr.InList ->
                expr(e.e, indent, 5) + (if (e.negated) " NOT IN (" else " IN (") +
                    e.items.joinToString(", ") { expr(it, indent, 0) } + ")"
            is SqlExpr.InSelect ->
                expr(e.e, indent, 5) + (if (e.negated) " NOT IN " else " IN ") +
                    sub(e.select, indent)
            is SqlExpr.Exists -> (if (e.negated) "NOT EXISTS " else "EXISTS ") + sub(e.select, indent)
            is SqlExpr.Between ->
                expr(e.e, indent, 5) + " BETWEEN " + expr(e.lo, indent, 5) + " AND " + expr(e.hi, indent, 5)
            is SqlExpr.RowNumber ->
                "ROW_NUMBER() OVER (PARTITION BY " + e.partition.joinToString(", ") { expr(it, indent, 0) } +
                    " ORDER BY " + expr(e.order, indent, 0) + " DESC)"
            is SqlExpr.Case ->
                "CASE " +
                    e.branches.joinToString(" ") { (w, t) ->
                        "WHEN " + expr(w, indent, 0) + " THEN " +
                            expr(t, indent, 0)
                    } +
                    (e.otherwise?.let { " ELSE " + expr(it, indent, 0) } ?: "") + " END"
        }

    private companion object {
        val ARITHMETIC = setOf("+", "-", "*", "/")
    }
}
