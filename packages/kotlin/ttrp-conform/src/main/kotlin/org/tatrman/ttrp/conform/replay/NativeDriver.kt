// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.accept
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import java.net.URI

interface Driver {
    fun run(
        fixture: ReplayFixture,
        cell: String,
    ): Observed
}

/** Native dry replay over the run API. No perform/outcome calls: evaluate creates the preview and action evidence.
 * Collection reads page to completion and reject bounded/truncated detail evidence. HTTP errors are driver errors,
 * never mismatches. The caller owns the token; no body/header/token is included in error text. */
class NativeDriver(
    server: String,
    private val token: String,
    private val client: HttpClient =
        HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
        },
    private val pollLimit: Int = 120,
    private val pollMillis: Long = 250,
) : Driver,
    AutoCloseable {
    private val base = server.trimEnd('/').let { if (it.endsWith("/automation")) it else "$it/automation" }

    init {
        val uri = URI(base)
        require(
            uri.scheme in setOf("http", "https") &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) { "--server must be an HTTP(S) origin or /automation URL" }
        require(token.isNotBlank()) { "empty bearer token" }
    }

    override fun close() {
        client.close()
    }

    override fun run(
        fixture: ReplayFixture,
        cell: String,
    ): Observed =
        runBlocking {
            val mode = cell.substringAfter('.')
            require(
                cell.substringBefore('.').replace('-', '_') == "native" &&
                    mode in setOf("batch", "on-event", "on_event"),
            ) {
                "unsupported replay cell $cell"
            }
            val descriptor = request("GET", "/").second
            val programs = descriptor.getValue("programs").jsonArray.map { it.jsonObject }
            val deployments = descriptor.getValue("deployments").jsonArray.map { it.jsonObject }
            val result = linkedMapOf<String, Expected>()
            val effects = mutableListOf<JsonObject>()
            for (recipe in fixture.recipes) {
                val recipeDetail = request("GET", "/recipes/${segment(recipe)}").second
                val meta = (recipeDetail["recipe"] ?: recipeDetail).jsonObject
                val keys = meta.getValue("key").jsonArray.map { it.jsonPrimitive.content.substringAfterLast('.') }
                for ((parameter, expected) in fixture.parameters) {
                    val declared =
                        meta["parameters"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
                            it["name"]?.jsonPrimitive?.content ==
                                parameter
                        }
                    val actual = declared?.get("value")
                    val type = declared?.get("type")?.jsonPrimitive?.content ?: ""
                    require(actual != null && parameterValue(actual, type) == parameterValue(expected, type)) {
                        "$recipe: parameter $parameter differs from fixture; replay cannot override approved parameters"
                    }
                }
                val matchingPrograms =
                    programs
                        .filter {
                            it["recipe"]?.jsonPrimitive?.content == recipe &&
                                it["mode"]?.jsonPrimitive?.content?.replace('_', '-') == mode.replace('_', '-')
                        }.map { it.getValue("name").jsonPrimitive.content }
                val matching =
                    deployments.filter { d ->
                        d["program"]?.jsonPrimitive?.content in matchingPrograms &&
                            d["targetType"]?.jsonPrimitive?.content == "native" &&
                            d["enabled"]?.jsonPrimitive?.boolean == true &&
                            d["granted"]?.jsonPrimitive?.boolean == true &&
                            d["stale"]?.jsonPrimitive?.boolean == false
                    }
                val deployment =
                    requireNotNull(matching.singleOrNull()) {
                        "$recipe.$mode: expected one enabled, granted, current native deployment; found ${matching.size}"
                    }.getValue("name").jsonPrimitive.content
                val batches: List<JsonArray?> =
                    if (mode ==
                        "batch"
                    ) {
                        listOf(fixture.keys)
                    } else {
                        requireNotNull(
                            fixture.keys,
                        ) { "on-event replay requires explicit keys" }.map { JsonArray(listOf(it)) }
                    }
                val verdicts = mutableListOf<JsonObject>()
                val actions = mutableListOf<JsonObject>()
                val details = mutableListOf<JsonObject>()
                for (batch in batches) {
                    val port =
                        buildJsonObject {
                            if (mode ==
                                "batch"
                            ) {
                                if (batch ==
                                    null
                                ) {
                                    put("none", true)
                                } else {
                                    put("keys", batch)
                                }
                            } else {
                                put("key", batch!!.single())
                            }
                        }
                    val body =
                        buildJsonObject {
                            put("deployment", deployment)
                            put("port", port)
                            put("asOf", replayInstant(fixture.asOf))
                            put("dryRun", true)
                            put("origin", "driver")
                        }
                    val opened = request("POST", "/recipes/${segment(recipe)}/runs", body, setOf(200, 201, 202)).second
                    val id = segment(opened.getValue("runId").jsonPrimitive.content)
                    var sealed = false
                    try {
                        var evaluated =
                            if (opened["replayed"] == JsonPrimitive(true)) {
                                request("GET", "/runs/$id")
                            } else {
                                request("POST", "/runs/$id/evaluate", buildJsonObject {}, setOf(200, 202))
                            }
                        var polls = 0
                        while (evaluated.first == 202 ||
                            evaluated.second["status"]?.jsonPrimitive?.content in setOf("open", "running")
                        ) {
                            require(polls++ < pollLimit) { "$recipe: evaluation polling timed out" }
                            delay(pollMillis)
                            evaluated = request("GET", "/runs/$id")
                        }
                        require(
                            evaluated.second["status"]?.jsonPrimitive?.content !in
                                setOf("failed", "abandoned", "waiting"),
                        ) { "$recipe: run did not evaluate successfully" }
                        sealed = evaluated.second["status"] == JsonPrimitive("closed")
                        val routes =
                            evaluated.second["verdict"]
                                ?.jsonObject
                                ?.get("routes")
                                ?.jsonObject
                                ?: error("$recipe: evaluation omitted verdict.routes")
                        for ((route, _) in routes) {
                            val rows = pages("/runs/$id/routes/${segment(route)}")
                            rows.forEachIndexed { offset, row ->
                                val key = keyOf(row, keys)
                                verdicts +=
                                    buildJsonObject {
                                        put("key", key)
                                        put("route", route)
                                        put(
                                            "verdict_reason",
                                            row["verdict_reason"] ?: JsonNull,
                                        )
                                        row["fills"]?.let { put("fills", it) }
                                    }
                                val contexts = row["detail"]?.jsonObject.orEmpty()
                                for (context in contexts.keys) {
                                    val dr =
                                        pages(
                                            "/runs/$id/routes/${segment(
                                                route,
                                            )}/details/${segment(context)}?row=$offset",
                                        )
                                    details +=
                                        buildJsonObject {
                                            put("key", key)
                                            put("route", route)
                                            put("context", context)
                                            put("rows", JsonArray(dr))
                                        }
                                }
                            }
                        }
                        val status = request("GET", "/runs/$id").second
                        require(status["dryRun"] == JsonPrimitive(true)) { "$recipe: Server did not confirm dryRun" }
                        val previews =
                            status["actions"] as? JsonArray ?: error("$recipe: status omitted preview actions")
                        actions +=
                            previews.map { raw ->
                                val a = raw.jsonObject
                                buildJsonObject {
                                    val actionKey = a.getValue("key")
                                    put("key", if (actionKey is JsonObject) keyOf(actionKey, keys) else actionKey)
                                    put("action", a.getValue("action"))
                                    put(
                                        "columns",
                                        normalizeArguments(
                                            a["arguments"]?.jsonObject ?: error("$recipe: action omitted arguments"),
                                        ),
                                    )
                                }
                            }
                        val batchRows = request("GET", "/runs/$id/batch").second
                        require(batchRows["mode"] == JsonPrimitive("preview")) { "$recipe: batch is not a preview" }
                        effects += batchRows.getValue("rows").jsonArray.map { it.jsonObject }
                        if (!sealed) {
                            val closed = request("POST", "/runs/$id/close", buildJsonObject {}).second
                            require(closed["status"] == JsonPrimitive("closed")) { "$recipe: dry run did not close" }
                            sealed = true
                        }
                    } finally {
                        if (!sealed) runCatching { request("POST", "/runs/$id/close", buildJsonObject {}) }
                    }
                }
                val counts = linkedMapOf<String, MutableMap<String, Int>>()
                details.forEach { d ->
                    val key =
                        d.getValue("key").let {
                            if (it is JsonArray) {
                                it.joinToString(
                                    ":",
                                ) { v -> v.jsonPrimitive.content }
                            } else {
                                it.jsonPrimitive.content
                            }
                        }
                    val context = d.getValue("context").jsonPrimitive.content
                    val perKey = counts.getOrPut(key) { linkedMapOf() }
                    perKey[context] =
                        (perKey[context] ?: 0) + d.getValue("rows").jsonArray.size
                }
                result[recipe] =
                    Expected(
                        verdicts,
                        actions,
                        details,
                        JsonObject(
                            counts.mapValues {
                                JsonObject(it.value.mapValues { JsonPrimitive(it.value) })
                            },
                        ),
                    )
            }
            // Dry runs never mutate model state. Transition effects are the only available final-state evidence.
            val finalStates =
                fixture.finalStates?.let { expected ->
                    val entity = expected.getValue("entity").jsonPrimitive.content
                    val attr = expected.getValue("attr").jsonObject
                    val observed =
                        attr.mapValues { (attribute, _) ->
                            buildJsonObject {
                                effects
                                    .filter {
                                        it["entity"] == JsonPrimitive(entity) &&
                                            it["op"] == JsonPrimitive("transition") &&
                                            it["attribute"] == JsonPrimitive(attribute)
                                    }.forEach { effect ->
                                        val key =
                                            effect
                                                .getValue("key")
                                                .jsonObject.values
                                                .joinToString(":") { it.jsonPrimitive.content }
                                        put(key, effect.getValue("to"))
                                    }
                            }
                        }
                    buildJsonObject {
                        put("entity", entity)
                        put("attr", JsonObject(observed))
                    }
                }
            Observed(result, finalStates)
        }

    private suspend fun pages(path: String): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        var offset = 0
        repeat(10_000) {
            val data = request("GET", path + (if ('?' in path) "&" else "?") + "offset=$offset&limit=1000").second
            val page = data.getValue("rows").jsonArray.map { it.jsonObject }
            require(page.none { it["truncated"] == JsonPrimitive(true) }) { "detail evidence is truncated" }
            rows += page
            offset += page.size
            val count = data.getValue("rowCount").jsonPrimitive.int
            require(offset <= count) { "paging returned more rows than rowCount" }
            if (offset == count) return rows
            require(page.isNotEmpty()) { "paging stopped before rowCount" }
        }
        error("paging limit exceeded")
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        statuses: Set<Int> = setOf(200),
    ): Pair<Int, JsonObject> {
        val response =
            client.request(base + path) {
                this.method = HttpMethod.parse(method)
                bearerAuth(token)
                accept(ContentType.Application.Json)
                if (body !=
                    null
                ) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        require(
            response.status.value in statuses,
        ) { "$method ${path.substringBefore('?')}: HTTP ${response.status.value}" }
        return response.status.value to Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun segment(s: String): String {
        require(s.matches(Regex("[A-Za-z0-9_.-]+")) && s !in setOf(".", "..")) { "invalid API identifier" }
        return s
    }

    private fun keyOf(
        row: JsonObject,
        names: List<String>,
    ): JsonElement {
        val values = names.map { row[it]?.takeIf { it != JsonNull } ?: error("route omitted key column $it") }
        return if (values.size == 1) values.single() else JsonArray(values)
    }

    /** Run-scoped detail URLs are transport handles, not action values. Keep their semantic descriptors. */
    private fun normalizeArguments(arguments: JsonObject): JsonObject =
        JsonObject(
            arguments.mapValues { (_, value) ->
                if (value is JsonObject && value["refs"] is JsonArray) {
                    val refs =
                        value.getValue("refs").jsonArray.map { raw ->
                            val ref = raw.jsonObject
                            require(
                                ref["context"] is JsonPrimitive && ref["ref"] is JsonPrimitive,
                            ) { "malformed action detail reference" }
                            require(ref["truncated"] != JsonPrimitive(true)) { "action detail evidence is truncated" }
                            JsonObject(ref.filterKeys { it != "ref" })
                        }
                    JsonObject(value + ("refs" to JsonArray(refs)))
                } else {
                    value
                }
            },
        )

    /** Golden demo days and offset-free local instants are explicitly interpreted as UTC. */
    internal fun replayInstant(value: String): String =
        runCatching {
            java.time.Instant
                .parse(value)
                .toString()
        }.getOrElse {
            runCatching {
                java.time.LocalDate
                    .parse(
                        value,
                    ).atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toString()
            }.getOrElse {
                java.time.LocalDateTime
                    .parse(value)
                    .toInstant(java.time.ZoneOffset.UTC)
                    .toString()
            }
        }

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
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            val unquoted = if (text.startsWith('"')) Json.parseToJsonElement(text) else value
            return ReplayComparator.canonical(unquoted)
        }
        return ReplayComparator.canonical(value)
    }
}
