// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.concept.DefinitionFixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * CN-P5 S5.2 T1 — the published `cn-candidate.v1.schema.json` and the Kotlin reader ([CnCandidates]) must agree, the
 * lexicon's `SchemaEquivalenceSpec` pattern: the schema is what a producer outside the JVM (a document pipeline, an
 * evidence miner) validates against; the reader is what the store enforces at `submit`, written by hand so ttr-concept
 * ships no JSON-schema stack (networknt is test scope). Every fixture runs through BOTH:
 * - `examples/` and `valid/` — both accept;
 * - `invalid/` — both reject, except the `kotlin-only-*` ones, which the schema knowingly passes (a calendar-impossible
 *   date, a span whose end does not exceed its start, a term naming its own candidate) and Kotlin must not.
 *
 * The fixture lists are the directories: a new file is checked without anyone remembering to list it.
 */
class CandidateSchemaSpec :
    FunSpec({
        val root = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/candidates")
        val schema =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                requireNotNull(CnCandidates::class.java.getResourceAsStream("/schemas/cn-candidate.v1.schema.json"))
                    .reader()
                    .readText(),
            )
        val mapper = ObjectMapper()

        fun files(dir: String): List<Path> =
            Files.list(root.resolve(dir)).use { s -> s.filter { it.extension == "json" }.sorted().toList() }

        fun schemaAccepts(text: String): Boolean = schema.validate(mapper.readTree(text)).isEmpty()

        test("the corpus is there") {
            files("examples").size shouldBe 4
            files("valid").size shouldBeGreaterThan 5
            files("invalid").size shouldBeGreaterThan 20
        }

        context("examples and valid fixtures: the schema and the reader both accept") {
            for (f in files("examples") + files("valid")) {
                test("${f.parent.name}/${f.name}") {
                    val text = f.readText()
                    schemaAccepts(text) shouldBe true
                    CnCandidates.parse(text).shouldBeInstanceOf<CandidateLoad.Ok>()
                }
            }
        }

        context("invalid fixtures: the schema and the reader both reject") {
            for (f in files("invalid").filterNot { it.name.startsWith("kotlin-only-") }) {
                test(f.nameWithoutExtension) {
                    val text = f.readText()
                    schemaAccepts(text) shouldBe false
                    CnCandidates
                        .parse(text)
                        .shouldBeInstanceOf<CandidateLoad.Rejected>()
                        .violations
                        .shouldNotBeEmpty()
                }
            }
        }

        context("Kotlin-only rules: the schema knowingly passes them, the reader refuses") {
            val expected =
                mapOf(
                    "kotlin-only-calendar-date" to "timestamp",
                    "kotlin-only-reversed-span" to "source-ref",
                    "kotlin-only-term-targets-itself" to "self-reference",
                )
            files("invalid").map { it.nameWithoutExtension }.filter { it.startsWith("kotlin-only-") }.toSet() shouldBe
                expected.keys
            for ((name, rule) in expected) {
                test(name) {
                    val text = root.resolve("invalid/$name.json").readText()
                    schemaAccepts(text) shouldBe true
                    CnCandidates
                        .parse(text)
                        .shouldBeInstanceOf<CandidateLoad.Rejected>()
                        .violations
                        .map { it.rule } shouldBe listOf(rule)
                }
            }
        }

        test("every violation in a candidate is reported, each with its path") {
            val text =
                """{"id":"nope","proposal":{"concept":{"stereo":"role","name":"9x"}},"status":"done","extra":1}"""
            val v = CnCandidates.parse(text).shouldBeInstanceOf<CandidateLoad.Rejected>().violations
            v.map { it.path }.toSet() shouldBe
                setOf(
                    "/id",
                    "/proposal/concept/name",
                    "/proposal/concept/event",
                    "/provenance",
                    "/status",
                    "/extra",
                )
        }

        test("not JSON, or not an object, is one violation at the root") {
            for (text in listOf("{", "[]", "\"candidate\"")) {
                CnCandidates
                    .parse(text)
                    .shouldBeInstanceOf<CandidateLoad.Rejected>()
                    .violations
                    .map { it.path } shouldBe listOf("")
            }
        }
    })
