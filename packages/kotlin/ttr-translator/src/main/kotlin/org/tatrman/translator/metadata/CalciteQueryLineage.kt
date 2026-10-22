// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.metadata

import org.apache.calcite.config.Lex
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.Frameworks
import org.tatrman.ttr.metadata.source.QueryLineage
import org.tatrman.ttr.metadata.source.QueryLineageContext

/**
 * Optional lineage provider. Calcite resolves SELECT/joins/set operations; each ER origin is recursively expanded
 * through the snapshot's entity/query bindings to physical columns, with the metadata context's cycle guard.
 */
class CalciteQueryLineage : QueryLineage {
    override fun originsOf(
        context: QueryLineageContext,
        query: String,
        column: String,
        ordinal: Int,
    ): Set<String>? = runCatching { origins(context, query, ordinal) }.getOrNull()

    private fun origins(
        context: QueryLineageContext,
        query: String,
        ordinal: Int,
    ): Set<String>? {
        val sql = context.queryText(query) ?: return null
        val root = Frameworks.createRootSchema(true)
        val er =
            root.add(
                "er",
                org.apache.calcite.schema.impl
                    .AbstractSchema(),
            )
        val entitySchema =
            er.add(
                "entity",
                org.apache.calcite.schema.impl
                    .AbstractSchema(),
            )
        val byName = linkedMapOf<String, String>()
        for ((key, entry) in context.physical.entities) {
            val name = entry.second.name
            if (context.entity(query, name) != key) continue
            byName[name] = key
            entitySchema.add(
                name,
                object : AbstractTable() {
                    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
                        val builder = typeFactory.builder()
                        for (attr in entry.second.attributes) builder.add(attr.name, SqlTypeName.ANY).nullable(true)
                        return builder.build()
                    }
                },
            )
        }
        val config =
            Frameworks
                .newConfigBuilder()
                .defaultSchema(entitySchema)
                .parserConfig(SqlParser.config().withLex(Lex.MYSQL_ANSI))
                .build()
        Frameworks.getPlanner(config).use { planner ->
            val rel = planner.rel(planner.validate(planner.parse(sql))).project()
            if (ordinal !in rel.rowType.fieldList.indices) return null
            val origins = rel.cluster.metadataQuery.getColumnOrigins(rel, ordinal) ?: return null
            if (origins.any { it.isDerived }) return null
            val out = sortedSetOf<String>()
            for (origin in origins) {
                val entity = byName[origin.originTable.qualifiedName.last()] ?: return null
                val attr =
                    origin.originTable.rowType.fieldList[origin.originColumnOrdinal]
                        .name
                out += context.attributeOrigins(entity, attr, this) ?: return null
            }
            return out
        }
    }
}
