// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import org.camunda.bpm.impl.juel.ExpressionFactoryImpl
import org.camunda.bpm.impl.juel.SimpleContext
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.camunda.feel.api.FeelEngineBuilder
import org.tatrman.ttrp.emit.spi.*
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import groovy.lang.Binding
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyShell
import kotlinx.serialization.json.*
import org.camunda.spin.Spin

/** Parsers execute locally; no Camunda engine or connector runtime is involved. */
class CamundaExpressionSpec :
    StringSpec({
        fun expressions(target: String): List<String> {
            val graph = releaseGraph(target)
            val bare = graph.copy(routes = emptyList(), actions = emptyMap(), wirings = emptyList())
            val second =
                bare.copy(
                    recipe = bare.recipe.copy(name = "second"),
                    program = bare.program.copy(name = "second"),
                )
            val process =
                bare.copy(
                    process =
                        ProcessMeta(
                            "process",
                            "order_number",
                            listOf(
                                StepMeta("first", bare.program.name, "key", emptyList(), null, emptyList(), bare),
                                StepMeta(
                                    "second",
                                    second.program.name,
                                    "key",
                                    listOf("inbound"),
                                    null,
                                    listOf("fulfilled", "due", "absent"),
                                    second,
                                ),
                            ),
                        ),
                )
            val hints = listOf("06:00 Europe/Prague", "Mon 09:00 Europe/Prague", "1st 09:00 Europe/Prague", "*/7")
            val scheduled =
                hints.map { hint ->
                    releaseGraph(target, true).let { it.copy(program = it.program.copy(schedule = hint)) }
                }
            return (listOf(graph, process) + scheduled)
                .flatMap { g ->
                    val xml =
                        CamundaRenderer(
                            RecipeEmitRequest(g, "{}", "", "{}"),
                            if (target ==
                                "camunda_7"
                            ) {
                                Dialect.C7
                            } else {
                                Dialect.C8
                            },
                        ).render()
                    val doc =
                        DocumentBuilderFactory
                            .newInstance()
                            .apply {
                                isNamespaceAware = true
                            }.newDocumentBuilder()
                            .parse(InputSource(StringReader(xml)))
                    val nodes = doc.getElementsByTagName("*")
                    buildList {
                        for (i in 0 until nodes.length) {
                            val e = nodes.item(i) as Element
                            for (a in 0 until e.attributes.length) add(e.attributes.item(a).nodeValue)
                            if (e.childNodes.length == 1 &&
                                e.firstChild.nodeType == org.w3c.dom.Node.TEXT_NODE
                            ) {
                                add(e.textContent)
                            }
                        }
                    }.filter { if (target == "camunda_7") it.startsWith("\${") else it.startsWith("=") }
                }.distinct()
        }
        "every generated C7 expression parses as JUEL and every C8 expression parses as FEEL" {
            val factory = ExpressionFactoryImpl()
            val context = SimpleContext()
            context.setFunction("", "S", ExpressionFunctions::class.java.getMethod("spin", Any::class.java))
            context.setFunction("", "dateTime", ExpressionFunctions::class.java.getMethod("dateTime"))
            expressions("camunda_7").forEach { expression ->
                withClue(expression) { factory.createValueExpression(context, expression, Any::class.java) }
            }
            val feel = FeelEngineBuilder.forJava().build()
            expressions("camunda_8").forEach { expression ->
                val parsed = feel.parseExpression(expression.removePrefix("="))
                withClue("$expression => $parsed") { parsed.isSuccess shouldBe true }
            }
        }
        "C7 every HTTP result rejects non-success responses and maps stale to the boundary error" {
            val xml = CamundaRenderer(RecipeEmitRequest(releaseGraph(), "{}", "", "{}"), Dialect.C7).render()
            val doc =
                DocumentBuilderFactory
                    .newInstance()
                    .apply {
                        isNamespaceAware = true
                    }.newDocumentBuilder()
                    .parse(InputSource(StringReader(xml)))
            val outputs = doc.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "outputParameter")
            val script =
                (0 until outputs.length)
                    .map { outputs.item(it) as Element }
                    .first {
                        it.getAttribute("name") ==
                            "ttrOpen"
                    }.textContent
            GroovyClassLoader().use { loader ->
                loader.parseClass(
                    "package org.camunda.bpm.engine.delegate; class BpmnError extends RuntimeException { BpmnError(String code, String message) { super(code + ': ' + message) } }",
                )

                fun run(code: Int) =
                    GroovyShell(
                        loader,
                        Binding(
                            mapOf(
                                "statusCode" to code,
                                "response" to """{"runId":"run"}""",
                            ),
                        ),
                    ).evaluate(script)
                for (code in listOf(200, 201, 202)) {
                    (run(code) as org.camunda.spin.json.SpinJsonNode).prop("runId").stringValue() shouldBe
                        "run"
                }
                for (code in listOf(400, 403, 500)) {
                    io.kotest.assertions.throwables
                        .shouldThrow<RuntimeException> { run(code) }
                        .message shouldBe
                        "run API HTTP $code"
                }
                io.kotest.assertions.throwables
                    .shouldThrow<RuntimeException> { run(409) }
                    .javaClass.name shouldBe
                    "org.camunda.bpm.engine.delegate.BpmnError"
            }
        }
        "calendar schedules evaluate to zoned ISO cycles and intervals retain their period" {
            val feel = FeelEngineBuilder.forJava().build()
            for (hint in listOf("06:00 Europe/Prague", "Mon 09:00 Europe/Prague", "1st 09:00 Europe/Prague")) {
                val cycle = CamundaSchedule.parse(hint, Dialect.C8).cycle
                val result = feel.evaluateExpression(cycle.removePrefix("="), emptyMap<String, Any>())
                withClue("$hint => $result") { result.isSuccess shouldBe true }
                require(result.result() is String) { "$hint => $result" }
                val value = result.result() as String
                value.startsWith("R/") shouldBe true
                value.contains("[Europe/Prague]/") shouldBe true
                java.time.ZonedDateTime
                    .parse(
                        value.removePrefix("R/").substringBeforeLast("/P"),
                    ).isAfter(java.time.ZonedDateTime.now()) shouldBe
                    true
            }
            CamundaSchedule.parse("*/7", Dialect.C8).cycle shouldBe "R/PT7M"
        }
        "C7 obligation glue evaluates until and nearest due instants, refusing a missing array" {
            GroovyClassLoader().use { loader ->
                // Test-only engine exception adapter: the shipped exporter has no engine dependency.
                loader.parseClass(
                    "package org.camunda.bpm.engine.delegate; class BpmnError extends RuntimeException { BpmnError(String code, String message) { super(code + ': ' + message) } }",
                )

                fun run(
                    until: List<String>,
                    body: String,
                ): Map<String, Any> {
                    val xml =
                        CamundaRenderer(
                            RecipeEmitRequest(processGraph("camunda_7", until), "{}", "", "{}"),
                            Dialect.C7,
                        ).render()
                    val doc =
                        DocumentBuilderFactory
                            .newInstance()
                            .apply {
                                isNamespaceAware = true
                            }.newDocumentBuilder()
                            .parse(InputSource(StringReader(xml)))
                    val script =
                        doc
                            .getElementsByTagNameNS(
                                "http://camunda.org/schema/1.0/bpmn",
                                "executionListener",
                            ).item(0)
                            .textContent
                    val execution = ScriptExecution(Json.parseToJsonElement(body))
                    GroovyShell(loader, Binding(mapOf("execution" to execution))).evaluate(script)
                    return execution.values
                }
                val past = """{"obligations":[{"kind":"inbound","state":"pending","dueAt":"2000-01-01T00:00:00Z"}]}"""
                run(listOf("fulfilled"), past)["ttrObligationReady"] shouldBe false
                run(listOf("due"), past)["ttrObligationReady"] shouldBe true
                val future =
                    java.time.OffsetDateTime
                        .now()
                        .plusSeconds(90)
                        .toString()
                val result =
                    run(
                        listOf("fulfilled"),
                        """{"obligations":[{"kind":"inbound","state":"pending","dueAt":"$future"}]}""",
                    )
                val seconds =
                    java.time.Duration
                        .parse(result.getValue("ttrObligationDelay") as String)
                        .seconds
                (seconds in 1..90) shouldBe true
                io.kotest.assertions.throwables
                    .shouldThrow<RuntimeException> {
                        run(listOf("absent"), "{}")
                    }.message!!
                    .contains("no obligations array") shouldBe
                    true
            }
        }
        "both dialects normalize numeric date and missing Retry-After values" {
            val feel = FeelEngineBuilder.forJava().build()
            val date =
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    java.time.ZonedDateTime
                        .now(java.time.ZoneOffset.UTC)
                        .plusSeconds(60),
                )
            for (headers in listOf(mapOf("Retry-After" to "37"), mapOf("retry-after" to date), emptyMap())) {
                for (dialect in Dialect.entries) {
                    val xml =
                        CamundaRenderer(
                            RecipeEmitRequest(
                                releaseGraph(
                                    if (dialect ==
                                        Dialect.C7
                                    ) {
                                        "camunda_7"
                                    } else {
                                        "camunda_8"
                                    },
                                ),
                                "{}",
                                "",
                                "{}",
                            ),
                            dialect,
                        ).render()
                    val doc =
                        DocumentBuilderFactory
                            .newInstance()
                            .apply {
                                isNamespaceAware = true
                            }.newDocumentBuilder()
                            .parse(InputSource(StringReader(xml)))
                    val delay: Number =
                        if (dialect == Dialect.C7) {
                            val outputs =
                                doc.getElementsByTagNameNS(
                                    "http://camunda.org/schema/1.0/bpmn",
                                    "outputParameter",
                                )
                            val script =
                                (0 until outputs.length)
                                    .map { outputs.item(it) as Element }
                                    .first {
                                        it.getAttribute("name") ==
                                            "ttrOpenRetryAfter"
                                    }.textContent
                            GroovyShell(Binding(mapOf("headers" to headers))).evaluate(script) as Number
                        } else {
                            val outputs = doc.getElementsByTagNameNS("http://camunda.org/schema/zeebe/1.0", "header")
                            val expression =
                                (0 until outputs.length)
                                    .map { outputs.item(it) as Element }
                                    .first {
                                        it.getAttribute("key") ==
                                            "resultExpression"
                                    }.getAttribute("value")
                            val evaluated =
                                feel.evaluateExpression(
                                    expression.removePrefix("="),
                                    mapOf<String, Any>(
                                        "response" to
                                            mapOf(
                                                "headers" to headers,
                                                "body" to emptyMap<String, Any>(),
                                                "status" to 201,
                                            ),
                                    ),
                                )
                            require(evaluated.isSuccess && evaluated.result() is Map<*, *>) { evaluated.toString() }
                            (evaluated.result() as Map<*, *>)["ttrOpenRetryAfter"] as Number
                        }
                    when {
                        headers.isEmpty() -> delay.toLong() shouldBe 15
                        headers["Retry-After"] == "37" -> delay.toLong() shouldBe 37
                        else -> (delay.toLong() in 1..60) shouldBe true
                    }
                }
            }
        }
        "mail and approval predicates in both dialects require the selected key and an explicitly wet Server" {
            val feel = FeelEngineBuilder.forJava().build()
            val factory = ExpressionFactoryImpl()
            for (dialect in Dialect.entries) {
                val target = if (dialect == Dialect.C7) "camunda_7" else "camunda_8"
                val base = releaseGraph(target)
                val graph = base.copy(deployment = base.deployment.copy(dryRun = false))
                val xml = CamundaRenderer(RecipeEmitRequest(graph, "{}", "", "{}"), dialect).render()
                val doc =
                    DocumentBuilderFactory
                        .newInstance()
                        .apply {
                            isNamespaceAware = true
                        }.newDocumentBuilder()
                        .parse(InputSource(StringReader(xml)))
                val flows = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "sequenceFlow")
                for (action in listOf("notify", "approve_release")) {
                    val predicate =
                        (0 until flows.length)
                            .map { flows.item(it) as Element }
                            .first {
                                it.getAttribute("name") ==
                                    "$action selected and wet"
                            }.textContent
                            .trim()
                    for (selected in listOf(true, false)) {
                        for (dry in listOf(true, false)) {
                            val key = if (selected) 4711 else 9
                            val selectedAction = mapOf("action" to action, "key" to mapOf("order_number" to key))
                            val status = mapOf("dryRun" to dry, "actions" to listOf(selectedAction))
                            val result: Any? =
                                if (dialect == Dialect.C7) {
                                    val context = SimpleContext()
                                    context.setVariable(
                                        "ttrStatus",
                                        factory.createValueExpression(Spin.JSON(status), Any::class.java),
                                    )
                                    context.setVariable(
                                        "ttrRow",
                                        factory.createValueExpression(
                                            Spin.JSON("""{"order_number":4711}"""),
                                            Any::class.java,
                                        ),
                                    )
                                    factory.createValueExpression(context, predicate, Any::class.java).getValue(context)
                                } else {
                                    val vars =
                                        mapOf<String, Any>(
                                            "ttrStatus" to status,
                                            "ttrRow" to mapOf("order_number" to 4711),
                                        )
                                    val evaluated = feel.evaluateExpression(predicate.removePrefix("="), vars)
                                    require(evaluated.isSuccess) { evaluated.toString() }
                                    evaluated.result()
                                }
                            withClue("$dialect selected=$selected dry=$dry predicate=$predicate") {
                                result shouldBe
                                    (selected && !dry)
                            }
                        }
                    }
                }
            }
        }
        "both dialects preserve step clocks and anchor later caller keys; none ports stay untimed" {
            val feel = FeelEngineBuilder.forJava().build()
            val factory = ExpressionFactoryImpl()
            for (target in listOf("camunda_7", "camunda_8")) {
                for (graph in listOf(processGraph(target), releaseGraph(target, true))) {
                    val dialect = if (target == "camunda_7") Dialect.C7 else Dialect.C8
                    val xml = CamundaRenderer(RecipeEmitRequest(graph, "{}", "", "{}"), dialect).render()
                    val doc =
                        DocumentBuilderFactory
                            .newInstance()
                            .apply {
                                isNamespaceAware = true
                            }.newDocumentBuilder()
                            .parse(InputSource(StringReader(xml)))
                    val inputs =
                        doc.getElementsByTagNameNS(
                            if (dialect ==
                                Dialect.C7
                            ) {
                                "http://camunda.org/schema/1.0/bpmn"
                            } else {
                                "http://camunda.org/schema/zeebe/1.0"
                            },
                            if (dialect ==
                                Dialect.C7
                            ) {
                                "inputParameter"
                            } else {
                                "input"
                            },
                        )
                    val bodies =
                        (0 until inputs.length).map { inputs.item(it) as Element }.filter {
                            it.getAttribute(
                                if (dialect ==
                                    Dialect.C7
                                ) {
                                    "name"
                                } else {
                                    "target"
                                },
                            ) ==
                                "ttrOpenBody"
                        }
                    val variables =
                        mapOf<String, Any>(
                            "ttrInput" to mapOf("key" to 4711),
                            "ttrStepAt" to "2026-11-01T10:00:00Z",
                            "ttrAnchor" to "first-run",
                            "ttrExecutionId" to "execution",
                            "execution" to C7Execution("execution"),
                        )
                    val context = SimpleContext()
                    context.setFunction("", "S", Spin::class.java.getMethod("S", Any::class.java))
                    variables.forEach { (name, value) ->
                        context.setVariable(name, factory.createValueExpression(value, Any::class.java))
                    }
                    for ((index, body) in bodies.withIndex()) {
                        val result: Map<*, *> =
                            if (dialect == Dialect.C7) {
                                val node =
                                    factory
                                        .createValueExpression(
                                            context,
                                            body.textContent,
                                            Any::class.java,
                                        ).getValue(context)
                                val json = Json.parseToJsonElement(node.toString()).jsonObject
                                json.mapValues { (_, v) ->
                                    if (v is JsonObject) {
                                        v.mapValues { (_, x) ->
                                            x.jsonPrimitive.content
                                        }
                                    } else {
                                        v.jsonPrimitive.content
                                    }
                                }
                            } else {
                                val evaluated =
                                    feel.evaluateExpression(
                                        body.getAttribute("source").removePrefix("="),
                                        variables,
                                    )
                                require(evaluated.isSuccess && evaluated.result() is Map<*, *>) { evaluated.toString() }
                                evaluated.result() as Map<*, *>
                            }
                        if (graph.process != null) {
                            if (index == 0) {
                                result.containsKey("idempotencyKey") shouldBe false
                            } else {
                                result["idempotencyKey"] shouldBe "first-run:second"
                            }
                            (result["port"] as Map<*, *>)["at"] shouldBe "2026-11-01T10:00:00Z"
                        } else {
                            (result["port"] as Map<*, *>).keys shouldBe setOf("none")
                            result["idempotencyKey"] shouldBe "execution"
                        }
                    }
                }
            }
        }
    })

/** Parsing registers the same function names as C7; these functions are not invoked by parser checks. */
object ExpressionFunctions {
    @JvmStatic fun spin(value: Any): Any = value

    @JvmStatic fun dateTime(): Any = error("parse only")
}

data class C7Execution(
    val processInstanceId: String,
)

/** Minimal Spin/execution adapters for the script's pure clock/readiness logic, not an engine simulation. */
class ScriptExecution(
    body: JsonElement,
) {
    val values = mutableMapOf<String, Any>("ttrObligations" to ScriptJson(body))

    fun getVariable(name: String): Any? = values[name]

    fun setVariable(
        name: String,
        value: Any,
    ) {
        values[name] = value
    }
}

class ScriptJson(
    private val value: JsonElement,
) {
    fun hasProp(name: String): Boolean = name in value.jsonObject

    fun prop(name: String): ScriptJson = ScriptJson(value.jsonObject.getValue(name))

    fun isArray(): Boolean = value is JsonArray

    fun isNull(): Boolean = value == JsonNull

    fun elements(): List<ScriptJson> = value.jsonArray.map(::ScriptJson)

    fun stringValue(): String = value.jsonPrimitive.content
}
