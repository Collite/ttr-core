// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import groovy.lang.Binding
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyShell
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.camunda.bpm.impl.juel.ExpressionFactoryImpl
import org.camunda.bpm.impl.juel.SimpleContext
import org.camunda.feel.api.FeelEngineBuilder
import org.camunda.spin.Spin
import org.tatrman.ttrp.emit.spi.*
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

private const val C7NS = "http://camunda.org/schema/1.0/bpmn"
private const val C8NS = "http://camunda.org/schema/zeebe/1.0"

internal fun reviewDocument(
    graph: RecipeGraph,
    dialect: Dialect,
): Element =
    DocumentBuilderFactory
        .newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(CamundaRenderer(RecipeEmitRequest(graph, "{}", "", "{}"), dialect).render())))
        .documentElement

internal fun Element.descendants(
    name: String,
    namespace: String = BPMN,
): List<Element> =
    getElementsByTagNameNS(namespace, name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

internal fun Element.named(name: String): Element = descendants("*").single { it.getAttribute("name") == name }

internal class ReviewExpressions(
    private val dialect: Dialect,
) {
    private val factory = ExpressionFactoryImpl()
    private val feel = FeelEngineBuilder.forJava().build()

    fun evaluate(
        expression: String,
        variables: Map<String, Any?>,
    ): Any? =
        withClue(expression) {
            if (dialect == Dialect.C7) {
                val context = SimpleContext()
                context.setFunction("", "S", Spin::class.java.getMethod("S", Any::class.java))
                context.setFunction("", "dateTime", ReviewFunctions::class.java.getMethod("dateTime"))
                variables.forEach { (name, value) ->
                    context.setVariable(name, factory.createValueExpression(value, Any::class.java))
                }
                factory.createValueExpression(context, expression, Any::class.java).getValue(context)
            } else {
                val result = feel.evaluateExpression(expression.removePrefix("="), variables)
                result.isSuccess shouldBe true
                result.result()
            }
        }

    fun value(json: String): Any? =
        if (dialect ==
            Dialect.C7
        ) {
            Spin.JSON(json)
        } else {
            decode(Json.parseToJsonElement(json))
        }

    private fun decode(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonObject -> element.mapValues { decode(it.value) }
            is JsonArray -> element.map(::decode)
            is JsonPrimitive ->
                if (element.isString) {
                    element.content
                } else {
                    element.booleanOrNull ?: element.longOrNull
                        ?: element.doubleOrNull
                }
        }

    fun condition(
        flow: Element,
        variables: Map<String, Any?>,
    ) = evaluate(flow.textContent.trim(), variables) == true
}

/** The C7 function returns offset text; this harness models input scope, not an engine. */
object ReviewFunctions {
    @JvmStatic fun dateTime(): java.time.OffsetDateTime = java.time.OffsetDateTime.now()
}

class ReviewExecution(
    val values: MutableMap<String, Any?>,
) {
    fun getVariable(name: String): Any? = values[name]

    fun setVariable(
        name: String,
        value: Any?,
    ) {
        values[name] = value
    }
}

class CamundaReviewRegressionSpec :
    StringSpec({
        "C1 C7 inputs resolve in their parent scope without sibling inputs for event and batch programs" {
            val base = releaseGraph()
            val expressions = ReviewExpressions(Dialect.C7)
            for (graph in listOf(base, releaseGraph(batch = true))) {
                val root = reviewDocument(graph, Dialect.C7)
                val evaluation =
                    expressions.value(
                        """{"status":"waiting","verdict":{"routes":{"hold_sales":{"rowCount":2},"hold_ar":{"rowCount":0},"release":{"rowCount":0},"release_confirmed":{"rowCount":0}}}}""",
                    )
                val variables =
                    mapOf<String, Any?>(
                        "ttrInput" to mapOf("key" to 4711, "at" to "2026-11-01T00:00:00Z"),
                        "ttrAnchor" to "reopened-first",
                        "execution" to C7Execution("execution"),
                        "TTR_CONN_AUTOMATION" to mapOf("url" to "https://example.test", "token" to "test"),
                        "TTR_MAIL_RECIPIENTS" to
                            mapOf("role.sales" to "sales@example.test", "role.ar" to "ar@example.test"),
                        "ttrOpen" to expressions.value("""{"runId":"run1"}"""),
                        "ttrRunId" to "run1",
                        "ttrOpenRetryAfter" to 15,
                        "ttrPreviousRunId" to "run0",
                        "ttrEvaluation" to evaluation,
                        "ttrRow" to expressions.value("""{"order_number":4711}"""),
                    )

                // Camunda IoParameter evaluates every input against the parent's snapshot, then sets locals.
                // Only ancestors' inputs become visible to children. An element's earlier inputs are never visible.
                fun visit(
                    element: Element,
                    parent: Map<String, Any?>,
                ) {
                    val own =
                        element.childNodes.let { nodes ->
                            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                        }
                    val extension = own.singleOrNull { it.localName == "extensionElements" }
                    val mapping =
                        extension
                            ?.childNodes
                            ?.let { nodes ->
                                (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                            }?.singleOrNull { it.localName == "inputOutput" }
                    val local = parent.toMutableMap()
                    mapping?.descendants("inputParameter", C7NS)?.forEach { input ->
                        local[input.getAttribute("name")] = expressions.evaluate(input.textContent.trim(), parent)
                    }
                    if (element.localName == "serviceTask") {
                        element
                            .descendants(
                                "inputParameter",
                                C7NS,
                            ).filter { it.textContent.trim().startsWith("\${") }
                            .forEach { input ->
                                expressions.evaluate(input.textContent.trim(), parent)
                            }
                        element.descendants("entry", C7NS).filter { it.textContent.startsWith("\${") }.forEach {
                            expressions.evaluate(it.textContent, parent)
                        }
                    }
                    own
                        .filter { it.namespaceURI == BPMN && it.localName !in setOf("extensionElements", "script") }
                        .forEach { visit(it, local) }
                }
                visit(root, variables)
                root.descendants("inputParameter", C7NS).any { it.getAttribute("name") == "ttrStepAt" } shouldBe false
                val opens =
                    root
                        .descendants(
                            "serviceTask",
                        ).filter { it.getAttribute("name") in setOf("open", "reopen once") }
                opens.forEach {
                    it
                        .descendants("inputParameter", C7NS)
                        .single { p ->
                            p.getAttribute("name") == "payload"
                        }.textContent shouldBe
                        "\${(ttrOpenBody).toString()}"
                }
            }
        }
        "C2 exclusive rows in two routes activate both inclusive branches before one close" {
            for (dialect in Dialect.entries) {
                val graph =
                    releaseGraph(dialect.choose("camunda_7", "camunda_8"), true).let {
                        it.copy(roles = it.roles.copy(switchMode = "exclusive"))
                    }
                val root = reviewDocument(graph, dialect)
                val fork = root.named("routes")
                val join = root.named("routes complete")
                fork.localName shouldBe "inclusiveGateway"
                join.localName shouldBe "inclusiveGateway"
                val vars =
                    mapOf(
                        "ttrEvaluation" to
                            ReviewExpressions(
                                dialect,
                            ).value(
                                """{"verdict":{"routes":{"release":{"rowCount":1},"hold_sales":{"rowCount":1},"hold_ar":{"rowCount":0},"release_confirmed":{"rowCount":0}}}}""",
                            ),
                    )
                val flows = root.descendants("sequenceFlow")
                val active =
                    flows
                        .filter {
                            it.getAttribute("sourceRef") == fork.getAttribute("id") &&
                                it.getAttribute("name").isNotEmpty()
                        }.filter { ReviewExpressions(dialect).condition(it, vars) }
                active.map { it.getAttribute("name") }.toSet() shouldBe setOf("release", "hold_sales")
                active.forEach { flow ->
                    val pages =
                        root.descendants("subProcess").single {
                            it.getAttribute("id") ==
                                flow.getAttribute("targetRef")
                        }
                    pages.descendants("serviceTask").single().getAttribute("name") shouldBe
                        "route ${flow.getAttribute("name")}"
                    val row = root.named("each ${flow.getAttribute("name")} row")
                    row.descendants("serviceTask").isNotEmpty() shouldBe true
                    flows.any {
                        it.getAttribute("sourceRef") == row.getAttribute("id") &&
                            it.getAttribute("targetRef") == join.getAttribute("id")
                    } shouldBe
                        true
                }
                root.descendants("serviceTask").count { it.getAttribute("name") == "close" } shouldBe 1
            }
        }
        "C3 every Server clock is an offset instant under UTC and Prague" {
            val original = TimeZone.getDefault()
            try {
                for (zone in listOf("UTC", "Europe/Prague")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    for (dialect in Dialect.entries) {
                        val graph = releaseGraph(dialect.choose("camunda_7", "camunda_8"), true)
                        // Include mail outcome clocks and event/batch program clocks.
                        for (g in listOf(graph, releaseGraph(dialect.choose("camunda_7", "camunda_8")))) {
                            val root = reviewDocument(g, dialect)
                            val inputs =
                                if (dialect ==
                                    Dialect.C7
                                ) {
                                    root.descendants("inputParameter", C7NS)
                                } else {
                                    root.descendants("input", C8NS)
                                }
                            val expr = ReviewExpressions(dialect)
                            var at: Any? = null
                            inputs
                                .filter {
                                    it.getAttribute(dialect.choose("name", "target")) in
                                        setOf("ttrStepAt", "ttrOpenBody", "payload", "body")
                                }.forEach { input ->
                                    val source =
                                        if (dialect ==
                                            Dialect.C7
                                        ) {
                                            input.textContent.trim()
                                        } else {
                                            input.getAttribute("source")
                                        }
                                    if ("dateTime()" !in source &&
                                        "now()" !in source &&
                                        "ttrStepAt" !in source
                                    ) {
                                        return@forEach
                                    }
                                    val result =
                                        expr.evaluate(
                                            source,
                                            mapOf(
                                                "ttrInput" to mapOf("key" to 4711),
                                                "ttrAnchor" to "first",
                                                "ttrStepAt" to at,
                                                "ttrRow" to expr.value("""{"order_number":4711}"""),
                                            ),
                                        )
                                    if (input.getAttribute(dialect.choose("name", "target")) == "ttrStepAt") at = result
                                    val json =
                                        if (result is Map<*, *>) {
                                            Json.parseToJsonElement(Spin.JSON(result).toString())
                                        } else if (result is String &&
                                            !result.startsWith("{")
                                        ) {
                                            JsonPrimitive(result)
                                        } else {
                                            Json.parseToJsonElement(result.toString())
                                        }

                                    fun clocks(e: JsonElement): List<String> =
                                        when (e) {
                                            is JsonObject ->
                                                e.entries.flatMap { (k, v) ->
                                                    if (k ==
                                                        "at"
                                                    ) {
                                                        listOf(v.jsonPrimitive.content)
                                                    } else {
                                                        clocks(v)
                                                    }
                                                }
                                            is JsonArray -> e.flatMap(::clocks)
                                            else -> emptyList()
                                        }
                                    val values = if (json is JsonPrimitive) listOf(json.content) else clocks(json)
                                    values.isNotEmpty() shouldBe true
                                    values.forEach { Instant.parse(it) }
                                }
                        }
                    }
                }
            } finally {
                TimeZone.setDefault(original)
            }
        }
        "C4 null verdict keeps polling until waiting with a real verdict in both dialects" {
            for (dialect in Dialect.entries) {
                val root = reviewDocument(releaseGraph(dialect.choose("camunda_7", "camunda_8")), dialect)
                val gateway = root.named("evaluation complete")
                val ready =
                    root.descendants("sequenceFlow").single {
                        it.getAttribute("sourceRef") ==
                            gateway.getAttribute("id") &&
                            it.getAttribute("name") == "verdict ready"
                    }
                val expr = ReviewExpressions(dialect)
                for ((body, expected) in listOf(
                    """{"status":"running","verdict":null}""" to false,
                    """{"status":"waiting","verdict":null}""" to false,
                    """{"status":"open","verdict":{}}""" to false,
                    """{"status":"waiting","verdict":{"routes":{}}}""" to true,
                )) {
                    expr.condition(ready, mapOf("ttrEvaluation" to expr.value(body))) shouldBe expected
                }
                val evaluate = root.named("evaluate")
                val status = root.named("evaluation status")
                root
                    .descendants("sequenceFlow")
                    .single {
                        it.getAttribute("sourceRef") == evaluate.getAttribute("id")
                    }.getAttribute("targetRef") shouldBe
                    status.getAttribute("id")
            }
        }
        "C5 450 rows page at offsets 0 200 400 and all iterate; empty or short evidence refuses" {
            for (dialect in Dialect.entries) {
                val root = reviewDocument(releaseGraph(dialect.choose("camunda_7", "camunda_8")), dialect)
                val pages = root.named("page release rows")
                val expr = ReviewExpressions(dialect)
                val read = pages.named("route release")
                val url =
                    if (dialect ==
                        Dialect.C7
                    ) {
                        read.descendants("inputParameter", C7NS).single { it.getAttribute("name") == "url" }.textContent
                    } else {
                        read
                            .descendants("input", C8NS)
                            .single {
                                it.getAttribute("target") ==
                                    "url"
                            }.getAttribute("source")
                    }
                val valid =
                    pages
                        .descendants(
                            "sequenceFlow",
                        ).single { it.getAttribute("name") == "consistent nonempty page" }
                val all = pages.descendants("sequenceFlow").single { it.getAttribute("name") == "all rows available" }
                val next =
                    pages.descendants("sequenceFlow").single {
                        it.getAttribute("name") ==
                            "next full page within bound"
                    }
                val append = pages.named("append release page")
                val script =
                    if (dialect ==
                        Dialect.C7
                    ) {
                        append.descendants("script").single().textContent
                    } else {
                        append.descendants("script", C8NS).single().getAttribute("expression")
                    }
                val loop = pages.descendants("sequenceFlow").single { it.getAttribute("id") == next.getAttribute("id") }
                loop.getAttribute("targetRef") shouldBe read.getAttribute("id")
                for ((sizes, succeeds) in listOf(
                    listOf(200, 200, 50) to true,
                    listOf(200, 20) to false,
                    listOf(200, 0) to false,
                )) {
                    val variables =
                        mutableMapOf<String, Any?>(
                            "ttrPageState" to expr.value("""{"rows":[],"offset":0,"size":0,"reads":0}"""),
                            "ttrExpectedRows" to 450,
                            "ttrRunId" to "run1",
                            "TTR_CONN_AUTOMATION" to mapOf("url" to "https://example.test"),
                            "TTR_AUTOMATION_URL" to "https://example.test",
                        )
                    val offsets = mutableListOf<Int>()
                    var complete = false
                    for (size in sizes) {
                        val address = expr.evaluate(url, variables).toString()
                        address.contains("limit=") shouldBe false
                        val offset = address.substringAfter("?offset=").toInt()
                        offsets += offset
                        val rows = (offset until offset + size).joinToString(",") { "{\"order_number\":$it}" }
                        variables["ttrPage"] = expr.value("""{"rowCount":450,"rows":[$rows]}""")
                        if (!expr.condition(valid, variables)) break
                        if (dialect ==
                            Dialect.C7
                        ) {
                            GroovyShell(Binding(mapOf("execution" to ReviewExecution(variables)))).evaluate(script)
                        } else {
                            variables["ttrPageState"] = expr.evaluate(script, variables)
                        }
                        complete = expr.condition(all, variables)
                        if (complete || !expr.condition(next, variables)) break
                    }
                    complete shouldBe succeeds
                    if (succeeds) {
                        offsets shouldBe listOf(0, 200, 400)
                        val state =
                            Json
                                .parseToJsonElement(
                                    (
                                        if (dialect ==
                                            Dialect.C7
                                        ) {
                                            variables["ttrPageState"].toString()
                                        } else {
                                            Spin.JSON(variables["ttrPageState"]).toString()
                                        }
                                    ),
                                ).jsonObject
                        state["rows"]!!.jsonArray.size shouldBe 450
                        // The actual multi-instance collection consumes the paging output array.
                        val each = root.named("each release row")
                        val collection =
                            if (dialect ==
                                Dialect.C7
                            ) {
                                each
                                    .descendants(
                                        "multiInstanceLoopCharacteristics",
                                    ).single()
                                    .getAttributeNS(C7NS, "collection")
                            } else {
                                each.descendants("loopCharacteristics", C8NS).single().getAttribute("inputCollection")
                            }
                        val name = "ttrRows_${stableId("release") }"
                        val count =
                            expr.evaluate(
                                collection,
                                mapOf(name to expr.value(state["rows"].toString())),
                            ) as Collection<*>
                        count.size shouldBe 450
                    }
                }
            }
        }
        "C5 parallel route responses stay in separate paging scopes" {
            for (dialect in Dialect.entries) {
                val root = reviewDocument(releaseGraph(dialect.choose("camunda_7", "camunda_8")), dialect)
                val expr = ReviewExpressions(dialect)
                val parent =
                    mutableMapOf<String, Any?>(
                        "ttrEvaluation" to
                            expr.value(
                                """{"verdict":{"routes":{"release":{"rowCount":1},"hold_sales":{"rowCount":1}}}}""",
                            ),
                    )

                fun locals(route: String): MutableMap<String, Any?> {
                    val pages = root.named("page $route rows")
                    val inputs =
                        pages.descendants(
                            dialect.choose("inputParameter", "input"),
                            dialect.choose(C7NS, C8NS),
                        )
                    return inputs
                        .filter {
                            it.getAttribute(dialect.choose("name", "target")) in
                                setOf("ttrPage", "ttrPageState", "ttrExpectedRows")
                        }.associate {
                            it.getAttribute(dialect.choose("name", "target")) to
                                expr.evaluate(
                                    if (dialect == Dialect.C7) it.textContent.trim() else it.getAttribute("source"),
                                    parent,
                                )
                        }.toMutableMap()
                }
                val a = locals("release")
                val b = locals("hold_sales")

                // Both engines update the closest existing variable, otherwise creating it at root.
                // Complete B between A's read and validation, as inclusive branches can do.
                fun complete(
                    scope: MutableMap<String, Any?>,
                    key: Int,
                ) {
                    val owner = if ("ttrPage" in scope) scope else parent
                    owner["ttrPage"] = expr.value("""{"rows":[{"order_number":$key}],"rowCount":1}""")
                }
                complete(a, 4711)
                complete(b, 4712)
                val key =
                    dialect.condition(
                        dialect.choose(
                            "ttrPage.prop('rows').elements().get(0).prop('order_number').numberValue()",
                            "ttrPage.rows[1].order_number",
                        ),
                    )
                (expr.evaluate(key, parent + a) as Number).toInt() shouldBe 4711
                (expr.evaluate(key, parent + b) as Number).toInt() shouldBe 4712
                parent.containsKey("ttrPage") shouldBe false
            }
        }
        "C7 budgets support days and fractional seconds and refuse unknown units" {
            for (dialect in Dialect.entries) {
                val base = releaseGraph(dialect.choose("camunda_7", "camunda_8"), true)
                for ((raw, seconds) in listOf("2 days" to 172920, "500 milliseconds" to 121, null to 1920)) {
                    val graph =
                        base.copy(
                            program =
                                base.program.copy(
                                    budget =
                                        if (raw ==
                                            null
                                        ) {
                                            emptyMap()
                                        } else {
                                            mapOf("batch" to raw)
                                        },
                                ),
                        )
                    reviewDocument(
                        graph,
                        dialect,
                    ).descendants("timeDuration").count { it.textContent == "PT${seconds}S" } shouldBe
                        2
                }
                shouldThrow<IllegalStateException> {
                    reviewDocument(
                        base.copy(
                            program =
                                base.program.copy(
                                    budget =
                                        mapOf(
                                            "batch" to "2 fortnights",
                                        ),
                                ),
                        ),
                        dialect,
                    )
                }
            }
        }
        "C8 failed or abandoned evaluation refuses at its first status read" {
            for (dialect in Dialect.entries) {
                val root = reviewDocument(releaseGraph(dialect.choose("camunda_7", "camunda_8")), dialect)
                val fail = root.descendants("sequenceFlow").single { it.getAttribute("name") == "failed or abandoned" }
                for (status in listOf("failed", "abandoned")) {
                    val expr = ReviewExpressions(dialect)
                    expr.condition(
                        fail,
                        mapOf("ttrEvaluation" to expr.value("""{"status":"$status","verdict":null}""")),
                    ) shouldBe
                        true
                    val end =
                        root
                            .descendants(
                                "endEvent",
                            ).single { it.getAttribute("id") == fail.getAttribute("targetRef") }
                    end.descendants("errorEventDefinition").single().getAttribute("errorRef") shouldBe "run_refused"
                }
            }
        }
        "F1 all C8 retries cap numeric and date Retry-After at each program budget plus 120 seconds" {
            val base = releaseGraph("camunda_8")
            val batch = releaseGraph("camunda_8", batch = true)
            val authored = base.copy(program = base.program.copy(budget = mapOf("sync" to "7 seconds")))
            val authoredBatch = batch.copy(program = batch.program.copy(budget = mapOf("batch" to "45 seconds")))
            val later = base.copy(program = base.program.copy(name = "later", budget = mapOf("sync" to "17 seconds")))
            val cases =
                listOf(
                    Triple(reviewDocument(base, Dialect.C8), 180L, "default sync"),
                    Triple(reviewDocument(batch, Dialect.C8), 1920L, "default batch"),
                    Triple(reviewDocument(authored, Dialect.C8), 127L, "authored sync"),
                    Triple(reviewDocument(authoredBatch, Dialect.C8), 165L, "authored batch"),
                    Triple(reviewDocument(later, Dialect.C8), 137L, "independent program budget"),
                )
            val expr = ReviewExpressions(Dialect.C8)
            for ((scope, cap, label) in cases) {
                val sources = scope.descendants("header", C8NS).filter { it.getAttribute("key") == "errorExpression" }
                (sources.size > 10) shouldBe true
                // Evaluate the generated expression at every HTTP site, including outside bounded polling scopes.
                for (header in sources) {
                    val source = header.getAttribute("value").removePrefix("=")
                    val functions =
                        "{bpmnError:function(code,message) {code:code}, " +
                            "jobError:function(message,variables,retries,timeout) {retries:retries,timeout:timeout}, result:"
                    for ((code, headers, delay) in listOf(
                        Triple("429", mapOf("Retry-After" to "999999"), cap),
                        Triple("503", mapOf("retry-after" to "Wed, 21 Oct 2099 07:28:00 GMT"), cap),
                        Triple("408", mapOf("Retry-After" to "37"), 37L),
                        Triple("429", mapOf("Retry-After" to "0"), 15L),
                        Triple("503", emptyMap<String, String>(), 15L),
                        Triple("503", mapOf("Retry-After" to "invalid"), 15L),
                        Triple("429", mapOf("Retry-After" to "0.2"), 1L),
                        Triple("503", mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT"), 1L),
                    )) {
                        withClue("$label HTTP $code $headers") {
                            // Connector functions are adapters; the FEEL expression is real, no connector is running.
                            val result =
                                expr.evaluate(
                                    functions + source + "}.result",
                                    mapOf(
                                        "error" to
                                            mapOf(
                                                "code" to code,
                                                "variables" to mapOf("response" to mapOf("headers" to headers)),
                                            ),
                                        "job" to mapOf("retries" to 3),
                                    ),
                                ) as Map<*, *>
                            (result["retries"] as Number).toInt() shouldBe 2
                            result["timeout"] shouldBe java.time.Duration.ofSeconds(delay)
                        }
                    }
                    expr.evaluate(
                        functions + source + "}.result",
                        mapOf(
                            "error" to mapOf("code" to "TRANSPORT_ERROR"),
                            "job" to mapOf("retries" to 3),
                        ),
                    ) shouldBe null
                }
            }
        }

        "C9 HTTP refusal maps 403 and 422 to drawn handlers while 409 is stale and 429 503 retry" {
            GroovyClassLoader().use { loader ->
                loader.parseClass(
                    "package org.camunda.bpm.engine.delegate; class BpmnError extends RuntimeException { String errorCode; BpmnError(String code, String message) { super(message); errorCode=code } }",
                )
                for (dialect in Dialect.entries) {
                    val root = reviewDocument(releaseGraph(dialect.choose("camunda_7", "camunda_8")), dialect)
                    val expr = ReviewExpressions(dialect)
                    for ((code, expected) in listOf(
                        403 to "TTR_REFUSED",
                        422 to "TTR_REFUSED",
                        409 to "TTR_STALE",
                        408 to null,
                        429 to null,
                        503 to null,
                    )) {
                        // Every HTTP task uses the same error mapping, including perform/outcome/close.
                        for (task in root.descendants("serviceTask").filter {
                            it.descendants("connector", C7NS).isNotEmpty() ||
                                it.descendants("taskDefinition", C8NS).isNotEmpty()
                        }) {
                            if (dialect == Dialect.C7) {
                                val script = task.descendants("outputParameter", C7NS).first().textContent
                                val error =
                                    shouldThrow<RuntimeException> {
                                        GroovyShell(
                                            loader,
                                            Binding(
                                                mapOf(
                                                    "statusCode" to code,
                                                    "response" to "refused",
                                                ),
                                            ),
                                        ).evaluate(script)
                                    }
                                if (expected != null) {
                                    error.javaClass.name shouldBe "org.camunda.bpm.engine.delegate.BpmnError"
                                    error.javaClass.getMethod("getErrorCode").invoke(error) shouldBe expected
                                } else {
                                    error.javaClass shouldBe IllegalStateException::class.java
                                }
                            } else {
                                val source =
                                    task
                                        .descendants("header", C8NS)
                                        .single {
                                            it.getAttribute("key") ==
                                                "errorExpression"
                                        }.getAttribute("value")
                                // Connector functions are explicit FEEL adapters, not a running connector runtime.
                                val functions =
                                    "{bpmnError:function(code,message) " +
                                        "{errorType:\"bpmnError\",code:code,message:message}, " +
                                        "jobError:function(message,variables,retries,timeout) " +
                                        "{errorType:\"jobError\",retries:retries,timeout:timeout}, result:"
                                val mapped =
                                    expr.evaluate(
                                        functions + source.removePrefix("=") + "}.result",
                                        mapOf(
                                            "error" to
                                                mapOf(
                                                    "code" to code.toString(),
                                                    "variables" to
                                                        mapOf(
                                                            "response" to
                                                                mapOf("headers" to mapOf("Retry-After" to "37")),
                                                        ),
                                                ),
                                            "job" to mapOf("retries" to 3),
                                        ),
                                    ) as Map<*, *>
                                if (expected != null) {
                                    mapped["errorType"] shouldBe "bpmnError"
                                    mapped["code"] shouldBe expected
                                } else {
                                    mapped["errorType"] shouldBe "jobError"
                                    (mapped["retries"] as Number).toInt() shouldBe 2
                                    mapped["timeout"].toString() shouldBe "PT37S"
                                }
                            }
                        }
                    }
                    root.descendants("sequenceFlow").none { it.getAttribute("name") == "409 stale" } shouldBe true
                    val refused = root.named("run refused; inspect evidence").getAttribute("id")
                    root
                        .descendants("boundaryEvent")
                        .filter {
                            it.descendants("errorEventDefinition").any { d ->
                                d.getAttribute("errorRef") ==
                                    "run_refused"
                            }
                        }.forEach { boundary ->
                            root
                                .descendants("sequenceFlow")
                                .single {
                                    it.getAttribute("sourceRef") ==
                                        boundary.getAttribute("id")
                                }.getAttribute("targetRef") shouldBe
                                refused
                        }
                }
            }
        }
        "C11 files identify their platform and minimum version with only their own extension namespace" {
            for (dialect in Dialect.entries) {
                val root = reviewDocument(releaseGraph(dialect.choose("camunda_7", "camunda_8")), dialect)
                root.getAttributeNS("http://camunda.org/schema/modeler/1.0", "executionPlatform") shouldBe
                    dialect.choose("Camunda Platform", "Camunda Cloud")
                root.getAttributeNS("http://camunda.org/schema/modeler/1.0", "executionPlatformVersion") shouldBe
                    dialect.choose("7.24.0", "8.6.0")
                root.hasAttribute(dialect.choose("xmlns:zeebe", "xmlns:camunda")) shouldBe false
            }
        }
        "K1 mail role sources and invalid schedule zones refuse consistently" {
            for (dialect in Dialect.entries) {
                val base = releaseGraph(dialect.choose("camunda_7", "camunda_8"))
                val graph =
                    base.copy(
                        wirings =
                            base.wirings.map {
                                if (it.action ==
                                    "notify"
                                ) {
                                    it.copy(columns = mapOf("who" to "sales"))
                                } else {
                                    it
                                }
                            },
                    )
                shouldThrow<IllegalArgumentException> { reviewDocument(graph, dialect) }.message!! shouldContain
                    "role.<name>"
                shouldThrow<IllegalArgumentException> {
                    CamundaSchedule.parse("06:00 Nowhere/Invalid", dialect)
                }.message!! shouldContain
                    "invalid schedule hint"
            }
        }
    })
