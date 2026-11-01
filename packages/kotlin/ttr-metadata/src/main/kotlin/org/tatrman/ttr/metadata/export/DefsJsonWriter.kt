// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.parser.serialization.DefinitionProperties
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
        val values = DefinitionProperties.project(e.def) { parameter(it, e) }
        val order = DefsJsonWriter.propertyOrder.getValue(kindOf(e.def))
        return JsonObject(
            DefinitionProperties
                .serialize(
                    values.entries.filter { it.value != null }.map { it.key to it.value },
                    order,
                ) { key, v ->
                    value(v, e, referenceModel(e, key))
                }.toMap(),
        )
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
            is ConceptSugar -> value(DefinitionProperties.concept(v.body), e.copy(model = "cn"))
            is ConceptParticipant -> value(mapOf("ref" to v.ref, "label" to v.label), e, defaultModel)
            is ConceptBody -> value(DefinitionProperties.concept(v), e, defaultModel)
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
            is AutoBody -> value(DefinitionProperties.auto(v), e, defaultModel)
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
