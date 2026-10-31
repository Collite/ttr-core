// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.kindOf

/** Parsed-AST projection. Property-map keys precede retained free-form/structural extension keys. */
object DefsJsonWriter {
    internal val propertyOrder: Map<String, List<String>> =
        Json
            .parseToJsonElement(
                requireNotNull(
                    DefsJsonWriter::class.java.getResourceAsStream("/export/property-map.json"),
                ).bufferedReader().use {
                    it.readText()
                },
            ).jsonObject
            .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }

    fun write(
        set: DefinitionSet,
        header: ExportHeader = ExportHeader.from(set),
        context: DefinitionSet = set,
    ): ByteArray =
        jsonBytes(
            buildJsonObject {
                put("defs", "v1")
                putHeader(header)
                put(
                    "connections",
                    JsonArray(
                        header.connections.sortedWith(compareBy(bytewise) { it.id }).map { c ->
                            buildJsonObject {
                                put("id", c.id)
                                put("database", jsonValue(c.database))
                                put("schema", jsonValue(c.schema))
                                put("locators", jsonValue(c.locators))
                            }
                        },
                    ),
                )
                require(
                    set.entries
                        .map { it.qname }
                        .distinct()
                        .size == set.entries.size,
                ) { "Duplicate definition ids" }
                val ast = AstJson(context)
                put(
                    "definitions",
                    buildJsonObject {
                        for (entry in set.entries.sortedWith(
                            compareBy<DefinedObject, String>(bytewise) {
                                it.qname
                            }.thenBy(bytewise) { it.kind },
                        )) {
                            put(entry.qname, ast.entry(entry))
                        }
                    },
                )
            },
        )
}

