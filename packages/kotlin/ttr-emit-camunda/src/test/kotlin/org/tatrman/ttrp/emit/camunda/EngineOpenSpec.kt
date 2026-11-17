// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import org.camunda.bpm.impl.juel.ExpressionFactoryImpl
import org.camunda.bpm.impl.juel.SimpleContext
import org.camunda.spin.Spin
import org.tatrman.ttrp.emit.spi.*
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** S1.5.6: execute the engine Open's JUEL against the public execution/repository-service shape. */
class EngineOpenSpec :
    StringSpec({
        fun request(batch: Boolean = false): RecipeEmitRequest {
            val graph = releaseGraph("process_engine", batch)
            return RecipeEmitRequest(
                graph.copy(deployment = graph.deployment.copy(egressUrl = "http://egress/automation")),
                """{"world":"hartland.worlds.demo"}""",
                "",
                "{}",
                WorldChoices(
                    programs = listOf(ProgramChoice("release_on_placement", "release_order", "key", emptyList())),
                ),
            )
        }

        for (batch in listOf(false, true)) {
            "engine Open has instance, business key and running definition deployment (batch=$batch)" {
                val xml =
                    ProcessEngineEmitPlugin()
                        .emitRecipe(
                            request(batch),
                        ).files
                        .getValue("release_order.c7.bpmn")
                        .decodeToString()
                val doc =
                    DocumentBuilderFactory
                        .newInstance()
                        .apply { isNamespaceAware = true }
                        .newDocumentBuilder()
                        .parse(InputSource(StringReader(xml)))
                val nodes = doc.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter")
                val bodies =
                    (0 until nodes.length)
                        .map { nodes.item(it) as Element }
                        .filter { it.getAttribute("name") == "ttrOpenBody" }
                bodies.isNotEmpty() shouldBe true
                val factory = ExpressionFactoryImpl()
                val context = SimpleContext()
                context.setFunction("", "S", Spin::class.java.getMethod("S", Any::class.java))
                context.setFunction("", "dateTime", ReviewFunctions::class.java.getMethod("dateTime"))
                mapOf(
                    "execution" to EngineOpenExecution(),
                    "ttrInput" to mapOf("key" to 4711, "at" to "2026-11-16T10:00:00Z"),
                ).forEach { (name, value) ->
                    context.setVariable(name, factory.createValueExpression(value, Any::class.java))
                }
                bodies.forEach { body ->
                    val value =
                        factory
                            .createValueExpression(
                                context,
                                body.textContent,
                                Any::class.java,
                            ).getValue(context)
                    val open = Json.parseToJsonElement(value.toString()).jsonObject
                    open.getValue("engine") shouldBe
                        Json.parseToJsonElement(
                            """{"instanceId":"instance-156","businessKey":"order-4711","deploymentId":"engine-deployment-9"}""",
                        )
                    open.getValue("deployment") shouldBe JsonPrimitive("release_bpmn")
                    if (!batch) {
                        open.getValue("port").jsonObject.getValue("at") shouldBe
                            JsonPrimitive("2026-11-16T10:00:00Z")
                    }
                }
            }

            "engine BPMN and Hidden template payload goldens (batch=$batch)" {
                val files = ProcessEngineEmitPlugin().emitRecipe(request(batch)).files
                val paths =
                    if (batch) {
                        listOf("release_order.c7.bpmn")
                    } else {
                        listOf("release_order.c7.bpmn", "templates/c7/release_on_placement.json")
                    }
                paths.forEach { relative ->
                    val golden = Path.of("src/test/resources/goldens/engine/${if (batch) "batch/" else ""}$relative")
                    val bytes = files.getValue(relative)
                    if (System.getenv("UPDATE_AU_EXPORT_GOLDENS") == "1") {
                        Files.createDirectories(golden.parent)
                        Files.write(golden, bytes)
                    }
                    Files.readAllBytes(golden).contentEquals(bytes) shouldBe true
                }
            }
        }

        for (target in listOf("camunda_7", "camunda_8")) {
            "customer $target BPMN and templates carry no engine block" {
                val plugin = if (target == "camunda_7") Camunda7EmitPlugin() else Camunda8EmitPlugin()
                val engineRequest = request()
                val files = plugin.emitRecipe(engineRequest.copy(graph = releaseGraph(target))).files
                files.filterKeys { it.endsWith(".bpmn") || it.startsWith("templates/") }.values.forEach {
                    val text = it.decodeToString()
                    text shouldNotContain "prop(&quot;engine&quot;,"
                    text shouldNotContain "&quot;engine&quot;:"
                    text shouldNotContain "\"engine\":"
                    text shouldNotContain "engine:"
                    text shouldNotContain "processEngineServices"
                }
            }
        }
    })

open class EngineOpenExecution {
    val processInstanceId = "instance-156"
    val processBusinessKey = "order-4711"
    val processDefinitionId = "release:3:definition-7"
    val processEngineServices = EngineOpenServices()
}

class EngineOpenServices {
    val repositoryService = EngineOpenRepository()
}

class EngineOpenRepository {
    fun getProcessDefinition(id: String): EngineOpenDefinition {
        require(id == "release:3:definition-7")
        return EngineOpenDefinition()
    }
}

class EngineOpenDefinition {
    val deploymentId = "engine-deployment-9"
}
