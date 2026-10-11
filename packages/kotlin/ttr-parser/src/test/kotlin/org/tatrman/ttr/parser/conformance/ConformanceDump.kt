// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.model.AreaDef
import org.tatrman.ttr.parser.model.CubeletDef
import org.tatrman.ttr.parser.model.SecurityBlock
import org.tatrman.ttr.parser.model.SecurityStatement
import org.tatrman.ttr.parser.model.Md2dbCubeletDef
import org.tatrman.ttr.parser.model.Md2dbDomainDef
import org.tatrman.ttr.parser.model.Md2dbMapDef
import org.tatrman.ttr.parser.model.Md2erCubeletDef
import org.tatrman.ttr.parser.model.DimensionDef
import org.tatrman.ttr.parser.model.HierarchyDef
import org.tatrman.ttr.parser.model.MdDomainDef
import org.tatrman.ttr.parser.model.MdMapDef
import org.tatrman.ttr.parser.model.MeasureDef
import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.BindingColumnBareId
import org.tatrman.ttr.parser.model.BindingColumnEntry
import org.tatrman.ttr.parser.model.BindingColumnObject
import org.tatrman.ttr.parser.model.BindingProperty
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.ColumnDef
import org.tatrman.ttr.parser.model.ConstraintDef
import org.tatrman.ttr.parser.model.DataType
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.DrillMapDef
import org.tatrman.ttr.parser.model.EngineDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.ExecutorDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.IndexDef
import org.tatrman.ttr.parser.model.LexiconBlock
import org.tatrman.ttr.parser.model.LexiconEntryDef
import org.tatrman.ttr.parser.model.LocalizedStringListValue
import org.tatrman.ttr.parser.model.LocalizedStringValue
import org.tatrman.ttr.parser.model.ProjectDef
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.SchemaDef
import org.tatrman.ttr.parser.model.SearchHintsValue
import org.tatrman.ttr.parser.model.SemanticsBlock
import org.tatrman.ttr.parser.model.SemanticsValue
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.TaggedBlockValue
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.StorageDef
import org.tatrman.ttr.parser.model.TargetValue
import org.tatrman.ttr.parser.model.ViewDef
import org.tatrman.ttr.parser.model.WorldDef
import org.tatrman.ttr.parser.model.WorldSchemaDef

/**
 * Emits the normalised conformance JSON (contracts.md §5) so the TS and Kotlin
 * parsers can be diffed structurally. The output is naming-agnostic: `kind` is
 * the TTR keyword, property names are TTR surface names (per AST-NAMING.md), and
 * SourceLocation is dropped everywhere.
 *
 * The format is byte-identical to the TS dumper's `JSON.stringify(obj, null, 4)`:
 *   - object keys sorted alphabetically (recursively),
 *   - 4-space indent, `": "` separators, empty `[]` / `{}`,
 *   - whole numbers as integers (no trailing `.0`),
 *   - present-only properties (false booleans, empty lists/objects omitted) so a
 *     TS-absent field and a Kotlin-default field both disappear.
 */
object ConformanceDump {
    fun dump(result: ParseResult): String = printCanonical(dumpTree(result))

    fun dumpTree(result: ParseResult): JsonObject {
        val top =
            linkedMapOf<String, JsonElement>(
                "schemaDirective" to
                    (
                        result.modelDirective?.let {
                            val m =
                                linkedMapOf<String, JsonElement>(
                                    "code" to JsonPrimitive(it.modelCode),
                                    "namespace" to (it.schema?.let { n -> JsonPrimitive(n) } ?: JsonNull),
                                )
                            it.locale?.let { l -> m["locale"] = JsonPrimitive(l) }
                            obj(m)
                        } ?: JsonNull
                    ),
                "package" to (result.packageName?.let { JsonPrimitive(it) } ?: JsonNull),
                "imports" to
                    JsonArray(
                        result.imports.map {
                            obj(
                                "target" to JsonPrimitive(it.target),
                                "wildcard" to JsonPrimitive(it.wildcard),
                            )
                        },
                    ),
                "definitions" to JsonArray(result.definitions.map { defTree(it) }),
            )
        // PL-P4.S3 — only present when the document declares `security { … }`
        // blocks (the `locale`/`duplicateProperties` conditional-key precedent),
        // so no existing fixture baseline churns.
        if (result.securityBlocks.isNotEmpty()) {
            top["securityBlocks"] = JsonArray(result.securityBlocks.map { securityBlockTree(it) })
        }
        return obj(top)
    }

