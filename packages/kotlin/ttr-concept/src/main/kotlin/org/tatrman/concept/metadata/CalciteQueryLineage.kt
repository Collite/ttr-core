// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.metadata

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.framework.*
import org.tatrman.ttr.metadata.source.QueryLineage
import org.tatrman.ttr.metadata.source.QueryLineageContext
import java.util.WeakHashMap

/** Optional grammar-bundle provider: the translator's parser, types, conformance and validation rewrites. */
private typealias QueryOrigins = Map<String, Set<Pair<String, String>>?>

class CalciteQueryLineage : QueryLineage {
    // A provider belongs to one derivation; weak context keys also keep direct SPI clients bounded.
    private val plans = WeakHashMap<QueryLineageContext, MutableMap<String, QueryOrigins?>>()

    override fun originsOf(
        context: QueryLineageContext,
        query: String,
        column: String,
        ordinal: Int,
    ): Set<String>? {
        CalciteCharset.ensureUtf8()
        val queries = plans.getOrPut(context) { mutableMapOf() }
        if (query !in queries) queries[query] = runCatching { plan(context, query) }.getOrNull()
        val origins = queries[query]?.get(column.lowercase()) ?: return null
        val out = sortedSetOf<String>()
        for ((entity, attr) in origins) out += context.attributeOrigins(entity, attr, this) ?: return null
        return out
    }

    private fun plan(
        context: QueryLineageContext,
        query: String,
    ): Map<String, Set<Pair<String, String>>?>? {
        val sql = context.queryText(query) ?: return null
        val byName =
            context.physical.entities.filter { (key, entry) ->
                context.entity(query, entry.second.name) == key
            }

        fun qname(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()
        val entities =
            byName.values.associate { (_, def) ->
                val qn = qname(def.name)
                qn to
                    ModelEntity(
                        qn,
                        def.attributes.map { attr ->
                            ModelAttribute(
                                attr.name,
                                when (attr.type?.name?.lowercase()) {
                                    "int", "integer", "bigint" -> SurfaceType.INT
                                    "decimal", "float", "double" -> SurfaceType.FLOAT
                                    "date", "datetime", "timestamp" -> SurfaceType.DATETIME
                                    "bool", "boolean" -> SurfaceType.BOOL
                                    else -> SurfaceType.TEXT
                                },
                                true,
                                attr.isKey,
                            )
                        },
                    )
            }
        val model =
            object : ModelHandle {
                override fun tables(
                    schemaCode: SchemaCode,
                    namespace: String,
                ) = emptyMap<QualifiedName, ModelTable>()

                override fun columns(tableQname: QualifiedName) = emptyList<ModelColumn>()

                override fun foreignKeys() = emptyList<ModelForeignKey>()

                override fun entities(
                    schemaCode: SchemaCode,
                    namespace: String,
                ) = if (schemaCode == SchemaCode.ER &&
                    namespace == "entity"
                ) {
                    entities
                } else {
                    emptyMap()
                }

                override fun attributes(entityQname: QualifiedName) = entities[entityQname]?.attributes.orEmpty()

                override fun relations() = emptyList<ModelRelation>()

                override fun entityMapping(entityQname: QualifiedName): EntityMapping? = null

                override fun savedQueries(
                    schemaCode: SchemaCode,
                    namespace: String,
                ) = emptyMap<QualifiedName, ModelSavedQuery>()

                override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
                    error("Lineage uses entity scans")

                override fun currentVersion() = "lineage"

                override fun namespaces(schemaCode: SchemaCode) =
                    if (schemaCode ==
                        SchemaCode.ER
                    ) {
                        setOf("entity")
                    } else {
                        emptySet()
                    }
            }
        val framework = TranslatorFramework(model, SchemaCode.ER, "entity")
        framework.newPlanner().use { planner ->
            val result =
                SqlValidator.validateAndConvert(planner, sql.trim().removeSuffix(";")) as? ValidateResult.Success
                    ?: return null
            val rel = result.rel
            val output = linkedMapOf<String, Set<Pair<String, String>>?>()
            for ((ordinal, field) in rel.rowType.fieldList.withIndex()) {
                val origins = rel.cluster.metadataQuery.getColumnOrigins(rel, ordinal)
                val entries =
                    origins
                        ?.takeIf { set -> set.none { it.isDerived } }
                        ?.map { origin ->
                            val entity =
                                byName.entries
                                    .singleOrNull {
                                        it.value.second.name.equals(
                                            origin.originTable.qualifiedName.last(),
                                            true,
                                        )
                                    }?.key
                                    ?: return null
                            entity to
                                origin.originTable.rowType.fieldList[origin.originColumnOrdinal]
                                    .name
                        }?.toSet()
                val label = field.name.lowercase()
                // Duplicate output names are ambiguous, never guessed.
                output[label] = if (label in output) null else entries
            }
            return output
        }
    }
}
