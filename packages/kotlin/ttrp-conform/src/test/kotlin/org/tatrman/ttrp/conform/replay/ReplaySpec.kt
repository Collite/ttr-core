// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

class ReplaySpec :
    StringSpec({
        val golden =
            """
            case: release
            recipe: release_order
            world: us
            as_of: "2026-11-02"
            parameters: { tolerance: 0.10000000000000000001 }
            keys: [4711]
            expected:
              verdicts: [{ key: 4711, route: release, verdict_reason: "released {tolerance}" }]
              actions: []
              details: [{key: 4711, route: release, context: stock, rows: [{amount: 0.10000000000000000001, at: "2026-11-02T10:00:00.123456789+02:00", memo: null}]}]
              final_states: { entity: er.entity.order, attr: { state: {4711: released} } }
            """.trimIndent()
        val pin = "\npinned_from: {run: r1, at: \"2026-11-02T08:00:00Z\", by: bora}\n"
        "goldens retain exact decimal text, world and pin provenance" {
            val seed = FixtureLoader.parse(golden)
            seed.seed shouldBe true
            seed.world shouldBe "us"
            seed.parameters["tolerance"].toString() shouldBe "0.10000000000000000001"
            FixtureLoader.parse(golden + pin).seed shouldBe false
        }
        "replays load per-recipe collections and all candidates" {
            val f =
                FixtureLoader.parse(
                    """
                    recipes: [release_order, settle]
                    as_of: "2026-11-02T08:00:00Z"
                    all: true
                    expected:
                      verdicts: {release_order: [], settle: []}
                      actions: {release_order: [], settle: []}
                    """.trimIndent(),
                )
            f.recipes shouldBe listOf("release_order", "settle")
            f.keys shouldBe null
            f.expected.getValue("settle").actions shouldBe emptyList()
        }
        "malformed fields, duplicate YAML keys and unsafe tags fail by name" {
            shouldThrow<Exception> {
                FixtureLoader.parse(
                    golden.replace("keys: [4711]", "keys: nope"),
                )
            }.message shouldContain
                "keys"
            shouldThrow<Exception> {
                FixtureLoader.parse(
                    golden.lines().filterNot { it.startsWith("as_of:") }.joinToString("\n"),
                )
            }.message shouldContain
                "as_of"
            shouldThrow<Exception> { FixtureLoader.parse(golden + "\nkeys: [4712]") }.message shouldContain "duplicate"
            shouldThrow<Exception> { FixtureLoader.parse(golden.replace("verdict_reason:", "reason_typo:")) }
            shouldThrow<Exception> { FixtureLoader.parse(golden.replace("details:", "details_typo:")) }
            shouldThrow<Exception> { FixtureLoader.parse(golden.replace("actions: []", "actions: wrong")) }
            shouldThrow<Exception> { FixtureLoader.parse(golden.replace("4711]", "!!java.net.URL [4711]")) }
        }
        "multisets compare exact decimals, filled reasons, UTC microseconds, detail rows and final states" {
            val f = FixtureLoader.parse(golden + pin)
            val expected = f.expected.getValue("release_order")
            val verdict =
                JsonObject(
                    expected.verdicts.single() + ("verdict_reason" to JsonPrimitive("released 0.10000000000000000001")),
                )
            val details =
                Json
                    .parseToJsonElement(
                        """[{"key":4711.0,"route":"release","context":"stock","rows":[{"memo":null,"at":"2026-11-02T08:00:00.123456Z","amount":0.100000000000000000010}]}]""",
                    ).jsonArray
                    .map {
                        it.jsonObject
                    }
            val actual =
                Observed(
                    mapOf("release_order" to expected.copy(verdicts = listOf(verdict), details = details)),
                    f.finalStates,
                )
            ReplayComparator.compare(f, actual) shouldBe emptyList()
            val doubled =
                actual.copy(
                    recipes = actual.recipes.mapValues { it.value.copy(verdicts = listOf(verdict, verdict)) },
                )
            ReplayComparator
                .compare(f, doubled)
                .single()
                .extra.size shouldBe 1
            val missing = actual.copy(recipes = actual.recipes.mapValues { it.value.copy(verdicts = emptyList()) })
            ReplayComparator
                .compare(f, missing)
                .single()
                .missing.size shouldBe 1
            val changed = JsonObject(verdict + ("verdict_reason" to JsonPrimitive("wrong reason")))
            ReplayComparator
                .compare(
                    f,
                    actual.copy(
                        recipes =
                            actual.recipes.mapValues {
                                it.value.copy(verdicts = listOf(changed))
                            },
                    ),
                ).single()
                .collection shouldBe
                "release_order.verdicts"
            ReplayComparator.compare(f, actual.copy(finalStates = null)).single().collection shouldBe "final_states"
            ReplayComparator.canonical(JsonPrimitive("4711")) shouldBe "\"4711\""
        }
        "seed, disagreement, driver error, and world filtering have stable exit codes" {
            val seed = FixtureLoader.parse(golden)
            ReplayRunner.run(seed, listOf("native.batch")) { error("seeds must not run") }.exitCode shouldBe 0
            val pinned = FixtureLoader.parse(golden + pin)
            val different =
                object : Driver {
                    override fun run(
                        fixture: ReplayFixture,
                        cell: String,
                    ) = Observed(emptyMap())
                }
            ReplayRunner.run(pinned, listOf("native.batch")) { different }.exitCode shouldBe 3
            ReplayRunner.run(pinned, listOf("native.batch")) { error("no driver") }.exitCode shouldBe 2
            ReplayRunner
                .run(pinned, listOf("native.batch"), world = "cz") {
                    error("world must be skipped")
                }.cells
                .single()
                .status shouldBe
                "skipped-world"
        }
    })