    private fun securityBlockTree(b: SecurityBlock): JsonObject =
        obj("statements" to JsonArray(b.statements.map { securityStatementTree(it) }))

    private fun securityStatementTree(s: SecurityStatement): JsonObject =
        when (s) {
            is SecurityStatement.Own ->
                obj(
                    "verb" to JsonPrimitive("own"),
                    "objectRef" to JsonPrimitive(s.objectRef),
                    "owner" to JsonPrimitive(s.owner),
                )
            is SecurityStatement.Classify ->
                obj(
                    "verb" to JsonPrimitive("classify"),
                    "objectRef" to JsonPrimitive(s.objectRef),
                    "classification" to JsonPrimitive(s.classification),
                )
            is SecurityStatement.Grant ->
                obj(
                    "verb" to JsonPrimitive("grant"),
                    "privilege" to JsonPrimitive(s.privilege),
                    "objectRef" to JsonPrimitive(s.objectRef),
                    "grantee" to JsonPrimitive(s.grantee),
                )
            is SecurityStatement.Mask ->
                obj(
                    "verb" to JsonPrimitive("mask"),
                    "objectRef" to JsonPrimitive(s.objectRef),
                )
        }

    // ----- definition envelope -----

    private fun defTree(d: Definition): JsonObject {
        val fields =
            linkedMapOf<String, JsonElement>(
                "kind" to JsonPrimitive(kindKeyword(d)),
                "name" to JsonPrimitive(d.name),
                "description" to (d.description?.let { JsonPrimitive(it) } ?: JsonNull),
                "tags" to JsonArray(d.tags.map { JsonPrimitive(it) }),
                "properties" to obj(propsOf(d)),
            )
        // 0.13 (NLS-P10): the localised form of the SAME property, present-only (a
        // plain-string model dumps byte-identically to what 0.12 produced).
        localized(d.descriptionLocalized)?.let { fields["descriptionLocalized"] = it }
        return obj(fields)
    }

    private fun kindKeyword(d: Definition): String =
        when (d) {
            is ProjectDef -> "project"
            is TableDef -> "table"
            is ViewDef -> "view"
            is ColumnDef -> "column"
            is IndexDef -> "index"
            is ConstraintDef -> "constraint"
            is FkDef -> "fk"
            is ProcedureDef -> "procedure"
            is EntityDef -> "entity"
            is AttributeDef -> "attribute"
            is RelationDef -> "relation"
            is Er2DbEntityDef -> "er2db_entity"
            is Er2DbAttributeDef -> "er2db_attribute"
            is Er2DbRelationDef -> "er2db_relation"
            is QueryDef -> "query"
            is RoleDef -> "role"
            is Er2CncRoleDef -> "er2cnc_role"
            is DrillMapDef -> "drill_map"
            is AreaDef -> "area"
            is SchemaDef -> "schema"
            is MdDomainDef -> "domain"
            is DimensionDef -> "dimension"
            is MdMapDef -> "map"
            is HierarchyDef -> "hierarchy"
            is MeasureDef -> "measure"
            is CubeletDef -> "cubelet"
            is Md2dbCubeletDef -> "md2db_cubelet"
            is Md2dbDomainDef -> "md2db_domain"
            is Md2dbMapDef -> "md2db_map"
            is Md2erCubeletDef -> "md2er_cubelet"
            is WorldDef -> "world"
            is LexiconEntryDef -> d.entryKind
        }

