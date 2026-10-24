// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.tatrman.ttr.metadata.export.xmi.*
import org.w3c.dom.Document
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

internal fun xmlDocument(bytes: ByteArray): Document =
    DocumentBuilderFactory
        .newInstance()
        .apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        .parse(bytes.inputStream())

internal fun Document.count(path: String): Int =
    (XPathFactory.newInstance().newXPath().evaluate("count($path)", this, XPathConstants.NUMBER) as Double).toInt()

internal fun Document.value(path: String): String = XPathFactory.newInstance().newXPath().evaluate(path, this)

internal fun xmiGolden(
    name: String,
    bytes: ByteArray,
) {
    val path = Path.of("src/test/resources/xm-goldens/xmi/$name")
    if (System.getProperty("updateGolden") == "true") {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
    bytes.toList() shouldBe Files.readAllBytes(path).toList()
}

private fun expectedEaId(iri: String): String {
    // Independent RFC computation in the shape oracle, without calling the writer's helper.
    val ns = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
    val bytes =
        ByteBuffer
            .allocate(16)
            .putLong(ns.mostSignificantBits)
            .putLong(ns.leastSignificantBits)
            .array()
    val digest = MessageDigest.getInstance("SHA-1").digest(bytes + iri.toByteArray(Charsets.UTF_8))
    digest[6] = (digest[6].toInt().and(15).or(80)).toByte()
    digest[8] = (digest[8].toInt().and(63).or(128)).toByte()
    val buffer = ByteBuffer.wrap(digest)
    return "EAID_" + UUID(buffer.long, buffer.long).toString().uppercase().replace('-', '_')
}

private fun verifyXmiReferences(doc: Document) {
    val all = doc.getElementsByTagName("*")
    val elements = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }
    val ids = elements.map { it.getAttributeNS(XmiWriter.XMI, "id") }.filter { it.isNotEmpty() }
    ids.distinct().size shouldBe ids.size
    for (e in elements) {
        for (i in 0 until e.attributes.length) {
            val a = e.attributes.item(i)
            if (a.nodeValue.startsWith("EAID_") && a.nodeName != "xmi:id") (a.nodeValue in ids) shouldBe true
        }
    }
}

class XmiGoldenSpec :
    StringSpec({
        "world XMI golden and deterministic reversed inputs" {
            val set = xmJsonSet()
            val graph = DefinitionGraph.build(set)
            val bytes = XmiWriter.write(graph, set)
            xmiGolden("xm-world.xmi", bytes)
            XmiWriter
                .write(
                    graph.copy(nodes = graph.nodes.reversed(), edges = graph.edges.reversed()),
                    set.copy(entries = set.entries.reversed()),
                ).toList() shouldBe
                bytes.toList()
        }
    })

