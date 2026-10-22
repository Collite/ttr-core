// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.SchemaCode as MetadataSchemaCode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model

/**
 * CN-P3 S3.7 — the golden ORACLE's translator model: a world's er tier as an [InMemoryModelHandle] (the S0.3 spike's
 * shape — each entity on the `db.dbo` table of its own name, attribute = column), so an expected SQL text translates
 * the way the er renderer's consumers will translate a rendering. Test-only and deliberately independent of the code
 * under test; S3.4 builds the production `ErModelHandle` and proves it against this one.
 */
internal object GoldenModelHandle {
    fun of(model: Model): InMemoryModelHandle {
        val er = model.schemas["er"] as? ErSchema ?: return InMemoryModelHandle(emptyList())
        val entities =
            er.entities.values.sortedBy { it.qname.name }.map { e ->
                ModelEntity(
                    entity(e.qname.name),
                    e.attributes.map { a ->
                        ModelAttribute(a.qname.name.substringAfterLast('.'), surface(a.type), a.nullable, a.isKey)
                    },
                )
            }
        val relations =
            er.relations.values.sortedBy { it.qname.name }.map { r ->
                ModelRelation(
                    entity(r.fromEntity.name),
                    entity(r.toEntity.name),
                    r.joinPairs.map { p ->
                        attribute(r.fromEntity.name, p.fromAttr.name.substringAfterLast('.')) to
                            attribute(r.toEntity.name, p.toAttr.name.substringAfterLast('.'))
                    },
                )
            }
        val flat =
            InMemoryModelHandle(
                tables =
                    entities.map { e ->
                        ModelTable(
                            table(e.qname.name),
                            e.attributes.map { ModelColumn(it.name, it.surfaceType, it.nullable) },
                        )
                    },
                entities = entities,
                relations = relations,
                entityMappings = entities.associate { it.qname to EntityMapping.ToTable(table(it.qname.name)) },
            )
        // The independent oracle compiles stored ER bodies over the flat source handle. The production handle
        // compiles lazily over its actual mappings; both must expand Sales to the same ER plan.
        val bodies =
            model.queries.values
                .filter { it.qname.schemaCode == MetadataSchemaCode.ER }
                .associate { q ->
                    val key =
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(
                                SchemaCode.ER,
                            ).setNamespace("query")
                            .setName(q.qname.name)
                            .build()
                    val result =
                        Translator(flat).parseToRelNode(
                            q.sourceText,
                            Language.SQL,
                            targetSchema = SchemaCode.ER,
                            sourceSchema = SchemaCode.ER,
                        )
                    val plan = (result as ParseResult.Success).plan
                    key to
                        SavedQueryBody(plan, emptyList(), emptyList())
                }
        if (bodies.isEmpty()) return flat
        val mapped =
            model.mappings
                .filterIsInstance<Er2DbEntityMapping>()
                .mapNotNull { mapping ->
                    val query =
                        mapping.target as? MappingTarget.SqlQuery
                            ?: return@mapNotNull null
                    entity(mapping.entity.name) to
                        EntityMapping.ToQuery(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(SchemaCode.ER)
                                .setNamespace("query")
                                .setName(query.qname.name.substringAfter("query."))
                                .build(),
                        )
                }.toMap()
        return InMemoryModelHandle(
            tables =
                entities.map { e ->
                    ModelTable(
                        table(e.qname.name),
                        e.attributes.map { ModelColumn(it.name, it.surfaceType, it.nullable) },
                    )
                },
            entities = entities,
            relations = relations,
            entityMappings =
                entities.associate {
                    it.qname to
                        (mapped[it.qname] ?: EntityMapping.ToTable(table(it.qname.name)))
                },
            savedQueryBodies = bodies,
        )
    }

    private fun surface(type: String): SurfaceType =
        when (type.lowercase()) {
            "int", "integer", "long", "bigint" -> SurfaceType.INT
            "decimal", "float", "double", "number" -> SurfaceType.FLOAT
            "bool", "boolean" -> SurfaceType.BOOL
            "date", "timestamp", "datetime" -> SurfaceType.DATETIME
            else -> SurfaceType.TEXT
        }

    private fun entity(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("entity")
            .setName(name)
            .build()

    private fun attribute(
        entity: String,
        attribute: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("attribute")
            .setName("$entity.$attribute")
            .build()

    private fun table(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.DB)
            .setNamespace("dbo")
            .setName(name)
            .build()
}
