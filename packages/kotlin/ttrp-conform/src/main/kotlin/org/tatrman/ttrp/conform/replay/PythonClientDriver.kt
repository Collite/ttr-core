// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** One exported service process per fixture; ordered dry-run requests use the fixture clock. */
class PythonClientDriver(
    private val server: String,
    private val token: String,
    private val exports: List<Path>,
    private val uvCommand: String = "uv",
) : Driver {
    override fun run(
        fixture: ReplayFixture,
        cell: String,
    ): Observed {
        val mode = cell.substringAfter('.').replace('_', '-')
        require(cell.substringBefore('.').replace('-', '_') == "python_client" && mode in setOf("batch", "on-event")) {
            "unsupported Python replay cell"
        }
        require(exports.isNotEmpty()) { "Python replay requires --export directories" }
        val configs =
            exports.mapNotNull { folder ->
                val manifest = json(folder.resolve("manifest.json"))
                if (manifest
                        .getValue("target")
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                        .replace('-', '_') !=
                    "python_client"
                ) {
                    return@mapNotNull null
                }
                val config = json(folder.resolve("python-client.json"))
                val module = config.getValue("module").jsonPrimitive.content
                require(module.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid generated module" }
                require(
                    config == json(folder.resolve("worker/$module/config.json")),
                ) { "Python package metadata differs from export" }
                require(manifest["calls"] == config["callPlan"]) { "Python manifest calls differ from package" }
                folder to config
            }
        val inputs = mutableListOf<JsonObject>()
        val labels = mutableListOf<Pair<String, JsonElement?>>()
        for (recipe in fixture.recipes) {
            val (_, config) =
                configs.singleOrNull { (_, c) ->
                    c["recipe"] == JsonPrimitive(recipe) && c["mode"] == JsonPrimitive(mode)
                } ?: error("$recipe.$mode: expected one matching Python export")
            val keys =
                if (mode == "batch") {
                    listOf<JsonElement?>(null)
                } else {
                    requireNotNull(fixture.keys) { "on-event replay requires keys" }
                }
            val keyNames = config.getValue("key").jsonArray.map { it.jsonPrimitive.content }

            fun portKey(key: JsonElement): JsonElement =
                if (key is JsonArray) {
                    require(key.size == keyNames.size) { "composite key arity" }
                    JsonObject(keyNames.zip(key).toMap())
                } else {
                    key
                }
            for (key in keys) {
                inputs +=
                    buildJsonObject {
                        put("module", config.getValue("module"))
                        put(
                            "port",
                            buildJsonObject {
                                if (mode == "batch") {
                                    if (fixture.keys == null) {
                                        put("none", true)
                                    } else {
                                        put("keys", JsonArray(fixture.keys.map(::portKey)))
                                    }
                                } else {
                                    require(
                                        config["port"] == JsonPrimitive("key"),
                                    ) { "key replay requires a key program" }
                                    put("key", portKey(key!!))
                                }
                            },
                        )
                        put("as_of", instant(fixture.asOf))
                        put("dry_run", true)
                        put("parameters", fixture.parameters)
                    }
                labels += recipe to key
            }
        }
        val envelope =
            execute(
                configs.filter {
                    it.second["mode"] == JsonPrimitive(mode) &&
                        it.second["recipe"]?.jsonPrimitive?.content in fixture.recipes
                },
                buildJsonObject { put("requests", JsonArray(inputs)) },
            )
        val responses = envelope.getValue("results").jsonArray.map { it.jsonObject }
        require(responses.size == inputs.size) { "Python service result count differs from requests" }
        val failures =
            responses.mapIndexedNotNull { i, result ->
                result["error"]?.let {
                    val (recipe, key) = labels[i]
                    "$recipe key ${key ?: "batch"}: ${it.jsonPrimitive.content}"
                }
            }
        require(failures.isEmpty()) { failures.joinToString("; ") }
        val effects = mutableListOf<JsonObject>()
        val results = linkedMapOf<String, Expected>()
        for (recipe in fixture.recipes) {
            val verdicts = mutableListOf<JsonObject>()
            val actions = mutableListOf<JsonObject>()
            val details = mutableListOf<JsonObject>()
            val counts = linkedMapOf<String, MutableMap<String, Int>>()
            responses.forEachIndexed { i, observed ->
                if (labels[i].first != recipe) return@forEachIndexed

                fun rows(name: String) = observed.getValue(name).jsonArray.map { it.jsonObject }
                verdicts += rows("verdicts")
                actions += rows("actions")
                details += rows("details")
                effects += rows("effects")
                observed.getValue("detail_counts").jsonObject.forEach { (k, value) ->
                    val perKey = counts.getOrPut(k) { linkedMapOf() }
                    value.jsonObject.forEach { (context, n) ->
                        perKey[context] = (perKey[context] ?: 0) + n.jsonPrimitive.int
                    }
                }
            }
            results[recipe] =
                Expected(
                    verdicts,
                    actions,
                    details,
                    JsonObject(counts.mapValues { (_, v) -> JsonObject(v.mapValues { JsonPrimitive(it.value) }) }),
                )
        }
        val finalStates =
            fixture.finalStates?.let { expected ->
                val entity = expected.getValue("entity")
                buildJsonObject {
                    put("entity", entity)
                    put(
                        "attr",
                        JsonObject(
                            expected.getValue("attr").jsonObject.mapValues { (attribute, _) ->
                                buildJsonObject {
                                    effects
                                        .filter {
                                            it["entity"] == entity &&
                                                it["op"] == JsonPrimitive("transition") &&
                                                it["attribute"] == JsonPrimitive(attribute)
                                        }.forEach {
                                            put(
                                                it
                                                    .getValue("key")
                                                    .jsonObject.values
                                                    .joinToString(":") { v -> v.jsonPrimitive.content },
                                                it.getValue("to"),
                                            )
                                        }
                                }
                            },
                        ),
                    )
                }
            }
        return Observed(results, finalStates)
    }

    private fun execute(
        configs: List<Pair<Path, JsonObject>>,
        input: JsonObject,
    ): JsonObject {
        val output = Files.createTempFile("ttrp-python-", ".json")
        val errors = Files.createTempFile("ttrp-python-", ".stderr")
        var process: Process? = null
        try {
            val (folder, config) = configs.first()
            val module = config.getValue("module").jsonPrimitive.content
            process =
                ProcessBuilder(uvCommand, "run", "--no-dev", "--python", "3.13.9", "python", "-m", "$module.service")
                    .directory(folder.resolve("worker").toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(errors.toFile())
                    .apply {
                        environment()["PYTHONPATH"] =
                            configs.joinToString(
                                java.io.File.pathSeparator,
                            ) {
                                it.first
                                    .resolve("worker")
                                    .toAbsolutePath()
                                    .toString()
                            }
                        environment()["TTR_SERVER"] = server
                        environment()["TTR_BEARER"] = token
                    }.start()
            process.outputStream.bufferedWriter().use { it.write(input.toString()) }
            require(process.waitFor(35, TimeUnit.MINUTES)) { "Python service timed out" }
            require(Files.size(output) <= 50_000_000) { "Python service output exceeds replay limit" }
            val exit = process.exitValue()

            fun failed(): Nothing {
                val tail =
                    Files.newByteChannel(errors).use { channel ->
                        channel.position((channel.size() - 4096).coerceAtLeast(0))
                        val bytes = java.nio.ByteBuffer.allocate(4096)
                        channel.read(bytes)
                        String(bytes.array(), 0, bytes.position(), Charsets.UTF_8).replace(token, "[redacted]")
                    }
                error("Python service failed (exit $exit): $tail")
            }
            if (exit != 0) failed()
            val result = runCatching { json(output) }.getOrElse { failed() }
            result["error"]?.let { error("Python service: ${it.jsonPrimitive.content}") }
            return result
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()?.waitFor()
            Files.deleteIfExists(output)
            Files.deleteIfExists(errors)
        }
    }

    private fun json(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun instant(value: String): String =
        runCatching {
            java.time.Instant
                .parse(value)
                .toString()
        }.getOrElse {
            runCatching {
                java.time.LocalDate
                    .parse(value)
                    .atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toString()
            }.getOrElse {
                java.time.LocalDateTime
                    .parse(value)
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toString()
            }
        }
}
