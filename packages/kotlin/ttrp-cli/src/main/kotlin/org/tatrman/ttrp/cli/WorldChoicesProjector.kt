// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import org.tatrman.ttr.metadata.model.*
import org.tatrman.ttrp.emit.spi.*
import org.tatrman.translator.framework.*
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.params.SqlParam
import org.tatrman.translator.wire.PlanNodeDecoder
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName as WireName
import org.tatrman.plan.v1.SchemaCode as WireSchema
import org.tatrman.translate.v1.Language

/** Host-only compiler edge over one metadata snapshot. No execution or server access. */
internal object WorldChoicesProjector {
    fun project(
        model: Model,
        world: World,
        parameters: (AutomationProgram) -> List<ParameterMeta>,
    ): WorldChoices {
        val objects = model.objectByQname().values
        val programs =
            world.deployments.map { it.program }.distinct().sorted().map { name ->
                val program =
                    requireNotNull(
                        objects.filterIsInstance<AutomationProgram>().singleOrNull {
                            it.qname.name ==
                                name
                        },
                    ) {
                        "TTRP-EXPORT-REF: unknown or ambiguous world program $name"
                    }
                val params =
                    try {
                        parameters(program)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("TTRP-EXPORT-REF: cannot resolve program $name: ${e.message}", e)
                    }
                ProgramChoice(
                    name,
                    requireNotNull(program.recipe) { "TTRP-EXPORT-REF: program $name has no recipe" },
                    program.port,
                    params.also { declared ->
                        declared.forEach { ExportTypes.family(it.type) }
                    },
                )
            }
        val handles = mutableMapOf<SchemaCode, SnapshotQueryHandle>()
        val omitted = mutableListOf<OmittedQuery>()
        val queries =
            model.queries.values.sortedBy { it.qname.dotted() }.mapNotNull { query ->
                try {
                    val handle =
                        handles.getOrPut(
                            query.qname.schemaCode,
                        ) { SnapshotQueryHandle(model, query.qname.schemaCode) }
                    val rel = PlanNodeDecoder.decode(handle.plan(query), TranslatorFramework(handle))
                    val columns =
                        rel.rowType.fieldList.map {
                            ColumnMeta(
                                it.name,
                                ExportTypes.family(it.type.sqlTypeName.name),
                            )
                        }
                    require(
                        columns.isNotEmpty() &&
                            columns.map { it.name }.distinct().size == columns.size &&
                            columns.none { it.name == "row_count" },
                    ) {
                        "TTRP-EXPORT-COLUMNS: ${query.qname.dotted()} needs unique named columns, excluding reserved row_count"
                    }
                    QueryMeta(
                        query.qname.dotted(),
                        query.parameters.map {
                            ExportTypes.family(it.type)
                            ParameterMeta(it.name, it.type, it.defaultValue)
                        },
                        columns,
                    )
                } catch (e: Exception) {
                    val reason = "TTRP-EXPORT-QUERY: ${query.qname.dotted()}: ${e.message ?: e.javaClass.simpleName}"
                    omitted += OmittedQuery(query.qname.dotted(), reason)
                    null
                }
            }
        return WorldChoices(programs, queries, omitted)
    }
}

internal object ExportTypes {
    fun family(type: String): String =
        when (type.lowercase().substringBefore('(')) {
            "text", "string", "varchar", "char", "nvarchar", "nchar" -> "string"
            "decimal", "numeric", "float", "double", "real", "int", "integer",
            "bigint", "smallint", "tinyint", "days", "working_days",
            -> "number"
            "bool", "boolean", "bit" -> "boolean"
            "date" -> "date"
            "time" -> "time"
            "datetime", "datetime2", "timestamp", "timestamp_with_local_time_zone" -> "dateTime"
            else -> error("TTRP-EXPORT-TYPE: unsupported template/DMN type $type")
        }
}

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
            throw IllegalArgumentException("TTRP-EXPORT-REF: cyclic saved query '${query.qname.dotted()}'")
        }
        try {
            val language =
                when (query.sourceLanguage.uppercase(java.util.Locale.ROOT)) {
                    "SQL" -> Language.SQL
                    "TRANSFORMATION_DSL", "TRANSDSL" -> Language.TRANSFORMATION_DSL
                    "DATAFRAME_DSL", "DFDSL" -> Language.DATAFRAME_DSL
                    else -> throw IllegalArgumentException(
                        "TTRP-EXPORT-REF: unsupported query language '${query.sourceLanguage}'",
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
                is ParseResult.Failure -> throw IllegalArgumentException(
                    "${result.code}: ${result.message}",
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
            throw IllegalArgumentException(
                "TTRP-EXPORT-REF: saved query '${queryQname.name}' must identify one program",
            )
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
        when (ExportTypes.family(type)) {
            "number" ->
                if (type.lowercase() in
                    setOf("int", "integer", "bigint", "smallint")
                ) {
                    SurfaceType.INT
                } else {
                    SurfaceType.FLOAT
                }
            "boolean" -> SurfaceType.BOOL
            "date", "time", "dateTime" -> SurfaceType.DATETIME
            else -> SurfaceType.TEXT
        }
}
