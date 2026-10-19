// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import java.util.Locale

/**
 * LQ-T6 (contract L-30) — the attribute pairs of a relation bound only to its foreign key (`binding: { fk: … }`, no
 * `join:` list), read through both entities' column bindings. Each FK column is the column an attribute binds to:
 * through the entity's table, or through the query the entity stacks on (that query's [ColumnLineage]).
 *
 * Pairs come back only when exactly one FK joins the two sides and each of its columns is exactly one attribute's on
 * each side. Otherwise the result is null, and the relation stays `WithoutPairs`, for [JoinerPhysical] to condition
 * from the FK after MAP_TO_PHYSICAL, as before.
 */
internal object RelationPairs {
    /** `(namespace.table, column)`, lowercased: how an FK column and an attribute's bound column are matched. */
    private data class ColumnKey(
        val table: String,
        val column: String,
    )

    private const val MAX_DEPTH = 8

    /** The relation's pairs: its own `join:` pairs, or pairs derived from its FK; null when none can be derived. */
    fun of(
        model: ModelHandle,
        relation: ModelRelation,
    ): List<Pair<QualifiedName, QualifiedName>>? {
        if (relation.joinPairs.isNotEmpty()) return relation.joinPairs
        val from = invert(boundColumns(model, relation.fromEntity, 0) ?: return null)
        val to = invert(boundColumns(model, relation.toEntity, 0) ?: return null)
        val candidates =
            model
                .foreignKeys()
                .filter { it.from.isNotEmpty() && it.from.size == it.to.size }
                .flatMap { fk ->
                    listOfNotNull(
                        match(fk.from, fk.to, from, to)?.map { (a, b) ->
                            attribute(relation.fromEntity, a) to attribute(relation.toEntity, b)
                        },
                        // An FK may point either way along the relation; a self-relation reads it forward only.
                        if (relation.fromEntity == relation.toEntity) {
                            null
                        } else {
                            match(fk.from, fk.to, to, from)?.map { (b, a) ->
                                attribute(relation.fromEntity, a) to attribute(relation.toEntity, b)
                            }
                        },
                    )
                }
        return candidates.singleOrNull()
    }

    /**
     * ttr-core#204 — a relation's pairs at the ER level, but only where the physical stage cannot condition it: one
     * side is an entity stacked on a query whose tables sit behind an `Aggregate` (or anything but `Join`, `Filter`
     * and `Project`), so [JoinerPhysical] would find no table to match an FK against. Elsewhere null, so today's
     * plans stay byte-identical: the FK is still applied after MAP_TO_PHYSICAL.
     */
    fun behindOpaqueSide(
        model: ModelHandle,
        relation: ModelRelation,
    ): List<Pair<QualifiedName, QualifiedName>>? {
        if (relation.joinPairs.isNotEmpty()) return null
        val opaque = listOf(relation.fromEntity, relation.toEntity).any { opaqueToPhysical(model, it, 0) }
        return if (opaque) of(model, relation) else null
    }

    /** True when [entity] is stacked on a query whose body [JoinerPhysical] cannot see a table through. */
    private fun opaqueToPhysical(
        model: ModelHandle,
        entity: QualifiedName,
        depth: Int,
    ): Boolean {
        val mapping = model.entityMapping(entity) as? EntityMapping.ToQuery ?: return false
        val body = runCatching { model.savedQueryBody(mapping.query).planNode }.getOrNull() ?: return false
        return !reachesScan(model, body, depth + 1)
    }

    /** [JoinerPhysical.collectTableScans]'s descent, over a stored plan that may still read entities. */
    private fun reachesScan(
        model: ModelHandle,
        plan: PlanNode,
        depth: Int,
    ): Boolean =
        depth <= MAX_DEPTH &&
            when (plan.nodeCase) {
                PlanNode.NodeCase.TABLE_SCAN -> true
                PlanNode.NodeCase.SCAN ->
                    plan.scan.getObject().schemaCode == SchemaCode.ER &&
                        !opaqueToPhysical(model, plan.scan.getObject(), depth)
                PlanNode.NodeCase.JOIN ->
                    reachesScan(model, plan.join.left, depth) || reachesScan(model, plan.join.right, depth)
                PlanNode.NodeCase.FILTER -> reachesScan(model, plan.filter.input, depth)
                PlanNode.NodeCase.PROJECT -> reachesScan(model, plan.project.input, depth)
                else -> false
            }

    /** Attribute name → the physical column it binds to; null when the entity's binding cannot be read. */
    private fun boundColumns(
        model: ModelHandle,
        entity: QualifiedName,
        depth: Int,
    ): Map<String, ColumnKey>? {
        if (depth > MAX_DEPTH) return null
        val renames = model.attributeColumnRenames(entity)
        val attributes = model.attributes(entity).map { it.name }
        return when (val mapping = model.entityMapping(entity)) {
            is EntityMapping.ToTable -> attributes.associateWith { key(mapping.table, renames[it] ?: it) }
            is EntityMapping.ToQuery -> {
                val body = runCatching { model.savedQueryBody(mapping.query).planNode }.getOrNull() ?: return null
                val lineage =
                    (ColumnLineage.of(body, model) ?: return null).associate { (name, origin) ->
                        name.lowercase(Locale.ROOT) to origin
                    }
                attributes
                    .mapNotNull { attribute ->
                        val origin =
                            lineage[(renames[attribute] ?: attribute).lowercase(Locale.ROOT)] ?: return@mapNotNull null
                        val column =
                            when (origin.source.schemaCode) {
                                SchemaCode.DB -> key(origin.source, origin.column)
                                SchemaCode.ER -> boundColumns(model, origin.source, depth + 1)?.get(origin.column)
                                else -> null
                            }
                        column?.let { attribute to it }
                    }.toMap()
            }
            null -> null
        }
    }

    private fun invert(bound: Map<String, ColumnKey>): Map<ColumnKey, List<String>> =
        bound.entries.groupBy({ it.value }, { it.key })

    /** For each FK column pair, the one attribute on each side bound to it; null if any column has none or several. */
    private fun match(
        fkFrom: List<QualifiedName>,
        fkTo: List<QualifiedName>,
        fromSide: Map<ColumnKey, List<String>>,
        toSide: Map<ColumnKey, List<String>>,
    ): List<Pair<String, String>>? =
        fkFrom.indices.map { i ->
            val a = fromSide[fkColumn(fkFrom[i])]?.singleOrNull() ?: return null
            val b = toSide[fkColumn(fkTo[i])]?.singleOrNull() ?: return null
            a to b
        }

    private fun key(
        table: QualifiedName,
        column: String,
    ): ColumnKey = ColumnKey("${table.namespace}.${table.name}".lowercase(Locale.ROOT), column.lowercase(Locale.ROOT))

    /** An FK column is spelled `(namespace, "<table>.<column>")`. */
    private fun fkColumn(column: QualifiedName): ColumnKey =
        ColumnKey(
            "${column.namespace}.${column.name.substringBeforeLast('.')}".lowercase(Locale.ROOT),
            column.name.substringAfterLast('.').lowercase(Locale.ROOT),
        )

    /** An attribute qname as the model spells one: `<entity>.<attribute>` in `name`. */
    fun attribute(
        entity: QualifiedName,
        name: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("attribute")
            .setName("${entity.name}.$name")
            .build()
}
