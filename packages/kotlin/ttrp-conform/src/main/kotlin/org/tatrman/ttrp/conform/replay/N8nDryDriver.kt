// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Interprets the generated workflow, then reads exact Server evidence. No n8n process, SMTP or model writes.
 * Node 22+ is the expression runtime; the bearer is supplied through stdin and is never exposed to expressions.
 * Only trusted exporter output should be replayed (as with other executable conformance artifacts). */
class N8nDryDriver(
    server: String,
    private val token: String,
    private val exports: List<Path>,
    private val nodeCommand: String = "node",
) : Driver,
    AutoCloseable {
    private val origin = server.trimEnd('/').removeSuffix("/automation")
    private val client =
        HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
        }
    private val runner: Path

    init {
        val uri = URI(origin)
        require(
            uri.scheme in setOf("http", "https") &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) { "invalid Server URL" }
        require(token.isNotBlank()) { "empty bearer token" }
        require(exports.isNotEmpty()) { "n8n replay requires --export directories" }
        runner = Files.createTempFile("ttrp-n8n-dry-", ".mjs")
        Files.write(
            runner,
            requireNotNull(javaClass.getResourceAsStream("/replay/n8n-dry-runner.mjs")).use { it.readBytes() },
        )
    }

    override fun close() {
        client.close()
        Files.deleteIfExists(runner)
    }

    override fun run(
        fixture: ReplayFixture,
        cell: String,
    ): Observed =
        runBlocking {
            val mode = cell.substringAfter('.').replace('_', '-')
            require(cell.substringBefore('.').replace('-', '_') == "n8n_l1" && mode in setOf("batch", "on-event")) {
                "unsupported replay cell"
            }
            val workflows =
                exports.map { folder ->
                    val workflow = Json.parseToJsonElement(Files.readString(folder.resolve("workflow.json"))).jsonObject
                    val manifest = Json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
                    val declared =
                        workflow
                            .getValue("meta")
                            .jsonObject
                            .getValue("tatrman")
                            .jsonObject
                            .getValue("calls")
                    require(manifest["calls"] == declared) { "export manifest calls differ from workflow" }
                    workflow
                }
            val descriptor = get("/automation/")
            val results = linkedMapOf<String, Expected>()
            val effects = mutableListOf<JsonObject>()
            for (recipe in fixture.recipes) {
                val workflow =
                    workflows.singleOrNull { w ->
                        val meta =
                            w
                                .getValue("meta")
                                .jsonObject
                                .getValue("tatrman")
                                .jsonObject
                        meta.getValue("recipes").jsonArray == JsonArray(listOf(JsonPrimitive(recipe))) &&
                            meta
                                .getValue(
                                    "calls",
                                ).jsonArray
                                .first()
                                .jsonObject["mode"]
                                ?.jsonPrimitive
                                ?.content
                                ?.replace('_', '-') ==
                            mode
                    } ?: error("$recipe.$mode: expected one matching single-recipe export")
                val meta =
                    workflow
                        .getValue("meta")
                        .jsonObject
                        .getValue("tatrman")
                        .jsonObject
                val deployment = meta.getValue("deployment")
                val selected =
                    descriptor.getValue("deployments").jsonArray.map { it.jsonObject }.singleOrNull {
                        it["name"] ==
                            deployment
                    }
                require(
                    selected != null &&
                        selected["targetType"]?.jsonPrimitive?.content?.replace('-', '_') == "n8n_l1" &&
                        selected["enabled"] == JsonPrimitive(true) &&
                        selected["granted"] == JsonPrimitive(true) &&
                        selected["stale"] == JsonPrimitive(false),
                ) { "$recipe: deployment is unavailable, ungranted or stale" }
                val recipeMeta = get("/automation/recipes/${segment(recipe)}").let { (it["recipe"] ?: it).jsonObject }
                for ((name, expected) in fixture.parameters) {
                    val parameter =
                        recipeMeta["parameters"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
                            it["name"] ==
                                JsonPrimitive(name)
                        }
                    val actual = parameter?.get("value")
                    val type =
                        parameter
                            ?.get("type")
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    require(actual != null && parameterValue(actual, type) == parameterValue(expected, type)) {
                        "$recipe: replay parameter differs"
                    }
                }
                val open =
                    meta.getValue("calls").jsonArray.map { it.jsonObject }.first {
                        it["name"] ==
                            JsonPrimitive("open")
                    }
                val keyNames = open.getValue("key").jsonArray.map { it.jsonPrimitive.content.substringAfterLast('.') }
                val keys =
                    if (mode ==
                        "batch"
                    ) {
                        listOf<JsonElement?>(null)
                    } else {
                        requireNotNull(fixture.keys) { "on-event replay requires keys" }.map { it }
                    }
                val verdicts = mutableListOf<JsonObject>()
                val actions = mutableListOf<JsonObject>()
                val details = mutableListOf<JsonObject>()
                for (key in keys) {
                    val input =
                        buildJsonObject {
                            if (key != null) {
                                val portKey = if (key is JsonArray) JsonObject(keyNames.zip(key).toMap()) else key
                                put("key", portKey)
                            }
                        }
                    val executed = execute(workflow, input, fixture, mode)
                    executed["error"]?.takeIf { it != JsonNull }?.let { error(it.jsonPrimitive.content) }
                    val runs = executed.getValue("opens").jsonArray.map { it.jsonObject }
                    require(runs.size == 1 && runs.single()["recipe"] == JsonPrimitive(recipe)) {
                        "workflow did not execute exactly one recipe run"
                    }
                    val id =
                        segment(
                            runs
                                .single()
                                .getValue("id")
                                .jsonPrimitive.content,
                        )
                    val status = get("/automation/runs/$id")
                    require(status["dryRun"] == JsonPrimitive(true) && status["status"] == JsonPrimitive("closed")) {
                        "$recipe: workflow did not close a dry run"
                    }
                    val routes =
                        status
                            .getValue("verdict")
                            .jsonObject
                            .getValue("routes")
                            .jsonObject
                    for (route in routes.keys) {
                        pages("/automation/runs/$id/routes/${segment(route)}").forEachIndexed { offset, row ->
                            val rowKey = keyOf(row, keyNames)
                            verdicts +=
                                buildJsonObject {
                                    put("key", rowKey)
                                    put("route", route)
                                    put("verdict_reason", row["verdict_reason"] ?: JsonNull)
                                    row["fills"]?.let { put("fills", it) }
                                }
                            for (context in row["detail"]?.jsonObject.orEmpty().keys) {
                                details +=
                                    buildJsonObject {
                                        put("key", rowKey)
                                        put("route", route)
                                        put("context", context)
                                        put(
                                            "rows",
                                            JsonArray(
                                                pages(
                                                    "/automation/runs/$id/routes/${segment(
                                                        route,
                                                    )}/details/${segment(context)}?row=$offset",
                                                ),
                                            ),
                                        )
                                    }
                            }
                        }
                    }
                    actions +=
                        status.getValue("actions").jsonArray.map { raw ->
                            val action = raw.jsonObject
                            buildJsonObject {
                                val k = action.getValue("key")
                                put("key", if (k is JsonObject) keyOf(k, keyNames) else k)
                                put("action", action.getValue("action"))
                                put("columns", normalizeArguments(action.getValue("arguments").jsonObject))
                            }
                        }
                    val batch = get("/automation/runs/$id/batch")
                    require(batch["mode"] == JsonPrimitive("preview")) { "batch is not a preview" }
                    effects += batch.getValue("rows").jsonArray.map { it.jsonObject }
                }
                val counts = linkedMapOf<String, MutableMap<String, Int>>()
                details.forEach { detail ->
                    val k =
                        detail.getValue("key").let {
                            if (it is JsonArray) {
                                it.joinToString(
                                    ":",
                                ) { v -> v.jsonPrimitive.content }
                            } else {
                                it.jsonPrimitive.content
                            }
                        }
                    val context = detail.getValue("context").jsonPrimitive.content
                    val perKey = counts.getOrPut(k) { linkedMapOf() }
                    perKey[context] =
                        (perKey[context] ?: 0) + detail.getValue("rows").jsonArray.size
                }
                results[recipe] =
                    Expected(
                        verdicts,
                        actions,
                        details,
                        JsonObject(
                            counts.mapValues {
                                JsonObject(
                                    it.value.mapValues { v ->
                                        JsonPrimitive(v.value)
                                    },
                                )
                            },
                        ),
                    )
            }
            val finalStates =
                fixture.finalStates?.let { expected ->
                    val entity = expected.getValue("entity")
                    val attr = expected.getValue("attr").jsonObject
                    buildJsonObject {
                        put("entity", entity)
                        put(
                            "attr",
                            JsonObject(
                                attr.mapValues { (attribute, _) ->
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
            Observed(results, finalStates)
        }

    private fun execute(
        workflow: JsonObject,
        input: JsonObject,
        fixture: ReplayFixture,
        mode: String,
    ): JsonObject {
        val output = Files.createTempFile("ttrp-n8n-output-", ".json")
        val errors = Files.createTempFile("ttrp-n8n-errors-", ".txt")
        try {
            val process =
                ProcessBuilder(
                    nodeCommand,
                    runner.toString(),
                ).redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
            try {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(
                        buildJsonObject {
                            put("workflow", workflow)
                            put("server", origin)
                            put("token", token)
                            put("input", input)
                            put("asOf", replayInstant(fixture.asOf))
                            put("mode", mode)
                            fixture.keys?.let { put("keys", it) }
                        }.toString(),
                    )
                }
                require(process.waitFor(120, TimeUnit.SECONDS)) { "n8n dry driver timed out" }
                require(process.exitValue() == 0) { "n8n expression runtime failed (Node 22+ required)" }
                return Json.parseToJsonElement(Files.readString(output)).jsonObject
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(errors)
        }
    }

    private suspend fun get(path: String): JsonObject {
        val response =
            client.get(origin + path) {
                bearerAuth(token)
                accept(ContentType.Application.Json)
            }
        require(response.status.value == 200) { "GET ${path.substringBefore('?')}: HTTP ${response.status.value}" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun pages(path: String): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        var offset = 0
        repeat(10000) {
            val data = get(path + (if ('?' in path) "&" else "?") + "offset=$offset&limit=1000")
            val page = data.getValue("rows").jsonArray.map { it.jsonObject }
            require(page.none { it["truncated"] == JsonPrimitive(true) }) { "detail evidence is truncated" }
            rows += page
            offset += page.size
            val count = data.getValue("rowCount").jsonPrimitive.int
            require(offset <= count) { "paging exceeded rowCount" }
            if (offset == count) return rows
            require(page.isNotEmpty()) { "paging stopped before rowCount" }
        }
        error("paging limit exceeded")
    }

    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_.-]+")) && value !in setOf(".", "..")) { "invalid API identifier" }
        return value
    }

    private fun keyOf(
        row: JsonObject,
        names: List<String>,
    ): JsonElement {
        val values =
            names.map {
                row[it]?.takeIf { v ->
                    v != JsonNull
                } ?: error("route omitted key")
            }
        return if (values.size ==
            1
        ) {
            values.single()
        } else {
            JsonArray(values)
        }
    }

    private fun normalizeArguments(arguments: JsonObject): JsonObject =
        JsonObject(
            arguments.mapValues { (_, value) ->
                if (value is JsonObject &&
                    value["refs"] is JsonArray
                ) {
                    JsonObject(
                        value + (
                            "refs" to
                                JsonArray(
                                    value.getValue("refs").jsonArray.map { raw ->
                                        val ref = raw.jsonObject
                                        require(
                                            ref["truncated"] != JsonPrimitive(true),
                                        ) { "action detail evidence is truncated" }
                                        JsonObject(
                                            ref.filterKeys {
                                                it !=
                                                    "ref"
                                            },
                                        )
                                    },
                                )
                        ),
                    )
                } else {
                    value
                }
            },
        )

    private fun parameterValue(
        value: JsonElement,
        type: String,
    ): String {
        if (type in setOf("decimal", "int", "days", "working_days")) {
            return java.math
                .BigDecimal(value.jsonPrimitive.content)
                .stripTrailingZeros()
                .toPlainString()
        }
        if (type == "bool") {
            return value.jsonPrimitive.content
                .toBooleanStrict()
                .toString()
        }
        if (value is JsonPrimitive && value.isString && value.content.startsWith('"')) {
            return ReplayComparator.canonical(Json.parseToJsonElement(value.content))
        }
        return ReplayComparator.canonical(value)
    }

    private fun replayInstant(value: String): String =
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
