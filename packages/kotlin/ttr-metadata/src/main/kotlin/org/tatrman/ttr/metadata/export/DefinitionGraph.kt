// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.cn.conceptSugarOf
import org.tatrman.ttr.semantics.cn.sugarStereotypeKey

/** Bodiless tier-1 data. Nullable metadata distinguishes minimal external stubs from complete nodes. */
data class Node(
    val id: String,
    val iri: String? = null,
    val model: String? = null,
    val kind: String,
    val ossieTerm: String? = null,
    val label: String? = null,
    val schema: String? = null,
    val pkg: String? = null,
    val displayLabel: Map<String, String>? = null,
    val labelPlural: String? = null,
    val description: Map<String, String>? = null,
    val synonyms: Map<String, List<String>>? = null,
    val hasDefinition: Boolean? = null,
    val definitionRef: String? = null,
    val external: Boolean = false,
    val props: Map<String, Any> = emptyMap(),
)

data class Edge(
    val from: String,
    val to: String,
    val kind: String,
    val id: String? = null,
    val label: String? = null,
    val ossieTerm: String? = null,
    val props: Map<String, Any> = emptyMap(),
    val hasDefinition: Boolean = false,
    val definitionRef: String? = null,
)

data class DefinitionGraph(
    val nodes: List<Node>,
    val edges: List<Edge>,
) {
    companion object {
        fun build(
            set: DefinitionSet,
            world: String = set.world,
        ): DefinitionGraph = GraphBuilder(set, world).build()
    }
}

private class GraphBuilder(
    private val set: DefinitionSet,
    private val world: String,
) {
    private val entries =
        set.entries
            .filter {
                it.kind in ExportKinds.nodes && it.model !in setOf("cnc", "automation")
            }.toMutableList()
    private val resolver by lazy { DefinitionResolver(entries) }
    private val sugarHosts = linkedMapOf<String, DefinedObject>()
    private val nodes = linkedMapOf<String, Node>()
    private val edges = mutableListOf<Edge>()

    fun build(): DefinitionGraph {
        // Use the same declaring-slot rule as CN-P2; the sugar's host remains in the definition set.
        for (host in set.entries) {
            val sugar = conceptSugarOf(host.def) ?: continue
            val key = sugarStereotypeKey(sugar.body) ?: continue
            val name =
                sugar.body
                    .ref(key)
                    ?.parts
                    ?.lastOrNull() ?: continue
            val id = "cn.$key.$name"
            val existing = entries.indexOfFirst { it.qname == id }
            val body =
                sugar.body.copy(
                    properties =
                        sugar.body.properties.filterNot {
                            it is ConceptProperty.Ref &&
                                it.key == key
                        },
                )
            if (existing < 0) {
                entries +=
                    host.copy(
                        qname = id,
                        model = "cn",
                        kind = key,
                        def = ConceptDef(key, name, sugar.source, body),
                        parentQname = null,
                    )
            } else {
                val stated = entries[existing]
                val concept = stated.def as ConceptDef
                entries[existing] =
                    stated.copy(
                        def =
                            concept.copy(
                                body =
                                    concept.body.copy(
                                        properties =
                                            concept.body.properties + body.properties,
                                    ),
                            ),
                    )
            }
            sugarHosts[id] = host
        }
        require(
            entries
                .map {
                    it.qname
                }.distinct()
                .size == entries.size,
        ) {
            "XM identities must be unique: ${entries.groupBy { it.qname }.filterValues { it.size > 1 }.keys}"
        }
        for (entry in entries) nodes[entry.qname] = node(entry)
        for (entry in entries) structural(entry)
        for (entry in entries) references(entry)
        for (entry in set.entries.filter { it.kind in ExportKinds.bindings }) binding(entry)
        for ((id, host) in sugarHosts) {
            edge(
                id,
                host.qname,
                "grounds",
                props =
                    mapOf(
                        "via" to "sugar",
                        "layer" to host.model,
                    ),
            )
        }
        lexicon()
        return DefinitionGraph(
            nodes.values.sortedWith(compareBy<Node, String>(bytewise) { it.id }.thenBy(bytewise) { it.kind }),
            edges.distinct().sortedWith(edgeOrder),
        )
    }

    private fun node(e: DefinedObject): Node {
        val d = e.def
        val body = (d as? ConceptDef)?.body
        val props = properties(d).toMutableMap()
        if (e.qname in sugarHosts) props["declaredBy"] = "sugar"
        val defined =
            when (d) {
                is ViewDef -> d.definitionSql != null
                is ConceptDef ->
                    d.body.properties.any {
                        it is ConceptProperty.DefinitionBlock ||
                            it is ConceptProperty.MembersBlock
                    }
                is MeasureDef -> d.aggregation != null
                else -> false
            }
        val display =
            when (d) {
                is EntityDef -> d.displayLabel
                is AttributeDef -> d.displayLabel
                is ConceptDef -> d.body.label
                else -> null
            }
        val descriptions =
            d.descriptionLocalized.byLanguage.ifEmpty {
                d.description
                    ?.let {
                        mapOf(
                            "en" to it,
                        )
                    }.orEmpty()
            }
        return Node(
            id = e.qname,
            iri = body?.entry("iri")?.scalar()?.toString() ?: "urn:ttr:$world:${e.qname}",
            model = e.model,
            kind = e.kind,
            ossieTerm = OssieTerms["${e.model}.${e.kind}"] ?: OssieTerms[e.kind],
            label = d.name,
            schema = e.schemaCode,
            pkg = e.pkg,
            displayLabel = display?.byLanguage.orEmpty().toSortedMap(bytewise),
            labelPlural = (d as? EntityDef)?.labelPlural,
            description = descriptions.toSortedMap(bytewise),
            synonyms = emptyMap(),
            hasDefinition = defined,
            definitionRef =
                if (defined) {
                    val sourceId =
                        if (set.entries.any { it.qname == e.qname }) {
                            e.qname
                        } else {
                            sugarHosts[e.qname]?.qname
                                ?: e.qname
                        }
                    "defs.json#$sourceId"
                } else {
                    null
                },
            props = props.toSortedMap(bytewise),
        )
    }

    private fun structural(e: DefinedObject) {
        val pkg = "pkg.${e.pkg}"
        nodes.putIfAbsent(pkg, Node(pkg, kind = "package", external = true))
        edge(pkg, e.qname, "contains")
        e.parentQname?.let { edge(it, e.qname, "contains") }
        if (e.model == "db" && e.kind in setOf("table", "view")) {
            val schema = "db.${e.schemaCode ?: "dbo"}"
            nodes.putIfAbsent(schema, Node(schema, kind = "schema", external = true))
            edge(schema, e.qname, "contains")
        }
    }

    private fun references(e: DefinedObject) {
        fun target(
            raw: String,
            model: String = e.model,
            kind: String? = null,
        ) = resolve(raw, e, model, kind)
        when (val d = e.def) {
            is RelationDef -> for (a in refs(d.from)) {
                for (b in refs(d.to)) {
                    edge(
                        target(a, "er", "entity"),
                        target(b, "er", "entity"),
                        "relation",
                        props =
                            buildMap {
                                d.cardinality?.scalar()?.let { put("cardinality", it) }
                                put("join", d.join.mapNotNull { it.scalar() })
                            },
                    )
                }
            }
            is FkDef -> {
                val from = refs(d.from).map { target(it, "db") }
                val to = refs(d.to).map { target(it, "db") }
                for (a in from.map { columnOwner(it) }.distinct()) {
                    for (b in to.map { columnOwner(it) }.distinct()) {
                        edge(a, b, "foreign_key", props = mapOf("fromColumns" to from, "toColumns" to to))
                    }
                }
            }
            is CubeletDef -> {
                d.grain.forEach { edge(e.qname, target(it.path, "md", "attribute"), "grain") }
                d.measures.forEach {
                    val name =
                        when (it) {
                            is CubeletMeasure.Ref -> it.ref.path
                            is CubeletMeasure.Inline -> it.measure.name
                        }
                    edge(e.qname, target(name, "md", "measure"), "measures")
                }
            }
            is HierarchyDef ->
                d.levels.forEachIndexed { i, level ->
                    val dim =
                        d.dimensionRef
                            ?.path
                            ?.removePrefix("md.")
                            ?.removePrefix("dimension.")
                    edge(
                        e.qname,
                        target(
                            if (dim != null &&
                                !level.attribute.contains('.')
                            ) {
                                "$dim.${level.attribute}"
                            } else {
                                level.attribute
                            },
                            "md",
                            "attribute",
                        ),
                        "level",
                        props = mapOf("order" to i),
                    )
                }
            is ConceptDef -> for (p in d.body.properties.distinctBy { it.key }) {
                when (p) {
                    is ConceptProperty.Entry ->
                        if (p.key ==
                            "is_a"
                        ) {
                            refs(p.value).forEach { edge(e.qname, target(it, "cn"), "is_a") }
                        }
                    is ConceptProperty.Involves ->
                        p.participants.forEach {
                            edge(e.qname, target(it.ref.path, "cn"), "involves", label = it.label)
                        }
                    is ConceptProperty.ValueSpace -> edge(e.qname, target(p.ref.path, "cn"), "value_space")
                    is ConceptProperty.Period -> for ((key, value) in p.value.entries) {
                        if (key in
                            setOf("at", "start", "end")
                        ) {
                            refs(value).forEach { edge(e.qname, target(it, "cn"), "period_of", label = key) }
                        }
                    }
                    is ConceptProperty.Grounds -> {
                        val layer = p.ref.parts.firstOrNull { it in setOf("md", "er", "db") } ?: "er"
                        edge(
                            e.qname,
                            target(p.ref.path, layer),
                            "grounds",
                            props =
                                mapOf(
                                    "via" to "direct",
                                    "layer" to layer,
                                ),
                        )
                    }
                    else -> Unit
                }
            }
            is AreaDef -> {
                d.packages.forEach { pkg ->
                    val id = "pkg.$pkg"
                    nodes.putIfAbsent(id, Node(id, kind = "package", external = true))
                    edge(e.qname, id, "area")
                }
                d.entities.forEach { edge(e.qname, target(it, "er", "entity"), "area") }
            }
            else -> Unit
        }
    }

    private fun columnOwner(id: String): String =
        if (entries.any {
                it.qname == id && it.kind == "column"
            }
        ) {
            id.substringBeforeLast('.')
        } else {
            id
        }

    private fun binding(e: DefinedObject) {
        fun add(
            source: String?,
            target: String?,
            kind: String,
            props: Map<String, Any> = emptyMap(),
            body: Boolean = false,
        ) {
            if (source == null || target == null) return
            val from =
                resolve(
                    source,
                    e,
                    if (kind == "grounds") {
                        "cn"
                    } else if (e.kind.startsWith("er")) {
                        "er"
                    } else {
                        "md"
                    },
                )
            val layer =
                if (kind ==
                    "grounds"
                ) {
                    e.kind.substringAfter("cn2").substringBefore('_')
                } else if (e.kind.contains("2er")) {
                    "er"
                } else {
                    "db"
                }
            val to = resolve(target, e, layer)
            edge(from, to, kind, id = e.qname, props = props + mapOf("target" to to), hasDefinition = body)
        }
        when (val d = e.def) {
            is Er2DbEntityDef -> add(d.entity?.path, targetRef(d.target), "er2db", body = d.whereFilter != null)
            is Er2DbAttributeDef -> add(d.attribute?.path, targetRef(d.target), "er2db")
            is Er2DbRelationDef -> add(d.relation?.path, d.fk?.path, "er2db")
            is Md2dbCubeletDef ->
                add(
                    d.cubeletRef?.path,
                    d.table?.path,
                    "md2db",
                    mapOf(
                        "columns" to
                            (
                                d.attributes.values.map {
                                    attrColumn(it)
                                } + d.measures.values.map { measureColumn(it) }
                            ).filterNotNull().sortedWith(bytewise),
                    ),
                )
            is Md2dbDomainDef ->
                add(
                    d.domainRef?.path,
                    d.columnSource?.let { "${it.table.path}.${it.column}" },
                    "md2db",
                )
            is Md2dbMapDef ->
                add(
                    d.mapRef?.path,
                    d.table?.path,
                    "md2db",
                    mapOf(
                        "columns" to d.columns.values.sortedWith(bytewise),
                    ),
                )
            is Md2erDimensionDef ->
                add(
                    d.dimensionRef?.path,
                    d.entity?.path,
                    "md2er",
                    mapOf(
                        "columns" to d.attributes.values.sortedWith(bytewise),
                    ),
                )
            is Md2erCubeletDef ->
                add(
                    d.cubeletRef?.path,
                    d.entity?.path,
                    "md2er",
                    mapOf(
                        "columns" to (d.attributes.values + d.measures.values).sortedWith(bytewise),
                    ),
                )
            is ConceptBindingDef -> {
                val concept =
                    d.body.properties
                        .filterIsInstance<ConceptProperty.Ref>()
                        .firstOrNull()
                        ?.ref
                        ?.path
                val target =
                    d.body.properties
                        .filterIsInstance<ConceptProperty.Target>()
                        .firstOrNull()
                        ?.value
                val layer = d.bindingKind.substringAfter("cn2").substringBefore('_')
                val defined =
                    d.body.properties.any {
                        it is ConceptProperty.FilterBlock ||
                            it.key in setOf("hierarchy", "via", "participants")
                    }
                add(concept, targetRef(target), "grounds", mapOf("via" to "direct", "layer" to layer), defined)
            }
            else -> Unit
        }
    }

    private fun edge(
        from: String,
        to: String,
        kind: String,
        id: String? = null,
        label: String? = null,
        props: Map<String, Any> = emptyMap(),
        hasDefinition: Boolean = false,
    ) {
        for (target in listOf(from, to)) nodes.putIfAbsent(target, Node(target, kind = "unresolved", external = true))
        edges +=
            Edge(
                from,
                to,
                kind,
                id,
                label,
                OssieTerms[kind],
                props,
                hasDefinition,
                if (hasDefinition) "defs.json#$id" else null,
            )
    }

    private fun resolve(
        raw: String,
        context: DefinedObject,
        defaultModel: String,
        kind: String? = null,
    ): String = resolver.resolve(raw, context, defaultModel, kind)

    private fun lexicon() {
        val synonyms = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        for (entry in set.entries) {
            val d = entry.def as? LexiconEntryDef ?: continue
            if (d.entryKind != "term") continue
            val target = d.target ?: continue
            val id = resolve(target.path, entry, target.parts.firstOrNull() ?: "cn")
            if (nodes[id]?.external != false) continue
            synonyms
                .getOrPut(
                    id,
                ) { sortedMapOf(bytewise) }
                .getOrPut(entry.locale ?: "en") { sortedSetOf(bytewise) }
                .addAll(d.forms)
        }
        for ((id, locales) in synonyms) {
            nodes[id] =
                nodes.getValue(id).copy(synonyms = locales.mapValues { it.value.toList() })
        }
    }
}

internal val edgeOrder: Comparator<Edge> =
    compareBy<Edge, String>(bytewise) { it.from }
        .thenBy(bytewise) {
            it.to
        }.thenBy(bytewise) { it.kind }
        .thenBy(bytewise) { it.label.orEmpty() }
        .thenBy(bytewise) { it.id.orEmpty() }

internal fun PropertyValue.scalar(): Any? =
    when (this) {
        is PropertyValue.StringValue -> raw
        is PropertyValue.TripleStringValue -> raw
        is PropertyValue.NumberValue -> raw
        is PropertyValue.BoolValue -> raw
        is PropertyValue.IdValue -> ref.path
        is PropertyValue.ListValue -> items.mapNotNull { it.scalar() }
        is PropertyValue.ObjectValue ->
            entries
                .mapNotNull { (k, v) ->
                    v.scalar()?.let { k to it }
                }.toMap()
                .toSortedMap(bytewise)
        else -> null
    }

private fun refs(value: PropertyValue?): List<String> =
    when (value) {
        is PropertyValue.IdValue -> listOf(value.ref.path)
        is PropertyValue.ListValue -> value.items.flatMap { refs(it) }
        is PropertyValue.ObjectValue -> value.entries.values.flatMap { refs(it) }
        else -> emptyList()
    }

private fun targetRef(target: TargetValue?): String? =
    when (target) {
        is TargetReferenceValue -> target.ref.path
        is TargetObjectValue -> refs(target.obj).firstOrNull()
        else -> null
    }

private fun attrColumn(binding: AttrColumnBinding): String? =
    when (binding) {
        is AttrColumnBinding.Column -> binding.column
        is AttrColumnBinding.Via -> binding.from.column
    }

private fun measureColumn(binding: MeasureColumnBinding): String =
    when (binding) {
        is MeasureColumnBinding.Column -> binding.column
        is MeasureColumnBinding.Code -> binding.code
    }

private fun properties(d: Definition): Map<String, Any> =
    buildMap {
        when (d) {
            is ColumnDef -> {
                d.type?.let { put("type", it.name) }
                put("nullable", d.optional)
            }
            is ViewDef -> put("sql", false)
            is MeasureDef -> {
                d.aggregation?.default?.let { put("aggregation", it) }
                d.aggregation?.perDimension?.let { put("aggregationPerDimension", it.toSortedMap(bytewise)) }
                d.measureClass?.let { put("additivity", it) }
                d.validBy?.let { put("validBy", it) }
            }
            is CubeletDef -> put("grain", d.grain.map { it.path })
            is HierarchyDef -> put("levels", d.levels.map { it.attribute })
            is DimensionDef -> d.key?.let { put("key", it) }
            is EntityDef -> {
                d.nameAttribute?.let { put("nameAttribute", it.path) }
                d.codeAttribute?.let { put("codeAttribute", it.path) }
                put("aliases", d.aliases)
            }
            is RelationDef -> d.cardinality?.scalar()?.let { put("cardinality", it) }
            is ConceptDef -> {
                put(
                    "tier",
                    if (d.stereotype == "relation_type" ||
                        (d.body.entry("abstract") as? PropertyValue.BoolValue)?.raw == true
                    ) {
                        "open"
                    } else {
                        "core"
                    },
                )
                for (p in d.body.properties.distinctBy { it.key }) {
                    when (p) {
                        is ConceptProperty.Extent -> put("extent", p.value)
                        is ConceptProperty.Basis -> put("basis", p.value)
                        is ConceptProperty.Period -> p.value.scalar()?.let { put("period", it) }
                        is ConceptProperty.Entry ->
                            if (p.key in
                                setOf("abstract", "temporal")
                            ) {
                                p.value.scalar()?.let { put(p.key, it) }
                            }
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
    }
