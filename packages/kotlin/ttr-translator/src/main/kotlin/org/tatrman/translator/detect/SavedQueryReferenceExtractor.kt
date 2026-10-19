// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.detect

import org.apache.calcite.sql.SqlBasicCall
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlJoin
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlSelect
import org.apache.calcite.sql.util.SqlBasicVisitor
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.codec.sql.SqlParser
import org.tatrman.translator.codec.sql.ParseResult as SqlParseResult
import org.tatrman.translator.codec.sql.TableHintExtractor
import org.tatrman.translator.codec.sql.TopClauseExtractor

/**
 * LQ (contract L-24) — the saved queries a SQL body reads as `obj.<namespace>.<name>`, wherever a FROM clause sits:
 * the top level, a join, a CTE body, a derived table, a set-operation branch, or a sub-query in WHERE / the select
 * list / HAVING. A metadata worker calls this before parsing anything, to parse a query after the queries it reads.
 *
 * Only a three-part identifier whose first part is `obj` (any case) in a FROM position counts — a CTE, an entity, a
 * table and a two-part name do not. Names are returned as written, deduplicated case-insensitively (the SQL resolver
 * matches them that way), in first-occurrence order. Pure: no model.
 */
object SavedQueryReferenceExtractor {
    /** [source] as a worker holds it: a trailing `;`, `TOP n`, table hints and `{name}` placeholders are accepted. */
    fun fromSource(source: String): List<QualifiedName> {
        // A Calcite entry point like the parse: promote Calcite's literal charset to UTF-8 before any Calcite class
        // loads. A metadata worker calls this before its first parse; without it Calcite froze ISO-8859-1 for the
        // process and every later body with a literal outside Latin-1 failed. Idempotent; see CalciteCharset.
        org.tatrman.translator.framework.CalciteCharset
            .ensureUtf8()
        val terminated = source.trimEnd().removeSuffix(";").trimEnd()
        val cleaned = TableHintExtractor.extract(TopClauseExtractor.rewrite(terminated)).cleanedSql
        val placeholdersFree = PLACEHOLDER.replace(cleaned, "?")
        val parsed = SqlParser.parseQuery(placeholdersFree)
        return if (parsed is SqlParseResult.Success) fromQuery(parsed.sqlNode) else emptyList()
    }

    fun fromQuery(sqlNode: SqlNode): List<QualifiedName> {
        val found = LinkedHashMap<String, QualifiedName>()
        sqlNode.accept(
            object : SqlBasicVisitor<Unit>() {
                override fun visit(call: SqlCall) {
                    if (call is SqlSelect) collectFrom(call.from, found)
                    super.visit(call)
                }
            },
        )
        return found.values.toList()
    }

    private fun collectFrom(
        node: SqlNode?,
        found: MutableMap<String, QualifiedName>,
    ) {
        when (node) {
            is SqlIdentifier ->
                savedQuery(
                    node,
                )?.let { found.putIfAbsent("${it.namespace}.${it.name}".lowercase(), it) }
            is SqlJoin -> {
                collectFrom(node.left, found)
                collectFrom(node.right, found)
            }
            is SqlBasicCall -> if (node.kind == SqlKind.AS) collectFrom(node.operandList.firstOrNull(), found)
            else -> Unit // a derived table / set operation is a call the visitor descends into on its own
        }
    }

    private fun savedQuery(id: SqlIdentifier): QualifiedName? {
        if (id.names.size != 3 || !id.names[0].equals(OBJ, ignoreCase = true)) return null
        return QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.OBJ)
            .setNamespace(id.names[1])
            .setName(id.names[2])
            .build()
    }

    private const val OBJ = "obj"
    private val PLACEHOLDER = Regex("""\{[A-Za-z_][A-Za-z0-9_]*}""")
}
