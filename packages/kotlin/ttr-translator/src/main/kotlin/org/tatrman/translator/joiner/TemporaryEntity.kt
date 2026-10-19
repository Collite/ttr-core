// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import java.util.Locale

/**
 * LQ-T6 (design §9 ruling 5, contract L-30) — a logical query read by name in another body (`FROM obj.query.x r`) is
 * a **temporary entity**, as the result of an SQL query is a temporary table. Its attributes are the query's output
 * columns, and its relations follow its columns (v1):
 *
 *  - a column that passes an entity attribute through unchanged (as is, renamed, or as a `GROUP BY` key; see
 *    [ColumnLineage]) keeps that attribute's relations. A relation is inherited when every one of its attributes on
 *    that entity's side is passed through. FK-only relations count, with their pairs read through the column
 *    bindings ([RelationPairs]);
 *  - a column that passes all of an entity's key attributes through also relates the temporary entity to that
 *    entity, on them (⚑T6-1: "the top customers, with their names");
 *  - an aggregate or a computed column carries none.
 *
 * Only a logical query (a stored plan that reads entities) is a temporary entity. A db query read by name stays a
 * derived table, whose joins the physical stage conditions from its FKs.
 */
internal class TemporaryEntity private constructor(
    val qname: QualifiedName,
    private val model: ModelHandle,
    origins: List<Pair<String, ColumnLineage.Origin?>>,
) {
    /** `(entity, attribute)` lowercased → the output columns that pass it through. */
    private val passedThrough: Map<Pair<QualifiedName, String>, List<String>> =
        origins
            .mapNotNull { (column, origin) ->
                origin?.takeIf { it.source.schemaCode == SchemaCode.ER }?.let {
                    (it.source to it.column.lowercase(Locale.ROOT)) to column
                }
            }.groupBy({ it.first }, { it.second })

    private val sources: List<QualifiedName> = passedThrough.keys.map { it.first }.distinct()

    /** The relations this temporary entity takes part in, one side being [qname]. */
    val relations: List<ModelRelation> by lazy { inherited() + identity() }

    /** The one output column passing [attribute] of [entity] through, or null (none, or more than one). */
    private fun column(
        entity: QualifiedName,
        attribute: QualifiedName,
    ): String? = passedThrough[entity to JoinPolicy.bareAttribute(attribute).lowercase(Locale.ROOT)]?.singleOrNull()

    private fun own(column: String): QualifiedName = RelationPairs.attribute(qname, column)

    private fun inherited(): List<ModelRelation> =
        model.relations().flatMap { relation ->
            if (relation.fromEntity !in sources && relation.toEntity !in sources) return@flatMap emptyList()
            val pairs = RelationPairs.of(model, relation) ?: return@flatMap emptyList()
            val fromColumns = pairs.map { (f, _) -> column(relation.fromEntity, f) }
            val toColumns = pairs.map { (_, t) -> column(relation.toEntity, t) }
            listOfNotNull(
                fromColumns.takeIf { cols -> cols.all { it != null } }?.let { cols ->
                    ModelRelation(qname, relation.toEntity, pairs.mapIndexed { i, (_, t) -> own(cols[i]!!) to t })
                },
                toColumns.takeIf { cols -> cols.all { it != null } }?.let { cols ->
                    ModelRelation(relation.fromEntity, qname, pairs.mapIndexed { i, (f, _) -> f to own(cols[i]!!) })
                },
            )
        }

    private fun identity(): List<ModelRelation> =
        sources.mapNotNull { entity ->
            val keys = model.attributes(entity).filter { it.isKey }.map { RelationPairs.attribute(entity, it.name) }
            val columns = keys.map { column(entity, it) }
            if (keys.isEmpty() || columns.any { it == null }) {
                null
            } else {
                ModelRelation(qname, entity, keys.mapIndexed { i, key -> own(columns[i]!!) to key })
            }
        }

    companion object {
        /** The temporary entity for the saved query [qname] (`obj.<namespace>.<name>`), or null if it is not one. */
        fun of(
            model: ModelHandle,
            qname: QualifiedName,
        ): TemporaryEntity? {
            if (qname.schemaCode != SchemaCode.OBJ) return null
            val body = runCatching { model.savedQueryBody(qname).planNode }.getOrNull() ?: return null
            if (!readsEntities(body)) return null
            val origins = ColumnLineage.of(body, model) ?: return null
            return TemporaryEntity(qname, model, origins)
        }

        /** A logical query's stored plan reads entities; a db query's reads tables only. */
        private fun readsEntities(plan: PlanNode): Boolean {
            var found = false
            JoinerPlanWalker.forEachNode(plan) {
                if (it.nodeCase == PlanNode.NodeCase.SCAN && it.scan.getObject().schemaCode == SchemaCode.ER) {
                    found = true
                }
            }
            return found
        }
    }
}