    private fun propsOf(d: Definition): Map<String, JsonElement> {
        val p = linkedMapOf<String, JsonElement>()
        when (d) {
            is ProjectDef -> {
                d.version?.let { p["version"] = JsonPrimitive(it) }
            }
            is TableDef -> {
                if (d.primaryKey.isNotEmpty()) p["primaryKey"] = strList(d.primaryKey)
                if (d.columns.isNotEmpty()) p["columns"] = JsonArray(d.columns.map { defTree(it) })
                if (d.indices.isNotEmpty()) p["indices"] = JsonArray(d.indices.map { defTree(it) })
                if (d.constraints.isNotEmpty()) p["constraints"] = JsonArray(d.constraints.map { defTree(it) })
                searchHints(d.search)?.let { p["search"] = it }
                d.semantics?.let { p["semantics"] = semantics(it) }
                d.lexicon?.let { l -> lexicon(l)?.let { p["lexicon"] = it } }
            }
            is ViewDef -> {
                if (d.columns.isNotEmpty()) p["columns"] = JsonArray(d.columns.map { defTree(it) })
                d.definitionSqlBlock?.let { p["definitionSql"] = embeddedDump(it) }
                searchHints(d.search)?.let { p["search"] = it }
            }
            is ColumnDef -> {
                d.type?.let { p["type"] = dataType(it) }
                if (d.optional) p["optional"] = JsonPrimitive(true)
                if (d.isKey) p["isKey"] = JsonPrimitive(true)
                if (d.indexed) p["indexed"] = JsonPrimitive(true)
                searchHints(d.search)?.let { p["search"] = it }
                d.semantics?.let { p["semantics"] = semantics(it) }
                d.lexicon?.let { l -> lexicon(l)?.let { p["lexicon"] = it } }
            }
            is IndexDef -> {
                d.indexType?.let { p["indexType"] = JsonPrimitive(it) }
                if (d.columns.isNotEmpty()) p["columns"] = strList(d.columns)
            }
            is ConstraintDef -> {
                d.constraintType?.let { p["constraintType"] = JsonPrimitive(it) }
                if (d.columns.isNotEmpty()) p["columns"] = strList(d.columns)
            }
            is FkDef -> {
                d.from?.let { p["from"] = pv(it) }
                d.to?.let { p["to"] = pv(it) }
            }
            is ProcedureDef -> {
                if (d.parameters.isNotEmpty()) p["parameters"] = JsonArray(d.parameters.map { param(it) })
                if (d.resultColumns.isNotEmpty()) p["resultColumns"] = JsonArray(d.resultColumns.map { defTree(it) })
            }
            is EntityDef -> {
                d.labelPlural?.let { p["labelPlural"] = JsonPrimitive(it) }
                d.nameAttribute?.let { p["nameAttribute"] = JsonPrimitive(it.path) }
                d.codeAttribute?.let { p["codeAttribute"] = JsonPrimitive(it.path) }
                if (d.aliases.isNotEmpty()) p["aliases"] = strList(d.aliases)
                if (d.attributes.isNotEmpty()) p["attributes"] = JsonArray(d.attributes.map { defTree(it) })
                if (d.roles.isNotEmpty()) p["roles"] = strList(d.roles.map { it.path })
                d.displayLabel?.let { ls -> localized(ls)?.let { p["displayLabel"] = it } }
                searchHints(d.search)?.let { p["search"] = it }
                d.semantics?.let { p["semantics"] = semantics(it) }
                d.lexicon?.let { l -> lexicon(l)?.let { p["lexicon"] = it } }
                d.binding?.let { p["binding"] = binding(it) }
            }
            is AttributeDef -> {
                d.type?.let { p["type"] = dataType(it) }
                if (d.isKey) p["isKey"] = JsonPrimitive(true)
                if (d.optional) p["optional"] = JsonPrimitive(true)
                d.displayLabel?.let { ls -> localized(ls)?.let { p["displayLabel"] = it } }
                if (d.valueLabels.isNotEmpty()) p["valueLabels"] = valueLabels(d.valueLabels, d.valueLabelAliases)
                searchHints(d.search)?.let { p["search"] = it }
                d.semantics?.let { p["semantics"] = semantics(it) }
                d.lexicon?.let { l -> lexicon(l)?.let { p["lexicon"] = it } }
                d.binding?.let { p["binding"] = binding(it) }
            }
            is RelationDef -> {
                d.from?.let { p["from"] = pv(it) }
                d.to?.let { p["to"] = pv(it) }
                d.cardinality?.let { p["cardinality"] = pv(it) }
                if (d.join.isNotEmpty()) p["join"] = JsonArray(d.join.map { pv(it) })
                searchHints(d.search)?.let { p["search"] = it }
                d.binding?.let { p["binding"] = binding(it) }
            }
            is Er2DbEntityDef -> {
                d.entity?.let { p["entity"] = JsonPrimitive(it.path) }
                d.target?.let { p["target"] = target(it) }
                d.whereFilter?.let { p["whereFilter"] = pv(it) }
            }
            is Er2DbAttributeDef -> {
                d.attribute?.let { p["attribute"] = JsonPrimitive(it.path) }
                d.target?.let { p["target"] = target(it) }
            }
            is Er2DbRelationDef -> {
                d.relation?.let { p["relation"] = JsonPrimitive(it.path) }
                d.fk?.let { p["fk"] = JsonPrimitive(it.path) }
            }
            is QueryDef -> {
                d.language?.let { p["language"] = JsonPrimitive(it) }
                if (d.parameters.isNotEmpty()) p["parameters"] = JsonArray(d.parameters.map { param(it) })
                d.sourceTextBlock?.let { p["sourceText"] = embeddedDump(it) }
                searchHints(d.search)?.let { p["search"] = it }
            }
            is RoleDef -> {
                d.label?.let { ls -> localized(ls)?.let { p["label"] = it } }
                searchHints(d.search)?.let { p["search"] = it }
            }
            is Er2CncRoleDef -> {
                d.entity?.let { p["entity"] = JsonPrimitive(it.path) }
                d.role?.let { p["role"] = JsonPrimitive(it.path) }
            }
            is DrillMapDef -> {
                d.from?.let { p["from"] = JsonPrimitive(it.path) }
                d.to?.let { p["to"] = JsonPrimitive(it.path) }
                if (d.args.isNotEmpty()) {
                    p["args"] = obj(d.args.entries.associate { (k, v) -> k to JsonPrimitive(v) })
                }
                d.display?.let { ls -> localized(ls)?.let { p["display"] = it } }
                if (d.overrideAuto) p["override"] = JsonPrimitive(true)
            }
            is AreaDef -> {
                if (d.packages.isNotEmpty()) p["packages"] = strList(d.packages)
                if (d.entities.isNotEmpty()) p["entities"] = strList(d.entities)
            }
            // 0.14 — named row schema: its column list, dumped exactly like a view's.
            is SchemaDef -> {
                if (d.columns.isNotEmpty()) p["columns"] = JsonArray(d.columns.map { defTree(it) })
            }
            // MD (v3.1) logical + md2db binding defs are not in the cross-target conformance corpus
            // yet (S8) — their parser-port coverage lives in MdDefParseSpec / Md2dbDefParseSpec.
            // Emit identity only; no props compared.
            is MdDomainDef, is DimensionDef, is MdMapDef, is HierarchyDef, is MeasureDef, is CubeletDef,
            is Md2dbCubeletDef, is Md2dbDomainDef, is Md2dbMapDef, is Md2erCubeletDef,
            -> {}
            is WorldDef -> {
                d.extends?.let { p["extends"] = JsonPrimitive(it) }
                if (d.engines.isNotEmpty()) {
                    p["engines"] =
                        JsonArray(
                            d.engines.map {
                                enginePartTree(
                                    it.kindName(),
                                    it.name,
                                    it.description,
                                    it.tags,
                                    it.type,
                                    it.version,
                                    it.extends,
                                    it.manifest,
                                )
                            },
                        )
                }
                if (d.executors.isNotEmpty()) {
                    p["executors"] =
                        JsonArray(
                            d.executors.map {
                                enginePartTree(
                                    it.kindName(),
                                    it.name,
                                    it.description,
                                    it.tags,
                                    it.type,
                                    it.version,
                                    it.extends,
                                    it.manifest,
                                )
                            },
                        )
                }
                if (d.storages.isNotEmpty()) p["storages"] = JsonArray(d.storages.map { storageTree(it) })
            }
            is LexiconEntryDef -> {
                d.target?.let { p["for"] = JsonPrimitive(it.path) }
                if (d.forms.isNotEmpty()) p["forms"] = strList(d.forms)
                d.match?.let { p["match"] = JsonPrimitive(it) }
                d.text?.let { p["text"] = JsonPrimitive(it) }
            }
        }
        return p
    }

