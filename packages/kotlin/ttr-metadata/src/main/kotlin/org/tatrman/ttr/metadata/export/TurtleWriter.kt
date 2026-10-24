// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.metadata.export.rdf.*
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.TTR
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.RDF
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.RDFS
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.OWL
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.SKOS
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.XSD
import org.tatrman.ttr.metadata.export.rdf.Vocabulary.DCT
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.semantics.cn.conceptSugarOf
import org.tatrman.ttr.semantics.cn.sugarStereotypeKey
import java.security.MessageDigest

enum class TtlProfile(
    val owl: Boolean,
    val skos: Boolean,
) {
    SKOS(false, true),
    OWL(true, false),
    BOTH(true, true),
}

/** C-11: conceptual assertions plus optional standard aliases. Grounded layers remain IRI-only. */
object TurtleWriter {
    fun write(
        graph: DefinitionGraph,
        set: DefinitionSet,
        profile: TtlProfile = TtlProfile.BOTH,
        header: ExportHeader = ExportHeader.from(set),
    ): ByteArray = TurtleSerializer.write(triples(graph, set, profile, header), header.world)

    fun triples(
        graph: DefinitionGraph,
        set: DefinitionSet,
        profile: TtlProfile = TtlProfile.BOTH,
        header: ExportHeader = ExportHeader.from(set),
    ): TripleSink = Builder(graph, set, profile, header).build()

