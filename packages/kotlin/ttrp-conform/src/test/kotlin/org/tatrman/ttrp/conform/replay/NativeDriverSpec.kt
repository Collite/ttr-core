// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.CopyOnWriteArrayList

/** CONTRACT responses, not a Server recording. The S1.3 H-1 recording remains a separate acceptance gate. */
class NativeDriverSpec :
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
        "native batch and on-event agree through async polling, full pagination and per-key details" {
            ContractServer().use { server ->
                NativeDriver(server.url, "test-token", pollMillis = 1).use { driver ->
                    val report = ReplayRunner.run(fixture, listOf("native.batch", "native.on-event")) { driver }
                    io.kotest.assertions.withClue(report.summary()) { report.exitCode shouldBe 0 }
                }
                server.opens.size shouldBe 3
                server.opens.forEach { it["asOf"] shouldBe JsonPrimitive("2026-11-02T00:00:00Z") }
                server.opens[0]
                    .getValue("port")
                    .jsonObject.keys shouldBe setOf("keys")
                server.opens.drop(1).forEach { it.getValue("port").jsonObject.keys shouldBe setOf("key") }
                server.requests.count { it.endsWith("/close") } shouldBe 3
                server.requests.count { "/details/stock" in it } shouldBe 4
                server.requests.any { "/perform" in it || "/outcome" in it } shouldBe false
            }
        }
        "a changed reason is disagreement, truncated evidence and HTTP failures are driver errors" {
            ContractServer().use { server ->
                NativeDriver(server.url, "test-token", pollMillis = 1).use { driver ->
                    server.wrongReason = true
                    ReplayRunner.run(fixture, listOf("native.batch")) { driver }.exitCode shouldBe 3
                    server.wrongReason = false
                    server.truncated = true
                    val truncated = ReplayRunner.run(fixture, listOf("native.batch")) { driver }
                    truncated.exitCode shouldBe 2
                    truncated.summary() shouldContain "truncated"
                    server.failHttp = true
                    val failed = ReplayRunner.run(fixture, listOf("native.batch")) { driver }
                    failed.exitCode shouldBe 2
                    failed.summary() shouldContain "HTTP 401"
                    failed.summary() shouldNotContain "test-token"
                }
            }
        }
    })

internal class ContractServer : AutoCloseable {
    val opens = CopyOnWriteArrayList<JsonObject>()
    val requests = CopyOnWriteArrayList<String>()
    private val closed =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val bodies = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    @Volatile var wrongReason = false

    @Volatile var truncated = false

    @Volatile var failHttp = false

    @Volatile var precisionLoss = false

    @Volatile var includeMail = false

