// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import io.kotest.core.spec.style.StringSpec
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/** Deploy real generated BPMN, with real connector scopes, Spin plugins and HTTP on each distribution. */
class EmbeddedEngineSpec :
    StringSpec({
        for (engine in listOf("camunda", "cibseven")) {
            for (scenario in listOf("http", "approval", "template")) {
                "$engine embedded engine executes generated $scenario BPMN" {
                    val classpath = checkNotNull(System.getProperty("$engine.embedded.classpath"))
                    URLClassLoader(
                        classpath.split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray(),
                        ClassLoader.getPlatformClassLoader(),
                    ).use { loader ->
                        val thread = Thread.currentThread()
                        val previous = thread.contextClassLoader
                        thread.contextClassLoader = loader
                        try {
                            val graph = releaseGraph("process_engine")
                            val request =
                                RecipeEmitRequest(
                                    graph.copy(
                                        deployment =
                                            graph.deployment.copy(
                                                egressUrl = "http://127.0.0.1:1/automation",
                                                dryRun = false,
                                            ),
                                    ),
                                    "{}",
                                    "",
                                    "{}",
                                    WorldChoices(
                                        listOf(
                                            ProgramChoice(graph.program.name, graph.recipe.name, "key", emptyList()),
                                        ),
                                        listOf(
                                            QueryMeta(
                                                "er.query.order_totals",
                                                listOf(ParameterMeta("limit", "decimal", null)),
                                                listOf(ColumnMeta("total", "decimal")),
                                            ),
                                        ),
                                    ),
                                )
                            val bindings =
                                mapOf(
                                    "enginePackage" to "org.$engine",
                                    "scenario" to scenario,
                                    "bpmn" to CamundaRenderer(request, Dialect.C7, engine = true).render(),
                                    "templates" to
                                        listOf(
                                            templateProcess(request, graph.program.name),
                                            templateProcess(request, "er.query.order_totals"),
                                        ),
                                )
                            val bindingClass = loader.loadClass("groovy.lang.Binding")
                            val binding = bindingClass.getConstructor(Map::class.java).newInstance(bindings)
                            val shellClass = loader.loadClass("groovy.lang.GroovyShell")
                            val shell =
                                shellClass
                                    .getConstructor(
                                        ClassLoader::class.java,
                                        bindingClass,
                                    ).newInstance(loader, binding)
                            val script = javaClass.getResource("/embedded-engine.groovy")!!.readText()
                            shellClass.getMethod("evaluate", String::class.java).invoke(shell, script)
                        } catch (error: InvocationTargetException) {
                            // Materialize Groovy diagnostics before the isolated loader closes.
                            throw AssertionError(error.targetException.stackTraceToString())
                        } finally {
                            thread.contextClassLoader = previous
                        }
                    }
                }
            }
        }
    })

/** Apply the actual Hidden template bindings as Modeler does; no replacement payload/result scripts. */
private fun templateProcess(
    request: RecipeEmitRequest,
    source: String,
): String {
    val json =
        Json
            .parseToJsonElement(
                AuthoringArtifacts(
                    request,
                    Dialect.C7,
                    engine = true,
                ).files().getValue("templates/c7/${request.graph.program.name}.json").decodeToString(),
            ).jsonObject
    val properties =
        json.getValue("scopes").jsonArray.single().jsonObject.getValue("properties").jsonArray.map {
            it.jsonObject
        }
    val model = BpmnModel("template_test", Dialect.C7)
    val start = model.root.node("startEvent", "start")
    val task = model.root.node("serviceTask", "template")
    task.extensions +=
        Dialect.C7.mapping(mapOf("ttr_source" to quote(source), "ttr_deployment" to quote("release_bpmn")))
    val parameters =
        properties
            .filter {
                it
                    .getValue("binding")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content !=
                    "property"
            }.map { prop ->
                val binding = prop.getValue("binding").jsonObject
                val output = binding.getValue("type").jsonPrimitive.content == "camunda:outputParameter"
                val value =
                    if (output) {
                        binding
                            .getValue(
                                "source",
                            ).jsonPrimitive.content
                    } else {
                        prop.getValue("value").jsonPrimitive.content
                    }
                Xml(
                    if (output) "camunda:outputParameter" else "camunda:inputParameter",
                    mapOf(
                        "name" to
                            if (output) {
                                prop
                                    .getValue(
                                        "value",
                                    ).jsonPrimitive.content
                            } else {
                                binding.getValue("name").jsonPrimitive.content
                            },
                    ),
                    children =
                        if (binding.containsKey("scriptFormat")) {
                            listOf(
                                Xml(
                                    "camunda:script",
                                    mapOf(
                                        "scriptFormat" to "groovy",
                                    ),
                                    text = value,
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    text = if (binding.containsKey("scriptFormat")) null else value,
                )
            }
    task.extensions +=
        Xml(
            "camunda:connector",
            children =
                listOf(
                    Xml("camunda:connectorId", text = "http-connector"),
                    Xml("camunda:inputOutput", children = parameters),
                ),
        )
    model.root.link(start, task)
    model.root.link(task, model.root.node("userTask", "inspect result"))
    return model.render(buildJsonObject {})
}
