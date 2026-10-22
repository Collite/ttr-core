// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.Model

/**
 * A ttr-metadata [Model] as the translator's [ModelHandle] (CN-P3 S3.4, A-CN-46): the er tier (`er.entity.<e>`, its
 * attributes typed and keyed, its relations), the db tier (`db.<namespace>.<table>`, its columns and foreign keys) and the
 * `er2db` mappings between them — what an er rendering translates over (the goldens' oracle proves it against the
 * test-only `GoldenModelHandle`; the live suite unparses through it). No production handle over a `Model` existed in
 * tatrman (`IslandModelHandle` is db-only). Public since S3.8 (A-CN-55, the AM-31 pre-check): a caller of
 * `definitionRender` — AU's `ttrp-doc` / `ttrp build`, P5's door — translates the island it gets over this handle,
 * with no ttr-concept internals. An entity without an explicit mapping maps to the db table of its own name when there
 * is one (TTR-M's convention); a query-bound entity maps to its query, whose body this handle does not carry (saved
 * queries are the translator's UNFOLD stage — empty).
 */
class ErModelHandle(
    private val model: Model,
) : ModelHandle {
    private val db: DbSchema? = model.schemas["db"] as? DbSchema
    private val er: ErSchema? = model.schemas["er"] as? ErSchema
    private val namespace: String = db?.namespace ?: "dbo"

    private val tables: List<ModelTable> by lazy {
        db
            ?.tables
            ?.values
            ?.sortedBy { it.qname.name }
            ?.map { t ->
                ModelTable(
                    table(t.qname.name),
                    t.columns.map {
                        ModelColumn(
                            it.qname.name.substringAfterLast('.'),
                            surface(it.dataType),
                            it.nullable,
                        )
                    },
                    t.primaryKey,
                )
            }.orEmpty()
    }

    private val entities: List<ModelEntity> by lazy {
        er
            ?.entities
            ?.values
            ?.sortedBy { it.qname.name }
            ?.map { e ->
                ModelEntity(
                    entity(e.qname.name),
                    e.attributes.map {
                        ModelAttribute(
                            it.qname.name.substringAfterLast('.'),
                            surface(it.type),
                            it.nullable,
                            it.isKey,
                        )
                    },
                )
            }.orEmpty()
    }

    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> =
        if (schemaCode == SchemaCode.DB && namespace == this.namespace) tables.associateBy { it.qname } else emptyMap()

    override fun columns(tableQname: QualifiedName): List<ModelColumn> =
        tables
            .firstOrNull {
                it.qname == tableQname
            }?.columns
            .orEmpty()

    override fun foreignKeys(): List<ModelForeignKey> =
        db
            ?.foreignKeys
            ?.values
            ?.sortedBy { it.qname.name }
            ?.map { fk ->
                ModelForeignKey(fk.fromColumns.map { column(it.name) }, fk.toColumns.map { column(it.name) })
            }.orEmpty()

    override fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity> =
        if (schemaCode == SchemaCode.ER && namespace == "entity") entities.associateBy { it.qname } else emptyMap()

    override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
        entities.firstOrNull { it.qname == entityQname }?.attributes.orEmpty()

    override fun relations(): List<ModelRelation> =
        er
            ?.relations
            ?.values
            ?.sortedBy { it.qname.name }
            ?.map { r ->
                ModelRelation(
                    entity(r.fromEntity.name),
                    entity(r.toEntity.name),
                    r.joinPairs.map { p -> attribute(p.fromAttr.name) to attribute(p.toAttr.name) },
                )
            }.orEmpty()

    override fun entityMapping(entityQname: QualifiedName): EntityMapping? {
        val explicit =
            model.mappings.filterIsInstance<Er2DbEntityMapping>().firstOrNull {
                it.entity.name ==
                    entityQname.name
            }
        return when (val t = explicit?.target) {
            is MappingTarget.Table -> EntityMapping.ToTable(table(bare(t.qname.name)))
            is MappingTarget.View -> EntityMapping.ToTable(table(bare(t.qname.name)))
            is MappingTarget.SqlQuery ->
                EntityMapping.ToQuery(
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(SchemaCode.ER)
                        .setNamespace("query")
                        .setName(bare(t.qname.name))
                        .build(),
                )
            null -> tables.firstOrNull { it.qname.name == entityQname.name }?.let { EntityMapping.ToTable(it.qname) }
        }
    }

    override fun attributeColumnRenames(entityQname: QualifiedName): Map<String, String> =
        model.mappings
            .filterIsInstance<Er2DbAttributeMapping>()
            .filter { it.attribute.name.substringBeforeLast('.') == entityQname.name }
            .mapNotNull { m ->
                val column =
                    (m.target as? AttributeMappingTarget.Column)?.qname?.name?.substringAfterLast('.')
                        ?: return@mapNotNull null
                val attribute = m.attribute.name.substringAfterLast('.')
                if (column == attribute) null else attribute to column
            }.toMap()

    override fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

    override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
        error("ErModelHandle carries no saved query bodies ($queryQname)")

    override fun currentVersion(): String = model.version.value

    override fun namespaces(schemaCode: SchemaCode): Set<String> =
        when (schemaCode) {
            SchemaCode.DB -> if (tables.isEmpty()) emptySet() else setOf(namespace)
            SchemaCode.ER -> if (entities.isEmpty()) emptySet() else setOf("entity")
            else -> emptySet()
        }

    private companion object {
        /** A mapping target as the ref spells it (`table.order`, `view.v`) → the object's name. */
        fun bare(name: String): String = name.substringAfter("table.").substringAfter("view.").substringAfter("query.")

        fun surface(type: String): SurfaceType =
            when (type.lowercase().substringBefore('(')) {
                "int", "integer", "long", "bigint", "smallint", "tinyint" -> SurfaceType.INT
                "decimal", "float", "double", "number", "numeric", "money", "real" -> SurfaceType.FLOAT
                "bool", "boolean", "bit" -> SurfaceType.BOOL
                "date", "timestamp", "datetime", "datetime2", "timestamptz", "smalldatetime" -> SurfaceType.DATETIME
                else -> SurfaceType.TEXT
            }

        fun entity(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()

        /** An er attribute, `er.attribute.<entity>.<attribute>` on the translator's side. */
        fun attribute(qualified: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("attribute")
                .setName(qualified)
                .build()
    }

    private fun table(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.DB)
            .setNamespace(namespace)
            .setName(name)
            .build()

    /** A db column, `db.<namespace>.<table>.<column>`. */
    private fun column(qualified: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.DB)
            .setNamespace(namespace)
            .setName(qualified)
            .build()
}
