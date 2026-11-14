// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.python

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
import java.util.concurrent.TimeUnit

class PythonClientEmitSpec :
    StringSpec({
        val plugin = PythonClientEmitPlugin()
        val graph = pythonGraph()

        fun render(g: RecipeGraph = graph) = plugin.emitRecipe(RecipeEmitRequest(g, "{}", "stale rule", "{}"))

        "SPI provider generates a typed package and one pytest per route, with no executable recipe payload" {
            ServiceLoader
                .load(
                    TtrEmitPlugin::class.java,
                ).any { it.targetId == "python_client" && it.supportsRecipes } shouldBe
                true
            val files = render().files
            files.keys shouldBe
                setOf(
                    "README.md",
                    "conformance.json",
                    "python-client.json",
                    "worker/.python-version",
                    "worker/pyproject.toml",
                    "worker/tests/test_routes.py",
                    "worker/release_order_client/__init__.py",
                    "worker/release_order_client/config.json",
                    "worker/release_order_client/client.py",
                    "worker/release_order_client/runtime.py",
                    "worker/release_order_client/service.py",
                    "worker/release_order_client/main.py",
                    "worker/release_order_client/py.typed",
                )
            val client = files.getValue("worker/release_order_client/client.py").decodeToString()
            client shouldContain "def release(self)"
            client shouldContain "def hold(self)"
            client shouldContain "def candidates(self)"
            client shouldContain "def notify(self) -> Action"
            files
                .getValue("worker/tests/test_routes.py")
                .decodeToString()
                .split("def test_route_")
                .size shouldBe 3
            files.values.joinToString { it.decodeToString() } shouldNotContain "SELECT "
            files.getValue("worker/pyproject.toml").decodeToString() shouldContain "pytest==8.3.5"
            files.getValue("README.md").decodeToString() shouldContain "f28120e1"
        }
        "H-6 determinism covers all granularities, route order and runApi projection" {
            val variants =
                listOf(
                    graph,
                    graph.copy(deployment = graph.deployment.copy(granularity = "parts")),
                    graph.copy(deployment = graph.deployment.copy(granularity = "islands")),
                )
            EmitDeterminismKit
                .checkRecipes(
                    plugin,
                    variants.map {
                        RecipeEmitRequest(it, "{}", "stale", "{}")
                    },
                ).deterministic shouldBe
                true
            val moved =
                render(
                    graph.copy(
                        runApi =
                            graph.runApi.copy(
                                basePath = "/custom",
                                calls =
                                    graph.runApi.calls + ("open" to "/begin/{recipe}"),
                            ),
                    ),
                )
            val config = Json.parseToJsonElement(moved.files.getValue("python-client.json").decodeToString()).jsonObject
            config["base_path"] shouldBe JsonPrimitive("/custom")
            config.getValue("calls").jsonObject["open"] shouldBe JsonPrimitive("/begin/{recipe}")
        }
        "authored decimal duration budgets are compiled into seconds with mode defaults" {
            fun seconds(g: RecipeGraph) =
                Json
                    .parseToJsonElement(
                        render(g).files.getValue("python-client.json").decodeToString(),
                    ).jsonObject["budget_seconds"]
            seconds(graph.copy(program = graph.program.copy(budget = mapOf("sync" to "10.0 minutes")))) shouldBe
                JsonPrimitive(600)
            seconds(graph) shouldBe JsonPrimitive(60)
            seconds(graph.copy(program = graph.program.copy(mode = "batch"))) shouldBe JsonPrimitive(1800)
            render().files.getValue("python-client.json").decodeToString() shouldNotContain "\"budget\":"
        }
        "approval holds use the Server native sink, independent of the Python executor sink" {
            val action = graph.actions.getValue("release")
            val actions =
                mapOf(
                    "writer" to action.copy(sinks = mapOf("native" to "writer", "python_client" to "door")),
                    "obligation" to action.copy(sinks = mapOf("native" to "obligation")),
                    "transition" to action.copy(sinks = mapOf("native" to "transition")),
                    "not_required" to action.copy(approval = null),
                    "python_only" to action.copy(sinks = mapOf("native" to "door", "python_client" to "transition")),
                    "no_native" to action.copy(sinks = mapOf("python_client" to "writer")),
                )
            val config =
                Json
                    .parseToJsonElement(
                        render(graph.copy(actions = actions)).files.getValue("python-client.json").decodeToString(),
                    ).jsonObject
            config["held_actions"] shouldBe JsonArray(listOf("obligation", "transition", "writer").map(::JsonPrimitive))
        }
        "synthetic approval-held package matches its complete golden (real H-1 is covered by the CLI)" {
            val golden = Path.of("src/test/resources/goldens/synthetic_client.txt")
            val text =
                render().files.entries.joinToString(
                    "",
                ) { (path, bytes) -> "=== $path ===\n${bytes.decodeToString()}\n" } + "=== END ===\n"
            if (System.getenv("UPDATE_AU_EXPORT_GOLDENS") ==
                "1"
            ) {
                Files.createDirectories(golden.parent)
                Files.writeString(golden, text)
            }
            text shouldBe Files.readString(golden)
        }
        "the generated pytest suite runs under pinned uv Python, including exact decimal transport" {
            val dir = Files.createTempDirectory("python-client-pytest-")
            try {
                render().files.forEach { (path, bytes) ->
                    Files.createDirectories(dir.resolve(path).parent)
                    Files.write(dir.resolve(path), bytes)
                }
                requireNotNull(javaClass.getResourceAsStream("/python/test_runtime.py")).use {
                    Files.copy(it, dir.resolve("worker/tests/test_runtime.py"))
                }
                val log = dir.resolve("pytest-output.txt")
                val process =
                    ProcessBuilder("uv", "run", "--python", "3.13.9", "--group", "dev", "pytest", "-q")
                        .directory(
                            dir.resolve("worker").toFile(),
                        ).redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start()
                process.waitFor(120, TimeUnit.SECONDS) shouldBe true
                io.kotest.assertions.withClue(Files.readString(log)) { process.exitValue() shouldBe 0 }
                Files.readString(log) shouldContain "67 passed"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })

private fun pythonGraph() =
    RecipeGraph(
        RecipeMeta(
            "release_order",
            listOf("order.order_number"),
            "as_of",
            emptyList(),
            sortedMapOf(
                "text" to "text-fp",
                "values" to "values-fp",
                "program" to "program-fp",
                "deployment" to "deployment-fp",
            ),
            "per_candidate",
            listOf("sql"),
        ),
        ProgramMeta2("release_on_placement", "key", "on_event", "event", emptyMap(), emptyMap()),
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
        Roles("candidates", listOf(ContextMeta("stock", "detail", "sql")), "decision", "exclusive"),
        listOf(
            RouteMeta("release", "ready", emptyList(), false, false, false),
            RouteMeta("hold", "blocked", emptyList(), false, true, false),
        ),
        listOf(WiringMeta(listOf("release"), "release", mapOf("order" to "order_number"), null)),
        mapOf(
            "release" to
                ActionMeta(
                    mapOf("order" to "order.order_number"),
                    mapOf("native" to "transition", "python_client" to "door"),
                    "required",
                    false,
                    null,
                    emptyMap(),
                    null,
                ),
            "notify" to
                ActionMeta(emptyMap(), mapOf("python_client" to "door"), null, false, null, emptyMap(), null),
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
        listOf(listOf("input"), listOf("decision")),
    )
