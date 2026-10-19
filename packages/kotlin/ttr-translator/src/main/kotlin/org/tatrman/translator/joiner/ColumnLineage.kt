// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.wire.PlanNodeDecoder

/**
 * LQ-T6 (contract L-30) — where a stored plan's output columns come from. A column that passes one source column
 * through unchanged (selected as is, renamed, or a `GROUP BY` key) has that column as its origin; an aggregate, a
 * computed expression, or a column merged from several sources has none. Read with Calcite's column-origin metadata
 * over the decoded plan, so it follows every node Calcite does (Project, Filter, Join, Aggregate, Sort, set
 * operations).
 */
internal object ColumnLineage {
    /** [source] is the entity (ER) or table (DB) the column comes from; [column] is its attribute or column name. */
    data class Origin(
        val source: QualifiedName,
        val column: String,
    )

    /** Output column name → its origin (null when it has none), in output order; null when [plan] does not decode. */
    fun of(
        plan: PlanNode,
        model: ModelHandle,
    ): List<Pair<String, Origin?>>? {
        val rel = runCatching { PlanNodeDecoder.decode(plan, TranslatorFramework(model)) }.getOrNull() ?: return null
        val mq = rel.cluster.metadataQuery
        return rel.rowType.fieldList.mapIndexed { i, field ->
            val origin =
                mq
                    .getColumnOrigins(rel, i)
                    ?.singleOrNull()
                    ?.takeUnless { it.isDerived }
                    ?.let { o ->
                        val path = o.originTable.qualifiedName
                        val schema = path.firstOrNull()?.let(::parseSchemaCode)
                        if (path.size != 3 || schema == null) {
                            null
                        } else {
                            Origin(
                                QualifiedName
                                    .newBuilder()
                                    .setSchemaCode(schema)
                                    .setNamespace(path[1])
                                    .setName(path[2])
                                    .build(),
                                o.originTable.rowType.fieldList[o.originColumnOrdinal]
                                    .name,
                            )
                        }
                    }
            field.name to origin
        }
    }
}
