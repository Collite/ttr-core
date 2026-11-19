// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.camunda.bpm.impl.juel.ExpressionFactoryImpl
import org.camunda.bpm.impl.juel.SimpleContext
import org.camunda.feel.api.FeelEngineBuilder
import org.camunda.spin.Spin
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest
import org.tatrman.ttrp.emit.spi.RecipeGraph
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AU S1.5.10 (ttr-server contracts §1.2, ✅R254-A): a Server key is always the column-named object. Every emitted
 * `perform` body sends `keys: [{<key column>: value, …}]` and every `outcome` body `key: {…}`, for one-column keys too.
 * The bodies are evaluated locally (JUEL + Spin for C7, FEEL for C8); no engine is involved.
 */
class PerformKeySpec :
    StringSpec({
        val row = mapOf("order_number" to 4711, "line_no" to 2)

        fun twoColumn(graph: RecipeGraph) =
            graph.copy(recipe = graph.recipe.copy(key = listOf("order.order_number", "order.line_no")))

        /** The request body of every service task whose name matches [task], evaluated against [row]. */
        fun bodies(
            graph: RecipeGraph,
            dialect: Dialect,
            task: (String) -> Boolean,
        ): List<Pair<String, JsonObject>> {
            val xml = CamundaRenderer(RecipeEmitRequest(graph, "{}", "", "{}"), dialect).render()
            val doc =
                DocumentBuilderFactory
                    .newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(InputSource(StringReader(xml)))
            val tasks = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "serviceTask")
            return (0 until tasks.length)
                .map { tasks.item(it) as Element }
                .filter { task(it.getAttribute("name")) }
                .map { element ->
                    val name = element.getAttribute("name")
                    val body =
                        if (dialect == Dialect.C7) {
                            val inputs =
                                element.getElementsByTagNameNS(
                                    "http://camunda.org/schema/1.0/bpmn",
                                    "inputParameter",
                                )
                            val payload =
                                (0 until inputs.length)
                                    .map { inputs.item(it) as Element }
                                    .single { it.getAttribute("name") == "payload" }
                                    .textContent
                            val factory = ExpressionFactoryImpl()
                            val context = SimpleContext()
                            context.setFunction("", "S", Spin::class.java.getMethod("S", Any::class.java))
                            context.setFunction("", "dateTime", ReviewFunctions::class.java.getMethod("dateTime"))
                            context.setVariable(
                                "ttrRow",
                                factory.createValueExpression(
                                    Spin.JSON(Json.encodeToString(JsonObject.serializer(), json(row))),
                                    Any::class.java,
                                ),
                            )
                            val value =
                                factory
                                    .createValueExpression(
                                        context,
                                        payload,
                                        Any::class.java,
                                    ).getValue(context)
                            Json.parseToJsonElement(value.toString()).jsonObject
                        } else {
                            val inputs = element.getElementsByTagNameNS("http://camunda.org/schema/zeebe/1.0", "input")
                            val source =
                                (0 until inputs.length)
                                    .map { inputs.item(it) as Element }
                                    .single { it.getAttribute("target") == "body" }
                                    .getAttribute("source")
                            val evaluated =
                                FeelEngineBuilder
                                    .forJava()
                                    .build()
                                    .evaluateExpression(source.removePrefix("="), mapOf<String, Any>("ttrRow" to row))
                            require(evaluated.isSuccess) { "$name: $evaluated" }
                            toJson(evaluated.result()).jsonObject
                        }
                    name to body
                }
        }

        /** Every `ttrOpenBody` input, evaluated with the caller's input `{key: 4711}` (a `port: key` program). */
        fun openBodies(
            graph: RecipeGraph,
            dialect: Dialect,
        ): List<JsonObject> {
            val xml = CamundaRenderer(RecipeEmitRequest(graph, "{}", "", "{}"), dialect).render()
            val doc =
                DocumentBuilderFactory
                    .newInstance()
                    .apply { isNamespaceAware = true }
                    .newDocumentBuilder()
                    .parse(InputSource(StringReader(xml)))
            val variables =
                mapOf<String, Any>(
                    "ttrInput" to mapOf("key" to 4711, "at" to "2026-11-01T10:00:00Z"),
                    "ttrStepAt" to "2026-11-01T10:00:00Z",
                    "ttrAnchor" to "first-run",
                    "ttrExecutionId" to "execution",
                    "execution" to C7Execution("execution"),
                )
            return if (dialect == Dialect.C7) {
                val inputs = doc.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter")
                val factory = ExpressionFactoryImpl()
                val context = SimpleContext()
                context.setFunction("", "S", Spin::class.java.getMethod("S", Any::class.java))
                context.setFunction("", "dateTime", ReviewFunctions::class.java.getMethod("dateTime"))
                variables.forEach { (name, value) ->
                    context.setVariable(name, factory.createValueExpression(value, Any::class.java))
                }
                (0 until inputs.length)
                    .map { inputs.item(it) as Element }
                    .filter { it.getAttribute("name") == "ttrOpenBody" }
                    .map {
                        val value =
                            factory
                                .createValueExpression(
                                    context,
                                    it.textContent,
                                    Any::class.java,
                                ).getValue(context)
                        Json.parseToJsonElement(value.toString()).jsonObject
                    }
            } else {
                val inputs = doc.getElementsByTagNameNS("http://camunda.org/schema/zeebe/1.0", "input")
                val feel = FeelEngineBuilder.forJava().build()
                (0 until inputs.length)
                    .map { inputs.item(it) as Element }
                    .filter { it.getAttribute("target") == "ttrOpenBody" }
                    .map {
                        val evaluated = feel.evaluateExpression(it.getAttribute("source").removePrefix("="), variables)
                        require(evaluated.isSuccess) { evaluated.toString() }
                        toJson(evaluated.result()).jsonObject
                    }
            }
        }

        for (dialect in listOf(Dialect.C7, Dialect.C8)) {
            val target = if (dialect == Dialect.C7) "camunda_7" else "camunda_8"
            "$dialect: a one-column key's perform body sends keys [{order_number: value}], never a bare scalar" {
                for (graph in listOf(releaseGraph(target), releaseGraph(target, true))) {
                    val performs = bodies(graph, dialect) { it.startsWith("perform ") }
                    performs.shouldNotBeEmpty()
                    performs.forEach { (name, body) ->
                        withClue(name) { body["keys"] shouldBe JsonArray(listOf(json(mapOf("order_number" to 4711)))) }
                    }
                }
            }
            "$dialect: a two-column key's perform body names every key column (pinned)" {
                for (graph in listOf(releaseGraph(target), releaseGraph(target, true)).map(::twoColumn)) {
                    val performs = bodies(graph, dialect) { it.startsWith("perform ") }
                    performs.shouldNotBeEmpty()
                    performs.forEach { (name, body) ->
                        withClue(name) { body["keys"] shouldBe JsonArray(listOf(json(row))) }
                    }
                }
            }
            "$dialect: an outcome body's key is the same column-named object (§1.2 OutcomeItem.key)" {
                for (graph in listOf(releaseGraph(target), twoColumn(releaseGraph(target)))) {
                    val expected = json(row.filterKeys { "order.$it" in graph.recipe.key })
                    val outcomes = bodies(graph, dialect) { it.endsWith(" delivered") }
                    outcomes.shouldNotBeEmpty()
                    outcomes.forEach { (name, body) ->
                        withClue(name) {
                            body["outcomes"]!!.jsonArray.map { it.jsonObject["key"] } shouldBe listOf(expected)
                        }
                    }
                }
            }
            "$dialect: T1(d) the Open's port.key stays the caller's value, never an object (review-379 L2)" {
                val opens = openBodies(releaseGraph(target), dialect)
                opens.shouldNotBeEmpty()
                opens.forEach { body ->
                    withClue(body) { body.getValue("port").jsonObject["key"] shouldBe JsonPrimitive(4711) }
                }
            }
        }
    })

private fun json(values: Map<String, Any>): JsonObject =
    buildJsonObject {
        values.forEach { (k, v) ->
            when (v) {
                is Number -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

/** FEEL hands back Scala-converted Java values; numbers compare by value, as the Server's key match does. */
private fun toJson(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        is java.math.BigDecimal ->
            value.stripTrailingZeros().let { JsonPrimitive(runCatching { it.toBigIntegerExact() }.getOrDefault(it)) }
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