    /** v4.4 — inline `lexicon { … }` block; canonical shorthand keys, present-only. */
    private fun lexicon(l: LexiconBlock): JsonElement? {
        val m = linkedMapOf<String, JsonElement>()
        if (l.terms.isNotEmpty()) m["terms"] = strList(l.terms)
        if (l.patterns.isNotEmpty()) m["patterns"] = strList(l.patterns)
        if (l.examples.isNotEmpty()) m["examples"] = strList(l.examples)
        return if (m.isEmpty()) null else obj(m)
    }

    // ----- world member serialisers (present-only flat objects; shared shape with TS dump.ts) -----

    private fun EngineDef.kindName() = "engine"

    private fun ExecutorDef.kindName() = "executor"

    private fun manifestDump(m: Map<String, PropertyValue>): JsonElement = obj(m.mapValues { pv(it.value) })

    @Suppress("LongParameterList")
    private fun enginePartTree(
        kind: String,
        name: String,
        description: String?,
        tags: List<String>,
        type: String?,
        version: String?,
        extends: String?,
        manifest: Map<String, PropertyValue>,
    ): JsonElement {
        val m = linkedMapOf<String, JsonElement>("kind" to JsonPrimitive(kind), "name" to JsonPrimitive(name))
        description?.let { m["description"] = JsonPrimitive(it) }
        if (tags.isNotEmpty()) m["tags"] = strList(tags)
        type?.let { m["type"] = JsonPrimitive(it) }
        version?.let { m["version"] = JsonPrimitive(it) }
        extends?.let { m["extends"] = JsonPrimitive(it) }
        if (manifest.isNotEmpty()) m["manifest"] = manifestDump(manifest)
        return obj(m)
    }

