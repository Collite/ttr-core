// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.conform.EmitDeterminismKit
import org.tatrman.ttrp.emit.spi.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

class N8nEmitSpec :
    StringSpec({
        val plugin = N8NL1EmitPlugin()

        fun render(graph: RecipeGraph) = plugin.emitRecipe(RecipeEmitRequest(graph, "{}", "stale rule", "{}"))

        fun workflow(graph: RecipeGraph) =
            Json.parseToJsonElement(render(graph).files.getValue("workflow.json").decodeToString()).jsonObject

        fun nodes(graph: RecipeGraph) = workflow(graph).getValue("nodes").jsonArray.map { it.jsonObject }

        fun golden(
            name: String,
            graph: RecipeGraph,
        ) {
            val file = Path.of("src/test/resources/goldens/$name.json")
            val bytes = render(graph).files.getValue("workflow.json")
            if (System.getenv("UPDATE_AU_EXPORT_GOLDENS") == "1") {
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
            bytes.decodeToString() shouldBe Files.readString(file)
        }
        "provider discovery, pinned HTTP credentials and overlapping route golden" {
            ServiceLoader.load(TtrEmitPlugin::class.java).any { it.targetId == "n8n_l1" && it.supportsRecipes } shouldBe
                true
            val graph = n8nGraph()
            golden("release_n8n", graph)
            val http = nodes(graph).filter { it["type"] == JsonPrimitive("n8n-nodes-base.httpRequest") }
            http.isNotEmpty() shouldBe true
            http.forEach {
                it["typeVersion"] shouldBe JsonPrimitive(4.2)
                it["retryOnFail"] shouldBe JsonPrimitive(true)
                it["maxTries"] shouldBe JsonPrimitive(3)
                it
                    .getValue("credentials")
                    .jsonObject
                    .getValue("httpBearerAuth")
                    .jsonObject["name"] shouldBe
                    JsonPrimitive("tatrman-automation")
            }
            nodes(graph)
                .single { it["type"] == JsonPrimitive("n8n-nodes-base.switch") }
                .getValue("parameters")
                .jsonObject
                .getValue("options")
                .jsonObject["allMatchingOutputs"] shouldBe
                JsonPrimitive(true)
            val open = http.single { it["name"] == JsonPrimitive("open") }.getValue("parameters").jsonObject
            open.getValue("jsonBody").jsonPrimitive.content shouldNotContain "idempotencyKey"
            val ask = http.single { it["name"] == JsonPrimitive("release_confirmed ask") }
            ask
                .getValue("parameters")
                .jsonObject
                .getValue("jsonBody")
                .jsonPrimitive.content shouldContain "callbackUrl"
            nodes(graph).count { it["name"] == JsonPrimitive("release_confirmed ask wait") } shouldBe 1
        }
        "batch schedule and digest groups use a Split Out and engine-side delivery outcomes" {
            val g =
                n8nGraph().copy(
                    program =
                        n8nGraph().program.copy(
                            name = "morning_release",
                            port = "none",
                            mode = "batch",
                            schedule = "06:00 Europe/Prague",
                            deliver = "{digest: {by: who, per: day}}",
                        ),
                )
            golden("morning_release", g)
            val ns = nodes(g)
            ns.first()["type"] shouldBe JsonPrimitive("n8n-nodes-base.scheduleTrigger")
            ns.single { it["name"] == JsonPrimitive("open") }.toString() shouldContain "idempotencyKey"
            ns.any { it["type"] == JsonPrimitive("n8n-nodes-base.splitOut") } shouldBe true
            ns.count { it["type"] == JsonPrimitive("n8n-nodes-base.emailSend") } shouldBe 1
            ns.any { it["name"] == JsonPrimitive("digest outcome") } shouldBe true
        }
        "recipe and island granularities follow runApi metadata and never embed SQL" {
            val graph = n8nGraph()
            val recipe = graph.copy(deployment = graph.deployment.copy(granularity = "recipe"))
            golden("recipe", recipe)
            nodes(recipe).count { it["name"] == JsonPrimitive("evaluate") } shouldBe 1
            nodes(recipe).any { it["name"] == JsonPrimitive("candidates") } shouldBe false
            val islands = graph.copy(deployment = graph.deployment.copy(granularity = "islands"))
            golden("islands", islands)
            val calls =
                workflow(
                    islands,
                ).getValue("meta").jsonObject.getValue("tatrman").jsonObject.getValue("calls").jsonArray
            calls
                .filter {
                    it.jsonObject["name"] == JsonPrimitive("island")
                }.map {
                    it.jsonObject
                        .getValue("island")
                        .jsonPrimitive.content
                } shouldBe
                listOf("input", "context", "decision")
            val moved =
                graph.copy(
                    runApi =
                        graph.runApi.copy(
                            basePath = "/custom",
                            calls =
                                graph.runApi.calls + ("open" to "/begin/{recipe}"),
                        ),
                )
            nodes(moved).single { it["name"] == JsonPrimitive("open") }.toString() shouldContain
                "/custom/begin/release_order"
            nodes(graph).toString() shouldNotContain "SELECT"
            EmitDeterminismKit
                .checkRecipes(
                    plugin,
                    listOf(graph, recipe, islands).map {
                        RecipeEmitRequest(it, "{}", "stale", "{}")
                    },
                ).deterministic shouldBe
                true
        }
        "process rendering retains three distinct recipes and skips absent obligations" {
            val first =
                n8nGraph().copy(
                    program = n8nGraph().program.copy(name = "decide"),
                    recipe = n8nGraph().recipe.copy(name = "decide_return"),
                )
            val second =
                first.copy(
                    program = first.program.copy(name = "inbound"),
                    recipe = first.recipe.copy(name = "monitor_inbound"),
                )
            val third =
                first.copy(
                    program = first.program.copy(name = "settle"),
                    recipe = first.recipe.copy(name = "settle"),
                )
            val process =
                first.copy(
                    process =
                        ProcessMeta(
                            "return_line_flow",
                            "line_id",
                            listOf(
                                StepMeta("decide", "decide", "key", emptyList(), null, emptyList(), first),
                                StepMeta(
                                    "inbound",
                                    "inbound",
                                    "key",
                                    listOf("inbound"),
                                    null,
                                    listOf("fulfilled", "due", "absent"),
                                    second,
                                ),
                                StepMeta(
                                    "settle",
                                    "settle",
                                    "key",
                                    listOf("credit_note", "payment"),
                                    null,
                                    listOf("fulfilled", "due", "absent"),
                                    third,
                                ),
                            ),
                        ),
                )
            golden("returns_n8n", process)
            val ns = nodes(process)
            ns.filter { it["name"]!!.jsonPrimitive.content.endsWith("open") }.size shouldBe 3
            ns.filter { it["name"]!!.jsonPrimitive.content.endsWith("obligation present") }.size shouldBe 2
            ns.filter { it["name"]!!.jsonPrimitive.content.endsWith("obligation wait") }.size shouldBe 2
            ns.filter { it["name"]!!.jsonPrimitive.content.endsWith("obligation wait") }.forEach {
                it.getValue("parameters").jsonObject["limitWaitTime"] shouldBe JsonPrimitive(true)
            }
        }
    })

internal fun n8nGraph(): RecipeGraph =
    RecipeGraph(
        RecipeMeta(
            "release_order",
            listOf("order.order_number"),
            "now",
            emptyList(),
            mapOf(
                "text" to "sha256:text",
                "values" to "sha256:values",
                "labels" to "sha256:labels",
            ),
            "per_candidate",
            listOf("sql"),
        ),
        ProgramMeta2("release_on_placement", "key", "on_event", "event", "immediate", null, emptyMap(), emptyMap()),
        DeploymentMeta("release_n8n", "n8n", "n8n_l1", "1", "identity.automation", "parts", false, false, emptyMap()),
        Roles("placed_orders", listOf(ContextMeta("stock", "per_candidate", "sql")), "verdict", "overlapping"),
        listOf(
            RouteMeta("release", null, emptyList(), false, false, false),
            RouteMeta("release_confirmed", null, emptyList(), false, false, false),
            RouteMeta("hold", null, listOf("stock"), false, false, false),
        ),
        listOf(
            WiringMeta(listOf("verdict.release"), "release", emptyMap(), null),
            WiringMeta(listOf("verdict.release_confirmed"), "ask", emptyMap(), null),
            WiringMeta(
                listOf("verdict.hold"),
                "notify",
                mapOf(
                    "who" to "role.sales",
                ),
                null,
            ),
        ),
        mapOf(
            "release" to action("door"),
            "ask" to action("door"),
            "notify" to action("n8n-nodes-base.emailSend", true),
        ),
        null,
        RunApiMeta(
            "/automation",
            mapOf(
                "open" to "/recipes/{recipe}/runs",
                "part" to "/runs/{id}/parts/{part}",
                "island" to "/runs/{id}/islands/{island}",
                "evaluate" to "/runs/{id}/evaluate",
                "route" to "/runs/{id}/routes/{route}",
                "status" to "/runs/{id}",
                "perform" to "/runs/{id}/actions/{action}/perform",
                "outcome" to "/runs/{id}/actions/{action}/outcome",
                "digests" to "/runs/{id}/digests",
                "close" to "/runs/{id}/close",
            ),
        ),
        listOf(listOf("input"), listOf("context"), listOf("decision")),
    )

private fun action(
    sink: String,
    deliverable: Boolean = false,
) = ActionMeta(emptyMap(), mapOf("n8n" to sink), null, deliverable, null, emptyMap(), null)