private class AstJson(
    private val set: DefinitionSet,
) {
    private val resolver = DefinitionResolver(set.entries)

    fun entry(
        e: DefinedObject,
        nested: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            if (nested) put("id", e.qname)
            put("kind", e.kind)
            put("model", e.model)
            put("pkg", e.pkg)
            e.locale?.let { put("locale", it) }
            put("properties", properties(e))
        }

    private fun properties(e: DefinedObject): JsonObject {
        val d = e.def
        val raw: Map<String, Any?> =
            when (d) {
                is ProjectDef -> mapOf<String, Any?>("version" to d.version)
                is TableDef ->
                    mapOf<String, Any?>(
                        "primaryKey" to d.primaryKey,
                        "columns" to d.columns,
                        "indices" to d.indices,
                        "constraints" to d.constraints,
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                        "management" to d.management,
                        "changeSemantics" to d.changeSemantics,
                        "writebackReservation" to d.writeback,
                    )
                is ViewDef ->
                    mapOf<String, Any?>(
                        "columns" to d.columns,
                        "definitionSql" to sqlBlock(d.definitionSqlBlock, d.definitionSql),
                        "search" to d.search,
                    )
                is ColumnDef ->
                    mapOf<String, Any?>(
                        "type" to d.type,
                        "optional" to d.optional,
                        "isKey" to d.isKey,
                        "indexed" to d.indexed,
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                    )
                is IndexDef -> mapOf<String, Any?>("indexType" to d.indexType, "columnNames" to d.columns)
                is ConstraintDef ->
                    mapOf<String, Any?>(
                        "constraintType" to d.constraintType,
                        "columnNames" to d.columns,
                    )
                is FkDef -> mapOf<String, Any?>("from" to d.from, "to" to d.to)
                is ProcedureDef ->
                    mapOf<String, Any?>(
                        "parameters" to d.parameters.map { parameter(it, e) },
                        "resultColumns" to d.resultColumns,
                    )
                is EntityDef ->
                    mapOf<String, Any?>(
                        "labelPlural" to d.labelPlural,
                        "nameAttribute" to d.nameAttribute,
                        "codeAttribute" to d.codeAttribute,
                        "aliases" to d.aliases,
                        "attributes" to d.attributes,
                        "roles" to d.roles,
                        "displayLabel" to d.displayLabel,
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                        "binding" to d.binding,
                        "concept" to d.concept,
                    )
                is AttributeDef ->
                    mapOf<String, Any?>(
                        "type" to d.type,
                        "isKey" to d.isKey,
                        "optional" to d.optional,
                        "displayLabel" to d.displayLabel,
                        "valueLabels" to
                            d.valueLabels.mapValues { (key, label) ->
                                if (key in
                                    d.valueLabelAliases
                                ) {
                                    mapOf("label" to label, "aliases" to d.valueLabelAliases.getValue(key))
                                } else {
                                    label
                                }
                            },
                        "search" to d.search,
                        "semantics" to d.semantics,
                        "lexicon" to d.lexicon,
                        "binding" to d.binding,
                        "domainRef" to d.domainRef,
                        "aggregation" to d.aggregation,
                        "states" to d.states,
                        "transitions" to d.transitions,
                        "concept" to d.concept,
                    )
                is LexiconEntryDef ->
                    mapOf<String, Any?>(
                        "for" to d.target,
                        "forms" to d.forms,
                        "match" to d.match,
                        "text" to d.text,
                        "default" to d.default,
                        "sense_priority" to d.sensePriority,
                        "indexSecurity" to d.indexSecurity,
                    )
                is RelationDef ->
                    mapOf<String, Any?>(
                        "from" to d.from,
                        "to" to d.to,
                        "cardinality" to d.cardinality,
                        "join" to d.join,
                        "search" to d.search,
                        "binding" to d.binding,
                    )
                is Er2DbEntityDef ->
                    mapOf<String, Any?>(
                        "entity" to d.entity,
                        "target" to d.target,
                        "whereFilter" to d.whereFilter,
                    )
                is Er2DbAttributeDef -> mapOf<String, Any?>("attribute" to d.attribute, "target" to d.target)
                is Er2DbRelationDef -> mapOf<String, Any?>("relation" to d.relation, "fk" to d.fk)
                is QueryDef ->
                    mapOf<String, Any?>(
                        "language" to d.language,
                        "parameters" to d.parameters.map { parameter(it, e) },
                        "sourceText" to sqlBlock(d.sourceTextBlock, d.sourceText),
                        "skipSecurity" to d.skipSecurity,
                        "search" to d.search,
                    )
                is RoleDef ->
                    mapOf<String, Any?>("label" to d.label, "search" to d.search) +
                        d.entries.associate { it.key to it.value }
                is Er2CncRoleDef -> mapOf<String, Any?>("entity" to d.entity, "role" to d.role)
                is DrillMapDef ->
                    mapOf<String, Any?>(
                        "from" to d.from,
                        "to" to d.to,
                        "args" to d.args,
                        "display" to d.display,
                        "override" to d.overrideAuto,
                    )
                is AreaDef -> mapOf<String, Any?>("areaPackages" to d.packages, "areaEntities" to d.entities)
                is SchemaDef -> mapOf<String, Any?>("columns" to d.columns)
                is MdDomainDef ->
                    mapOf<String, Any?>(
                        "type" to d.type,
                        "kind" to d.domainKind,
                        "publish" to d.publishMembers,
                        "restrict" to d.restrictMembers,
                        "concept" to d.concept,
                    )
                is DimensionDef ->
                    mapOf<String, Any?>(
                        "key" to d.key,
                        "attributes" to d.attributes,
                        "hierarchies" to d.hierarchies,
                        "concept" to d.concept,
                    )
                is MdMapDef ->
                    mapOf<String, Any?>(
                        "from" to d.from,
                        "to" to d.to,
                        "cardinality" to d.cardinality,
                        "calc" to d.calc,
                    )
                is HierarchyDef -> mapOf<String, Any?>("dimensionRef" to d.dimensionRef, "levels" to d.levels)
                is MeasureDef ->
                    mapOf<String, Any?>(
                        "domainRef" to d.domainRef,
                        "class" to d.measureClass,
                        "aggregation" to d.aggregation,
                        "validBy" to d.validBy,
                        "concept" to d.concept,
                    )
                is CubeletDef ->
                    mapOf<String, Any?>(
                        "grain" to d.grain,
                        "measures" to d.measures,
                        "concept" to d.concept,
                    )
                is Md2dbCubeletDef ->
                    mapOf<String, Any?>(
                        "cubeletRef" to d.cubeletRef,
                        "target" to d.table,
                        "shape" to d.shape,
                        "attributesMap" to d.attributes,
                        "measuresMap" to d.measures,
                        "journaling" to d.journaling,
                        "allocation" to d.allocation,
                    )
                is Md2dbDomainDef -> mapOf<String, Any?>("domainRef" to d.domainRef, "source" to d.columnSource)
                is Md2dbMapDef ->
                    mapOf<String, Any?>(
                        "mapRef" to d.mapRef,
                        "target" to d.table,
                        "columnsMap" to d.columns,
                    )
                is Md2erDimensionDef ->
                    mapOf<String, Any?>(
                        "dimensionRef" to d.dimensionRef,
                        "entity" to d.entity,
                        "attributesMap" to d.attributes,
                    )
                is Md2erCubeletDef ->
                    mapOf<String, Any?>(
                        "cubeletRef" to d.cubeletRef,
                        "target" to d.entity,
                        "attributesMap" to d.attributes,
                        "measuresMap" to d.measures,
                    )
                is WorldDef ->
                    mapOf<String, Any?>(
                        "extends" to d.extends,
                        "engines" to d.engines,
                        "executors" to d.executors,
                        "storages" to d.storages,
                        "identities" to d.identities,
                        "grants" to d.grants,
                        "deployments" to d.deployments,
                        "processes" to d.processes,
                    )
                is ConceptDef -> concept(d.body)
                is ConceptBindingDef -> concept(d.body)
                is AutomationDef -> auto(d.body)
            }
        val common =
            mapOf(
                "description" to (d.descriptionLocalized.takeIf { it.byLanguage.isNotEmpty() } ?: d.description),
                "tags" to d.tags,
            )
        val values = common + raw
        val order = DefsJsonWriter.propertyOrder.getValue(kindOf(d))
        return buildJsonObject {
            for (key in (order + (values.keys - order.toSet()).sortedWith(bytewise))) {
                val v = values[key] ?: continue
                put(key, value(v, e, referenceModel(e, key)))
            }
        }
    }

    /** Only parameter binds resolve against ER attributes; other entries retain their original layer. */
    private fun parameter(
        p: PropertyValue,
        e: DefinedObject,
    ): JsonElement =
        if (p is PropertyValue.ObjectValue) {
            buildJsonObject {
                p.entries.entries.sortedWith(compareBy(bytewise) { it.key }).forEach { (key, v) ->
                    put(key, if (key == "binds") bindingValue(v, e) else value(v, e))
                }
            }
        } else {
            value(p, e)
        }

    private fun bindingValue(
        v: PropertyValue,
        e: DefinedObject,
    ): JsonElement =
        when (v) {
            is PropertyValue.IdValue ->
                JsonPrimitive(
                    resolver.resolveKnown(v.ref.path, e, "er", "attribute") ?: v.ref.path,
                )
            is PropertyValue.ListValue -> JsonArray(v.items.map { bindingValue(it, e) })
            else -> value(v, e)
        }

    private fun referenceModel(
        e: DefinedObject,
        key: String,
    ): String =
        when (e.def) {
            is Er2DbEntityDef, is Er2DbAttributeDef, is Er2DbRelationDef ->
                if (key in setOf("entity", "attribute", "relation")) "er" else "db"
            is Md2dbCubeletDef, is Md2dbMapDef, is Md2dbDomainDef ->
                if (key in setOf("target", "source")) "db" else "md"
            is Md2erDimensionDef, is Md2erCubeletDef ->
                if (key in setOf("cubeletRef", "dimensionRef")) "md" else "er"
            is ConceptBindingDef ->
                if (key in
                    setOf("target", "period", "participants")
                ) {
                    e.kind.substringAfter('2').substringBefore('_')
                } else {
                    "cn"
                }
            else -> if (key == "binding") "db" else e.model
        }

    private fun concept(body: ConceptBody): Map<String, Any?> =
        body.properties.associate {
            it.key to
                when (it) {
                    is ConceptProperty.Description -> it.localized ?: it.value
                    is ConceptProperty.Label -> it.value
                    is ConceptProperty.Tags -> it.value
                    is ConceptProperty.Ref -> it.ref
                    is ConceptProperty.From -> it.ref
                    is ConceptProperty.Until -> it.ref
                    is ConceptProperty.Per -> it.ref
                    is ConceptProperty.Grounds -> it.ref
                    is ConceptProperty.ValueSpace -> it.ref
                    is ConceptProperty.Involves -> it.participants
                    is ConceptProperty.Period -> it.value
                    is ConceptProperty.Extent -> it.value
                    is ConceptProperty.Basis -> it.value
                    is ConceptProperty.DefinitionBlock -> it.block
                    is ConceptProperty.MembersBlock -> it.block
                    is ConceptProperty.FilterBlock -> it.block
                    is ConceptProperty.Lexicon -> it.block
                    is ConceptProperty.Target -> it.value
                    is ConceptProperty.Entry -> it.value
                }
        }

    private fun auto(body: AutoBody): Map<String, Any?> =
        mapOf(
            "description" to (body.descriptionLocalized.takeIf { it.byLanguage.isNotEmpty() } ?: body.description),
            "tags" to body.tags,
            "label" to body.label,
            "type" to body.type,
            "columns" to body.columns,
            "target" to body.target,
            "transition" to body.transition,
            "set" to body.set,
        ) +
            body.entries.associate { it.key to it.value }

    private fun sqlBlock(
        structured: PropertyValue?,
        text: String?,
    ): Any? =
        text?.let {
            mapOf(
                "block" to it,
                "tag" to (structured as? TaggedBlockValue)?.tag,
            )
        }

    private fun block(
        text: String,
        tag: String?,
    ): JsonElement =
        buildJsonObject {
            put("block", text)
            put("tag", jsonValue(tag))
        }

    private fun value(
        v: Any?,
        e: DefinedObject,
        defaultModel: String = e.model,
    ): JsonElement =
        when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String, is Number, is Boolean -> jsonValue(v)
            is Reference ->
                JsonPrimitive(
                    resolver.resolve(
                        v.path,
                        e,
                        v.parts.firstOrNull()?.takeIf { it in setOf("cn", "md", "er", "db") }
                            ?: defaultModel,
                    ),
                )
            is LocalizedStringValue -> jsonValue(v.byLanguage)
            is LocalizedStringListValue -> jsonValue(v.byLanguage)
            is EmbeddedBlock -> block(v.text, v.language)
            is TaggedBlockValue -> block(v.value, v.tag)
            is PropertyValue.StringValue -> JsonPrimitive(v.raw)
            is PropertyValue.TripleStringValue -> JsonPrimitive(v.raw)
            is PropertyValue.NumberValue -> JsonPrimitive(v.raw)
            is PropertyValue.BoolValue -> JsonPrimitive(v.raw)
            is PropertyValue.NullValue -> JsonNull
            is PropertyValue.IdValue -> JsonPrimitive(resolver.resolveKnown(v.ref.path, e, defaultModel) ?: v.ref.path)
            is PropertyValue.ListValue -> value(v.items, e, defaultModel)
            is PropertyValue.ObjectValue -> value(v.entries, e, defaultModel)
            is PropertyValue.FunctionCall -> value(mapOf("function" to v.name, "args" to v.args), e, defaultModel)
            is Definition ->
                entry(
                    set.entries.firstOrNull { it.def === v }
                        ?: e.copy(qname = "${e.qname}.${v.name}", def = v, kind = kindOf(v)),
                    true,
                )
            is DataType ->
                if (v.length == null && v.precision == null) {
                    JsonPrimitive(v.name)
                } else {
                    buildJsonObject {
                        put("type", v.name)
                        v.length?.let { put("length", it) }
                        v.precision?.let { put("precision", it) }
                    }
                }
            is ConceptSugar -> value(concept(v.body), e.copy(model = "cn"))
            is ConceptParticipant -> value(mapOf("ref" to v.ref, "label" to v.label), e, defaultModel)
            is ConceptBody -> value(concept(v), e, defaultModel)
            is TargetReferenceValue -> value(v.ref, e, defaultModel)
            is TargetObjectValue -> value(v.obj, e, defaultModel)
            is BindingPropertyBareId -> value(v.id, e, defaultModel)
            is BindingPropertyBlock ->
                value(
                    mapOf(
                        "target" to v.target,
                        "columns" to v.columns.associate { it.name to it.value },
                        "fk" to v.fk,
                    ),
                    e,
                    defaultModel,
                )
            is BindingColumnBareId -> value(v.id, e, defaultModel)
            is BindingColumnObject -> value(v.obj, e, defaultModel)
            is AggregationSpec -> value(mapOf("default" to v.default) + v.perDimension, e, defaultModel)
            is CubeletMeasure.Ref -> value(v.ref, e, defaultModel)
            is CubeletMeasure.Inline -> value(v.measure, e, defaultModel)
            is SemanticsValue.Str -> value(v.value, e, defaultModel)
            is SemanticsValue.Num -> value(v.value, e, defaultModel)
            is SemanticsValue.Bool -> value(v.value, e, defaultModel)
            is SemanticsValue.NullV -> JsonNull
            is SemanticsValue.ListV -> value(v.items, e, defaultModel)
            is SemanticsValue.ObjV -> value(v.entries, e, defaultModel)
            is ShapeSpec.Wide -> JsonPrimitive("wide")
            is ShapeSpec.Long ->
                value(
                    mapOf("kind" to "long", "codeColumn" to v.codeColumn, "valueColumn" to v.valueColumn),
                    e,
                    defaultModel,
                )
            is AttrColumnBinding.Column -> value(v.column, e, defaultModel)
            is AttrColumnBinding.Via -> value(mapOf("via" to v.via, "from" to v.from), e, defaultModel)
            is MeasureColumnBinding.Column -> value(mapOf("column" to v.column), e, defaultModel)
            is MeasureColumnBinding.Code -> value(mapOf("code" to v.code), e, defaultModel)
            is JournalingSpec.Overwrite -> value("overwrite", e, defaultModel)
            is JournalingSpec.Diff -> value("diff", e, defaultModel)
            is JournalingSpec.Invalidate -> value(mapOf("invalidate" to v.validColumn), e, defaultModel)
            is AllocationSpec.Uniform -> value(v.strategy, e, defaultModel)
            is AllocationSpec.PerDimension -> value(v.byDimension, e, defaultModel)
            is AutoValue.Str -> value(v.raw, e, defaultModel)
            is AutoValue.Num -> value(v.raw, e, defaultModel)
            is AutoValue.Bool -> value(v.raw, e, defaultModel)
            is AutoValue.Null -> JsonNull
            is AutoValue.Id -> value(v.ref, e, defaultModel)
            is AutoValue.Duration -> value(mapOf("amount" to v.amount, "unit" to v.unit), e, defaultModel)
            is AutoValue.Tagged -> value(mapOf("tag" to v.tag, "value" to v.value), e, defaultModel)
            is AutoValue.Call ->
                value(
                    mapOf("call" to v.name, "entries" to v.entries.associate { it.key to it.value }),
                    e,
                    defaultModel,
                )
            is AutoValue.Star -> value("*", e, defaultModel)
            is AutoValue.Obj -> value(v.entries.associate { it.key to it.value }, e, defaultModel)
            is AutoValue.ListV -> value(v.items, e, defaultModel)
            is AutoBody -> value(auto(v), e, defaultModel)
            is Map<*, *> ->
                buildJsonObject {
                    v.entries.sortedWith(compareBy(bytewise) { it.key.toString() }).forEach { (k, x) ->
                        put(k.toString(), value(x, e, defaultModel))
                    }
                }
            is Iterable<*> -> JsonArray(v.map { value(it, e, defaultModel) })
            is ChangeSemanticsDecl -> value(mapOf("mode" to v.mode, "roles" to v.roles), e, defaultModel)
            is WritebackReservation -> value(mapOf("entries" to v.entries), e, defaultModel)
            is AttributeTransition -> value(mapOf("from" to v.from, "to" to v.to), e, defaultModel)
            is LexiconBlock ->
                value(
                    mapOf(
                        "terms" to v.terms,
                        "patterns" to v.patterns,
                        "examples" to v.examples,
                        "default" to v.default,
                        "sensePriority" to v.sensePriority,
                    ),
                    e,
                    defaultModel,
                )
            is CalcRef -> value(mapOf("name" to v.name, "args" to v.args), e, defaultModel)
            is HierarchyLevel -> value(mapOf("attribute" to v.attribute, "via" to v.via), e, defaultModel)
            is ColumnSource -> value(mapOf("table" to v.table, "column" to v.column), e, defaultModel)
            is EngineDef ->
                value(
                    mapOf(
                        "name" to v.name,
                        "description" to (
                            v.descriptionLocalized.takeIf {
                                it.byLanguage.isNotEmpty()
                            } ?: v.description
                        ),
                        "tags" to v.tags,
                        "type" to v.type,
                        "version" to v.version,
                        "extends" to v.extends,
                        "manifest" to v.manifest,
                    ),
                    e,
                    defaultModel,
                )
            is ExecutorDef ->
                value(
                    mapOf(
                        "name" to v.name,
                        "description" to (
                            v.descriptionLocalized.takeIf {
                                it.byLanguage.isNotEmpty()
                            } ?: v.description
                        ),
                        "tags" to v.tags,
                        "type" to v.type,
                        "version" to v.version,
                        "extends" to v.extends,
                        "manifest" to v.manifest,
                    ),
                    e,
                    defaultModel,
                )
            is StorageDef ->
                value(
                    mapOf(
                        "name" to v.name,
                        "description" to (
                            v.descriptionLocalized.takeIf {
                                it.byLanguage.isNotEmpty()
                            } ?: v.description
                        ),
                        "tags" to v.tags,
                        "type" to v.type,
                        "extends" to v.extends,
                        "via" to v.via,
                        "hosts" to v.hosts,
                        "staging" to v.staging,
                        "schemas" to v.schemas,
                        "manifest" to v.manifest,
                    ),
                    e,
                    defaultModel,
                )
            is WorldSchemaDef -> value(mapOf("name" to v.name, "fields" to v.fields), e, defaultModel)
            is WorldSchemaField -> value(mapOf("name" to v.name, "type" to v.type), e, defaultModel)
            is AutoTransition ->
                value(
                    mapOf(
                        "attribute" to v.attribute,
                        "to" to v.to,
                        "toColumn" to v.toColumn,
                        "from" to v.from,
                    ),
                    e,
                    defaultModel,
                )
            is AutoEntry -> value(mapOf("key" to v.key, "value" to v.value), e, defaultModel)
            is AutoSet -> value(mapOf("attribute" to v.attribute, "column" to v.column), e, defaultModel)
            is WorldAutoMember -> value(mapOf("kind" to v.kind, "name" to v.name, "body" to v.body), e, defaultModel)
            is ProcessDef ->
                value(
                    mapOf(
                        "name" to v.name,
                        "description" to (
                            v.descriptionLocalized.takeIf {
                                it.byLanguage.isNotEmpty()
                            } ?: v.description
                        ),
                        "tags" to v.tags,
                        "steps" to v.steps,
                        "entries" to v.entries,
                    ),
                    e,
                    defaultModel,
                )
            is SearchHintsValue ->
                value(
                    mapOf(
                        "keywords" to v.keywords,
                        "patterns" to v.patterns,
                        "descriptions" to v.descriptions,
                        "examples" to v.examples,
                        "aliases" to v.aliases,
                        "searchable" to v.searchable,
                        "fuzzy" to v.fuzzy.takeIf { v.fuzzyAuthored },
                        "method" to v.method,
                    ).filterValues { it != null },
                    e,
                    defaultModel,
                )
            is MatchMethodValue -> value(mapOf("name" to v.name, "argument" to v.argument), e, defaultModel)
            is SemanticsBlock -> value(mapOf("entries" to v.entries), e, defaultModel)
            is AttrColumnBinding.FromColumn -> value(mapOf("table" to v.table, "column" to v.column), e, defaultModel)
            else -> error("Unsupported AST value: ${v::class.simpleName}")
        }
}
