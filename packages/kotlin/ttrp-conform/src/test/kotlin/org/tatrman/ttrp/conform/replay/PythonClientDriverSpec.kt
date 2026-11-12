// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.python.PythonClientEmitPlugin
import org.tatrman.ttrp.emit.spi.*
import java.nio.file.Files

/** The existing contract HTTP fixture is not a real Server recording; P1C owns that acceptance. */
class PythonClientDriverSpec :
    StringSpec({
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
        val dirs = mutableListOf<java.nio.file.Path>()
        afterSpec { dirs.forEach { it.toFile().deleteRecursively() } }

        fun export(mode: String): java.nio.file.Path {
            val graph =
                RecipeGraph(
                    RecipeMeta(
                        "release_order",
                        listOf("order.order_number"),
                        "as_of",
                        emptyList(),
                        emptyMap(),
                        "per_candidate",
                        listOf("sql"),
                    ),
                    ProgramMeta2(
                        "event",
                        if (mode ==
                            "batch"
                        ) {
                            "none"
                        } else {
                            "key"
                        },
                        mode,
                        "event",
                        emptyMap(),
                        emptyMap(),
                    ),
                    DeploymentMeta(
                        "release_py",
                        "py",
                        "python_client",
                        "1",
                        "automation_decider",
                        "recipe",
                        true,
                        false,
                        emptyMap(),
                    ),
                    Roles("candidates", emptyList(), "decision", "exclusive"),
                    listOf(RouteMeta("release", "ready", emptyList(), false, false, false)),
                    emptyList(),
                    mapOf(
                        "release" to
                            ActionMeta(
                                mapOf("order" to "order.order_number"),
                                mapOf("python_client" to "door"),
                                null,
                                false,
                                null,
                                emptyMap(),
                                null,
                            ),
                    ),
                    RunApiMeta(
                        "/automation",
                        mapOf(
                            "open" to "/recipes/{recipe}/runs",
                            "part" to "/runs/{id}/parts/{part}",
                            "island" to "/runs/{id}/islands/{island}",
                            "evaluate" to "/runs/{id}/evaluate",
                            "status" to "/runs/{id}",
                            "route" to "/runs/{id}/routes/{route}",
                            "detail" to "/runs/{id}/routes/{route}/details/{context}",
                            "perform" to "/runs/{id}/actions/{action}/perform",
                            "outcome" to "/runs/{id}/actions/{action}/outcome",
                            "batch" to "/runs/{id}/batch",
                            "close" to "/runs/{id}/close",
                        ),
                    ),
                )
            val result = PythonClientEmitPlugin().emitRecipe(RecipeEmitRequest(graph, "{}", "stale", "{}"))
            val dir = Files.createTempDirectory("python-driver-").also { dirs.add(it) }
            result.files.forEach { (path, bytes) ->
                Files.createDirectories(dir.resolve(path).parent)
                Files.write(dir.resolve(path), bytes)
            }
            val config =
                Json
                    .parseToJsonElement(
                        result.files.getValue("python-client.json").decodeToString(),
                    ).jsonObject
            Files.writeString(
                dir.resolve("manifest.json"),
                buildJsonObject {
                    put("target", buildJsonObject { put("type", "python_client") })
                    put("calls", config.getValue("callPlan"))
                }.toString(),
            )
            return dir
        }
        "generated service replays per key and batch, including async evaluate, paging, details and actual perform" {
            ContractServer().use { server ->
                val driver = PythonClientDriver(server.url, "test-token", listOf(export("on_event"), export("batch")))
                val report =
                    ReplayRunner.run(
                        fixture,
                        listOf("python-client.on-event", "python-client.batch"),
                    ) { driver }
                io.kotest.assertions.withClue(report.summary()) { report.exitCode shouldBe 0 }
                server.opens.size shouldBe 3
                server.opens.forEach {
                    it["asOf"] shouldBe JsonPrimitive("2026-11-02T00:00:00Z")
                    it["dryRun"] shouldBe
                        JsonPrimitive(true)
                }
                server.requests.count { it.endsWith("/perform") } shouldBe 4
                server.requests.count { it.endsWith("/close") } shouldBe 3
            }
        }
        "wrong verdicts disagree; truncated details and denied HTTP fail without bearer disclosure" {
            ContractServer().use { server ->
                val driver = PythonClientDriver(server.url, "test-token", listOf(export("batch")))
                server.wrongReason = true
                ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }.exitCode shouldBe 3
                server.wrongReason = false
                server.truncated = true
                val truncated = ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }
                truncated.exitCode shouldBe 2
                truncated.summary() shouldContain "truncated"
                server.failHttp = true
                val refused = ReplayRunner.run(fixture, listOf("python-client.batch")) { driver }
                refused.exitCode shouldBe 2
                refused.summary() shouldContain "HTTP 401"
                refused.summary() shouldNotContain "test-token"
            }
        }
    })
