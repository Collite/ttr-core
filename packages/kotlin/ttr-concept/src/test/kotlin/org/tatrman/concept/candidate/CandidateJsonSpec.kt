// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.concept.DefinitionFixtures
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * CN-P5 S5.2 T1 — the Kotlin model ([CnCandidate]) and its JSON: every accepted fixture reads into the model and writes
 * back as the same JSON (nothing dropped, nothing invented), and the written form is canonical — one key order, so the
 * store's copy of a candidate is byte-stable.
 */
private fun byValue(e: JsonElement): JsonElement =
    when (e) {
        is JsonObject -> JsonObject(e.mapValues { byValue(it.value) })
        is JsonArray -> JsonArray(e.map(::byValue))
        is JsonPrimitive ->
            if (e.isString || e.content == "true" || e.content == "false" || e.content == "null") {
                e
            } else {
                JsonPrimitive(
                    e.content
                        .toBigDecimal()
                        .stripTrailingZeros()
                        .toPlainString(),
                )
            }
    }

class CandidateJsonSpec :
    FunSpec({
        val root = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/candidates")
        val accepted =
            listOf("examples", "valid").flatMap { dir ->
                Files.list(root.resolve(dir)).use { s -> s.filter { it.extension == "json" }.sorted().toList() }
            }

        fun read(text: String): CnCandidate = CnCandidates.parse(text).shouldBeInstanceOf<CandidateLoad.Ok>().candidate

        for (f in accepted) {
            test("round trip: ${f.parent.name}/${f.name}") {
                val text = f.readText()
                val candidate = read(text)
                // Numbers by value: an integral count written `14.0` reads back as 14 (JSON Schema's integer).
                byValue(CnCandidates.toJson(candidate)) shouldBe byValue(Json.parseToJsonElement(text))
                read(CnCandidates.render(candidate)) shouldBe candidate
                CnCandidates.render(read(CnCandidates.render(candidate))) shouldBe CnCandidates.render(candidate)
            }
        }

        test("the evidence phase reads into the model it says") {
            val c = read(root.resolve("examples/evidence-phase.json").readText())
            c.status shouldBe Status.PROPOSED
            val p = c.proposal.shouldBeInstanceOf<Proposal.Concept>()
            p.stereo shouldBe Stereo.PHASE
            p.name shouldBe "IschemicPatient"
            p.of shouldBe "cn.Patient"
            p.basis shouldBe null
            p.label shouldBe
                mapOf("en" to "Ischemic heart disease patient", "cs" to "Pacient s ischemickou chorobou srdeční")
            c.provenance.producer shouldBe "clinic:evidence"
            c.provenance.confidence shouldBe CandidateConfidence(0.82, "clinic:evidence", "code-set-frequency")
            val e = c.provenance.evidence!!
            e.questionCount shouldBe 14
            e.documentCount shouldBe 9
            e.examples.map { it.kind } shouldBe
                listOf(ExampleKind.QUESTION, ExampleKind.DOCUMENT, ExampleKind.PROCESSOR_OUTPUT)
        }

        test("the canonical form sorts label locales, so two equal candidates render the same bytes (review-133 F14)") {
            val c = read(root.resolve("examples/evidence-phase.json").readText())
            val p = c.proposal as Proposal.Concept
            val flipped =
                c.copy(
                    proposal =
                        p.copy(
                            label =
                                p.label.entries
                                    .reversed()
                                    .associate { it.toPair() },
                        ),
                )
            flipped shouldBe c
            CnCandidates.render(flipped) shouldBe CnCandidates.render(c)
        }

        test("the canonical form writes keys in the schema's order, whatever order they were read in") {
            val shuffled =
                """{"status":"proposed","provenance":{"created_at":"2026-10-23T09:14:00Z",""" +
                    """"confidence":{"method":"steward","producer":"manual:a.steward","value":1.0},""" +
                    """"producer":"manual:a.steward"},"proposal":{"relation":{"to":"cn.Item","from":"cn.Warehouse",""" +
                    """"type_name":"stocks"}},"id":"6f1d2c3a-0b4e-4f7a-9c21-3d5e8a1b7c40"}"""
            CnCandidates
                .render(read(shuffled))
                .lines()
                .first { it.contains(':') }
                .trim() shouldBe
                "\"id\": \"6f1d2c3a-0b4e-4f7a-9c21-3d5e8a1b7c40\","
            val rendered = CnCandidates.render(read(shuffled))
            (rendered.indexOf("\"type_name\"") < rendered.indexOf("\"from\"")) shouldBe true
        }
    })