    private fun storageTree(s: StorageDef): JsonElement {
        val m = linkedMapOf<String, JsonElement>("kind" to JsonPrimitive("storage"), "name" to JsonPrimitive(s.name))
        s.description?.let { m["description"] = JsonPrimitive(it) }
        if (s.tags.isNotEmpty()) m["tags"] = strList(s.tags)
        s.type?.let { m["type"] = JsonPrimitive(it) }
        s.via?.let { m["via"] = JsonPrimitive(it) }
        if (s.hosts.isNotEmpty()) m["hosts"] = strList(s.hosts)
        if (s.staging) m["staging"] = JsonPrimitive(true)
        s.extends?.let { m["extends"] = JsonPrimitive(it) }
        if (s.schemas.isNotEmpty()) m["schemas"] = JsonArray(s.schemas.map { worldSchemaTree(it) })
        if (s.manifest.isNotEmpty()) m["manifest"] = manifestDump(s.manifest)
        return obj(m)
    }

    private fun worldSchemaTree(w: WorldSchemaDef): JsonElement =
        obj(
            "kind" to JsonPrimitive("schema"),
            "name" to JsonPrimitive(w.name),
            "fields" to obj(w.fields.associate { it.name to (JsonPrimitive(it.type) as JsonElement) }),
        )

