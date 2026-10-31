// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Contract-derived HTTP fixture; the production H-1 recording is still a separate acceptance gate. */
class N8nDryDriverSpec :
    StringSpec({
        fun export(
            name: String,
            mutate: (JsonObject) -> JsonObject = { it },
        ): Path {
            val golden = Path.of("../ttr-emit-n8n/src/test/resources/goldens/$name.json")
            val workflow = mutate(Json.parseToJsonElement(Files.readString(golden)).jsonObject)
            val directory = Files.createTempDirectory("n8n-dry-export")
            Files.writeString(directory.resolve("workflow.json"), workflow.toString())
            Files.writeString(
                directory.resolve("manifest.json"),
                buildJsonObject {
                    put(
                        "calls",
                        workflow
                            .getValue("meta")
                            .jsonObject
                            .getValue("tatrman")
                            .jsonObject
                            .getValue("calls"),
                    )
                }.toString(),
            )
            return directory
        }
        val fixture =
            FixtureLoader.parse(
                """
                recipe: release_order
                as_of: "2026-11-02"
                keys: [4711, 4712]
                expected:
                  verdicts:
                    - {key: 4711, route: release, verdict_reason: ready}
                    - {key: 4712, route: release, verdict_reason: ready}
                  actions:
                    - {key: 4711, action: release, columns: {order: 4711}}
                    - {key: 4712, action: release, columns: {order: 4712}}
                  detail_counts: {4711: {stock: 1}, 4712: {stock: 1}}
                  final_states: {entity: er.entity.order, attr: {state: {4711: released, 4712: released}}}
                pinned_from: {run: prior, at: now, by: test}
                """.trimIndent(),
            )
        "parts, recipe and island workflows execute their declared calls and agree with native" {
            ContractServer().use { server ->
                val native =
                    NativeDriver(
                        server.url,
                        "test-token",
                        pollMillis = 1,
                    ).use { it.run(fixture, "native.on-event") }
                for (name in listOf("release_n8n", "recipe", "islands")) {
                    server.asyncOnce = true
                    val directory = export(name)
                    try {
                        N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                            val result = driver.run(fixture, "n8n-l1.on-event")
                            result shouldBe native
                            ReplayComparator.compare(fixture, result) shouldBe emptyList()
                        }
                    } finally {
                        directory.toFile().deleteRecursively()
                    }
                }
                server.requests.any { "/parts/" in it } shouldBe true
                server.requests.any { "/islands/" in it } shouldBe true
                server.requests.any { "/perform" in it } shouldBe true
                server.requests.count { it.endsWith("/close") } shouldBe 8
                server.opens.forEach { it["dryRun"] shouldBe JsonPrimitive(true) }
            }
        }
        "batch rendering pages route rows and suppresses SMTP in a Server-confirmed dry run" {
            val directory = export("morning_release")
            try {
                ContractServer().use { server ->
                    val native =
                        NativeDriver(
                            server.url,
                            "test-token",
                            pollMillis = 1,
                        ).use { it.run(fixture, "native.batch") }
                    server.asyncOnce = true
                    N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                        driver.run(fixture, "n8n_l1.batch") shouldBe native
                        driver.run(fixture, "n8n_l1.batch") shouldBe native
                    }
                    server.opens
                        .mapNotNull { it["idempotencyKey"] }
                        .distinct()
                        .size shouldBe 2
                    server.requests.any { it.endsWith("/digests") } shouldBe true
                    server.requests.any { it.endsWith("/outcome") } shouldBe false
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
        "reason changes disagree while truncated evidence and HTTP errors refuse certification" {
            val directory = export("release_n8n")
            try {
                ContractServer().use { server ->
                    N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                        server.wrongReason = true
                        ReplayRunner.run(fixture, listOf("n8n-l1.on-event")) { driver }.exitCode shouldBe 3
                        server.wrongReason = false
                        server.truncated = true
                        val truncated = ReplayRunner.run(fixture, listOf("n8n-l1.on-event")) { driver }
                        truncated.exitCode shouldBe 2
                        truncated.summary() shouldContain "truncated"
                        server.truncated = false
                        server.precisionLoss = true
                        val imprecise = ReplayRunner.run(fixture, listOf("n8n-l1.on-event")) { driver }
                        imprecise.exitCode shouldBe 2
                        imprecise.summary() shouldContain "unsafe JSON number"
                        server.precisionLoss = false
                        server.failHttp = true
                        val failed = ReplayRunner.run(fixture, listOf("n8n-l1.on-event")) { driver }
                        failed.exitCode shouldBe 2
                        failed.summary() shouldNotContain "test-token"
                    }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
        "R10 dropping perform or changing its requested keys disagrees with native and the preview" {
            for (mutation in listOf("drop", "wrong keys")) {
                val directory =
                    export("release_n8n") { workflow ->
                        val nodes =
                            workflow.getValue("nodes").jsonArray.map { raw ->
                                val n = raw.jsonObject
                                if (n["name"] == JsonPrimitive("action release release")) {
                                    if (mutation ==
                                        "drop"
                                    ) {
                                        JsonObject(
                                            n +
                                                mapOf(
                                                    "type" to JsonPrimitive("n8n-nodes-base.code"),
                                                    "parameters" to
                                                        buildJsonObject { put("jsCode", "return $" + "input.all();") },
                                                ),
                                        )
                                    } else {
                                        JsonObject(
                                            n +
                                                (
                                                    "parameters" to
                                                        JsonObject(
                                                            n.getValue("parameters").jsonObject +
                                                                ("jsonBody" to JsonPrimitive("={{ {keys:[999]} }}")),
                                                        )
                                                ),
                                        )
                                    }
                                } else {
                                    n
                                }
                            }
                        val byName = nodes.associateBy { it.getValue("name") }
                        val meta = workflow.getValue("meta").jsonObject
                        val tatrman = meta.getValue("tatrman").jsonObject
                        val calls =
                            JsonArray(
                                tatrman.getValue("calls").jsonArray.map { raw ->
                                    val c = raw.jsonObject
                                    val node = byName.getValue(c.getValue("node"))
                                    JsonObject(c + ("parameters" to node.getValue("parameters")))
                                },
                            )
                        JsonObject(
                            workflow +
                                mapOf(
                                    "nodes" to JsonArray(nodes),
                                    "meta" to
                                        JsonObject(
                                            meta + ("tatrman" to JsonObject(tatrman + ("calls" to calls))),
                                        ),
                                ),
                        )
                    }
                try {
                    ContractServer().use { server ->
                        N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                            val report = ReplayRunner.run(fixture, listOf("n8n-l1.on-event")) { driver }
                            io.kotest.assertions.withClue(mutation + report.summary()) { report.exitCode shouldBe 3 }
                            report.summary() shouldContain "actions.preview"
                        }
                    }
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }
        "R10 dry mail evidence includes the actual recipient, rows and matching outcomes" {
            for (mutation in listOf("none", "recipient", "rows", "drop")) {
                val directory =
                    export("release_n8n") { workflow ->
                        JsonObject(
                            workflow + (
                                "nodes" to
                                    JsonArray(
                                        workflow.getValue("nodes").jsonArray.map { raw ->
                                            val n = raw.jsonObject
                                            if (n["name"] != JsonPrimitive("action hold notify") ||
                                                mutation == "none"
                                            ) {
                                                n
                                            } else if (mutation ==
                                                "drop"
                                            ) {
                                                JsonObject(
                                                    n +
                                                        mapOf(
                                                            "type" to JsonPrimitive("n8n-nodes-base.code"),
                                                            "parameters" to
                                                                buildJsonObject {
                                                                    put(
                                                                        "jsCode",
                                                                        "return $" + "input.all();",
                                                                    )
                                                                },
                                                        ),
                                                )
                                            } else {
                                                JsonObject(
                                                    n +
                                                        (
                                                            "parameters" to
                                                                JsonObject(
                                                                    n.getValue("parameters").jsonObject +
                                                                        (
                                                                            if (mutation ==
                                                                                "recipient"
                                                                            ) {
                                                                                "toEmail" to
                                                                                    JsonPrimitive(
                                                                                        "={{ 'wrong@example.invalid' }}",
                                                                                    )
                                                                            } else {
                                                                                "text" to
                                                                                    JsonPrimitive("={{ '[]' }}")
                                                                            }
                                                                        ),
                                                                )
                                                        ),
                                                )
                                            }
                                        },
                                    )
                            ),
                        )
                    }
                try {
                    ContractServer().use { server ->
                        server.includeMail = true
                        val native =
                            NativeDriver(
                                server.url,
                                "test-token",
                                pollMillis = 1,
                            ).use { it.run(fixture, "native.on-event") }
                        val pinned = fixture.copy(expected = native.recipes, finalStates = native.finalStates)
                        N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                            val report = ReplayRunner.run(pinned, listOf("n8n-l1.on-event")) { driver }
                            io.kotest.assertions.withClue(mutation + report.summary()) {
                                report.exitCode shouldBe
                                    if (mutation == "none") 0 else 3
                            }
                        }
                        io.kotest.assertions.withClue(mutation + server.requests.joinToString()) {
                            server.requests.any { it.endsWith("/outcome") } shouldBe
                                (mutation != "drop")
                        }
                    }
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }
        "R10 a batch all fixture preserves the rendered none port" {
            val directory = export("morning_release")
            try {
                ContractServer().use { server ->
                    N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                        ReplayComparator.compare(
                            fixture.copy(keys = null),
                            driver.run(fixture.copy(keys = null), "n8n_l1.batch"),
                        ) shouldBe
                            emptyList()
                    }
                    server.opens
                        .single()
                        .getValue("port")
                        .jsonObject shouldBe buildJsonObject { put("none", true) }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
        "changing rendered HTTP parameters without the declared call is a driver error" {
            val directory =
                export("release_n8n") { workflow ->
                    JsonObject(
                        workflow + (
                            "nodes" to
                                JsonArray(
                                    workflow.getValue("nodes").jsonArray.map { raw ->
                                        val n = raw.jsonObject
                                        if (n["name"] ==
                                            JsonPrimitive("open")
                                        ) {
                                            JsonObject(
                                                n +
                                                    (
                                                        "parameters" to
                                                            JsonObject(
                                                                n.getValue("parameters").jsonObject +
                                                                    (
                                                                        "url" to
                                                                            JsonPrimitive(
                                                                                "={{ 'https://untrusted.invalid/' }}",
                                                                            )
                                                                    ),
                                                            )
                                                    ),
                                            )
                                        } else {
                                            n
                                        }
                                    },
                                )
                        ),
                    )
                }
            try {
                ContractServer().use { server ->
                    N8nDryDriver(server.url, "test-token", listOf(directory)).use { driver ->
                        val report = ReplayRunner.run(fixture, listOf("n8n-l1.on-event")) { driver }
                        report.exitCode shouldBe 2
                        report.summary() shouldContain "call declaration differs"
                        report.summary() shouldNotContain "test-token"
                        server.opens.size shouldBe 0
                    }
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })
