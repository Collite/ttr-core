// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export.xmi

import org.tatrman.ttr.metadata.export.*
import org.tatrman.ttr.parser.model.*
import java.security.MessageDigest

/** One UML model, standard profile applications, and the EA extension. No diagram/layout synthesis. */
object XmiWriter {
    const val XMI = "http://schema.omg.org/spec/XMI/2.1"
    const val UML = "http://schema.omg.org/spec/UML/2.1"
    const val PROFILE = "http://tatrman.org/ns/ttr-mdg/1"

    fun write(
        graph: DefinitionGraph,
        set: DefinitionSet,
    ): ByteArray = Projection(graph, set).render()
}

private data class Connector(
    val iri: String,
    val qname: String,
    val type: String,
    val stereotype: String?,
    val from: String,
    val to: String,
    val label: String,
    val pkg: String,
    val tags: Map<String, String>,
    val cardinality: Map<String, String> = emptyMap(),
    val description: String = "",
    val ordering: Edge = Edge(from, to, type, id = qname, label = label),
)

private class Projection(
    graph: DefinitionGraph,
    private val set: DefinitionSet,
) {
    private val profile = TtrMdgProfile.load().stereotypes.associateBy { it.name }
    private val entries = set.entries.associateBy { it.qname }
    private val resolver = DefinitionResolver(set.entries)
    private val fullGraph = DefinitionGraph.build(set)
    private val full = fullGraph.nodes.associateBy { it.id }
    private val nodes = projectedNodes(graph)

    private fun projectedNodes(graph: DefinitionGraph): List<Node> {
        val selected =
            graph.nodes
                .filter {
                    it.model in layers || it.external && it.kind !in setOf("schema", "package")
                }.associateBy { it.id }
                .toMutableMap()
        // Domain references are retained in the AST, not currently graph reference edges.
        for (node in selected.values.toList().filterNot { it.external }) {
            val entry = entries[node.id] ?: continue
            val ref = (entry.def as? AttributeDef)?.domainRef ?: continue
            val target = resolver.resolve(ref.path, entry, "md", "domain")
            selected.putIfAbsent(target, Node(target, kind = "domain", external = true))
        }
        return selected.values.sortedWith(compareBy(bytewise) { it.id })
    }

    private val byId = nodes.associateBy { it.id }
    private val edges = graph.edges.sortedWith(edgeOrder)
    private val connectors = connectors()
    private val properties = nodes.filter { it.kind in setOf("attribute", "column") }
    private val owner =
        properties.associate { n ->
            n.id to
                (
                    edges
                        .firstOrNull {
                            it.kind == "contains" &&
                                it.to == n.id &&
                                byId[it.from]?.kind !in setOf(null, "package", "schema")
                        }?.from
                        ?: entries[n.id]?.parentQname?.takeIf { it in byId }
                )
        }
    private val primitives = nodes.mapNotNull { primitive(it) }.distinct().sortedWith(bytewise)
    private val packages = (nodes.map { pkg(it) } + connectors.map { it.pkg }).distinct().sortedWith(bytewise)
    private val generalizations = connectors.filter { it.type == "Generalization" }.groupBy { it.from }

    private fun iri(id: String) = byId[id]?.iri ?: full[id]?.iri ?: "urn:ttr:${set.world}:$id"

    private fun synthetic(name: String) = "urn:ttr:${set.world}:$name"

    private fun derivedIri(
        base: String,
        part: String,
    ) = base + (if ('#' in base) "/" else "#") + part

    private fun id(qname: String) = EaIds.of(iri(qname))

    private fun pkg(n: Node) = n.pkg ?: "External"

    private fun primitive(n: Node): String? =
        when (val def = entries[n.id]?.def.takeUnless { n.external }) {
            is ColumnDef -> def.type?.name
            is AttributeDef -> def.type?.name
            is MdDomainDef -> def.type?.name
            else -> null
        }

    private fun domain(n: Node): String? {
        val e = entries[n.id]?.takeUnless { n.external } ?: return null
        val ref = (e.def as? AttributeDef)?.domainRef ?: return null
        return resolver.resolveKnown(ref.path, e, "md", "domain")?.takeIf { it in byId }
    }

    private fun stereotype(n: Node): String? =
        when {
            n.external -> null
            n.model == "cn" -> "concept"
            n.kind == "measure" -> "metric"
            n.kind == "foreign_key" -> "relation"
            n.kind in profile -> n.kind
            else -> null
        }

    private fun metaclass(n: Node) =
        when (n.kind) {
            "attribute", "column" -> "Property"
            "domain" -> "DataType"
            else -> "Class"
        }

    private fun text(value: Any?): String =
        when (value) {
            is Map<*, *> ->
                value.entries
                    .sortedWith(
                        compareBy(bytewise) {
                            it.key.toString()
                        },
                    ).joinToString(";") { "${it.key}=${text(it.value)}" }
            is List<*> -> value.joinToString(";") { text(it) }
            null -> ""
            else -> value.toString()
        }

    private fun tags(n: Node): Map<String, String> {
        val st = stereotype(n) ?: return emptyMap()
        val values = n.props.mapValues { text(it.value) }.toMutableMap()
        values["qname"] = n.id
        values["synonyms"] =
            n.synonyms
                .orEmpty()
                .toSortedMap(bytewise)
                .flatMap { (lang, forms) ->
                    forms.sortedWith(bytewise).map { "$lang:$it" }
                }.joinToString(";")
        if (n.model == "cn") values["cnKind"] = n.kind
        n.schema?.let { values["schema"] = it }
        primitive(n)?.let { values["type"] = it }
        when (val def = entries[n.id]?.def) {
            is AttributeDef -> {
                values["nullable"] = def.optional.toString()
                def.domainRef?.let {
                    values["type"] =
                        it.path
                }
            }
            else -> Unit
        }
        return profile
            .getValue(st)
            .tags
            .mapNotNull { t -> values[t.name]?.let { t.name to it } }
            .toMap()
    }

    private fun connectorKey(e: Edge): String {
        // Length framing prevents delimiter ambiguity; mutable properties do not rename a connector.
        val name =
            listOf(e.from, e.to, e.kind, e.label.orEmpty(), e.id.orEmpty()).joinToString("") {
                "${it.toByteArray(Charsets.UTF_8).size}:$it"
            }
        return "edge." +
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(name.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private fun refs(value: PropertyValue?): List<String> =
        when (value) {
            is PropertyValue.IdValue -> listOf(value.ref.path)
            is PropertyValue.ListValue -> value.items.flatMap { refs(it) }
            else -> emptyList()
        }

    private fun connectors(): List<Connector> {
        val result = mutableListOf<Connector>()
        for (n in nodes.filter { it.kind in setOf("relation", "foreign_key") && !it.external }) {
            val e = entries[n.id] ?: continue
            val def = e.def
            val raw =
                when (def) {
                    is RelationDef -> refs(def.from) to refs(def.to)
                    is FkDef -> refs(def.from) to refs(def.to)
                    else -> continue
                }

            fun targets(names: List<String>) =
                names
                    .map { resolver.resolve(it, e, requireNotNull(n.model)) }
                    .map { q ->
                        if (full[q]?.kind ==
                            "column"
                        ) {
                            q.substringBeforeLast('.')
                        } else {
                            q
                        }
                    }.distinct()
                    .sortedWith(bytewise)
            var index = 0
            for (from in targets(raw.first)) {
                for (to in targets(raw.second)) {
                    if (from !in byId || to !in byId) continue
                    val cardinality =
                        (n.props["cardinality"] as? Map<*, *>)?.entries?.associate {
                            it.key.toString() to
                                it.value.toString()
                        }
                            ?: if (def is FkDef) mapOf("from" to "0..*", "to" to "1") else emptyMap()
                    val uri =
                        if (index++ == 0) {
                            iri(n.id)
                        } else {
                            derivedIri(iri(n.id), connectorKey(Edge(from, to, n.kind)))
                        }
                    result +=
                        Connector(
                            uri,
                            n.id,
                            "Association",
                            "relation",
                            from,
                            to,
                            n.label.orEmpty(),
                            pkg(n),
                            tags(n) + ("cardinality" to text(cardinality)),
                            cardinality,
                            n.description?.get("en").orEmpty(),
                            ordering = Edge(from, to, n.kind, id = n.id),
                        )
                }
            }
        }
        for (e in edges.filter { it.kind in setOf("is_a", "involves", "grounds", "er2db", "md2db", "md2er") }) {
            if (e.from !in byId || e.to !in byId) continue
            val key = connectorKey(e)
            val qname = e.id ?: key
            val uri =
                if (e.id != null && fullGraph.edges.count { it.id == e.id } == 1) {
                    synthetic(e.id)
                } else {
                    synthetic(key)
                }
            val type =
                when (e.kind) {
                    "is_a" -> "Generalization"
                    "involves" -> "Association"
                    else -> "Dependency"
                }
            val st =
                when (e.kind) {
                    "is_a" -> null
                    "involves" -> "involves"
                    else -> "grounds"
                }
            val tags = linkedMapOf("qname" to qname, "synonyms" to "")
            if (st == "involves") e.label?.let { tags["label"] = it }
            if (st ==
                "grounds"
            ) {
                tags["layer"] = text(e.props["layer"] ?: full[e.to]?.model)
                tags["via"] =
                    text(e.props["via"] ?: e.kind)
            }
            result +=
                Connector(
                    uri,
                    qname,
                    type,
                    st,
                    e.from,
                    e.to,
                    e.label ?: e.kind,
                    pkg(byId.getValue(e.from)),
                    tags,
                    ordering = e,
                )
        }
        return result.sortedWith { a, b -> edgeOrder.compare(a.ordering, b.ordering) }
    }

    fun render(): ByteArray =
        Xml()
            .apply {
                element(
                    "xmi:XMI",
                    "xmi:version" to "2.1",
                    "xmlns:xmi" to XmiWriter.XMI,
                    "xmlns:uml" to XmiWriter.UML,
                    "xmlns:ttr-mdg" to XmiWriter.PROFILE,
                ) {
                    element("xmi:Documentation", "exporter" to "Tatrman", "exporterVersion" to "ttr-mdg/1")
                    element("uml:Model", "xmi:id" to EaIds.of(synthetic("world")), "name" to set.world) {
                        for (p in packages) {
                            element(
                                "packagedElement",
                                "xmi:type" to "uml:Package",
                                "xmi:id" to EaIds.of(synthetic("pkg.$p")),
                                "name" to p,
                            ) {
                                for (n in nodes.filter {
                                    pkg(it) == p &&
                                        (it.external || it.kind !in setOf("relation", "foreign_key")) &&
                                        it.kind !in setOf("attribute", "column")
                                }) {
                                    classifier(n)
                                }
                                val standalone = properties.filter { pkg(it) == p && owner[it.id] == null }
                                if (standalone.isNotEmpty()) {
                                    element(
                                        "packagedElement",
                                        "xmi:type" to "uml:Class",
                                        "xmi:id" to EaIds.of(synthetic("pkg.$p#attributes")),
                                        "name" to "Attributes",
                                    ) { for (n in standalone) property(n) }
                                }
                                for (c in connectors.filter { it.pkg == p && it.type != "Generalization" }) connector(c)
                            }
                        }
                        if (primitives.isNotEmpty()) {
                            element(
                                "packagedElement",
                                "xmi:type" to "uml:Package",
                                "xmi:id" to EaIds.of(synthetic("types")),
                                "name" to "Types",
                            ) {
                                for (name in primitives) {
                                    element(
                                        "packagedElement",
                                        "xmi:type" to "uml:PrimitiveType",
                                        "xmi:id" to EaIds.of(synthetic("type.$name")),
                                        "name" to name,
                                    )
                                }
                            }
                        }
                    }
                    for (n in nodes.filter {
                        it.external || it.kind !in setOf("relation", "foreign_key")
                    }) {
                        stereotype(n)?.let { application(it, metaclass(n), iri(n.id), tags(n)) }
                    }
                    for (c in connectors) c.stereotype?.let { application(it, c.type, c.iri, c.tags) }
                    element("xmi:Extension", "extender" to "Enterprise Architect", "extenderID" to "6.5") {
                        element("elements") {
                            for (n in nodes.filter { it.external || it.kind !in setOf("relation", "foreign_key") }) {
                                element(
                                    "element",
                                    "xmi:idref" to id(n.id),
                                    "xmi:type" to "uml:${metaclass(n)}",
                                    "name" to (n.label ?: n.id.substringAfterLast('.')),
                                    "scope" to "public",
                                ) {
                                    element(
                                        "properties",
                                        "stereotype" to stereotype(n).orEmpty(),
                                        "documentation" to n.description?.get("en").orEmpty(),
                                    )
                                    eaTags(id(n.id), tags(n))
                                }
                            }
                        }
                        element("connectors") {
                            for (c in connectors) {
                                element("connector", "xmi:idref" to EaIds.of(c.iri)) {
                                    endpoint("source", c.from, c.cardinality["from"], byId[c.from]?.label.orEmpty())
                                    endpoint(
                                        "target",
                                        c.to,
                                        c.cardinality["to"],
                                        c.tags["label"] ?: byId[c.to]?.label.orEmpty(),
                                    )
                                    element(
                                        "properties",
                                        "ea_type" to c.type,
                                        "stereotype" to c.stereotype.orEmpty(),
                                        "documentation" to c.description,
                                    )
                                    eaTags(EaIds.of(c.iri), c.tags)
                                }
                            }
                        }
                    }
                }
            }.bytes()

    private fun Xml.classifier(n: Node) {
        element(
            "packagedElement",
            "xmi:type" to "uml:${metaclass(n)}",
            "xmi:id" to id(n.id),
            "name" to (n.label ?: n.id.substringAfterLast('.')),
            "visibility" to "public",
            "isAbstract" to (n.props["abstract"] == true).toString(),
        ) {
            for (p in properties.filter { owner[it.id] == n.id }) property(p)
            for (c in generalizations[n.id].orEmpty()) {
                element(
                    "generalization",
                    "xmi:type" to "uml:Generalization",
                    "xmi:id" to EaIds.of(c.iri),
                    "general" to id(c.to),
                )
            }
        }
    }

    private fun Xml.property(n: Node) {
        val type = domain(n)?.let { id(it) } ?: primitive(n)?.let { EaIds.of(synthetic("type.$it")) }
        val attrs =
            mutableListOf(
                "xmi:type" to "uml:Property",
                "xmi:id" to id(n.id),
                "name" to (n.label ?: n.id.substringAfterLast('.')),
                "visibility" to "public",
            )
        type?.let { attrs += "type" to it }
        element("ownedAttribute", *attrs.toTypedArray())
    }

    private fun Xml.connector(c: Connector) {
        val attrs = mutableListOf("xmi:type" to "uml:${c.type}", "xmi:id" to EaIds.of(c.iri), "name" to c.label)
        if (c.type == "Dependency") {
            attrs += "client" to id(c.from)
            attrs += "supplier" to id(c.to)
        }
        element("packagedElement", *attrs.toTypedArray()) {
            if (c.type == "Association") {
                for (end in listOf("from", "to")) {
                    element(
                        "memberEnd",
                        "xmi:idref" to EaIds.of(derivedIri(c.iri, "end.$end")),
                    )
                }
                for ((end, target) in listOf("from" to c.from, "to" to c.to)) {
                    element(
                        "ownedEnd",
                        "xmi:type" to "uml:Property",
                        "xmi:id" to EaIds.of(derivedIri(c.iri, "end.$end")),
                        "name" to
                            (
                                if (end ==
                                    "to"
                                ) {
                                    c.tags["label"] ?: byId[target]?.label.orEmpty()
                                } else {
                                    byId[target]?.label.orEmpty()
                                }
                            ),
                        "type" to id(target),
                        "association" to EaIds.of(c.iri),
                    ) {
                        c.cardinality[end]?.let { card ->
                            require(card.matches(Regex("(?:0|[1-9][0-9]*)(?:\\.\\.(?:0|[1-9][0-9]*|\\*))?|\\*"))) {
                                "Unsupported cardinality: $card"
                            }
                            val bounds = card.split("..")
                            val lower =
                                if (card ==
                                    "*"
                                ) {
                                    "0"
                                } else {
                                    bounds.first()
                                }
                            val upper = bounds.last()
                            require(
                                lower.toIntOrNull()?.let { it >= 0 } == true &&
                                    (upper == "*" || upper.toIntOrNull()?.let { it >= lower.toInt() } == true),
                            ) { "Unsupported cardinality: $card" }
                            element(
                                "lowerValue",
                                "xmi:type" to "uml:LiteralInteger",
                                "xmi:id" to EaIds.of(derivedIri(c.iri, "end.$end.lower")),
                                "value" to lower,
                            )
                            element(
                                "upperValue",
                                "xmi:type" to "uml:LiteralUnlimitedNatural",
                                "xmi:id" to EaIds.of(derivedIri(c.iri, "end.$end.upper")),
                                "value" to upper,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun Xml.application(
        st: String,
        base: String,
        iri: String,
        tags: Map<String, String>,
    ) = element(
        "ttr-mdg:$st",
        *(
            listOf(
                "xmi:id" to EaIds.of(derivedIri(iri, "stereotype.$st")),
                "base_$base" to EaIds.of(iri),
            ) +
                tags.toList()
        ).toTypedArray(),
    )

    private fun Xml.eaTags(
        id: String,
        tags: Map<String, String>,
    ) = element("tags") {
        for ((name, value) in tags) {
            element(
                "tag",
                "name" to name,
                "value" to value,
                "modelElement" to id,
            )
        }
    }

    private fun Xml.endpoint(
        name: String,
        qname: String,
        multiplicity: String?,
        role: String,
    ) = element(
        name,
        "xmi:idref" to id(qname),
    ) {
        element(
            "model",
            "name" to (byId[qname]?.label ?: qname.substringAfterLast('.')),
            "type" to "${byId[qname]?.let { metaclass(it) } ?: "Class"}",
        )
        element("role", "name" to role, "multiplicity" to multiplicity.orEmpty())
    }

    companion object {
        private val layers = setOf("cn", "md", "er", "db")
    }
}
