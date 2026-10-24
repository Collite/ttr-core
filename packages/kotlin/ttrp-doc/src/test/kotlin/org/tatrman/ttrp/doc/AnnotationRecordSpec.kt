// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.tatrman.ttrp.doc.annotations.*

class AnnotationRecordSpec :
    FunSpec({
        val record =
            AnnotationRecord(
                "h1.md",
                "sha",
                listOf(
                    Section("section", listOf(0, 10), title = "Orders"),
                    Sentence("sentence", listOf(11, 20), text = "Consider orders."),
                    Mention(
                        "m1",
                        listOf(11, 12),
                        objectRef = "er.entity.order",
                        lexiconMatch = "order",
                        confidence = 1.0,
                    ),
                    Mention(
                        "m2",
                        listOf(12, 13),
                        objectRef = "er.entity.inventory",
                        lexiconMatch = "stock",
                        confidence = 0.9,
                    ),
                    Mention(
                        "m3",
                        listOf(13, 14),
                        objectRef = "er.entity.invoice",
                        lexiconMatch = "invoice",
                        confidence = 0.9,
                    ),
                    Coref("coref", listOf(14, 15), mention = "m1"),
                    Role("role", listOf(11, 20), role = "candidates", container = "placed_orders"),
                    Parameter("p1", listOf(16, 17), name = "tolerance", value = "0.01", valueType = "decimal"),
                    Parameter("p2", listOf(17, 18), name = "threshold", value = "50000", valueType = "int"),
                    Person("person", listOf(18, 19), role = "Sales"),
                    Block("block", listOf(21, 40), nodes = listOf("placed_orders")),
                    Link("link", listOf(21, 40), block = "block", sentences = listOf("sentence")),
                    Inexpressible(
                        "escape",
                        listOf(41, 50),
                        why = "needs a restatement",
                        escapes = listOf(Escape.RESTATEMENT),
                    ),
                    Verdict("verdict", listOf(21, 40), target = "link", accepted = false, producer = "human@reviewer"),
                ),
            )
        test("all eleven AU types round trip and records are immutable per document SHA") {
            AnnotationRecord.load(record.toJson(), "sha") shouldBe record
            shouldThrow<IllegalArgumentException> { AnnotationRecord.load(record.toJson(), "other-sha") }
        }
        test("the published JSON schema validates the handwritten AU record") {
            val root =
                generateSequence(
                    java.nio.file.Path
                        .of("")
                        .toAbsolutePath(),
                ) { it.parent }
                    .first {
                        java.nio.file.Files
                            .isDirectory(it.resolve("docs/features/ttr-p"))
                    }
            val schema =
                com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                    .getSchema(
                        root.resolve("docs/features/ttr-p/architecture/schemas/annotation-record.schema.json").toUri(),
                    )
            schema
                .validate(
                    com.fasterxml.jackson.databind
                        .ObjectMapper()
                        .readTree(record.toJson()),
                ).isEmpty() shouldBe true
        }
        test("the handwritten H-1 record covers every real container and validates against the schema") {
            val fixture =
                java.nio.file.Path.of(
                    "src/test/resources/docs/annotation-examples/releasing-a-placed-order.json",
                )
            val text =
                java.nio.file.Files
                    .readString(fixture)
            val sha =
                org.tatrman.ttrp.doc.DocExtractor
                    .extract(
                        java.nio.file.Files.readString(
                            java.nio.file.Path
                                .of("src/test/resources/docs/releasing-a-placed-order.md"),
                        ),
                        "releasing-a-placed-order.md",
                    ).recipes
                    .single()
                    .sidecar.documentSha
            val actual = AnnotationRecord.load(text, sha)
            val schema =
                com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                    .getSchema(requireNotNull(javaClass.getResourceAsStream("/annotation-record.schema.json")))
            schema
                .validate(
                    com.fasterxml.jackson.databind
                        .ObjectMapper()
                        .readTree(text),
                ).isEmpty() shouldBe true
            actual.annotations
                .filterIsInstance<Role>()
                .map { it.container }
                .toSet() shouldBe
                setOf("placed_orders", "shortfall", "price_differences", "overdue", "verdict")
            actual.annotations.filterIsInstance<Mention>().size shouldBe 3
            actual.annotations.filterIsInstance<Parameter>().size shouldBe 2
        }
        test("human rejection removes only the rejected sentence-block association") {
            val side =
                DocSidecar(
                    "h1.md",
                    "sha",
                    mapOf(
                        "placed_orders" to DocNode(0, listOf(21, 40), listOf(11, 20), listOf(0, 5)),
                    ),
                    listOf(DocSentence(listOf(11, 20), listOf("placed_orders"))),
                )
            record
                .applyVerdicts(side)
                .sentences
                .single()
                .nodes shouldBe emptyList()
            record.applyVerdicts(side).nodes shouldBe side.nodes
        }
    })
