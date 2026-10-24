// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.export.rdf.*

class TurtleSerializerSpec :
    StringSpec({
        "Turtle escaping round-trips control characters, quotes, Unicode and datatypes" {
            val values =
                listOf("quote \" and backslash \\", "first\nsecond\"\"\"", "tab\tcr\rcontrol\u0001", "Čeština 🦉", "")
            val sink = TripleSink()
            val subject = Iri("https://example.org/space%20here/trailing.")
            val predicate = Iri(Vocabulary.TTR + "definition")
            for (value in values) sink.add(subject, predicate, Literal(value))
            sink.add(
                subject,
                Iri(Vocabulary.TTR + "abstract"),
                Literal(
                    "true",
                    datatype =
                        Iri(
                            Vocabulary.XSD + "boolean",
                        ),
                ),
            )
            sink.add(subject, Iri(Vocabulary.TTR + "label"), Literal("osoba", "cs-CZ"))
            sink.add(
                subject,
                Iri(Vocabulary.TTR + "imprecision"),
                Literal(
                    "4",
                    datatype =
                        Iri(
                            Vocabulary.XSD + "integer",
                        ),
                ),
            )
            val bytes = TurtleSerializer.write(sink, "sample")
            val model = turtleModel(bytes)
            model.size() shouldBe sink.size.toLong()
            val actual =
                model
                    .listObjectsOfProperty(model.createProperty(predicate.value))
                    .toList()
                    .map {
                        it.asLiteral().lexicalForm
                    }.toSet()
            actual shouldBe values.toSet()
            model
                .getProperty(
                    model.createResource(subject.value),
                    model.createProperty(Vocabulary.TTR + "label"),
                ).literal.language
                .lowercase() shouldBe
                "cs-cz"
            val output = bytes.decodeToString()
            output.contains("\"\"\"first\n") shouldBe true
            output.contains("\"quote \\\" and backslash") shouldBe true
            output.contains("ttr:abstract true") shouldBe true
            output.contains("\\u0001") shouldBe true
            output.contains('\r') shouldBe false
            shouldThrow<IllegalArgumentException> { Literal("x", "en . <bad>") }
            shouldThrow<IllegalArgumentException> { Literal("x", "en", Iri(Vocabulary.XSD + "string")) }
        }
        "RDF literal identity deduplicates language case and explicit xsd string" {
            val sink = TripleSink()
            val subject = Iri("urn:ttr:sample:example")
            val predicate = Iri(Vocabulary.TTR + "label")
            for (value in listOf(
                Literal("x", "EN"),
                Literal("x", "en"),
                Literal("text"),
                Literal(
                    "text",
                    datatype =
                        Iri(
                            Vocabulary.XSD + "string",
                        ),
                ),
            )) {
                sink.add(subject, predicate, value)
            }
            sink.size shouldBe 2
            turtleModel(TurtleSerializer.write(sink, "sample")).size() shouldBe 2L
        }
        "inline blank nodes keep three triples, deduplicate and repeat byte-for-byte" {
            val sink = TripleSink()
            val subject = Iri("urn:ttr:sample:cn.event.Event")
            val blank = Blank("participation")
            sink.add(subject, Iri(Vocabulary.TTR + "involves"), blank)
            sink.add(blank, Iri(Vocabulary.RDF + "type"), Iri(Vocabulary.TTR + "Participation"))
            sink.add(blank, Iri(Vocabulary.TTR + "concept"), Iri("https://example.org/Person"))
            sink.add(blank, Iri(Vocabulary.TTR + "concept"), Iri("https://example.org/Person"))
            val bytes = TurtleSerializer.write(sink, "sample")
            sink.size shouldBe 3
            turtleModel(bytes).size() shouldBe 3L
            val reversed = TripleSink()
            sink.sorted().reversed().forEach { reversed.add(it.subject, it.predicate, it.obj) }
            TurtleSerializer.write(reversed, "sample").toList() shouldBe bytes.toList()
            val orphan = TripleSink()
            orphan.add(blank, Iri(Vocabulary.TTR + "concept"), blank)
            shouldThrow<IllegalArgumentException> { TurtleSerializer.write(orphan, "sample") }
            sink.add(subject, Iri(Vocabulary.TTR + "other"), blank)
            shouldThrow<IllegalArgumentException> { TurtleSerializer.write(sink, "sample") }
        }
    })
