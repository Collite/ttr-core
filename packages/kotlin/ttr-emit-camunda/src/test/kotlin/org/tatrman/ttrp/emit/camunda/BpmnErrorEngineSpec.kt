// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import groovy.lang.Binding
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyShell
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** T2b: each engine sees only its own BpmnError class, as on C7/CIB seven installations. */
class BpmnErrorEngineSpec :
    StringSpec({
        for (enginePackage in listOf("org.camunda", "org.cibseven")) {
            "$enginePackage output scripts preserve BPMN error codes and retryable failures" {
                val graph = releaseGraph("process_engine")
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
                val doc =
                    DocumentBuilderFactory
                        .newInstance()
                        .apply { isNamespaceAware = true }
                        .newDocumentBuilder()
                        .parse(InputSource(StringReader(xml)))
                val nodes = doc.getElementsByTagNameNS("http://camunda.org/schema/1.0/bpmn", "script")
                val scripts =
                    (0 until nodes.length)
                        .map { (nodes.item(it) as Element).textContent }
                        .filter { "BpmnError" in it }
                        .distinct()
                scripts.isNotEmpty() shouldBe true
                GroovyClassLoader().use { loader ->
                    loader.parseClass(
                        "package $enginePackage.bpm.engine.delegate; class BpmnError extends RuntimeException { String errorCode; BpmnError(String code, String message) { super(message); errorCode = code } }",
                    )
                    val executionClass = loader.parseClass("package $enginePackage.bpm.engine.impl; class Execution {}")
                    val execution = executionClass.getConstructor().newInstance()
                    for (script in scripts) {
                        script shouldNotContain "new org.camunda.bpm.engine.delegate.BpmnError"

                        fun run(code: Int) =
                            GroovyShell(
                                loader,
                                Binding(mapOf("execution" to execution, "statusCode" to code, "response" to "{}")),
                            ).evaluate(script)
                        for ((code, expected) in listOf(
                            409 to "TTR_STALE",
                            403 to "TTR_REFUSED",
                            422 to "TTR_REFUSED",
                        )) {
                            val error = shouldThrow<RuntimeException> { run(code) }
                            error.javaClass.name shouldBe "$enginePackage.bpm.engine.delegate.BpmnError"
                            error.javaClass.getMethod("getErrorCode").invoke(error) shouldBe expected
                            error.message shouldBe
                                if (code == 409) "run API fingerprint refused" else "run API HTTP $code: {}"
                        }
                        for (code in listOf(408, 429, 500, 503)) {
                            shouldThrow<IllegalStateException> { run(code) }.message shouldBe "run API HTTP $code"
                        }
                        for (code in listOf(200, 201, 202)) run(code).toString() shouldBe "{}"
                    }
                }
            }
        }
    })
