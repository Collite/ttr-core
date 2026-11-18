// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.tatrman.ttr.profile.ProfileException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** MW-P5 S5.2 T2 — the `turtle`/`rdfxml` front-ends: one subject map for one graph, whatever the syntax. */
class RdfFrontEndSpec :
    FunSpec({
        fun input(
            text: String,
            name: String,
        ): Path = Files.createTempDirectory("s52-rdf").resolve(name).also { it.writeText(text) }

        val turtle =
            """
            @prefix s: <http://www.w3.org/2004/02/skos/core#> .
            @prefix g: <https://example.org/orchard/> .
            g:Pear a s:Concept ;
                s:prefLabel "pear"@en, "hruška"@cs ;
                s:broader g:Fruit ;
                s:notation "P1" ;
                s:related g:Apple, <https://example.org/orchard#Quince> .
            g:Fruit a s:Concept ; <https://example.org/x/rank> 3 ; s:note [ s:prefLabel "aside" ] .
            """.trimIndent()

        val rdfxml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:skos="http://www.w3.org/2004/02/skos/core#" xmlns:x="https://example.org/x/">
              <skos:Concept rdf:about="https://example.org/orchard/Fruit">
                <x:rank rdf:datatype="http://www.w3.org/2001/XMLSchema#integer">3</x:rank>
                <skos:note rdf:parseType="Resource"><skos:prefLabel>aside</skos:prefLabel></skos:note>
              </skos:Concept>
              <rdf:Description rdf:about="https://example.org/orchard/Pear">
                <skos:related rdf:resource="https://example.org/orchard#Quince"/>
                <skos:related rdf:resource="https://example.org/orchard/Apple"/>
                <skos:prefLabel xml:lang="cs">hruška</skos:prefLabel>
                <skos:prefLabel xml:lang="en">pear</skos:prefLabel>
                <rdf:type rdf:resource="http://www.w3.org/2004/02/skos/core#Concept"/>
                <skos:notation>P1</skos:notation>
                <skos:broader rdf:resource="https://example.org/orchard/Fruit"/>
              </rdf:Description>
            </rdf:RDF>
            """.trimIndent()

        test(
            "a SKOS graph becomes the subject map: @id/@local, compacted predicates, objects sorted, blank nodes by content",
        ) {
            val tree = RdfFrontEnd.TURTLE.tree(turtle, "orchard.ttl")
            // ✅R350-B: a blank node's label is 16 hex digits of its content's SHA-256.
            val blank = Regex("_:[0-9a-f]{16}").find(tree)!!.value
            Json.parseToJsonElement(tree.replace(blank, "_:b0")) shouldBe
                Json.parseToJsonElement(
                    """
                    {"subjects": {
                      "_:b0": {"@id": "_:b0", "skos:prefLabel": [{"@value": "aside"}]},
                      "https://example.org/orchard/Fruit": {
                        "@id": "https://example.org/orchard/Fruit", "@local": "Fruit",
                        "rdf:type": [{"@id": "http://www.w3.org/2004/02/skos/core#Concept", "@local": "Concept"}],
                        "skos:note": [{"@id": "_:b0"}],
                        "https://example.org/x/rank": [{"@value": "3", "@type": "http://www.w3.org/2001/XMLSchema#integer"}]
                      },
                      "https://example.org/orchard/Pear": {
                        "@id": "https://example.org/orchard/Pear", "@local": "Pear",
                        "rdf:type": [{"@id": "http://www.w3.org/2004/02/skos/core#Concept", "@local": "Concept"}],
                        "skos:broader": [{"@id": "https://example.org/orchard/Fruit", "@local": "Fruit"}],
                        "skos:notation": [{"@value": "P1"}],
                        "skos:prefLabel": [{"@value": "hruška", "@language": "cs"}, {"@value": "pear", "@language": "en"}],
                        "skos:related": [
                          {"@id": "https://example.org/orchard#Quince", "@local": "Quince"},
                          {"@id": "https://example.org/orchard/Apple", "@local": "Apple"}
                        ]
                      }
                    }}
                    """,
                )
            // Order is part of the contract: subjects bytewise, predicates by full IRI after @id/@local.
            val pear = tree.substringAfter("\"https://example.org/orchard/Pear\": {")
            listOf("@id", "@local", "rdf:type", "skos:broader", "skos:notation", "skos:prefLabel", "skos:related")
                .map { pear.indexOf("\"$it\"") }
                .zipWithNext { a, b -> a < b }
                .all { it } shouldBe true
            (
                Json
                    .parseToJsonElement(tree)
                    .jsonObject
                    .getValue("subjects")
                    .jsonObject.keys
                    .toList()
            ) shouldBe
                listOf(blank, "https://example.org/orchard/Fruit", "https://example.org/orchard/Pear")
        }

        test("the same graph as RDF/XML yields the same tree text, byte for byte") {
            val fromTurtle =
                RdfFrontEnd.TURTLE
                    .parse(listOf(input(turtle, "g.ttl")))
                    .single()
                    .root.value
            val fromXml =
                RdfFrontEnd.RDFXML
                    .parse(listOf(input(rdfxml, "g.rdf")))
                    .single()
                    .root.value
            fromXml shouldBe fromTurtle
            RdfFrontEnd.RDFXML.parse(listOf(input(rdfxml, "g.rdf"))).single().let {
                it.file shouldBe "g.rdf"
                it.label shouldBe "g.rdf#subjects"
            }
            RdfFrontEnd.RDFXML.tree(rdfxml, "g") shouldBe RdfFrontEnd.TURTLE.tree(turtle, "g")
        }

        test("relative IRIs resolve against file:/// whatever the file is called (review-350 F3)") {
            listOf("local.ttl", "trade-2026-11.ttl", "a#b.ttl", "50%.ttl", "x[1].ttl", "Trade Glossary.ttl").forEach {
                val tree = RdfFrontEnd.TURTLE.tree("<#Plum> a <#Fruit> .", it)
                tree shouldContain "\"@id\": \"file:///#Plum\""
                tree shouldContain "\"@local\": \"Plum\""
            }
        }

        test("blank nodes: the same labels whatever the syntax and statement order; a cycle is refused (✅R350-B)") {
            val a = "https://example.org/g/A"
            val b = "https://example.org/g/B"
            val turtleAFirst =
                "@prefix s: <http://www.w3.org/2004/02/skos/core#> .\n" +
                    "<$a> s:note [ s:prefLabel \"x\" ; s:note [ s:prefLabel \"deep\" ] ] .\n" +
                    "<$b> s:note [ s:prefLabel \"y\" ] .\n"
            val xmlBFirst =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:s="http://www.w3.org/2004/02/skos/core#">
                  <rdf:Description rdf:about="$b">
                    <s:note rdf:parseType="Resource"><s:prefLabel>y</s:prefLabel></s:note>
                  </rdf:Description>
                  <rdf:Description rdf:about="$a">
                    <s:note rdf:parseType="Resource">
                      <s:note rdf:parseType="Resource"><s:prefLabel>deep</s:prefLabel></s:note>
                      <s:prefLabel>x</s:prefLabel>
                    </s:note>
                  </rdf:Description>
                </rdf:RDF>
                """.trimIndent()
            val tree = RdfFrontEnd.TURTLE.tree(turtleAFirst, "g")
            RdfFrontEnd.RDFXML.tree(xmlBFirst, "g") shouldBe tree
            Regex("\"(_:[0-9a-f]{16})\": \\{").findAll(tree).count() shouldBe 3

            shouldThrow<ProfileException> {
                RdfFrontEnd.TURTLE.tree(
                    "@prefix s: <http://www.w3.org/2004/02/skos/core#> .\n_:x s:related _:y .\n_:y s:related _:x .\n",
                    "loop.ttl",
                )
            }.let {
                it.code shouldBe "ys/profile-invalid"
                it.location.file shouldBe "loop.ttl#subjects"
                it.detail shouldContain "skos:related → skos:related"
            }
        }

        test("✅R350-C: a 20,000-item RDF list (skos:memberList) labels its blank nodes without overflowing the stack") {
            val members = (0 until 20_000).joinToString(" ") { "g:C$it" }
            val tree =
                RdfFrontEnd.TURTLE.tree(
                    "@prefix s: <http://www.w3.org/2004/02/skos/core#> .\n@prefix g: <https://example.org/g/> .\n" +
                        "g:List a s:OrderedCollection ; s:memberList ( $members ) .\n",
                    "list.ttl",
                )
            val subjects =
                Json
                    .parseToJsonElement(tree)
                    .jsonObject
                    .getValue("subjects")
                    .jsonObject
            subjects.keys.count { it.startsWith("_:") } shouldBe 20_000
            subjects.keys.filter { it.startsWith("_:") }.all { Regex("_:[0-9a-f]{16}").matches(it) } shouldBe true
        }

        test("a language tag is written in the TTR locale form: en-GB → en_gb, zh-Hans → zh_hans (✅D-MW-87 item 5)") {
            val tree =
                RdfFrontEnd.TURTLE.tree(
                    "<https://x/C> <http://www.w3.org/2004/02/skos/core#prefLabel> \"lorry\"@en-GB, \"货车\"@zh-Hans .",
                    "t.ttl",
                )
            tree shouldContain "\"@language\": \"en_gb\""
            tree shouldContain "\"@language\": \"zh_hans\""
        }

        test("a syntax error is ys/profile-invalid at the parser's line and column, labelled by the file name") {
            shouldThrow<ProfileException> {
                RdfFrontEnd.TURTLE.parse(listOf(input("@prefix s: <http://x/> .\ns:a s:b s:c s:d .\n", "bad.ttl")))
            }.let {
                it.code shouldBe "ys/profile-invalid"
                it.location.file shouldBe "bad.ttl"
                (it.location.line to it.location.column) shouldBe (2 to 13)
            }
            shouldThrow<ProfileException> {
                RdfFrontEnd.RDFXML.parse(
                    listOf(input("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<x", "bad.rdf")),
                )
            }.location.file shouldBe "bad.rdf"
        }
    })