class XmiShapeSpec :
    StringSpec({
        "UML model, stereotypes, EA tags and connector shapes" {
            val set = xmJsonSet()
            val graph = DefinitionGraph.build(set)
            val doc = xmlDocument(XmiWriter.write(graph, set))
            doc.count("//*[local-name()='Model']") shouldBe 1
            doc.count("//diagrams") shouldBe 0
            verifyXmiReferences(doc)
            doc.count("//packagedElement[@*[local-name()='type']='uml:Package']") shouldBe 5
            doc.count("//packagedElement[@*[local-name()='type']='uml:PrimitiveType']") shouldBe 4
            doc.count("//*[local-name()='concept' and @cnKind='relation_type']") shouldBe 1
            doc.count("//*[local-name()='concept' and @synonyms='cs:osoba;cs:partner;en:partner;en:party']") shouldBe 1
            doc.count("//*[local-name()='entity' and @base_Class]") shouldBe 3
            doc.count("//element/properties[@stereotype='entity']") shouldBe 3
            doc.count("//ownedAttribute") shouldBe graph.nodes.count { it.kind in setOf("attribute", "column") }
            doc.count("//generalization[@general]") shouldBe graph.edges.count { it.kind == "is_a" }
            doc.count("//*[local-name()='grounds' and @layer and @via]") shouldBe
                graph.edges.count { it.kind in setOf("grounds", "er2db", "md2db", "md2er") }
            doc.count("//*[local-name()='metric' and @aggregation='sum']") shouldBe 3
            doc.count(
                "//*[local-name()='metric' and @additivity='semiAdditive' and @validBy='Calendar.day']",
            ) shouldBe 1
            doc.count("//connector/properties[@stereotype='involves']/../tags/tag[@name='label']") shouldBe
                graph.edges.count { it.kind == "involves" && it.label != null }
            val assoc = "//packagedElement[@name='sale_buyer' and @*[local-name()='type']='uml:Association']"
            doc.count("$assoc") shouldBe 2
            doc.count("$assoc/memberEnd") shouldBe 4
            doc.count("$assoc/ownedEnd/lowerValue[@value='0']") shouldBe 2
            doc.count("$assoc/ownedEnd/upperValue[@value='*']") shouldBe 2
            for (node in graph.nodes.filter { !it.external && it.model in setOf("cn", "md", "er", "db") }) {
                val id = expectedEaId(requireNotNull(node.iri))
                doc.count("//*[@*[local-name()='id']='$id']") shouldBe 1
            }
            val ids =
                doc.getElementsByTagName("*").let { all ->
                    (0 until all.length)
                        .map {
                            all.item(it) as org.w3c.dom.Element
                        }.mapNotNull { it.getAttributeNS(XmiWriter.XMI, "id").takeIf(String::isNotEmpty) }
                }
            val semanticIris =
                Files
                    .readAllLines(Path.of("src/test/resources/xm-goldens/xmi/xm-world-iris.txt"))
                    .filterNot { it.startsWith("#") || it.isBlank() }
            // The manifest was independently derived from the graph contract, not emitted by XmiWriter.
            ids.toSet() shouldBe semanticIris.map { expectedEaId(it) }.toSet()
            ids.distinct().size shouldBe ids.size
            ids.all {
                it.matches(
                    Regex("EAID_[0-9A-F]{8}_[0-9A-F]{4}_5[0-9A-F]{3}_[89AB][0-9A-F]{3}_[0-9A-F]{12}"),
                )
            } shouldBe
                true
        }
        "scoped model targets remain references without importing their bodies" {
            val set = xmJsonSet()
            for (models in listOf(setOf("cn"), setOf("md"), setOf("er"), setOf("db"), setOf("bindings"))) {
                val result =
                    Exporter().export(
                        set,
                        null,
                        ExportRequest(models = models, format = "xmi"),
                    ) as ExportResult.Single
                verifyXmiReferences(xmlDocument(result.bytes))
            }
            val scope = ExportScope(packages = setOf("cn"))
            val graph = scope.apply(DefinitionGraph.build(set), set)
            val doc = xmlDocument(XmiWriter.write(graph, set))
            verifyXmiReferences(doc)
            doc.count("//*[local-name()='entity']") shouldBe 0
            doc.count("//element/properties[@documentation='A trading party']") shouldBe 1
            val partyId = expectedEaId("https://example.org/northline/Party")
            doc.count("//*[@*[local-name()='id']='$partyId']") shouldBe 1
            val empty =
                Exporter().export(
                    set,
                    null,
                    ExportRequest(format = "xmi", packages = emptySet()),
                ) as ExportResult.Single
            xmlDocument(empty.bytes).count("//packagedElement") shouldBe 0
        }
        "XML escaping preserves localized synonyms and English documentation exactly" {
            val content = "Quote \" & <tag>\nline\tend\rČ 🦊"
            val set = DefinitionSet(emptyList(), emptyMap(), "tiny", "0.18", "s1")
            val node =
                Node(
                    "cn.kind.Fox",
                    "https://example.org/tiny#Fox",
                    "cn",
                    "kind",
                    label = "Fox",
                    pkg = "cn",
                    description =
                        mapOf(
                            "en" to content,
                        ),
                    synonyms = mapOf("cs" to listOf(content)),
                )
            val doc = xmlDocument(XmiWriter.write(DefinitionGraph(listOf(node), emptyList()), set))
            doc.value("//element/properties/@documentation") shouldBe content
            doc.value("//*[local-name()='concept']/@synonyms") shouldBe "cs:$content"
            shouldThrow<IllegalArgumentException> {
                XmiWriter.write(DefinitionGraph(listOf(node.copy(label = "bad\u0001")), emptyList()), set)
            }
        }
        "out-of-scope domain types are placeholders and duplicate IRIs fail clearly" {
            val set = xmJsonSet()
            val graph = DefinitionGraph.build(set)
            val partial = graph.copy(nodes = graph.nodes.filter { it.id != "md.domain.Money" })
            val doc = xmlDocument(XmiWriter.write(partial, set))
            verifyXmiReferences(doc)
            val domainId = expectedEaId("urn:ttr:northline:md.domain.Money")
            doc.count("//packagedElement[@*[local-name()='id']='$domainId']") shouldBe 1
            doc.count("//*[local-name()='domain']") shouldBe 0
            doc.count("//ownedAttribute[@type='$domainId']") shouldBe 3
            val concept = graph.nodes.first { it.id == "cn.kind.Party" }
            shouldThrow<IllegalArgumentException> {
                XmiWriter.write(graph.copy(nodes = graph.nodes + concept.copy(id = "cn.kind.Duplicate")), set)
            }
        }
        "facade defaults, multi-model single document and cannot-carry" {
            val set = xmJsonSet()
            val exporter = Exporter()
            val result = exporter.export(set, null, ExportRequest(format = "xmi")) as ExportResult.Single
            result.name shouldBe "model.xmi"
            xmlDocument(result.bytes).count("//*[local-name()='Model']") shouldBe 1
            val multi =
                exporter.export(
                    set,
                    null,
                    ExportRequest(models = setOf("er", "md"), format = "xmi"),
                ) as ExportResult.Single
            xmlDocument(multi.bytes).count("//*[local-name()='Model']") shouldBe 1
            exporter.export(set, null, ExportRequest(format = "xmi", profile = "ea-erd/1")) shouldBe
                ExportResult.NotBuilt
            exporter.export(set, null, ExportRequest(models = setOf("lexicon"), format = "xmi")) shouldBe
                ExportResult.CannotCarry("L-XMI-2")
        }
    })