    private class Builder(
        val graph: DefinitionGraph,
        val set: DefinitionSet,
        val profile: TtlProfile,
        val header: ExportHeader,
    ) {
        val sink = TripleSink()
        val nodes = graph.nodes.associateBy { it.id }
        val resolver = DefinitionResolver(set.entries)

        // Full context preserves authored identities on scope stubs without leaking their bodies.
        val identities by lazy { DefinitionGraph.build(set, header.world).nodes.associate { it.id to it.iri } }
        val concepts = graph.nodes.filter { !it.external && it.model == "cn" }

        fun uri(id: String): Iri = Iri(nodes[id]?.iri ?: identities[id] ?: "urn:ttr:${header.world}:$id")

        fun type(
            s: Subject,
            value: String,
        ) = sink.add(s, Iri(RDF + "type"), Iri(value))

        fun add(
            s: Subject,
            key: String,
            value: Term,
        ) = sink.add(s, Iri(TTR + key), value)

        fun alias(
            s: Subject,
            namespace: String,
            key: String,
            value: Term,
        ) = sink.add(s, Iri(namespace + key), value)

        fun text(
            s: Subject,
            key: String,
            value: String,
        ) = add(s, key, Literal(value))

        fun bool(
            s: Subject,
            key: String,
            value: Boolean,
        ) = add(
            s,
            key,
            Literal(
                value.toString(),
                datatype =
                    Iri(
                        XSD + "boolean",
                    ),
            ),
        )

        fun build(): TripleSink {
            val ontology = Iri("urn:ttr:${header.world}")
            type(ontology, OWL + "Ontology")
            alias(ontology, DCT, "identifier", Literal(header.snapshot))
            alias(ontology, OWL, "versionInfo", Literal(header.grammar))
            for (node in concepts) concept(node)
            if (concepts.isNotEmpty()) {
                if (profile.skos) {
                    for (pkg in concepts.mapNotNull { it.pkg }.distinct()) {
                        type(
                            uri("pkg.$pkg"),
                            SKOS + "ConceptScheme",
                        )
                    }
                }
                for (area in graph.nodes.filter { it.model == "area" && !it.external }) {
                    val subject = uri(area.id)
                    type(subject, TTR + "Area")
                    graph.edges.filter { it.from == area.id && it.kind == "area" }.forEach {
                        add(
                            subject,
                            "member",
                            uri(it.to),
                        )
                    }
                }
            }
            return sink
        }

        fun body(node: Node): ConceptBody? {
            val declared = (set.entries.firstOrNull { it.qname == node.id }?.def as? ConceptDef)?.body
            val sugar =
                set.entries.mapNotNull { host ->
                    val sugar = conceptSugarOf(host.def) ?: return@mapNotNull null
                    val key = sugarStereotypeKey(sugar.body) ?: return@mapNotNull null
                    val name =
                        sugar.body
                            .ref(key)
                            ?.parts
                            ?.lastOrNull() ?: return@mapNotNull null
                    if ("cn.$key.$name" == node.id) sugar.body else null
                }
            if (declared == null && sugar.isEmpty()) return null
            return ConceptBody(
                properties =
                    (
                        declared?.properties.orEmpty() +
                            sugar.flatMap {
                                it.properties
                            }
                    ).distinctBy { it.key },
            )
        }

        fun concept(node: Node) {
            val subject = uri(node.id)
            val className =
                when (node.kind) {
                    "relation_type" -> "RelationType"
                    else -> node.kind.replaceFirstChar { it.uppercase() }
                }
            type(subject, TTR + className)
            if (profile.owl) type(subject, OWL + "Class")
            if (profile.skos) {
                type(subject, SKOS + "Concept")
                node.pkg?.let { alias(subject, SKOS, "inScheme", uri("pkg.$it")) }
            }
            text(subject, "tier", node.props["tier"]?.toString() ?: "core")
            if (node.props["abstract"] == true) bool(subject, "abstract", true)
            val canonical = "urn:ttr:${header.world}:${node.id}"
            if (profile.owl && subject.value != canonical) alias(subject, OWL, "sameAs", Iri(canonical))
            val labels =
                node.displayLabel.orEmpty().ifEmpty {
                    mapOf(
                        "en" to (node.label ?: node.id.substringAfterLast('.')),
                    )
                }
            for ((lang, value) in labels) {
                if (profile.skos) alias(subject, SKOS, "prefLabel", Literal(value, lang))
                if (profile.owl) alias(subject, RDFS, "label", Literal(value, lang))
            }
            for ((lang, forms) in node.synonyms.orEmpty()) {
                for (form in forms) {
                    if (profile.skos) alias(subject, SKOS, "altLabel", Literal(form, lang))
                }
            }
            for ((lang, value) in node.description.orEmpty()) {
                if (profile.skos) alias(subject, SKOS, "definition", Literal(value, lang))
                if (profile.owl) alias(subject, RDFS, "comment", Literal(value, lang))
            }
            val body = body(node)
            for (p in body?.properties.orEmpty()) {
                when (p) {
                    is ConceptProperty.Extent -> text(subject, "extent", p.value)
                    is ConceptProperty.Basis -> text(subject, "basis", p.value)
                    is ConceptProperty.Period -> for ((key, value) in p.value.entries) {
                        val predicate =
                            mapOf(
                                "at" to "periodAt",
                                "start" to "periodStart",
                                "end" to "periodEnd",
                                "end_open" to "end_open",
                            )[key]
                                ?: continue
                        value.scalar()?.let { text(subject, predicate, it.toString()) }
                    }
                    is ConceptProperty.DefinitionBlock -> {
                        text(subject, "definition", p.block.text)
                        text(subject, "definitionLanguage", "ttr-pl")
                    }
                    is ConceptProperty.MembersBlock -> text(subject, "memberRestriction", p.block.text)
                    is ConceptProperty.Entry ->
                        if (p.key in setOf("end_open", "imprecision")) {
                            val value = p.value.scalar()
                            if (value is Boolean) {
                                bool(
                                    subject,
                                    p.key,
                                    value,
                                )
                            } else {
                                value?.let { text(subject, p.key, it.toString()) }
                            }
                        }
                    is ConceptProperty.Lexicon -> {
                        val locale = set.entries.firstOrNull { it.qname == node.id }?.locale ?: "en"
                        if (profile.skos) {
                            p.block.terms.forEach {
                                alias(
                                    subject,
                                    SKOS,
                                    "altLabel",
                                    Literal(it, locale),
                                )
                            }
                        }
                        p.block.examples.forEach { add(subject, "example", Literal(it, locale)) }
                    }
                    else -> Unit
                }
            }
            for (edge in graph.edges.filter { it.from == node.id }) edge(subject, edge)
            for (entry in set.entries) {
                val lexicon = entry.def as? LexiconEntryDef ?: continue
                if (lexicon.entryKind != "example") continue
                val target = lexicon.target ?: continue
                if (resolver.resolve(target.path, entry, "cn") == node.id) {
                    lexicon.text?.let { add(subject, "example", Literal(it, entry.locale ?: "en")) }
                }
            }
            if (node.kind == "relation_type" && profile.owl) {
                val property = Iri(TTR + "rel_" + (node.label ?: node.id.substringAfterLast('.')))
                type(property, OWL + "ObjectProperty")
                for ((lang, value) in labels) alias(property, RDFS, "label", Literal(value, lang))
            }
        }

        fun edge(
            subject: Iri,
            edge: Edge,
        ) {
            val target = uri(edge.to)
            when (edge.kind) {
                "is_a" -> {
                    add(subject, "is_a", target)
                    if (profile.owl) alias(subject, RDFS, "subClassOf", target)
                    if (profile.skos) alias(subject, SKOS, "broader", target)
                }
                "involves" -> {
                    val key = listOf(edge.from, edge.to, edge.label.orEmpty()).joinToString("\u0000")
                    val id =
                        MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(key.toByteArray(Charsets.UTF_8))
                            .joinToString("") {
                                "%02x".format(it)
                            }
                    val participation = Blank(id)
                    add(subject, "involves", participation)
                    type(participation, TTR + "Participation")
                    edge.label?.let { text(participation, "label", it) }
                    add(participation, "concept", target)
                    if (profile.owl && edge.label != null) {
                        val property = Iri(TTR + "involves_" + edge.label)
                        type(property, OWL + "ObjectProperty")
                        alias(property, RDFS, "domain", subject)
                        alias(property, RDFS, "range", target)
                    }
                }
                "value_space" -> {
                    // Grammar value_space names a concept; a bound vocabulary projects its ER IRI.
                    val bound =
                        graph.edges
                            .filter {
                                it.from == edge.to && it.kind == "grounds" && it.props["layer"] == "er"
                            }.map { uri(it.to) }
                            .ifEmpty {
                                if (edge.to.startsWith("er.")) listOf(target) else emptyList()
                            }
                    for (vocabulary in bound.ifEmpty { listOf(target) }) {
                        add(subject, "valueSpace", vocabulary)
                        if (profile.skos && bound.isNotEmpty()) alias(subject, SKOS, "inScheme", vocabulary)
                    }
                }
                "grounds" -> {
                    add(subject, "groundsTo", target)
                    val layer = edge.props["layer"]?.toString() ?: nodes[edge.to]?.model ?: edge.to.substringBefore('.')
                    text(subject, "groundingLayer", layer)
                    if (profile.skos && layer == "db") alias(subject, SKOS, "exactMatch", target)
                }
            }
        }
    }
}
