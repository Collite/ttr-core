// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import java.nio.file.Files
import java.nio.file.Path

internal fun turtleModel(bytes: ByteArray): Model =
    ModelFactory.createDefaultModel().also {
        RDFDataMgr.read(it, bytes.inputStream(), Lang.TURTLE)
    }

private fun ttlFixtureSet(): DefinitionSet {
    val set = xmJsonSet()
    val parsed =
        org.tatrman.ttr.parser.loader.TtrLoader.parseString(
            "model binding\ndef cn2db_concept party_table { concept: cn.Party, target: db.retail.buyers }",
        )
    parsed.errors shouldBe emptyList()
    return set.copy(
        entries =
            set.entries +
                DefinedObject(
                    "cn2db_concept.party_table",
                    "bindings",
                    "cn2db_concept",
                    parsed.definitions.single(),
                    "binding",
                    "ttl-extra.ttrm",
                    "binding",
                ),
    )
}

private const val NS = "https://tatrman.org/ns/ttr#"

private fun Model.ask(body: String): Boolean =
    QueryExecutionFactory
        .create(
            "PREFIX ttr: <$NS> PREFIX owl: <http://www.w3.org/2002/07/owl#> " +
                "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> " +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ASK { $body }",
            this,
        ).use { it.execAsk() }

