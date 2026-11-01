// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Executes generated expressions/branches against a contract fixture; never a live n8n/Server certification. */
class N8nReviewRegressionSpec :
    StringSpec({
        fun bare(): RecipeGraph = n8nGraph().copy(routes = emptyList(), wirings = emptyList(), actions = emptyMap())

        fun workflow(g: RecipeGraph): JsonObject =
            Json
                .parseToJsonElement(
                    N8NL1EmitPlugin()
                        .emitRecipe(
                            RecipeEmitRequest(g, "{}", "", "{}"),
                        ).files
                        .getValue("workflow.json")
                        .decodeToString(),
                ).jsonObject

        fun process(
            until: List<String> = listOf("fulfilled", "due", "absent"),
            batch: Boolean = false,
        ): RecipeGraph {
            val a =
                bare().let {
                    if (batch) {
                        it.copy(
                            program = it.program.copy(mode = "batch", port = "none", schedule = "06:00 Europe/Prague"),
                        )
                    } else {
                        it
                    }
                }
            val b = a.copy(recipe = a.recipe.copy(name = "second"), program = a.program.copy(name = "second"))
            return a.copy(
                process =
                    ProcessMeta(
                        "flow",
                        "order_number",
                        listOf(
                            StepMeta("first", a.program.name, a.program.port, emptyList(), null, emptyList(), a),
                            StepMeta(
                                "second",
                                b.program.name,
                                b.program.port,
                                if (batch) emptyList() else listOf("inbound"),
                                null,
                                until,
                                b,
                            ),
                        ),
                    ),
            )
        }

        fun mailGraph(
            address: Boolean = true,
            digest: Boolean = false,
        ): RecipeGraph {
            val b = bare()
            return b.copy(
                program =
                    if (digest) {
                        b.program.copy(
                            mode = "batch",
                            port = "none",
                            schedule = "06:00 Europe/Prague",
                            deliver = "digest",
                        )
                    } else {
                        b.program
                    },
                routes = listOf(RouteMeta("mail", null, emptyList(), false, false, false)),
                wirings =
                    listOf(
                        WiringMeta(
                            listOf("verdict.mail"),
                            "notify",
                            mapOf(
                                if (address) {
                                    "to" to "email"
                                } else {
                                    "who" to
                                        "role.sales"
                                },
                            ),
                            null,
                        ),
                    ),
                actions =
                    mapOf(
                        "notify" to
                            ActionMeta(
                                mapOf(if (address) "to" to "customer.email" else "who" to "role"),
                                mapOf(
                                    "n8n" to "n8n-nodes-base.emailSend",
                                ),
                                null,
                                true,
                                null,
                                emptyMap(),
                                null,
                            ),
                    ),
            )
        }
        "R1 missing obligations fails; an explicit empty array skips only the absent branch" {
            WorkflowFixture().use { f ->
                val result = f.run(workflow(process()))
                result["error"]!!.jsonPrimitive.content shouldContain "run status has no obligations field"
                f.calls.count { it.path.endsWith("/second/runs") } shouldBe 0
            }
            WorkflowFixture().use { f ->
                f.obligations = JsonArray(emptyList())
                val result = f.run(workflow(process()))
                result["error"] shouldBe null
                f.calls.count { it.path.endsWith("/second/runs") } shouldBe 0
            }
        }
        "R2 customer mail is grouped by row address; empty addresses name the key; invalid wiring refuses export" {
            WorkflowFixture().use { f ->
                f.routes =
                    mapOf(
                        "mail" to listOf(row(1, "a@example.test"), row(2, "b@example.test"), row(3, "a@example.test")),
                    )
                f.actions = JsonArray((1..3).map { actionPreview(it, "notify") })
                val result = f.run(workflow(mailGraph()))
                result["error"] shouldBe null
                val mails = result.getValue("mails").jsonArray
                mails
                    .map {
                        it.jsonObject
                            .getValue("recipient")
                            .jsonPrimitive.content
                    }.toSet() shouldBe
                    setOf("a@example.test", "b@example.test")
                mails
                    .map {
                        it.jsonObject
                            .getValue("rows")
                            .jsonArray.size
                    }.sorted() shouldBe listOf(1, 2)
            }
            WorkflowFixture().use { f ->
                f.routes = mapOf("mail" to listOf(row(7, "")))
                f.actions = JsonArray(listOf(actionPreview(7, "notify")))
                f.run(workflow(mailGraph()))["error"]!!.jsonPrimitive.content shouldContain "no address for key [7]"
            }
            val g = mailGraph()
            for (columns in listOf(emptyMap(), mapOf("who" to "role", "to" to "customer.email"))) {
                shouldThrow<IllegalArgumentException> {
                    workflow(g.copy(actions = g.actions.mapValues { it.value.copy(columns = columns) }))
                }
            }
            shouldThrow<IllegalArgumentException> {
                workflow(
                    g.copy(
                        wirings =
                            g.wirings.map {
                                it.copy(
                                    columns =
                                        mapOf(
                                            "to" to "email + suffix",
                                        ),
                                )
                            },
                    ),
                )
            }.message!! shouldContain
                "bare route-row column"
        }
        "R3 a duplicate live handle waits for closed or waiting without driving it; abandonment permits one reopen" {
            for (state in listOf("closed", "waiting", "abandoned")) {
                WorkflowFixture().use { f ->
                    var opens = 0
                    f.override = { c ->
                        if (c.path.endsWith("/runs") &&
                            ++opens == 1
                        ) {
                            Reply(202, """{"runId":"other","status":"running","replayed":false}""")
                        } else if (c.path ==
                            "/automation/runs/other"
                        ) {
                            Reply(200, """{"status":"$state"}""", mapOf("Retry-After" to "19"))
                        } else {
                            null
                        }
                    }
                    val result = f.run(workflow(bare()))
                    result["error"] shouldBe null
                    if (state == "abandoned") {
                        f.calls.count { it.path.endsWith("/runs") } shouldBe 2
                        f.calls.count { it.path.endsWith("/evaluate") } shouldBe 1
                    } else {
                        f.calls.count { it.path.endsWith("/runs") } shouldBe 1
                        f.calls.any {
                            "/parts/" in it.path ||
                                it.path.endsWith("/evaluate") ||
                                it.path.endsWith("/perform") ||
                                it.path.endsWith("/close")
                        } shouldBe
                            false
                    }
                }
            }
            WorkflowFixture().use { f ->
                f.override =
                    { c ->
                        if (c.path.endsWith(
                                "/runs",
                            )
                        ) {
                            Reply(
                                202,
                                """{"runId":"other","status":"running"}""",
                            )
                        } else {
                            Reply(200, """{"status":"abandoned"}""")
                        }
                    }
                f.run(workflow(bare()))["error"]!!.jsonPrimitive.content shouldContain "re-open did not claim"
                f.calls.count { it.path.endsWith("/runs") } shouldBe 2
            }
        }
        "R4a later Open clocks survive a lost response with only one claim" {
            WorkflowFixture().use { f ->
                f.obligations =
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("kind", "inbound")
                                put("state", "fulfilled")
                            },
                        ),
                    )
                var attempt = 0
                val claims = mutableSetOf<JsonObject>()
                f.override = { c ->
                    when {
                        c.path.endsWith("/second/runs") -> {
                            val fresh = claims.add(c.body!!)
                            if (++attempt == 1) {
                                Thread.sleep(10)
                                Reply(0, "")
                            } else {
                                Reply(if (fresh)201 else 202, """{"runId":"step","status":"open"}""")
                            }
                        }
                        c.path == "/automation/runs/step" -> Reply(200, """{"status":"closed"}""")
                        else -> null
                    }
                }
                val result = f.run(workflow(process()))
                result["error"] shouldBe null
                val bodies = f.calls.filter { it.path.endsWith("/second/runs") }.map { it.body }
                bodies.size shouldBe 2
                bodies.distinct().size shouldBe 1
                claims.size shouldBe 1
                bodies
                    .first()!!
                    .getValue("port")
                    .jsonObject
                    .containsKey("at") shouldBe true
            }
        }
        "R4bc none steps omit at and batch step keys differ" {
            WorkflowFixture().use { f ->
                f.run(workflow(process(batch = true)))["error"] shouldBe null
                val opens = f.calls.filter { it.path.endsWith("/runs") }.map { it.body!! }
                opens.map { it.getValue("port").jsonObject.keys }.toSet() shouldBe setOf(setOf("none"))
                opens.map { it.getValue("idempotencyKey") }.distinct().size shouldBe 2
                opens.map { it.getValue("idempotencyKey").jsonPrimitive.content } shouldBe listOf("review", "r1:second")
            }
            workflow(process())
                .getValue("nodes")
                .jsonArray
                .filter {
                    it.jsonObject["type"] ==
                        JsonPrimitive("n8n-nodes-base.httpRequest")
                }.forEach { it.toString().contains("new Date()") shouldBe false }
        }
        "N1 redelivered processes claim each step once across executions and keep distinct instances separate" {
            WorkflowFixture().use { f ->
                f.obligations = Json.parseToJsonElement("""[{"kind":"inbound","state":"fulfilled"}]""").jsonArray
                val claims = mutableMapOf<String, String>()
                f.override = { c ->
                    if (c.path.endsWith("/runs")) {
                        val body = c.body!!
                        val key = body["idempotencyKey"]?.jsonPrimitive?.content ?: body.toString()
                        val replayed = key in claims
                        val id = claims.getOrPut(key) { "instance-run-${claims.size + 1}" }
                        Reply(if (replayed) 200 else 201, """{"runId":"$id","status":"closed","replayed":$replayed}""")
                    } else {
                        null
                    }
                }
                val graph = process()
                val meta = graph.process!!
                val last = meta.steps.last()
                val third =
                    last.graph!!.let {
                        it.copy(recipe = it.recipe.copy(name = "third"), program = it.program.copy(name = "third"))
                    }
                val rendered =
                    workflow(
                        graph.copy(
                            process =
                                meta.copy(
                                    steps =
                                        meta.steps + last.copy(name = "third", program = "third", graph = third),
                                ),
                        ),
                    )
                f.run(rendered, executionId = "original")["error"] shouldBe null
                val firstCalls = f.calls.size
                Thread.sleep(10) // A redelivery's later-step clock differs from the original execution's.
                f.run(rendered, executionId = "redelivery")["error"] shouldBe null
                claims.size shouldBe 3
                val duplicateCalls = f.calls.drop(firstCalls)
                duplicateCalls.count { it.path.endsWith("/runs") } shouldBe 3
                duplicateCalls.any {
                    it.path.endsWith(
                        "/evaluate",
                    ) ||
                        "/parts/" in it.path ||
                        it.path.endsWith("/close")
                } shouldBe
                    false
                val laterOpens = f.calls.filter { it.path.endsWith("/second/runs") || it.path.endsWith("/third/runs") }
                laterOpens
                    .map {
                        it.body!!
                            .getValue("idempotencyKey")
                            .jsonPrimitive.content
                    }.toSet() shouldBe
                    setOf("instance-run-1:second", "instance-run-1:third")
                f.run(
                    rendered,
                    buildJsonObject {
                        put("key", 2)
                        put("at", "2026-10-31T12:00:00Z")
                    },
                    executionId = "distinct",
                )["error"] shouldBe
                    null
                claims.size shouldBe 6
            }
        }
        "N1 later steps anchor on the first handle or its recovered run id" {
            for (state in listOf("closed", "abandoned")) {
                WorkflowFixture().use { f ->
                    f.obligations = Json.parseToJsonElement("""[{"kind":"inbound","state":"fulfilled"}]""").jsonArray
                    var firstOpens = 0
                    f.override = { c ->
                        when {
                            c.path.endsWith("/release_order/runs") -> {
                                if (++firstOpens == 1) {
                                    Reply(202, """{"runId":"original-handle","status":"running"}""")
                                } else {
                                    Reply(201, """{"runId":"recovered","status":"open"}""")
                                }
                            }
                            c.path == "/automation/runs/original-handle" ->
                                Reply(
                                    200,
                                    """{"status":"$state","obligations":[{"kind":"inbound","state":"fulfilled"}]}""",
                                )
                            else -> null
                        }
                    }
                    f.run(workflow(process()))["error"] shouldBe null
                    firstOpens shouldBe if (state == "abandoned") 2 else 1
                    val key =
                        f.calls
                            .single {
                                it.path.endsWith(
                                    "/second/runs",
                                )
                            }.body!!
                            .getValue("idempotencyKey")
                            .jsonPrimitive.content
                    key shouldBe if (state == "abandoned") "recovered:second" else "original-handle:second"
                }
            }
        }
        "R5 the event body excludes the port clock and identical timed redeliveries claim once" {
            WorkflowFixture().use { f ->
                val claims = mutableSetOf<JsonObject>()
                f.override = { c ->
                    if (c.path.endsWith("/runs")) {
                        val claimed = claims.add(c.body!!)
                        Reply(if (claimed)201 else 200, """{"runId":"event","status":"open","replayed":${!claimed}}""")
                    } else {
                        null
                    }
                }
                val graph = bare().copy(program = bare().program.copy(port = "event"))
                val input =
                    buildJsonObject {
                        put("line_id", 7)
                        put("at", "2026-10-31T12:00:00Z")
                    }
                repeat(2) { f.run(workflow(graph), input)["error"] shouldBe null }
                claims.size shouldBe 1
                val port = claims.single().getValue("port").jsonObject
                port["event"] shouldBe buildJsonObject { put("line_id", 7) }
                port["at"] shouldBe input["at"]
            }
        }
        "R6 actual Hartland daily, weekly, monthly and interval hints have explicit cron and timezone; others refuse" {
            mapOf(
                "06:00 Europe/Prague" to "0 0 6 * * *",
                "Mon 05:30 Europe/Prague" to "0 30 5 * * 1",
                "1st 08:00 Europe/Prague" to "0 0 8 1 * *",
                "*/60" to "0 0 * * * *",
            ).forEach { (hint, cron) ->
                val w =
                    workflow(bare().copy(program = bare().program.copy(mode = "batch", port = "none", schedule = hint)))
                w
                    .getValue("nodes")
                    .jsonArray
                    .first()
                    .toString() shouldContain cron
                w.getValue("settings").jsonObject["timezone"] shouldBe
                    JsonPrimitive(if (hint == "*/60") "UTC" else "Europe/Prague")
            }
            workflow(
                bare().copy(program = bare().program.copy(mode = "batch", port = "none", schedule = "*/7")),
            ).getValue("nodes").jsonArray.first().jsonObject.getValue("parameters").toString() shouldContain
                "minutesInterval"
            shouldThrow<IllegalStateException> {
                workflow(bare().copy(program = bare().program.copy(mode = "batch", schedule = "every tuesday")))
            }.message!! shouldContain
                "every tuesday"
        }
        "R7 a renamed task approval waits again after the first of two callbacks and closes after both" {
            WorkflowFixture().use { f ->
                val g =
                    n8nGraph().let { b ->
                        b.copy(
                            program = b.program.copy(mode = "batch", port = "none", schedule = "06:00 Europe/Prague"),
                            routes = b.routes.filter { it.name == "release_confirmed" },
                            wirings =
                                listOf(
                                    WiringMeta(
                                        listOf("verdict.release_confirmed"),
                                        "approve_release",
                                        emptyMap(),
                                        null,
                                    ),
                                ),
                            actions = mapOf("approve_release" to b.actions.getValue("ask")),
                        )
                    }
                f.routes = mapOf("release_confirmed" to listOf(row(1), row(2)))
                var performed = false
                var resumes = 0
                f.override = { c ->
                    when {
                        c.path.endsWith("/perform") -> {
                            performed = true
                            Reply(
                                200,
                                """{"performed":[{"key":1,"taskId":"one"},{"key":2,"taskId":"two"}],"skipped":[]}""",
                            )
                        }
                        c.path.matches(Regex("/automation/runs/[^/]+")) && performed -> {
                            resumes++
                            Reply(
                                200,
                                buildJsonObject {
                                    put(
                                        "status",
                                        if (resumes <=
                                            2
                                        ) {
                                            "waiting"
                                        } else {
                                            "evaluated"
                                        },
                                    )
                                }.toString(),
                            )
                        }
                        else -> null
                    }
                }
                val result = f.run(workflow(g))
                result["error"] shouldBe null
                result.getValue("waits").jsonArray.count {
                    it.jsonObject
                        .getValue("node")
                        .jsonPrimitive.content
                        .endsWith("approve_release wait")
                } shouldBe
                    2
                f.calls
                    .single { it.path.endsWith("/perform") }
                    .body!!
                    .containsKey("callbackUrl") shouldBe true
                resumes shouldBe 3
                f.calls
                    .last()
                    .path
                    .endsWith("/close") shouldBe true
                val wait =
                    result
                        .getValue("waits")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("parameters")
                        .jsonObject
                wait["limitWaitTime"] shouldBe JsonPrimitive(true)
                wait["resumeAmount"] shouldBe JsonPrimitive(172800)
            }
        }
        "R8 until fulfilled never advances on an expired dueAt; until due advances and waits use a one-hour backstop" {
            for (until in listOf("fulfilled", "due")) {
                WorkflowFixture().use { f ->
                    f.obligations =
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("kind", "inbound")
                                    put("state", "open")
                                    put("dueAt", "2000-01-01T00:00:00Z")
                                },
                            ),
                        )
                    val result = f.run(workflow(process(listOf(until))), maxWaits = 2)
                    if (until == "fulfilled") {
                        result["error"] shouldBe JsonPrimitive("test wait boundary")
                        f.calls.none { it.path.endsWith("/second/runs") } shouldBe true
                        result
                            .getValue(
                                "waits",
                            ).jsonArray
                            .first()
                            .jsonObject
                            .getValue("parameters")
                            .jsonObject["resumeAmount"] shouldBe
                            JsonPrimitive(3600)
                    } else {
                        result["error"] shouldBe null
                        f.calls.any { it.path.endsWith("/second/runs") } shouldBe true
                    }
                }
            }
        }
        "R11 and R12 missing dryRun never sends mail, deployment dryRun also guards it, and missing actions refuses" {
            for (dry in listOf<JsonElement?>(null, JsonPrimitive(false))) {
                WorkflowFixture().use { f ->
                    f.dryRun = dry
                    f.routes = mapOf("mail" to listOf(row(1, "a@example.test")))
                    f.actions =
                        JsonArray(listOf(actionPreview(1, "notify")))
                    val g =
                        mailGraph().let {
                            if (dry !=
                                null
                            ) {
                                it.copy(deployment = it.deployment.copy(dryRun = true))
                            } else {
                                it
                            }
                        }
                    val result = f.run(workflow(g))
                    result["error"] shouldBe null
                    result.getValue("mails").jsonArray.size shouldBe 0
                }
            }
            WorkflowFixture().use { f ->
                f.actions = null
                f.run(workflow(bare()))["error"]!!.jsonPrimitive.content shouldContain "run status has no actions field"
            }
        }
        "R13 polling honours Retry-After and the budget plus two-minute recovery grace" {
            WorkflowFixture().use { f ->
                var first = true
                f.override =
                    { c ->
                        if (c.path.endsWith("/evaluate") &&
                            first
                        ) {
                            first = false
                            Reply(202, "{}", mapOf("Retry-After" to "37"))
                        } else {
                            null
                        }
                    }
                val result = f.run(workflow(bare()))
                result["error"] shouldBe null
                result
                    .getValue("waits")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("parameters")
                    .jsonObject["amount"] shouldBe
                    JsonPrimitive(37)
            }
            val g = bare()
            workflow(
                g.copy(program = g.program.copy(budget = mapOf("sync" to "10 minutes"))),
            ).toString() shouldContain "720000"
            workflow(
                g.copy(
                    program =
                        g.program.copy(
                            mode = "batch",
                            port = "none",
                            schedule = "*/60",
                            budget =
                                mapOf(
                                    "batch" to "2 hours",
                                ),
                        ),
                ),
            ).toString() shouldContain
                "7320000"
        }
        "R14 two routed items lead to exactly one close" {
            WorkflowFixture().use { f ->
                val g =
                    bare().copy(
                        routes = listOf("a", "b").map { RouteMeta(it, null, emptyList(), false, false, false) },
                    )
                f.routes = mapOf("a" to listOf(row(1)), "b" to listOf(row(2)))
                f.run(workflow(g))["error"] shouldBe null
                f.calls.count { it.path.endsWith("/close") } shouldBe 1
            }
        }
        "R15 missing role address refuses and digest delivery uses the same row addressing" {
            WorkflowFixture().use { f ->
                f.routes = mapOf("mail" to listOf(row(1)))
                f.actions = JsonArray(listOf(actionPreview(1, "notify")))
                f.run(workflow(mailGraph(address = false)))["error"]!!.jsonPrimitive.content shouldContain
                    "no address for role role.sales"
            }
            WorkflowFixture().use { f ->
                f.routes = mapOf("mail" to listOf(row(1, "a@example.test"), row(2, "b@example.test")))
                f.actions = JsonArray(listOf(actionPreview(1, "notify"), actionPreview(2, "notify")))
                f.override =
                    { c ->
                        if (c.path.endsWith(
                                "/digests",
                            )
                        ) {
                            Reply(200, """{"groups":[{"by":{},"rows":"ref","period":"day"}]}""")
                        } else {
                            null
                        }
                    }
                val result = f.run(workflow(mailGraph(digest = true)))
                result["error"] shouldBe null
                result
                    .getValue("mails")
                    .jsonArray
                    .map { it.jsonObject["recipient"] }
                    .toSet() shouldBe
                    setOf(JsonPrimitive("a@example.test"), JsonPrimitive("b@example.test"))
            }
        }
        "R17 context and route names cannot collide with control names; manifest pins match node factory" {
            val g =
                n8nGraph().let {
                    it.copy(
                        roles = it.roles.copy(contexts = listOf(ContextMeta("decision", "per_candidate", "sql"))),
                        routes = listOf(RouteMeta("evaluation", null, emptyList(), false, false, false)),
                        wirings = listOf(WiringMeta(listOf("verdict.evaluation"), "status", emptyMap(), null)),
                        actions =
                            mapOf(
                                "status" to it.actions.getValue("release"),
                            ),
                    )
                }
            workflow(g).getValue("nodes").jsonArray.map { it.jsonObject.getValue("name") }.let {
                it.distinct().size shouldBe
                    it.size
            }
            val manifest = N8NL1EmitPlugin().executorTypeManifest()
            val pins =
                Regex("nodeVersions: \\{([^}]+)}").find(manifest)!!.groupValues[1].split(',').associate {
                    val(k, v) = it.trim().split(':')
                    k.trim() to
                        v.trim().toDouble()
                }
            NodeFactory.versions shouldBe pins
            val first = mailGraph()
            val second =
                first.copy(
                    program = first.program.copy(name = "follow_up"),
                    recipe = first.recipe.copy(name = "follow_up"),
                )
            val process =
                first.copy(
                    process =
                        ProcessMeta(
                            "mail_flow",
                            null,
                            listOf(
                                StepMeta("first", first.program.name, "key", emptyList(), null, emptyList(), first),
                                StepMeta("second", second.program.name, "key", emptyList(), null, emptyList(), second),
                            ),
                        ),
                )
            workflow(process)
                .getValue("nodes")
                .jsonArray
                .filter {
                    it.jsonObject["type"] ==
                        JsonPrimitive("n8n-nodes-base.emailSend")
                }.map {
                    it.jsonObject
                        .getValue("parameters")
                        .jsonObject
                        .getValue("subject")
                }.toSet() shouldBe
                setOf(JsonPrimitive("Tatrman ${first.program.name}"), JsonPrimitive("Tatrman follow_up"))
        }
    })

