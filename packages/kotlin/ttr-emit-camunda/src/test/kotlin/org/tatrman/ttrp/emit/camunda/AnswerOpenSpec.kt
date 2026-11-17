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
                open.getValue("port").jsonObject.getValue("key") shouldBe buildJsonObject { put("order_number", 4711) }
                open.getValue("port").jsonObject.containsKey("at") shouldBe true

                // Grammar 0.22's approval shape (a writer sink, the engine's door): asked, never performed directly.
                elements(BPMN, "serviceTask").none { it.getAttribute("name") == "perform approve_release" } shouldBe
                    true

                // D-233: the answer Open is the row's one call; the response decides.
                fun inside(
                    element: Element,
                    ancestor: Element,
                ): Boolean = generateSequence(element.parentNode) { it.parentNode }.any { it === ancestor }
                elements(BPMN, "serviceTask").filter { inside(it, answerStep) }.map { it.getAttribute("name") } shouldBe
                    listOf("answer approve_release")
                xml.contains("answer evaluate") shouldBe false
                xml.contains("answer close") shouldBe false
                val script =
                    answer
                        .getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "script")
                        .let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent } }
                        .single { "statusCode" in it }
                // A 409 on the answer (answer-unmatched, *-stale) refuses with the body; it is not an outdated export.
                script.contains("TTR_STALE") shouldBe false
                script.contains("TTR_REFUSED") shouldBe true
                // No route is read again inside the answer: the approval is not re-asked.
                elements(BPMN, "userTask").count { inside(it, answerStep) } shouldBe 0

                fun mapping(
                    element: Element,
                    kind: String,
                ): Map<String, String> =
                    (0 until element.childNodes.length)
                        .map { element.childNodes.item(it) }
                        .filterIsInstance<Element>()
                        .single { it.localName == "extensionElements" }
                        .getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "inputOutput")
                        .item(0)
                        .childNodes
                        .let { nodes -> (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>() }
                        .filter { it.localName == kind }
                        .associate { it.getAttribute("name") to it.textContent }
                val stepInputs = mapping(answerStep, "inputParameter")
                listOf(
                    "",
                    "Code",
                    "Headers",
                    "RetryAfter",
                ).forEach { stepInputs["ttrAnswerOpen$it"] shouldBe "\${null}" }
                val outputs = mapping(answerStep, "outputParameter")
                outputs.keys shouldBe setOf("ttrAnswerRunId", "ttrAnswerStatus", "ttrAnswerDecision")
                // C7 runs these outputs on cancellation too (a refused Open): each is guarded (D-229).
                outputs.values.forEach {
                    it.startsWith("\${execution.getVariable('ttrAnswerOpen') != null && ") shouldBe
                        true
                }
                // Row-local (R323-4): the row declares them, so none reaches the process instance.
                val row =
                    generateSequence(answerStep.parentNode) { it.parentNode }.filterIsInstance<Element>().first {
                        it.localName == "subProcess"
                    }
                val rowInputs = mapping(row, "inputParameter")
                listOf("ttrTaskId", "ttrAnsweredBy", "ttrAnswerRunId", "ttrAnswerStatus", "ttrAnswerDecision").forEach {
                    rowInputs[it] shouldBe "\${null}"
                }
                // The decision gateway: closed + commit continues; anything else refuses (an error end, run_refused).
                val decided =
                    elements(BPMN, "exclusiveGateway").single {
                        it.getAttribute("name") ==
                            "answer approve_release decided"
                    }
                flows.any {
                    it.getAttribute("sourceRef") == answerStep.getAttribute("id") &&
                        it.getAttribute("targetRef") == decided.getAttribute("id")
                } shouldBe true
                val outgoing = flows.filter { it.getAttribute("sourceRef") == decided.getAttribute("id") }
                outgoing.size shouldBe 2
                val toDone = outgoing.single { it.getAttribute("targetRef") == done.getAttribute("id") }
                toDone.textContent.trim() shouldBe
                    "\${(ttrAnswerStatus == \"closed\") && (ttrAnswerDecision == \"commit\")}"
                val refusal = outgoing.single { it !== toDone }
                decided.getAttribute("default") shouldBe refusal.getAttribute("id")
                val refusedEnd =
                    elements(BPMN, "endEvent").single {
                        it.getAttribute("id") ==
                            refusal.getAttribute("targetRef")
                    }
                (
                    refusedEnd
                        .getElementsByTagNameNS(
                            BPMN,
                            "errorEventDefinition",
                        ).item(0) as Element
                ).getAttribute("errorRef") shouldBe
                    "run_refused"
                // The engine keeps the refusal's message for the person who inspects the run.
                elements(BPMN, "errorEventDefinition").count {
                    it.getAttribute("errorRef") == "run_refused" &&
                        it.getAttributeNS("http://camunda.org/schema/1.0/bpmn", "errorMessageVariable") ==
                        "ttrRefusedReason"
                } shouldBe 1
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