    // ----- value normalisers (TTR-surface shape, shared with the TS dumper) -----

    private fun pv(v: PropertyValue): JsonElement =
        when (v) {
            is PropertyValue.StringValue -> obj("kind" to JsonPrimitive("string"), "value" to JsonPrimitive(v.raw))
            is PropertyValue.TripleStringValue ->
                obj("kind" to JsonPrimitive("tripleString"), "value" to JsonPrimitive(v.raw))
            is PropertyValue.NumberValue -> obj("kind" to JsonPrimitive("number"), "value" to num(v.raw))
            is PropertyValue.BoolValue -> obj("kind" to JsonPrimitive("bool"), "value" to JsonPrimitive(v.raw))
            is PropertyValue.NullValue -> obj("kind" to JsonPrimitive("null"))
            is PropertyValue.IdValue ->
                obj("kind" to JsonPrimitive("id"), "parts" to strList(v.parts), "path" to JsonPrimitive(v.ref.path))
            is PropertyValue.ListValue ->
                obj("items" to JsonArray(v.items.map { pv(it) }), "kind" to JsonPrimitive("list"))
            is PropertyValue.ObjectValue ->
                obj(
                    "entries" to obj(v.entries.mapValues { pv(it.value) }),
                    "kind" to JsonPrimitive("object"),
                )
            is PropertyValue.FunctionCall ->
                obj(
                    "args" to JsonArray(v.args.map { pv(it) }),
                    "kind" to JsonPrimitive("functionCall"),
                    "name" to JsonPrimitive(v.name),
                )
            is TaggedBlockValue -> embeddedDump(v)
        }

    /**
     * Serialises a `sourceText` / `definitionSql` value (embedded-sql §6.1). A
     * tagged block becomes a `{ kind, tag, language, dialect, value }` object so
     * the tag/dialect resolution is conformance-checked; a plain string or
     * triple-string serialises to its bare value (matching the TS dumper).
     */
    private fun embeddedDump(v: PropertyValue): JsonElement =
        when (v) {
            is TaggedBlockValue ->
                obj(
                    "dialect" to (v.dialect?.let { JsonPrimitive(it) } ?: JsonNull),
                    "kind" to JsonPrimitive("taggedBlock"),
                    "language" to JsonPrimitive(v.language),
                    "tag" to JsonPrimitive(v.tag),
                    "value" to JsonPrimitive(v.value),
                )
            is PropertyValue.StringValue -> JsonPrimitive(v.raw)
            is PropertyValue.TripleStringValue -> JsonPrimitive(v.raw)
            else -> error("embeddedDump: unexpected ${v::class.simpleName}")
        }