private fun row(
    key: Int,
    email: String? = null,
) = buildJsonObject {
    put("order_number", key)
    email?.let { put("email", it) }
}

private fun actionPreview(
    key: Int,
    name: String,
) = buildJsonObject {
    put("key", key)
    put("action", name)
    put("arguments", buildJsonObject {})
}

private data class Call(
    val path: String,
    val body: JsonObject?,
)

private data class Reply(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

private class WorkflowFixture : AutoCloseable {
    val calls = CopyOnWriteArrayList<Call>()
    var obligations: JsonArray? = null
    var actions: JsonArray? = JsonArray(emptyList())
    var dryRun: JsonElement? = JsonPrimitive(false)
    var routes: Map<String, List<JsonObject>> = emptyMap()
    var override: (Call) -> Reply? = { null }
    private var nextId = 0
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val text = exchange.requestBody.bufferedReader().readText()
                val call = Call(path, text.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject })
                calls += call
                val reply =
                    override(call) ?: when {
                        path.endsWith(
                            "/runs",
                        ) -> Reply(201, """{"runId":"r${++nextId}","status":"open","replayed":false}""")
                        path.endsWith(
                            "/evaluate",
                        ) -> Reply(200, buildJsonObject { put("verdict", verdict()) }.toString())
                        "/parts/" in path || "/islands/" in path ->
                            Reply(
                                200,
                                """{"rows":[],"rowCount":0,"ref":"rows"}""",
                            )
                        "/routes/" in path -> {
                            val rows =
                                routes[path.substringAfterLast('/')] ?: emptyList()
                            Reply(
                                200,
                                buildJsonObject {
                                    put("rows", JsonArray(rows))
                                    put("rowCount", rows.size)
                                }.toString(),
                            )
                        }
                        path.endsWith("/close") -> Reply(200, """{"status":"closed"}""")
                        path.endsWith("/perform") -> Reply(200, """{"performed":[],"skipped":[]}""")
                        path.endsWith("/outcome") -> Reply(200, "{}")
                        path.endsWith("/digests") -> Reply(200, """{"groups":[]}""")
                        else ->
                            Reply(
                                200,
                                buildJsonObject {
                                    put("status", "evaluated")
                                    put("verdict", verdict())
                                    dryRun?.let { put("dryRun", it) }
                                    actions?.let { put("actions", it) }
                                    obligations?.let { put("obligations", it) }
                                }.toString(),
                            )
                    }
                if (reply.code == 0) {
                    exchange.close()
                    return@createContext
                }
                reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
                exchange.responseHeaders.add("Content-Type", "application/json")
                val bytes = reply.body.toByteArray()
                exchange.sendResponseHeaders(reply.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun verdict() =
        buildJsonObject {
            put("routes", JsonObject(routes.mapValues { (_, rows) -> buildJsonObject { put("rowCount", rows.size) } }))
        }

    fun run(
        workflow: JsonObject,
        input: JsonObject =
            buildJsonObject {
                put("key", 1)
                put("at", "2026-10-31T12:00:00Z")
            },
        maxWaits: Int = 20,
        executionId: String = "review",
    ): JsonObject {
        val runner = Files.createTempFile("n8n-review-", ".mjs")
        val output = Files.createTempFile("n8n-review-output-", ".json")
        val errors = Files.createTempFile("n8n-review-errors-", ".txt")
        try {
            Files.write(
                runner,
                requireNotNull(javaClass.getResourceAsStream("/replay/n8n-dry-runner.mjs")).use { it.readBytes() },
            )
            val p =
                ProcessBuilder(
                    "node",
                    runner.toString(),
                ).redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
            try {
                p.outputStream.bufferedWriter().use {
                    it.write(
                        buildJsonObject {
                            put("workflow", workflow)
                            put("server", "http://127.0.0.1:${server.address.port}")
                            put("token", "test-token")
                            put("input", input)
                            put("executionId", executionId)
                            put("mailRecipients", buildJsonObject {})
                            put("maxWaits", maxWaits)
                        }.toString(),
                    )
                }
                p.waitFor(15, TimeUnit.SECONDS) shouldBe true
                io.kotest.assertions.withClue(Files.readString(errors)) { p.exitValue() shouldBe 0 }
                return Json.parseToJsonElement(Files.readString(output)).jsonObject
            } finally {
                if (p.isAlive) p.destroyForcibly()
            }
        } finally {
            Files.deleteIfExists(runner)
            Files.deleteIfExists(output)
            Files.deleteIfExists(errors)
        }
    }

    override fun close() {
        server.stop(0)
    }
}
