// SPDX-License-Identifier: Apache-2.0
@file:Suppress("ktlint:standard:max-line-length")

package org.tatrman.ttrp.emit.python

import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** Pure SPI v2 rendering. Runtime code has no compiler, SQL, model or credential dependency. */
internal class PythonClientRenderer(
    private val request: RecipeEmitRequest,
) {
    private val graph = request.graph
    private val module = identifier(graph.recipe.name) + "_client"
    private val parts =
        listOfNotNull(graph.roles.candidates?.let { "candidates" }) +
            graph.roles.contexts.map { it.name } + listOfNotNull(graph.roles.decision?.let { "decision" })

    fun render(): EmitResult {
        require(graph.program.mode in setOf("batch", "on_event", "on-event")) { "python-client: unsupported mode" }
        require(graph.deployment.granularity in setOf("recipe", "parts", "islands")) {
            "python-client: unsupported granularity"
        }
        val calls =
            listOf(
                "open",
                "part",
                "island",
                "evaluate",
                "status",
                "route",
                "detail",
                "perform",
                "outcome",
                "batch",
                "close",
            )
        require(calls.all { graph.runApi.calls[it]?.startsWith('/') == true }) { "python-client: incomplete runApi" }
        require(graph.runApi.basePath.startsWith('/') && !graph.runApi.basePath.startsWith("//")) {
            "python-client: invalid runApi base path"
        }
        val budgetSeconds =
            graph.program.budget[if (graph.program.mode == "batch") "batch" else "sync"]
                ?.let(::durationSeconds) ?: if (graph.program.mode == "batch") 1800L else 60L
        val config =
            buildJsonObject {
                put("module", module)
                put("recipe", graph.recipe.name)
                put("program", graph.program.name)
                put("deployment", graph.deployment.name)
                put("dry_run", graph.deployment.dryRun)
                put("mode", requireNotNull(graph.program.mode).replace('_', '-'))
                put("port", graph.program.port ?: "none")
                put("clock", graph.program.asOf ?: "run")
                put("granularity", graph.deployment.granularity)
                put("key", strings(graph.recipe.key.map { it.substringAfterLast('.') }))
                put("parts", strings(parts))
                put("islands", strings(graph.islandWaves.flatten()))
                put("routes", strings(graph.routes.map { it.name }))
                put("actions", strings(graph.actions.keys.sorted()))
                put(
                    "held_actions",
                    strings(
                        graph.actions
                            .filter { (_, action) ->
                                action.approval == "required" &&
                                    action.sinks["native"] in setOf("transition", "writer", "obligation")
                            }.keys
                            .sorted(),
                    ),
                )
                // The merged fixture baseline includes S3.1's mode-aware abandonment clock (D-192).
                put("abandonment_seconds", budgetSeconds + 120L)
                put("base_path", graph.runApi.basePath)
                put(
                    "calls",
                    JsonObject(
                        graph.runApi.calls
                            .filterKeys { it in calls }
                            .toSortedMap()
                            .mapValues { JsonPrimitive(it.value) },
                    ),
                )
                put(
                    "callPlan",
                    JsonArray(
                        calls.map { name ->
                            buildJsonObject {
                                put("name", name)
                                put("path", graph.runApi.calls.getValue(name))
                            }
                        },
                    ),
                )
                val exported =
                    Json
                        .parseToJsonElement(
                            request.manifestJson,
                        ).jsonObject["fingerprints"]
                        ?.jsonObject
                        .orEmpty()
                put(
                    "fingerprints",
                    JsonObject(
                        graph.recipe.fingerprints.mapValues { JsonPrimitive(it.value) } + exported,
                    ),
                )
                put(
                    "budget_seconds",
                    budgetSeconds,
                )
                put(
                    "parameters",
                    JsonArray(
                        graph.recipe.parameters.map { p ->
                            buildJsonObject {
                                put("name", p.name)
                                put("type", p.type)
                                p.value?.let { put("value", it) }
                            }
                        },
                    ),
                )
            }
        val files = sortedMapOf<String, ByteArray>()

        fun file(
            path: String,
            content: String,
        ) {
            files[path] = content.toByteArray(Charsets.UTF_8)
        }

        fun template(name: String) =
            requireNotNull(javaClass.getResourceAsStream("/python-client/$name"))
                .bufferedReader()
                .use {
                    it.readText()
                }.replace("@MODULE@", module)
        file(
            "worker/$module/config.json",
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config) + "\n",
        )
        file("worker/$module/__init__.py", "from .client import Client, Run\n\n__all__ = [\"Client\", \"Run\"]\n")
        file("worker/$module/runtime.py", template("runtime.py"))
        file("worker/$module/service.py", template("service.py"))
        file("worker/$module/main.py", template("main.py"))
        file("worker/$module/client.py", client())
        file("worker/$module/py.typed", "")
        file(
            "worker/pyproject.toml",
            """
            [project]
            name = "$module"
            version = "0.0.0"
            requires-python = "==3.13.*"
            dependencies = []

            [dependency-groups]
            dev = ["pytest==8.3.5"]

            [tool.pytest.ini_options]
            pythonpath = ["."]
            testpaths = ["tests"]
            """.trimIndent() + "\n",
        )
        file("worker/.python-version", "3.13.9\n")
        file("worker/tests/test_routes.py", tests())
        file("python-client.json", config.toString() + "\n")
        file(
            "README.md",
            request.readme
                .lineSequence()
                .filter { it.startsWith("Stale when") }
                .joinToString("\n") + "\n\n" +
                """
                Python client for `${graph.recipe.name}` (`${graph.deployment.name}`). HTTP run API,
                ttr-server contracts §1 + the 2026-11-14 perform seam. Fixture baseline: c2f2f049.
                Real-Server acceptance remains P1C / #366.
                Python 3.13.9; standard library runtime; pytest 8.3.5. No gRPC or proto wheel.

                Set TTR_SERVER to the Server origin and TTR_BEARER to your authorized bearer.
                From worker/: `uv run python -m $module.main --key 4711 --as-of 2026-11-02T08:00:00Z --dry-run`.
                `--key` is JSON (an object for composite keys); `--batch` accepts an optional JSON keys array,
                or selects all with no argument. The chosen port must match the exported program.
                `--as-of` accepts `now` or an ISO instant with offset. An event program sends the literal clock in
                port.at; a real event run uses that event clock without an asOf override.
                A real run of a run-clock program takes `now`; a literal instant needs a debug deployment.
                The typed API defaults to dry_run=True; pass dry_run=False for a real run.
                The CLI uses `--dry-run` for previews; omit it for a real run.
                `Run.actions.<action>.perform(keys)` sends object keys once per action; `outcome(items)` records
                the caller's delivered/failed/skipped outcomes while the run is open. Outcomes after seal refuse.
                `execute` reads batch evidence before perform, compares a multiset per preview entry, returns
                `perform_effects` (including failed/not-applied reasons), then closes normally.
                Open's echoed dryRun must equal the request; if absent, GET status supplies it before any work.
                Completed foreign claims get a fresh evidence-read budget and never perform or close here.
                `service.py` accepts `{"requests": [...]}` on stdin and emits `{"results": [...]}` in order,
                with errors per request (a stranded key includes retryable: true, runId and retryAfter);
                `conform --replay` uses it in a subprocess with dryRun true. Replays never override parameters.
                Run generated route tests: `uv run --group dev pytest`.
                Re-export after any recipe text/values, program, deployment or world fingerprint change.
                Only run trusted generated packages, like other executable conformance artifacts.
                """.trimIndent() + "\n\n" + template("perform-seam.md"),
        )
        file("conformance.json", request.conformanceJson)
        return EmitResult(files)
    }

    private fun client(): String =
        buildString {
            append(
                "from __future__ import annotations\nfrom typing import Any\nfrom .runtime import Action, BaseClient, BaseRun\n\n",
            )
            for ((name, values) in listOf(
                "Parts" to parts,
                "Routes" to graph.routes.map { it.name },
            )) {
                require(
                    values.map(::identifier).distinct().size == values.size,
                ) { "python-client: colliding $name method names" }
                append("class $name:\n    def __init__(self, run: BaseRun) -> None:\n        self._run = run\n")
                for (value in values) {
                    val method = identifier(value)
                    require(method != "_run") { "python-client: reserved method name" }
                    val literal = JsonPrimitive(value).toString()
                    when (name) {
                        "Parts" ->
                            append(
                                "\n    def $method(self) -> dict[str, Any]:\n        return self._run.part($literal)\n",
                            )
                        "Routes" ->
                            append(
                                "\n    def $method(self) -> list[dict[str, Any]]:\n        return self._run.route($literal)\n",
                            )
                    }
                }
                append("\n\n")
            }
            append("class Actions:\n    def __init__(self, run: BaseRun) -> None:\n")
            if (graph.actions.isEmpty()) append("        pass\n")
            require(
                graph.actions.keys
                    .map(::identifier)
                    .distinct()
                    .size == graph.actions.size,
            ) {
                "python-client: colliding action names"
            }
            for (name in graph.actions.keys.sorted()) {
                append("        self.${identifier(name)} = Action(run, ${JsonPrimitive(name)})\n")
            }
            append("\n\n")
            append(
                "class Run(BaseRun):\n    def __init__(self, *args: Any, **kwargs: Any) -> None:\n        super().__init__(*args, **kwargs)\n        self.parts = Parts(self)\n        self.routes = Routes(self)\n        self.actions = Actions(self)\n\n\nclass Client(BaseClient):\n    run_type = Run\n\n    def open(self, port: dict[str, Any], as_of: str | None = None, *, dry_run: bool = True, idempotency_key: str | None = None) -> Run:\n        return super().open(port, as_of, dry_run=dry_run, idempotency_key=idempotency_key)\n",
            )
        }

    private fun tests(): String =
        buildString {
            append("from $module.client import Client, Run\nfrom $module.runtime import CONFIG\n\n")
            graph.routes.forEachIndexed { i, route ->
                val literal = JsonPrimitive(route.name).toString()
                append(
                    "def test_route_$i():\n    client = Client(\"http://127.0.0.1\", \"test-token\")\n    seen = []\n    def request(method, name, body=None, **values):\n        seen.append((method, name, values))\n        return 200, {\"rows\": [], \"rowCount\": 0}, {}\n    client.request = request\n    run = Run(client, \"test-run\", 201)\n    assert run.routes.${identifier(
                        route.name,
                    )}() == []\n    assert seen[0][0:2] == (\"GET\", \"route\")\n    assert seen[0][2][\"route\"] == $literal\n    assert $literal in CONFIG[\"routes\"]\n\n",
                )
            }
        }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun identifier(value: String): String {
        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !value.startsWith("__")) {
            "python-client: invalid Python identifier $value"
        }
        return if (value in
            setOf(
                "False",
                "None",
                "True",
                "and",
                "as",
                "assert",
                "async",
                "await",
                "break",
                "class",
                "continue",
                "def",
                "del",
                "elif",
                "else",
                "except",
                "finally",
                "for",
                "from",
                "global",
                "if",
                "import",
                "in",
                "is",
                "lambda",
                "nonlocal",
                "not",
                "or",
                "pass",
                "raise",
                "return",
                "try",
                "while",
                "with",
                "yield",
            )
        ) {
            value +
                "_"
        } else {
            value
        }
    }
}
