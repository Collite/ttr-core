// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import groovy.lang.Binding
import groovy.lang.GroovyShell
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.camunda.bpm.impl.juel.ExpressionFactoryImpl
import org.camunda.bpm.impl.juel.SimpleContext
import org.camunda.spin.Spin
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** The complete listener supplies the answer's identity; the service task has no `task` binding. */
class AnswerOpenSpec :
    StringSpec({
        for (batch in listOf(false, true)) {
            "engine approval completion opens an answer with captured task identity (batch=$batch)" {
                val graph = releaseGraph("process_engine", batch)
                val xml =
                    CamundaRenderer(
                        RecipeEmitRequest(
                            graph.copy(deployment = graph.deployment.copy(egressUrl = "http://egress/automation")),
                            "{}",
                            "",
                            "{}",
                        ),
                        Dialect.C7,
                        engine = true,
                    ).render()
                validateBpmn(xml)
                val doc =
                    DocumentBuilderFactory
                        .newInstance()
                        .apply { isNamespaceAware = true }
                        .newDocumentBuilder()
                        .parse(InputSource(StringReader(xml)))

                fun elements(
                    namespace: String,
                    name: String,
                ): List<Element> {
                    val nodes = doc.getElementsByTagNameNS(namespace, name)
                    return (0 until nodes.length).map { nodes.item(it) as Element }
                }
                val user = elements(BPMN, "userTask").single { it.getAttribute("name") == "approve approve_release" }
                val listeners = user.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "taskListener")
                listeners.length shouldBe 1
                val listener = listeners.item(0) as Element
                listener.getAttribute("event") shouldBe "complete"
                val task = AnswerTask()
                GroovyShell(Binding(mapOf("task" to task))).evaluate(listener.textContent)
                task.execution.variables shouldBe mapOf("ttrTaskId" to "task-42", "ttrAnsweredBy" to "alice")
                val answer =
                    elements(
                        BPMN,
                        "serviceTask",
                    ).single { it.getAttribute("name") == "answer approve_release" }
                answer.getAttributeNS("http://camunda.org/schema/1.0/bpmn", "asyncBefore") shouldBe ""
                answer
                    .getElementsByTagNameNS(
                        "http://camunda.org/schema/1.0/bpmn",
                        "failedJobRetryTimeCycle",
                    ).length shouldBe
                    0
                val answerStep = answer.parentNode as Element
                val flows = elements(BPMN, "sequenceFlow")
                flows.any {
                    it.getAttribute("sourceRef") == user.getAttribute("id") &&
                        it.getAttribute("targetRef") == answerStep.getAttribute("id")
                } shouldBe
                    true
                val done =
                    elements(BPMN, "exclusiveGateway").single {
                        it.getAttribute("name") ==
                            "approve_release complete"
                    }
                flows.any {
                    it.getAttribute("sourceRef") == answerStep.getAttribute("id") &&
                        it.getAttribute("targetRef") == done.getAttribute("id")
                } shouldBe
                    true
                val inputs = answerStep.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter")
                val payload =
                    (0 until inputs.length)
                        .map { inputs.item(it) as Element }
                        .single { it.getAttribute("name") == "ttrAnswerOpenBody" }
                        .textContent
                val factory = ExpressionFactoryImpl()
                val context = SimpleContext()
                context.setFunction("", "S", Spin::class.java.getMethod("S", Any::class.java))
                context.setFunction("", "dateTime", ReviewFunctions::class.java.getMethod("dateTime"))
                (
                    task.execution.variables +
                        mapOf(
                            "execution" to EngineOpenExecution(),
                            "ttrAnchor" to "anchor-1",
                            "ttrInput" to mapOf("key" to 9999),
                            "ttrRow" to Spin.JSON("""{"order_number":4711}"""),
                        )
                ).forEach { (name, value) ->
                    context.setVariable(name, factory.createValueExpression(value, Any::class.java))
                }
                val anchor =
                    elements("http://camunda.org/schema/1.0/bpmn", "inputParameter")
                        .single { it.getAttribute("name") == "ttrAnchor" }
                anchor.textContent shouldBe "\${ttrOpen.prop(\"runId\").stringValue()}"
                val value = factory.createValueExpression(context, payload, Any::class.java).getValue(context)
                context.setVariable("ttrAnswerOpenBody", factory.createValueExpression(value, Any::class.java))
                val answerInputs = answer.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter")
                val savedPayload =
                    (0 until answerInputs.length)
                        .map { answerInputs.item(it) as Element }
                        .single { it.getAttribute("name") == "payload" }
                        .textContent
                factory
                    .createValueExpression(
                        context,
                        savedPayload,
                        Any::class.java,
                    ).getValue(context)
                    .toString() shouldBe
                    value.toString()
                val open = Json.parseToJsonElement(value.toString()).jsonObject
                open.getValue("engine") shouldBe
                    Json.parseToJsonElement(
                        """{"instanceId":"instance-156","businessKey":"order-4711","deploymentId":"engine-deployment-9","taskId":"task-42","answeredBy":"alice"}""",
                    )
                open.getValue("idempotencyKey") shouldBe JsonPrimitive("anchor-1:answer:task-42")
                open.getValue("deployment") shouldBe JsonPrimitive("release_bpmn")
                open.getValue("port").jsonObject.getValue("key") shouldBe JsonPrimitive(4711)
                open.getValue("port").jsonObject.containsKey("at") shouldBe true

                // Grammar 0.22's approval shape (a writer sink, the engine's door): asked, never performed directly.
                elements(BPMN, "serviceTask").none { it.getAttribute("name") == "perform approve_release" } shouldBe
                    true

                // The answer chain (D-228/D-230): open → evaluate → status → close of the answered run, inside the step.
                fun named(name: String) = elements(BPMN, "serviceTask").single { it.getAttribute("name") == name }

                fun inside(
                    element: Element,
                    ancestor: Element,
                ): Boolean = generateSequence(element.parentNode) { it.parentNode }.any { it === ancestor }
                val chain = listOf("answer evaluate", "answer evaluation status", "answer close").map(::named)
                chain.forEach { inside(it, answerStep) shouldBe true }
                val calls =
                    elements(BPMN, "serviceTask")
                        .filter { inside(it, answerStep) }
                        .map { it.getAttribute("name") }
                // The four calls of the answered run, plus the 202 handle's status poll (the root's loop, reused).
                calls shouldBe
                    listOf(
                        "answer approve_release",
                        "answer close",
                        "answer evaluate",
                        "answer evaluation status",
                        "handle status",
                    )

                fun url(task: Element): String =
                    (0 until task.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter").length)
                        .map {
                            task.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter").item(it)
                                as Element
                        }.single { it.getAttribute("name") == "url" }
                        .textContent
                chain.forEach {
                    url(it).contains("ttrRunId") shouldBe false
                    url(it).contains("ttrAnswerOpen.prop(\"runId\").stringValue()") shouldBe true
                    it.getAttributeNS("http://camunda.org/schema/1.0/bpmn", "asyncBefore") shouldBe "true"
                }
                url(named("answer evaluate")).endsWith(".concat(\"/evaluate\")}") shouldBe true
                url(named("answer close")).endsWith(".concat(\"/close\")}") shouldBe true
                // Only the Open carries a body with the engine block; evaluate/close send `{}`.
                listOf("answer evaluate", "answer close").forEach { name ->
                    val body =
                        (
                            0 until
                                named(
                                    name,
                                ).getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputParameter").length
                        ).map {
                            named(
                                name,
                            ).getElementsByTagNameNS(
                                "http://camunda.org/schema/1.0/bpmn",
                                "inputParameter",
                            ).item(it)
                                as Element
                        }.single { it.getAttribute("name") == "payload" }
                            .textContent
                    body shouldBe "\${(S('{}')).toString()}"
                }
                // The shared poll: the answer's evaluation reads status until the verdict is ready, as the root does.
                elements(BPMN, "endEvent").count { it.getAttribute("name") == "answer verdict ready" } shouldBe 1
                elements(
                    BPMN,
                    "intermediateCatchEvent",
                ).count { it.getAttribute("name") == "answer evaluation poll" } shouldBe
                    1
                elements(BPMN, "endEvent").count { it.getAttribute("name") == "verdict ready" } shouldBe 1
                // No route is read again inside the answer: the approval is not re-asked.
                elements(BPMN, "userTask").count { inside(it, answerStep) } shouldBe 0
                elements(BPMN, "subProcess").none {
                    inside(it, answerStep) &&
                        it.getAttribute("name").startsWith("page ")
                } shouldBe
                    true
                // Row-local variables (R323-4): every answer variable is declared null on the answer step.
                val declared =
                    (0 until answerStep.childNodes.length)
                        .map { answerStep.childNodes.item(it) }
                        .filterIsInstance<Element>()
                        .single { it.localName == "extensionElements" }
                        .getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputOutput")
                        .item(0)
                        .childNodes
                        .let { nodes -> (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>() }
                        .filter { it.localName == "inputParameter" }
                        .associate { it.getAttribute("name") to it.textContent }
                listOf("ttrAnswerOpen", "ttrAnswerEvaluation", "ttrAnswerClosed", "ttrHandle").forEach { v ->
                    listOf(v, "${v}Code", "${v}Headers", "${v}RetryAfter").forEach { declared[it] shouldBe "\${null}" }
                }
            }
        }
        for (target in listOf("camunda_7", "camunda_8")) {
            "the customer target $target renders the approval user task without an answer chain" {
                val dialect = if (target == "camunda_7") Dialect.C7 else Dialect.C8
                val xml = CamundaRenderer(RecipeEmitRequest(releaseGraph(target), "{}", "", "{}"), dialect).render()
                xml.contains("name=\"approve approve_release\"") shouldBe true
                xml.contains("name=\"answer ") shouldBe false
                xml.contains("ttrAnswer") shouldBe false
            }
        }
    })

class AnswerTask {
    val id = "task-42"
    val assignee = "alice"
    val execution = AnswerExecution()
}

class AnswerExecution {
    val variables = mutableMapOf<String, Any>()

    fun setVariable(
        name: String,
        value: Any,
    ) {
        variables[name] = value
    }
}
