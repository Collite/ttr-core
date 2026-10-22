// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import java.util.ServiceLoader

/** null means unknown/opaque; an empty set means a known literal with no physical origin. */
interface QueryLineage {
    fun originsOf(
        context: QueryLineageContext,
        query: String,
        column: String,
        ordinal: Int,
    ): Set<String>?

    companion object {
        fun provider(): QueryLineage =
            ServiceLoader.load(QueryLineage::class.java).firstOrNull() ?: SyntacticQueryLineage()
    }
}

/** Immutable snapshot inputs for providers. The metadata module never depends on the translator. */
class QueryLineageContext(
    val physical: PhysicalIndex,
) {
    private val visiting = mutableSetOf<String>()

    fun queryText(query: String): String? = physical.queries[query]?.second?.sourceText

    fun entity(
        query: String,
        path: String,
    ): String? {
        val doc = physical.queries[query]?.first ?: return null
        return physical
            .resolve(doc, if (path.startsWith("er.")) path else "er.entity.$path")
            ?.takeIf { it.kind == "entity" }
            ?.qname
    }

    fun attributeOrigins(
        entity: String,
        column: String,
        provider: QueryLineage,
    ): Set<String>? {
        val entry = physical.entities[entity] ?: return null
        val ordinal = entry.second.attributes.indexOfFirst { it.name.equals(column, ignoreCase = true) }
        if (ordinal < 0) return null
        val name = entry.second.attributes[ordinal].name
        val direct = physical.erAttrToColumn["$entity.$name"]
        if (direct != null) return direct.toSet()
        val query = physical.queryEntities[entity] ?: return null
        val guard = "$query.$name"
        if (!visiting.add(guard)) return null
        return try {
            runCatching { provider.originsOf(this, query, physical.boundColumn("$entity.$name"), ordinal) }.getOrNull()
        } finally {
            visiting.remove(guard)
        }
    }
}

/**
 * Conservative SELECT/UNION ALL projection walker: one entity per branch, bare attributes (qualified or not),
 * aliases and literals. Computations, joins, stars, CTEs and unsupported syntax return unknown, never a guessed origin.
 */
class SyntacticQueryLineage : QueryLineage {
    override fun originsOf(
        context: QueryLineageContext,
        query: String,
        column: String,
        ordinal: Int,
    ): Set<String>? {
        val sql = context.queryText(query) ?: return null
        val tokens = tokenize(sql) ?: return null
        val branches = mutableListOf<MutableList<String>>(mutableListOf())
        var i = 0
        while (i < tokens.size) {
            if (tokens[i].equals("UNION", true)) {
                if (!tokens.getOrNull(i + 1).equals("ALL", true)) return null
                branches += mutableListOf<String>()
                i += 2
            } else {
                branches.last() += tokens[i++]
            }
        }
        val out = sortedSetOf<String>()
        var selected: Int? = null
        for (branch in branches) {
            if (!branch.firstOrNull().equals("SELECT", true)) return null
            val from = branch.indexOfFirst { it.equals("FROM", true) }
            if (from < 0) return null
            val tail = branch.drop(from + 1).filter { it != ";" }
            if (tail.isEmpty()) return null
            // Only one source, with an optional alias. A filter doesn't change a column's origin.
            val stop = tail.indexOfFirst { it.equals("WHERE", true) }.let { if (it < 0) tail.size else it }
            val sourceTokens = tail.take(stop)
            if (sourceTokens.isEmpty()) return null
            var n = 1
            var source = unquote(sourceTokens[0])
            while (sourceTokens.getOrNull(n) == "." && sourceTokens.getOrNull(n + 1) != null) {
                source += "." + unquote(sourceTokens[n + 1])
                n += 2
            }
            val remainder = sourceTokens.drop(n)
            val alias =
                when {
                    remainder.isEmpty() -> source.substringAfterLast('.')
                    remainder.size == 1 && identifier(remainder[0]) -> unquote(remainder[0])
                    remainder.size == 2 &&
                        remainder[0].equals(
                            "AS",
                            true,
                        ) &&
                        identifier(remainder[1]) -> unquote(remainder[1])
                    else -> return null
                }
            val entity = context.entity(query, source) ?: return null
            val items = mutableListOf<MutableList<String>>(mutableListOf())
            for (token in branch.subList(1, from)) {
                if (token == ",") items += mutableListOf<String>() else items.last() += token
            }
            if (selected == null) {
                val matches =
                    items.indices.filter { index ->
                        val item = items[index]
                        val asAt = item.indexOfFirst { it.equals("AS", true) }
                        val label = if (asAt >= 0) item.getOrNull(asAt + 1) else item.lastOrNull()
                        label?.let(::unquote)?.equals(column, true) == true
                    }
                selected = matches.singleOrNull() ?: return null
            }
            var expr = items.getOrNull(selected)?.toList() ?: return null
            val asAt = expr.indexOfFirst { it.equals("AS", true) }
            if (asAt >= 0) {
                if (asAt != expr.size - 2 || !identifier(expr.last())) return null
                expr = expr.take(asAt)
            } else if (expr.size >= 2 && identifier(expr.last()) && expr[expr.lastIndex - 1] != ".") {
                expr = expr.dropLast(1)
            }
            if (expr.size == 1 &&
                (
                    expr[0].startsWith("'") ||
                        expr[0].toDoubleOrNull() != null ||
                        expr[0].uppercase() in setOf("NULL", "TRUE", "FALSE")
                )
            ) {
                continue
            }
            val attr =
                when {
                    expr.size == 1 && identifier(expr[0]) -> unquote(expr[0])
                    expr.size == 3 &&
                        expr[1] == "." &&
                        unquote(
                            expr[0],
                        ).equals(alias, true) &&
                        identifier(expr[2]) -> unquote(expr[2])
                    else -> return null
                }
            out += context.attributeOrigins(entity, attr, this) ?: return null
        }
        return out
    }

    private fun identifier(token: String): Boolean =
        token.matches(Regex("[A-Za-z_][A-Za-z_0-9]*|\"(?:[^\"]|\"\")+\"|`(?:[^`]|``)+`"))

    private fun unquote(token: String): String =
        if (token.startsWith('"') ||
            token.startsWith('`')
        ) {
            token.substring(1, token.length - 1)
        } else {
            token
        }

    private fun tokenize(sql: String): List<String>? {
        val pattern =
            Regex(
                "\\s+|--[^\\n]*|/\\*[\\s\\S]*?\\*/|'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|`(?:[^`]|``)*`|[A-Za-z_][A-Za-z_0-9]*|[0-9]+(?:\\.[0-9]+)?|[.,;()+*/=<>-]",
            )
        val tokens = mutableListOf<String>()
        var end = 0
        for (m in pattern.findAll(sql)) {
            if (m.range.first != end) return null
            val value = m.value
            if (!value.first().isWhitespace() && !value.startsWith("--") && !value.startsWith("/*")) tokens += value
            end = m.range.last + 1
        }
        return tokens.takeIf { end == sql.length }
    }
}
