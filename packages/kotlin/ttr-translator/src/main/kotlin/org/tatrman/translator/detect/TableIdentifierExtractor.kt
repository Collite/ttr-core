// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.detect

import org.apache.calcite.sql.SqlBasicCall
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlJoin
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlOrderBy
import org.apache.calcite.sql.SqlSelect
import org.apache.calcite.sql.SqlWith
import org.apache.calcite.sql.SqlWithItem

object TableIdentifierExtractor {
    /**
     * The lowercased simple names of the tables / entities [sqlNode] reads in its FROM clauses (CTE names
     * excluded). [skipSavedQueryRefs] (LQ) leaves out saved-query references (`obj.<namespace>.<name>`):
     * they name neither lane — UNFOLD replaces them with their bodies.
     */
    @JvmOverloads
    fun fromQuery(
        sqlNode: SqlNode,
        skipSavedQueryRefs: Boolean = false,
    ): List<String> {
        val collector = Collector(skipSavedQueryRefs)
        collector.collect(sqlNode)
        return collector.tables.sorted()
    }

    private class Collector(
        private val skipSavedQueryRefs: Boolean,
    ) {
        val tables = mutableSetOf<String>()
        private val cteNames = mutableSetOf<String>()

        fun collect(node: SqlNode?) {
            if (node == null) return

            when (node) {
                is SqlWith -> {
                    node.withList.forEach { withItem ->
                        if (withItem is SqlWithItem) {
                            cteNames.add(withItem.name.toString().lowercase())
                            collect(withItem.query)
                        }
                    }
                    collect(node.body)
                }

                is SqlOrderBy -> {
                    collect(node.query)
                }

                is SqlSelect -> {
                    collectFrom(node.from)
                }

                is SqlJoin -> {
                    collectFrom(node.left)
                    collectFrom(node.right)
                }

                is SqlBasicCall -> {
                    if (node.operator.kind == SqlKind.AS) {
                        collectFrom(node.operandList.getOrNull(0))
                    } else {
                        node.operandList.filterIsInstance<SqlNode>().forEach { operand ->
                            collect(operand)
                        }
                    }
                }

                is SqlIdentifier -> add(node)

                else -> {}
            }
        }

        private fun collectFrom(node: SqlNode?) {
            when (node) {
                is SqlIdentifier -> add(node)

                is SqlJoin -> {
                    collectFrom(node.left)
                    collectFrom(node.right)
                }

                is SqlSelect -> {
                    collectFrom(node.from)
                }

                is SqlWith -> {
                    collect(node)
                }

                is SqlBasicCall -> {
                    if (node.operator.kind == SqlKind.AS) {
                        collectFrom(node.operandList.getOrNull(0))
                    } else {
                        node.operandList.filterIsInstance<SqlNode>().forEach { operand ->
                            collectFrom(operand)
                        }
                    }
                }

                else -> {}
            }
        }

        private fun add(node: SqlIdentifier) {
            if (skipSavedQueryRefs && node.names.size >= 2 && node.names[0].equals("obj", ignoreCase = true)) return
            val name = node.names.lastOrNull() ?: return
            val lname = name.lowercase()
            if (lname !in cteNames) {
                tables.add(lname)
            }
        }
    }
}