class TtrMdgTechnologySpec :
    StringSpec({
        "Sparx reference shape and every declared stereotype and typed tag" {
            val profile = TtrMdgProfile.load()
            val bytes = TtrMdgTechnology.render(profile)
            xmiGolden("ttr-mdg.xml", bytes)
            val sample =
                xmlDocument(Files.readAllBytes(Path.of("src/test/resources/xmi/sparx-sample/testing-technology.xml")))
            (
                sample.count(
                    "/MDG.Technology/UMLProfiles/UMLProfile/Content/Stereotypes/Stereotype/AppliesTo/Apply",
                ) > 0
            ) shouldBe
                true
            val doc = xmlDocument(bytes)
            for (st in profile.stereotypes) {
                val path = "/MDG.Technology/UMLProfiles/UMLProfile/Content/Stereotypes/Stereotype[@name='${st.name}']"
                doc.count("$path/AppliesTo/Apply[@type='${st.metaclass}']") shouldBe 1
                for (tag in st.tags) {
                    doc.count("$path/TaggedValues/Tag[@name='${tag.name}' and @type='${tag.type}']") shouldBe
                        1
                }
            }
        }
    })

class Uuid5Spec :
    StringSpec({
        "RFC namespace DNS reference vector" {
            Uuid5.of(Uuid5.DNS, "www.example.com").toString() shouldBe "2ed6657d-e927-568b-95e1-2665a8aea6a2"
        }
    })