    private fun dataType(dt: DataType): JsonElement {
        val m = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(dt.name))
        dt.length?.let { m["length"] = JsonPrimitive(it) }
        dt.precision?.let { m["precision"] = JsonPrimitive(it) }
        return obj(m)
    }

    private fun searchHints(s: SearchHintsValue): JsonElement? {
        val m = linkedMapOf<String, JsonElement>()
        if (s.searchable) m["searchable"] = JsonPrimitive(true)
        if (s.fuzzy) m["fuzzy"] = JsonPrimitive(true)
        // 0.12 (RV-32): the method is dumped as its authored surface text
        // (`TYPOS(2)`), which keeps the three targets byte-identical without
        // committing to a numeric encoding.
        s.method?.let { mm -> m["method"] = JsonPrimitive(mm.toSurfaceText()) }
        localizedList(s.keywords)?.let { m["keywords"] = it }
        if (s.patterns.isNotEmpty()) m["patterns"] = strList(s.patterns)
        localizedList(s.descriptions)?.let { m["descriptions"] = it }
        if (s.examples.isNotEmpty()) m["examples"] = strList(s.examples)
        if (s.aliases.isNotEmpty()) m["aliases"] = strList(s.aliases)
        return if (m.isEmpty()) null else obj(m)
    }

    /**
     * Grounding Phase 1 (grammar 4.2) — `{ entries: {keys sorted}, duplicateProperties?: [sorted] }`.
     * Entries are emitted in sorted key order (via [obj]) so TS and Kotlin dumps
     * compare regardless of source ordering. Mirrors `semantics()` in dump.ts.
     */
    private fun semantics(s: SemanticsBlock): JsonElement {
        val m = linkedMapOf<String, JsonElement>("entries" to obj(s.entries.mapValues { semValue(it.value) }))
        if (s.duplicateProperties.isNotEmpty()) m["duplicateProperties"] = strList(s.duplicateProperties.sorted())
        return obj(m)
    }

    private fun semValue(v: SemanticsValue): JsonElement =
        when (v) {
            is SemanticsValue.Str -> JsonPrimitive(v.value)
            is SemanticsValue.Num -> num(v.value)
            is SemanticsValue.Bool -> JsonPrimitive(v.value)
            is SemanticsValue.NullV -> JsonNull
            // MS (vocabulary v3). LIST order is preserved — it is contract (the first
            // `measures:` item is the default measure). OBJECT keys are sorted, via the
            // same [obj] the block's own entries go through, so the two runtimes compare
            // regardless of authored key order. dump.ts sorts nested keys to match.
            is SemanticsValue.ListV -> JsonArray(v.items.map { semValue(it) })
            is SemanticsValue.ObjV -> obj(v.entries.mapValues { semValue(it.value) })
        }

    private fun localized(v: LocalizedStringValue): JsonElement? =
        if (v.byLanguage.isEmpty()) {
            null
        } else {
            obj(v.byLanguage.mapValues { JsonPrimitive(it.value) })
        }

    private fun localizedList(v: LocalizedStringListValue): JsonElement? =
        if (v.byLanguage.isEmpty()) {
            null
        } else {
            obj(v.byLanguage.mapValues { (_, items) -> strList(items) })
        }

    private fun valueLabels(
        v: Map<String, LocalizedStringValue>,
        aliases: Map<String, List<String>>,
    ): JsonElement =
        obj(
            v.mapValues { (key, ls) ->
                val a = aliases[key]
                // A4-β: an entry with `aliases` dumps `{ label, aliases }`; a legacy
                // entry keeps the flat localized-label shape (present-only).
                if (a != null && a.isNotEmpty()) {
                    obj("aliases" to strList(a), "label" to (localized(ls) ?: obj(emptyMap())))
                } else {
                    localized(ls) ?: obj(emptyMap())
                }
            },
        )

    private fun param(v: PropertyValue): JsonElement {
        // Query/procedure params are walker-built ObjectValues; normalise to the
        // TS ParameterDef surface { name, type?: {name}, label?, direction? }.
        //
        // This is the ONE place the two dumpers read from different AST shapes (TS
        // reads a typed ParameterDef; Kotlin an untyped ObjectValue). A lenient
        // `as?`-drops-silently projection could normalise a real walker difference
        // away (emitting `{}` instead of failing), so every step is STRICT: an
        // absent optional key is fine, but a present-but-wrong-typed key, a missing
        // required `name`, or an unexpected key fails the conformance run loudly.
        val o =
            v as? PropertyValue.ObjectValue
                ?: error("conformance: parameter must be an ObjectValue, got ${v::class.simpleName}")
        val m = linkedMapOf<String, JsonElement>()

        // `name` is required (TS ParameterDef.name is non-optional).
        val name = o.entries["name"] ?: error("conformance: parameter missing required 'name' (keys=${o.entries.keys})")
        val nameId =
            name as? PropertyValue.IdValue
                ?: error("conformance: parameter 'name' must be an id, got ${name::class.simpleName}")
        m["name"] = JsonPrimitive(nameId.ref.path)

        o.entries["type"]?.let { t ->
            val id =
                t as? PropertyValue.IdValue
                    ?: error("conformance: parameter 'type' must be an id, got ${t::class.simpleName}")
            m["type"] = obj("name" to JsonPrimitive(id.ref.path))
        }
        o.entries["label"]?.let { l ->
            val s =
                l as? PropertyValue.StringValue
                    ?: error("conformance: parameter 'label' must be a string, got ${l::class.simpleName}")
            m["label"] = JsonPrimitive(s.raw)
        }
        o.entries["direction"]?.let { d ->
            val id =
                d as? PropertyValue.IdValue
                    ?: error("conformance: parameter 'direction' must be an id, got ${d::class.simpleName}")
            m["direction"] = JsonPrimitive(id.ref.path)
        }

        val unexpected = o.entries.keys - setOf("name", "type", "label", "direction")
        if (unexpected.isNotEmpty()) {
            error("conformance: unexpected parameter key(s) $unexpected — update param() and the TS dump.ts together")
        }
        return obj(m)
    }

    private fun binding(m: BindingProperty): JsonElement =
        when (m) {
            is BindingPropertyBareId -> obj("id" to JsonPrimitive(m.id.path), "kind" to JsonPrimitive("bareId"))
            is BindingPropertyBlock -> {
                val o = linkedMapOf<String, JsonElement>("kind" to JsonPrimitive("block"))
                m.target?.let { o["target"] = target(it) }
                if (m.columns.isNotEmpty()) o["columns"] = JsonArray(m.columns.map { bindingColumn(it) })
                m.fk?.let { o["fk"] = JsonPrimitive(it.path) }
                obj(o)
            }
        }

    private fun bindingColumn(e: BindingColumnEntry): JsonElement {
        val value =
            when (val v = e.value) {
                is BindingColumnBareId -> obj("id" to JsonPrimitive(v.id.path), "kind" to JsonPrimitive("bareId"))
                is BindingColumnObject -> obj("kind" to JsonPrimitive("object"), "object" to pv(v.obj))
            }
        return obj("name" to JsonPrimitive(e.name), "value" to value)
    }

    private fun target(t: TargetValue): JsonElement =
        when (t) {
            is TargetObjectValue -> pv(t.obj)
            is TargetReferenceValue -> JsonPrimitive(t.ref.path)
        }

    private fun strList(items: List<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

    /** Whole doubles render as integers (matches JS `JSON.stringify`). */
    private fun num(d: Double): JsonPrimitive =
        if (d.isFinite() && d == kotlin.math.floor(d)) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    // ----- object builder + canonical printer -----

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = obj(pairs.toMap())

    private fun obj(m: Map<String, JsonElement>): JsonObject = JsonObject(m.toSortedMap())

    /** Pretty-prints a JsonElement byte-identically to JS `JSON.stringify(value, null, 4)`. */
    private fun printCanonical(e: JsonElement): String {
        val sb = StringBuilder()
        write(e, sb, 0)
        return sb.toString()
    }

    private fun write(
        e: JsonElement,
        sb: StringBuilder,
        indent: Int,
    ) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonObject -> {
                if (e.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val pad = "    ".repeat(indent + 1)
                e.entries.forEachIndexed { i, (k, v) ->
                    sb.append(pad).append(quote(k)).append(": ")
                    write(v, sb, indent + 1)
                    if (i < e.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("    ".repeat(indent)).append('}')
            }
            is JsonArray -> {
                if (e.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                val pad = "    ".repeat(indent + 1)
                e.forEachIndexed { i, v ->
                    sb.append(pad)
                    write(v, sb, indent + 1)
                    if (i < e.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("    ".repeat(indent)).append(']')
            }
            is JsonPrimitive -> {
                if (e.isString) sb.append(quote(e.content)) else sb.append(e.content)
            }
        }
    }

    /** JSON string escaping matching `JSON.stringify` (escapes control chars; leaves non-ASCII raw). */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