    @Volatile var asyncOnce = true
    private val server =
        embeddedServer(CIO, port = 0) {
            routing { route("/{path...}") { handle { reply(call) } } }
        }.start(wait = false)
    val url =
        "http://127.0.0.1:" +
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            }

    override fun close() {
        server.stop(0, 1000)
    }

    private fun keys(id: String): List<JsonElement> {
        val port = bodies.getValue(id).getValue("port").jsonObject
        return port["keys"]?.jsonArray
            ?: if (port["none"] ==
                JsonPrimitive(true)
            ) {
                listOf(JsonPrimitive(4711), JsonPrimitive(4712))
            } else {
                listOf(port.getValue("key"))
            }
    }

    private fun verdict(id: String) =
        buildJsonObject {
            put(
                "routes",
                buildJsonObject {
                    put("release", buildJsonObject { put("rowCount", keys(id).size) })
                    if (includeMail) put("hold", buildJsonObject { put("rowCount", keys(id).size) })
                },
            )
        }

    private suspend fun reply(call: ApplicationCall) {
        val path = call.request.path()
        requests += path
        call.request.headers["Authorization"] shouldBe "Bearer test-token"
        if (failHttp) {
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }
        val id = path.substringAfter("/runs/").substringBefore('/')
        if (path.endsWith("/evaluate") && asyncOnce) {
            asyncOnce = false
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.Accepted)
            return
        }
        val response =
            when {
                path == "/automation/" ->
                    requireNotNull(
                        javaClass.getResourceAsStream("/replay/contract-descriptor.json"),
                    ).bufferedReader().use {
                        it.readText()
                    }
                path == "/automation/recipes/release_order" ->
                    """{"recipe":{"key":["order.order_number"],"parameters":[]}}"""
                path == "/automation/recipes/release_order/runs" -> {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    body["dryRun"] shouldBe JsonPrimitive(true)
                    opens += body
                    val runId = "r${opens.size}"
                    bodies[runId] = body
                    buildJsonObject {
                        put("runId", runId)
                        put("status", "open")
                    }.toString()
                }
                path.endsWith("/evaluate") -> buildJsonObject { put("verdict", verdict(id)) }.toString()
                "/parts/" in path || "/islands/" in path -> """{"rows":[],"rowCount":0,"ref":"dry"}"""
                path.endsWith("/perform") -> {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    val selected = body.getValue("keys").jsonArray
                    val eligible = keys(id)
                    val action = path.substringAfter("/actions/").substringBefore('/')
                    val performed = selected.filter { action == "release" && it in eligible }
                    buildJsonObject {
                        put(
                            "performed",
                            JsonArray(
                                performed.map { key ->
                                    buildJsonObject {
                                        put("key", key)
                                        put("batchRows", JsonArray(emptyList()))
                                    }
                                },
                            ),
                        )
                        put(
                            "skipped",
                            JsonArray(
                                (selected - performed.toSet()).map { key ->
                                    buildJsonObject {
                                        put("key", key)
                                        put("reason", "not selected by preview")
                                    }
                                },
                            ),
                        )
                    }.toString()
                }
                path.endsWith("/outcome") -> {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    body.getValue("outcomes").jsonArray.forEach { raw ->
                        val outcome = raw.jsonObject
                        require(includeMail && outcome["key"] in keys(id)) { "outcome key not in mail preview" }
                        require(outcome["status"] == JsonPrimitive("delivered"))
                    }
                    "{}"
                }
                path.endsWith("/digests") -> """{"groups":[]}"""
                path.endsWith("/routes/release") || (includeMail && path.endsWith("/routes/hold")) -> {
                    val offset = call.request.queryParameters["offset"]!!.toInt()
                    buildJsonObject {
                        put(
                            "rows",
                            JsonArray(
                                keys(id).drop(offset).take(1).map { key ->
                                    buildJsonObject {
                                        put("order_number", key)
                                        if (precisionLoss) {
                                            put(
                                                "amount",
                                                JsonPrimitive(java.math.BigDecimal("1.00000000000000000001")),
                                            )
                                        }
                                        put("verdict_reason", if (wrongReason) "wrong" else "ready")
                                        put("detail", buildJsonObject { put("stock", "/unused-ref") })
                                    }
                                },
                            ),
                        )
                        put("rowCount", keys(id).size)
                        put("truncated", offset + 1 < keys(id).size)
                    }.toString()
                }
                path.endsWith("/details/stock") ->
                    buildJsonObject {
                        put(
                            "rows",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("available", 1)
                                        put("truncated", truncated)
                                    },
                                ),
                            ),
                        )
                        put("rowCount", 1)
                    }.toString()
                path.endsWith("/batch") ->
                    buildJsonObject {
                        put("mode", "preview")
                        put(
                            "rows",
                            JsonArray(
                                keys(id).map { key ->
                                    buildJsonObject {
                                        put("entity", "er.entity.order")
                                        put("op", "transition")
                                        put("attribute", "state")
                                        put("to", "released")
                                        put("key", buildJsonObject { put("order_number", key) })
                                    }
                                },
                            ),
                        )
                    }.toString()
                path.endsWith("/close") -> {
                    closed += id
                    """{"status":"closed"}"""
                }
                path == "/automation/runs/$id" ->
                    buildJsonObject {
                        put("status", if (id in closed) "closed" else "evaluated")
                        put("dryRun", true)
                        put("verdict", verdict(id))
                        put(
                            "actions",
                            JsonArray(
                                keys(id).flatMap { key ->
                                    listOf(
                                        buildJsonObject {
                                            put("key", buildJsonObject { put("order_number", key) })
                                            put("action", "release")
                                            put("arguments", buildJsonObject { put("order", key) })
                                        },
                                    ) +
                                        if (includeMail) {
                                            listOf(
                                                buildJsonObject {
                                                    put("key", buildJsonObject { put("order_number", key) })
                                                    put("action", "notify")
                                                    put("arguments", buildJsonObject { put("who", "role.sales") })
                                                },
                                            )
                                        } else {
                                            emptyList()
                                        }
                                },
                            ),
                        )
                    }.toString()
                else -> error("unexpected $path")
            }
        call.respondText(
            response,
            ContentType.Application.Json,
            if (path ==
                "/automation/recipes/release_order/runs"
            ) {
                HttpStatusCode.Created
            } else {
                HttpStatusCode.OK
            },
        )
    }
}
