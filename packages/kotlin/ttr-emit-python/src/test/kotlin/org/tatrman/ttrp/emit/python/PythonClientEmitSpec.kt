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
            client shouldContain "class Actions"
            val packageText = files.values.joinToString { it.decodeToString() }
            packageText shouldContain "/perform"
            packageText shouldContain "/outcome"
            packageText shouldContain "def perform"
            packageText shouldContain "def outcome"
            packageText shouldContain "held_actions"
            files
                .getValue("worker/tests/test_routes.py")
                .decodeToString()
                .split("def test_route_")
                .size shouldBe 3
            files.values.joinToString { it.decodeToString() } shouldNotContain "SELECT "
            files.getValue("worker/pyproject.toml").decodeToString() shouldContain "pytest==8.3.5"
            files.getValue("README.md").decodeToString() shouldContain "c2f2f049"
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
            val config =
                Json
                    .parseToJsonElement(
                        render().files.getValue("python-client.json").decodeToString(),
                    ).jsonObject
            config["abandonment_seconds"] shouldBe JsonPrimitive(180)
            config["dry_run"] shouldBe JsonPrimitive(graph.deployment.dryRun)
            val realAllowed = render(graph.copy(deployment = graph.deployment.copy(dryRun = false)))
            Json
                .parseToJsonElement(
                    realAllowed.files.getValue("python-client.json").decodeToString(),
                ).jsonObject["dry_run"] shouldBe JsonPrimitive(false)
            render().files.getValue("python-client.json").decodeToString() shouldNotContain "\"budget\":"
        }
        "the client requires the contracted perform and outcome endpoints" {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                render(graph.copy(runApi = graph.runApi.copy(calls = graph.runApi.calls - "perform")))
            }
        }
        "README and manual pin the contracted hold, answers, cleanup and stranded-key sentences" {
            val sentences =
                listOf(
                    "a batch row carries an approval `group` only for an action with `approval: required` on a native `transition`/`writer`/`obligation` sink; a route-level approval (D-145's user task) marks no batch row.",
                    "the client does **not** close; the run stays for inspection and perform is idempotent per key, so an operator re-drives it or the abandonment clock ends it.",
                    "On a dry-run-only deployment the client refuses a real run; pass `--dry-run` (typed API: `dry_run=True`).",
                    "A real run of a run-clock program takes `now`; a literal instant needs a debug deployment.",
                    "An entry sharing a deduplicated grouped row with a held entry is held too.",
                    "Nothing is applied; the client closes a dry run after its evidence is read.",
                    "Perform applies ahead of close and is idempotent per key; close never resends a performed row.",
                    "Once at least one perform returned a `performed` entry, the client closes the run (best effort) before re-raising, so the batch stays consistent with what was applied.",
                    "An Open that answers a foreign claim (`200` non-terminal or `202 + Location`) is polled inside the client's own window (its budget + 2 min).",
                    "A stranded real run blocks its key until the Server abandons it.",
                    "so an on-event run is abandoned after `budget_batch` + 2 min (~32 min by default).",
                    "`performed: []`; every held key `skipped {reason: \"held\"}` (held precedes dry-run); every other selected key `skipped {reason: \"dry-run\"}`.",
                    "an unheld key answers `performed`, or `skipped {reason: \"failed\"}` (its row failed in Apply) or `skipped {reason: \"not-applied\"}` (still staged).",
                    "A client that rejects evidence or fails mid-run does **not** close a real run while nothing has been performed on it (the run stays for inspection; the Server's abandonment ends it).",
                    "When the window ends the client POSTs the same Open **once** more; a `201` is a fresh claim, a `202` or a non-terminal `200` raises a distinct retryable error that carries the run id (\"key held by run <id>; retry later\") for the event system to redeliver.",
                    "**Contract:** a run whose program `mode` is on-event abandons by **`budget_sync` + 2 min**; a batch run by `budget_batch` + 2 min.",
                )
            val docs =
                listOf(
                    render().files.getValue("README.md").decodeToString(),
                    Files.readString(Path.of("README.md")),
                    Files.readString(Path.of("../../../docs/manual/en/19-automation.md")),
                )
            docs.forEach { text -> sentences.forEach { text shouldContain it } }
        }
        "synthetic perform-capable package matches its complete golden (real H-1 is covered by the CLI)" {
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
                requireNotNull(javaClass.getResourceAsStream("/python/server-perform.json")).use {
                    Files.copy(it, dir.resolve("worker/tests/server-perform.json"))
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
                Files.readString(log) shouldContain "105 passed"
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
