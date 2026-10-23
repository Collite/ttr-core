// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import org.tatrman.report.QueryModelLookup
import org.tatrman.report.QueryParameter
import org.tatrman.report.ReportTypes
import org.tatrman.report.ParamKind
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.report.ReportException
import org.tatrman.ttr.metadata.model.*
import org.tatrman.translator.framework.*
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.params.SqlParam
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName as WireName
import org.tatrman.plan.v1.SchemaCode as WireSchema
import org.tatrman.translate.v1.Language

/** Captures one non-strict metadata snapshot. Only compilation is used, never execution or a server. */
class SnapshotModelLookup(
    private val model: Model,
) : QueryModelLookup {
    private val handles = mutableMapOf<SchemaCode, SnapshotQueryHandle>()

    private fun handle(query: Query) =
        handles.getOrPut(query.qname.schemaCode) {
            SnapshotQueryHandle(model, query.qname.schemaCode)
        }

    override fun programExists(qname: String): Boolean = query(qname) != null

    override fun outputColumns(qname: String): List<String> =
        query(qname)?.let { Translator.outputColumnNames(handle(it).plan(it)) }
            ?: throw ReportException("program-missing", "Program '$qname' does not exist")

    override fun outputTypes(qname: String): Map<String, String> {
        val query = query(qname) ?: throw ReportException("program-missing", "Program '$qname' does not exist")
        val handle = handle(query)
        return PlanNodeDecoder.decode(handle.plan(query), TranslatorFramework(handle)).rowType.fieldList.associate {
            it.name to it.type.sqlTypeName.name
        }
    }

    override fun declaredParameters(qname: String): List<QueryParameter> =
        query(qname)?.parameters?.map { QueryParameter(it.name, it.type) }
            ?: throw ReportException("program-missing", "Program '$qname' does not exist")

    private fun query(qname: String): Query? {
        val candidates = model.queries.values.filter { it.qname.dotted() == qname }
        if (candidates.size >
            1
        ) {
            throw ReportException("program-ambiguous", "Program '$qname' is declared more than once")
        }
        return candidates.singleOrNull()
    }
}

/** Small snapshot → translator SPI edge, kept out of the dependency-free report format/fill module. */
private class SnapshotQueryHandle(
    private val model: Model,
    private val lane: SchemaCode,
) : ModelHandle {
    private val db = model.schemas.values.filterIsInstance<DbSchema>()
    private val er = model.schemas.values.filterIsInstance<ErSchema>()
    private val tables =
        db.flatMap { s ->
            s.tables.values.map { t ->
                ModelTable(
                    wire(t.qname),
                    t.columns.map {
                        ModelColumn(it.qname.name.substringAfterLast('.'), surface(it.dataType), it.nullable)
                    },
                    t.primaryKey,
                )
            } +
                s.views.values.map { v ->
                    ModelTable(
                        wire(v.qname),
                        v.columns.map {
                            ModelColumn(it.qname.name.substringAfterLast('.'), surface(it.dataType), it.nullable)
                        },
                    )
                }
        }
    private val entities =
        er.flatMap { s ->
            s.entities.values.map { e ->
                ModelEntity(
                    wire(e.qname),
                    e.attributes.map {
                        ModelAttribute(it.qname.name.substringAfterLast('.'), surface(it.type), it.nullable, it.isKey)
                    },
                )
            }
        }
    private val queries =
        model.queries.values
            .filter { it.qname.schemaCode == lane }
            .groupBy { obj(it.qname.name) }
    private val plans = mutableMapOf<QualifiedName, PlanNode>()
    private val compiling = mutableSetOf<QualifiedName>()

    fun plan(query: Query): PlanNode {
        plans[query.qname]?.let { return it }
        if (!compiling.add(
                query.qname,
            )
        ) {
            throw ReportException("program-invalid", "Cyclic saved query '${query.qname.dotted()}'")
        }
        try {
            val language =
                when (query.sourceLanguage.uppercase(java.util.Locale.ROOT)) {
                    "SQL" -> Language.SQL
                    "TRANSFORMATION_DSL", "TRANSDSL" -> Language.TRANSFORMATION_DSL
                    "DATAFRAME_DSL", "DFDSL" -> Language.DATAFRAME_DSL
                    else -> throw ReportException(
                        "program-invalid",
                        "Unsupported query language '${query.sourceLanguage}'",
                    )
                }
            val lane = if (query.qname.schemaCode == SchemaCode.ER) WireSchema.ER else WireSchema.DB
            val result =
                Translator(this).parseToRelNode(
                    query.sourceText,
                    language,
                    targetSchema = lane,
                    sourceSchema = lane,
                    parameters =
                        query.parameters.map {
                            SqlParam(
                                it.name,
                                surface(it.type).name.lowercase(java.util.Locale.ROOT),
                                null,
                            )
                        },
                    strictLane = true,
                )
            return when (result) {
                is ParseResult.Success -> result.plan.also { plans[query.qname] = it }
                is ParseResult.Failure -> throw ReportException(
                    "program-invalid",
                    "${query.qname.dotted()}: ${result.code}: ${result.message}",
                )
            }
        } finally {
            compiling.remove(query.qname)
        }
    }

    override fun tables(
        schemaCode: WireSchema,
        namespace: String,
    ) = tables
        .filter {
            it.qname.schemaCode == schemaCode &&
                it.qname.namespace == namespace
        }.associateBy { it.qname }

    override fun columns(tableQname: WireName) = tables.firstOrNull { it.qname == tableQname }?.columns.orEmpty()

    override fun entities(
        schemaCode: WireSchema,
        namespace: String,
    ) = entities
        .filter {
            it.qname.schemaCode ==
                schemaCode &&
                it.qname.namespace == namespace
        }.associateBy { it.qname }

    override fun attributes(entityQname: WireName) =
        entities.firstOrNull { it.qname == entityQname }?.attributes.orEmpty()

    override fun foreignKeys() =
        db
            .flatMap {
                it.foreignKeys.values
            }.map { ModelForeignKey(it.fromColumns.map(::wire), it.toColumns.map(::wire)) }

    override fun relations() =
        er.flatMap { it.relations.values }.map { r ->
            ModelRelation(
                wire(r.fromEntity),
                wire(r.toEntity),
                r.joinPairs.map {
                    wire(it.fromAttr) to
                        wire(it.toAttr)
                },
            )
        }

    override fun entityMapping(entityQname: WireName): EntityMapping? {
        val mapping =
            model.mappings.filterIsInstance<Er2DbEntityMapping>().firstOrNull { wire(it.entity) == entityQname }
                ?: return null
        return when (val target = mapping.target) {
            is MappingTarget.Table -> EntityMapping.ToTable(wire(target.qname))
            is MappingTarget.View -> EntityMapping.ToTable(wire(target.qname))
            is MappingTarget.SqlQuery -> EntityMapping.ToQuery(obj(target.qname.name))
        }
    }

    override fun attributeColumnRenames(entityQname: WireName): Map<String, String> =
        model.mappings
            .filterIsInstance<Er2DbAttributeMapping>()
            .mapNotNull { m ->
                val target = m.target as? AttributeMappingTarget.Column ?: return@mapNotNull null
                if (m.attribute.name.substringBeforeLast('.') ==
                    entityQname.name
                ) {
                    m.attribute.name.substringAfterLast('.') to target.qname.name.substringAfterLast('.')
                } else {
                    null
                }
            }.toMap()

    override fun savedQueries(
        schemaCode: WireSchema,
        namespace: String,
    ) = if (schemaCode == WireSchema.OBJ &&
        namespace == "query"
    ) {
        queries.keys.associateWith { ModelSavedQuery(it) }
    } else {
        emptyMap()
    }

    override fun savedQueryBody(queryQname: WireName): SavedQueryBody {
        val found = queries[queryQname].orEmpty()
        if (found.size !=
            1
        ) {
            throw ReportException("program-ambiguous", "Saved query '${queryQname.name}' must identify one program")
        }
        val q = found.single()
        return SavedQueryBody(
            plan(q),
            q.parameters.map {
                org.tatrman.translator.framework
                    .ParamSpec(it.name, surface(it.type))
            },
            emptyList(),
        )
    }

    override fun currentVersion() = model.version.toString()

    override fun namespaces(schemaCode: WireSchema): Set<String> =
        when (schemaCode) {
            WireSchema.DB -> tables.map { it.qname.namespace }.toSet()
            WireSchema.ER -> entities.map { it.qname.namespace }.toSet()
            WireSchema.OBJ -> if (queries.isEmpty()) emptySet() else setOf("query")
            else -> emptySet()
        }

    private fun wire(q: QualifiedName): WireName =
        WireName
            .newBuilder()
            .setSchemaCode(
                if (q.schemaCode ==
                    SchemaCode.ER
                ) {
                    WireSchema.ER
                } else {
                    WireSchema.DB
                },
            ).setNamespace(q.namespace)
            .setName(q.name)
            .build()

    private fun obj(name: String): WireName =
        WireName
            .newBuilder()
            .setSchemaCode(WireSchema.OBJ)
            .setNamespace("query")
            .setName(name)
            .build()

    private fun surface(type: String): SurfaceType =
        when (ReportTypes.kind(type)) {
            ParamKind.INT -> SurfaceType.INT
            ParamKind.FLOAT -> SurfaceType.FLOAT
            ParamKind.BOOL -> SurfaceType.BOOL
            ParamKind.DATETIME -> SurfaceType.DATETIME
            else -> SurfaceType.TEXT
        }
}