private fun ttlGolden(
    name: String,
    bytes: ByteArray,
) {
    val path = Path.of("src/test/resources/xm-goldens/ttl/$name.ttl")
    if (name != "contract" && System.getProperty("updateGolden") == "true") {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
    bytes.toList() shouldBe Files.readAllBytes(path).toList()
}

class TurtleShapeSpec :
    StringSpec({
        "unchanged XM fixture owl bytes for CLI and HTTP parity" {
            val result =
                Exporter().export(
                    xmJsonSet(),
                    null,
                    ExportRequest(setOf("cn"), "ttl", "owl"),
                ) as ExportResult.Single
            ttlGolden("world-owl", result.bytes)
        }
        "hand-authored one-concept contract golden" {
            val set = DefinitionSet(emptyList(), emptyMap(), "sample", "0.18", "s1")
            val graph =
                DefinitionGraph(
                    listOf(
                        Node(
                            "cn.kind.Party",
                            "urn:ttr:sample:cn.kind.Party",
                            "cn",
                            "kind",
                            label = "Party",
                            pkg = "cn",
                            displayLabel =
                                mapOf(
                                    "en" to "Party",
                                ),
                            props = mapOf("tier" to "core"),
                        ),
                    ),
                    emptyList(),
                )
            ttlGolden("contract", TurtleWriter.write(graph, set, TtlProfile.BOTH, ExportHeader.from(set)))
        }
        "all profiles parse, count triples exactly, and match reviewed goldens" {
            val set = ttlFixtureSet()
            val graph = DefinitionGraph.build(set)
            for (profile in TtlProfile.entries) {
                val sink = TurtleWriter.triples(graph, set, profile, ExportHeader.from(set))
                val bytes = TurtleWriter.write(graph, set, profile, ExportHeader.from(set))
                val model = turtleModel(bytes)
                model.size() shouldBe sink.size.toLong()
                ttlGolden(profile.name.lowercase(), bytes)
                val shuffled = graph.copy(nodes = graph.nodes.reversed(), edges = graph.edges.reversed())
                TurtleWriter
                    .write(
                        shuffled,
                        set.copy(entries = set.entries.reversed()),
                        profile,
                        ExportHeader.from(set),
                    ).toList() shouldBe
                    bytes.toList()
                if (profile ==
                    TtlProfile.OWL
                ) {
                    model.ask("?s ?p ?o FILTER(STRSTARTS(STR(?p), 'http://www.w3.org/2004/02/skos/core#'))") shouldBe
                        false
                }
                if (profile == TtlProfile.SKOS) {
                    model.ask(
                        "?s ?p ?o FILTER((STRSTARTS(STR(?p), 'http://www.w3.org/2002/07/owl#') || ?p = rdfs:subClassOf) && ?s != <urn:ttr:${set.world}>)",
                    ) shouldBe
                        false
                    model.ask("?s a owl:Class") shouldBe false
                    model.ask("?s a owl:ObjectProperty") shouldBe false
                }
            }
        }
        "both carries standards aliases, authored identity, all languages and opaque definition" {
            val set = ttlFixtureSet()
            val graph = DefinitionGraph.build(set)
            val model = turtleModel(TurtleWriter.write(graph, set, TtlProfile.BOTH, ExportHeader.from(set)))
            for (node in graph.nodes.filter { it.model == "cn" && !it.external }) {
                model.ask("<${node.iri}> a owl:Class, skos:Concept") shouldBe true
            }
            model.ask("?s ttr:is_a ?t ; rdfs:subClassOf ?t ; skos:broader ?t") shouldBe true
            model.ask(
                "?s ttr:involves [ a ttr:Participation ; ttr:label 'buyer' ; ttr:concept ?x ] . ttr:involves_buyer a owl:ObjectProperty ; rdfs:domain ?s ; rdfs:range ?x",
            ) shouldBe
                true
            model.ask("?s ttr:definition 'amount > 100' ; ttr:definitionLanguage 'ttr-pl'") shouldBe true
            model.ask(
                "<https://example.org/northline/Party> owl:sameAs <urn:ttr:${set.world}:cn.kind.Party> ; skos:prefLabel 'Osoba'@cs, 'Party'@en ; skos:altLabel 'osoba'@cs, 'partner'@cs, 'party'@en, 'partner'@en",
            ) shouldBe
                true
            model.ask("?s ttr:abstract true") shouldBe true
            model.ask("?s ttr:groundsTo <urn:ttr:${set.world}:er.entity.buyer> ; ttr:groundingLayer 'er'") shouldBe true
            model.ask("?s ttr:groundsTo ?db ; skos:exactMatch ?db ; ttr:groundingLayer 'db'") shouldBe true
            model.ask("ttr:rel_recommends a owl:ObjectProperty") shouldBe true
            model.ask("?s a ttr:Area ; ttr:member ?member") shouldBe true
            // Grounding endpoints are IRIs, never projected physical/logical resources.
            model.ask("<urn:ttr:${set.world}:er.entity.buyer> ?p ?o") shouldBe false
        }
        "no cn package still emits exactly one ontology and no concepts" {
            val set =
                xmJsonSet().let {
                    it.copy(
                        entries =
                            it.entries.filter { e ->
                                e.model != "cn" &&
                                    e.model != "bindings" &&
                                    e.model != "er" &&
                                    e.model != "md"
                            },
                    )
                }
            val bytes = TurtleWriter.write(DefinitionGraph.build(set), set, TtlProfile.BOTH, ExportHeader.from(set))
            ttlGolden("empty-world", bytes)
            val model = turtleModel(bytes)
            model.size() shouldBe 3L
            model.ask("?s a owl:Ontology") shouldBe true
            model.ask("?s a skos:Concept") shouldBe false
        }
        "facade dispatches default and explicit profiles with scope before projection" {
            val set = xmJsonSet()
            val exporter = Exporter()
            for (models in listOf(setOf("world"), setOf("cn"))) {
                for (profile in listOf(null, "both", "owl", "skos")) {
                    val result =
                        exporter.export(
                            set,
                            null,
                            ExportRequest(models, "ttl", profile),
                        ) as ExportResult.Single
                    result.name shouldBe "cn.ttl"
                    result.contentType shouldBe "text/turtle; charset=utf-8"
                    turtleModel(result.bytes).ask("?s a ttr:Kind") shouldBe true
                }
            }
            val cn = exporter.export(set, null, ExportRequest(setOf("cn"), "ttl")) as ExportResult.Single
            val world = exporter.export(set, null, ExportRequest(setOf("world"), "ttl")) as ExportResult.Single
            cn.bytes.toList() shouldBe world.bytes.toList()
            val empty =
                exporter.export(
                    set,
                    null,
                    ExportRequest(format = "ttl", packages = emptySet()),
                ) as ExportResult.Single
            turtleModel(empty.bytes).size() shouldBe 3L
            for (model in listOf("md", "er", "db", "bindings", "lexicon", "cn2er")) {
                exporter.export(set, null, ExportRequest(setOf(model), "ttl")) shouldBe
                    ExportResult.CannotCarry("L-TTL-1")
            }
        }
        "retained AST supplies members, period flags, basis, inline lexicon and localized examples" {
            val parsed =
                org.tatrman.ttr.parser.loader.TtrLoader.parseString(
                    """
                    model cn
                    def kind Vocabulary { grounds: er.entity.codes }
                    def kind Person { lexicon: { terms: ["human"], examples: ["A person"] } }
                    def event Visit { extent: durative, period: { start: opened, end: closed, end_open: unknown }, involves: [Person] }
                    def phase Active { of: Person, basis: current_state, imprecision: certainly, members: "x > 0" }
                    def quality code { of: Person, value_space: Vocabulary }
                    def quality unbound { of: Person, value_space: Person }
                    """.trimIndent(),
                )
            parsed.errors shouldBe emptyList()
            val file =
                org.tatrman.ttr.metadata.source.LoadedFile(
                    org.tatrman.ttr.metadata.source
                        .StorageFile("cn.ttrm", 0),
                    "cn",
                    "cn",
                    emptyList(),
                    parsed.definitions,
                    "cn",
                    "cn",
                )
            val examples =
                org.tatrman.ttr.parser.loader.TtrLoader.parseString(
                    "model lexicon locale cs\ndef example person_cs { for: cn.Person, text: \"Člověk čeká\" }",
                )
            examples.errors shouldBe emptyList()
            val lexicon =
                file.copy(
                    storageFile =
                        org.tatrman.ttr.metadata.source
                            .StorageFile("cs.ttrm", 0),
                    computedPackage = "lexicon",
                    declaredPackage = "lexicon",
                    schemaCode = "lexicon",
                    namespace = "lexicon",
                    locale = "cs",
                    definitions = examples.definitions,
                )
            val set = DefinitionSet.from(listOf(file, lexicon), null, "0.18", "test").copy(world = "sample")
            val graph = DefinitionGraph.build(set)
            for (profile in TtlProfile.entries) {
                val bytes = TurtleWriter.write(graph, set, profile)
                val model = turtleModel(bytes)
                model.size() shouldBe TurtleWriter.triples(graph, set, profile).size.toLong()
                model.ask(
                    "?s ttr:memberRestriction 'x > 0' ; ttr:basis 'current_state' ; ttr:imprecision 'certainly'",
                ) shouldBe
                    true
                model.ask("?s ttr:periodStart 'opened' ; ttr:periodEnd 'closed' ; ttr:end_open 'unknown'") shouldBe true
                model.ask("?s ttr:example 'A person'@en") shouldBe true
                model.ask("?s ttr:example 'Člověk čeká'@cs") shouldBe true
                model.ask("<urn:ttr:sample:cn.quality.code> ttr:valueSpace <urn:ttr:sample:er.entity.codes>") shouldBe
                    true
                model.ask("<urn:ttr:sample:cn.quality.unbound> ttr:valueSpace <urn:ttr:sample:cn.kind.Person>") shouldBe
                    true
                if (profile.skos) {
                    model.ask(
                        "<urn:ttr:sample:cn.quality.code> skos:inScheme <urn:ttr:sample:er.entity.codes>",
                    ) shouldBe
                        true
                }
                if (profile.skos) model.ask("?s skos:altLabel 'human'@en") shouldBe true
                model.ask("?s ttr:involves [ a ttr:Participation ; ttr:concept ?c ]") shouldBe true
            }
        }
        "scope stubs preserve authored target IRIs without projecting their classes or definitions" {
            val full = ttlFixtureSet()
            val set =
                full.copy(
                    entries =
                        full.entries.map {
                            if (it.qname ==
                                "cn.kind.Prospect"
                            ) {
                                it.copy(pkg = "selected")
                            } else {
                                it
                            }
                        },
                )
            val result =
                Exporter().export(
                    set,
                    null,
                    ExportRequest(setOf("cn"), "ttl", packages = setOf("selected")),
                ) as ExportResult.Single
            val model = turtleModel(result.bytes)
            model.ask("<urn:ttr:${set.world}:cn.kind.Prospect> ttr:is_a <https://example.org/northline/Party>") shouldBe
                true
            model.ask("<https://example.org/northline/Party> ?p ?o") shouldBe false
        }
    })
