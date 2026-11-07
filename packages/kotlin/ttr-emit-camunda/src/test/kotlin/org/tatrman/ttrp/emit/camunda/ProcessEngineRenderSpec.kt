// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import groovy.lang.Binding
import groovy.lang.GroovyShell
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** A stand-in for the C7 execution the template connector scripts read. */
class FakeExecution(
    private val variables: Map<String, Any?>,
) {
    fun getVariable(name: String): Any? = variables[name]
}

/** S1.5.2 T2b (D-145, D-158, ⚑R226-A, ⚑R223-D): the process engine target's C7 rendering and templates. */
class ProcessEngineRenderSpec :
    StringSpec({
        val world =
            WorldChoices(
                programs =
                    listOf(
                        ProgramChoice("release_on_placement", "release_order", "key", listOf(ParameterMeta("tolerance", "decimal", "0.01"))),
                        ProgramChoice("morning_release", "release_order", null, emptyList()),
                    ),
                queries =
                    listOf(
                        QueryMeta(
                            "er.query.order_totals",
                            listOf(ParameterMeta("limit", "decimal", null)),
                            listOf(ColumnMeta("total", "decimal")),
                        ),
                    ),
            )
        val egress = "http://process-engine-egress.process-engine.svc/automation"

        fun graph(
            groups: List<String>? = listOf("hartland-sales-managers", "hartland-ops"),
            url: String? = egress,
        ): RecipeGraph {
            val base = releaseGraph("process_engine")
            return base.copy(
                deployment =
                    base.deployment.copy(
                        startableBy = groups?.let { listOf("role.sales_manager", "role.ops").take(it.size) },
                        startableGroups = groups,
                        egressUrl = url,
                    ),
            )
        }

        fun emit(graph: RecipeGraph = graph()) =
            ProcessEngineEmitPlugin().emitRecipe(
                RecipeEmitRequest(graph, """{"world":"hartland.worlds.demo"}""", "", "{}", world),
            )

        "the plugin is the process_engine target over the CG-2 manifest, at the current SPI revision" {
            val plugin = ProcessEngineEmitPlugin()
            plugin.targetId shouldBe "process_engine"
            plugin.supportsRecipes shouldBe true
            plugin.spiRevision shouldBe TtrEmitPlugin.SPI_REVISION
            plugin.executorTypeManifest() shouldContain "def executor process_engine"
        }

        "the engine BPMN is XSD-valid C7, calls the egress without a bearer and carries the start groups" {
            val xml = emit().files.getValue("release_order.c7.bpmn").decodeToString()
            validateBpmn(xml)
            xml shouldContain "camunda:candidateStarterGroups=\"hartland-sales-managers,hartland-ops\""
            xml shouldContain "camunda:historyTimeToLive=\"30\""
            xml shouldContain "$egress/recipes/release_order/runs"
            xml shouldContain "$egress/runs/"
            xml shouldContain "http-connector"
            xml shouldNotContain "Authorization"
            xml shouldNotContain "TTR_CONN_AUTOMATION"
            // The structure driver still finds every named call as an HTTP task.
            org.tatrman.ttrp.conform.replay.CamundaStructureDriver
                .contract(xml)
                .getValue("calls")
                .jsonArray
                .isEmpty() shouldBe false
        }

        "customer C7 keeps the connector secret and writes no start groups" {
            val base = releaseGraph("camunda_7")
            val xml =
                Camunda7EmitPlugin()
                    .emitRecipe(
                        RecipeEmitRequest(
                            base.copy(
                                deployment =
                                    base.deployment.copy(
                                        startableBy = listOf("role.sales_manager"),
                                        startableGroups = listOf("hartland-sales-managers"),
                                    ),
                            ),
                            """{"world":"hartland.worlds.demo"}""",
                            "",
                            "{}",
                            world,
                        ),
                    ).files
            val bpmn = xml.getValue("release_order.c7.bpmn").decodeToString()
            bpmn shouldContain "TTR_CONN_AUTOMATION.token"
            bpmn shouldNotContain "candidateStarterGroups"
            val readme = xml.getValue("README.md").decodeToString()
            readme shouldContain "role.sales_manager (Keycloak group hartland-sales-managers)"
            readme shouldContain "this file does not provision permissions"
        }

        "start groups: [] and absent write no attribute; the README says which" {
            val empty = emit(graph(groups = emptyList())).files
            empty.getValue("release_order.c7.bpmn").decodeToString() shouldNotContain "candidateStarterGroups"
            empty.getValue("README.md").decodeToString() shouldContain "no role may start this process"
            val absent = emit(graph(groups = null)).files
            absent.getValue("release_order.c7.bpmn").decodeToString() shouldNotContain "candidateStarterGroups"
            absent.getValue("README.md").decodeToString() shouldContain "No startable_by restriction is declared"
            val named = emit().files.getValue("README.md").decodeToString()
            named shouldContain "role.sales_manager (Keycloak group hartland-sales-managers)"
            named shouldContain "--deploy"
        }

        "an unresolved start group or a group that cannot be listed refuses TTRP-EXPORT-ROLE" {
            val base = graph()
            shouldThrow<IllegalArgumentException> {
                emit(base.copy(deployment = base.deployment.copy(startableGroups = null)))
            }.message shouldContain "TTRP-EXPORT-ROLE"
            shouldThrow<IllegalArgumentException> { emit(graph(groups = listOf("a,b", "c"))) }.message shouldContain
                "TTRP-EXPORT-ROLE"
        }

        "a missing or unsafe egress_url refuses TTRP-EXPORT-EGRESS" {
            for (url in listOf(null, "", "ftp://egress/automation", "http://egress/automation?x=1", "http://u:p@egress/a")) {
                shouldThrow<IllegalArgumentException> { emit(graph(url = url)) }.message shouldContain
                    "TTRP-EXPORT-EGRESS"
            }
        }

        "engine templates are schema-valid C7 templates with an http-connector scope to the egress" {
            val files = emit().files
            val schema =
                com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V7)
                    .getSchema(requireNotNull(javaClass.getResourceAsStream("/schema/c7-template-schema.json")))
            val templates = files.filterKeys { it.startsWith("templates/c7/") }
            templates.keys shouldBe
                setOf(
                    "templates/c7/er.query.order_totals.json",
                    "templates/c7/morning_release.json",
                    "templates/c7/release_on_placement.json",
                )
            templates.values.forEach { bytes ->
                schema
                    .validate(com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes))
                    .map { it.message } shouldBe emptyList()
                val text = bytes.decodeToString()
                text shouldNotContain "Authorization"
                text shouldContain "http-connector"
            }
            files.getValue("README.md").decodeToString() shouldContain "Templates carry their implementation"
        }

        "the template scripts pick the run-API call from ttr_source and bind parameters by name" {
            val template =
                Json
                    .parseToJsonElement(emit().files.getValue("templates/c7/release_on_placement.json").decodeToString())
                    .jsonObject
            val scope = template.getValue("scopes").jsonArray.single().jsonObject
            scope.getValue("type").jsonPrimitive.content shouldBe "camunda:Connector"
            val properties = scope.getValue("properties").jsonArray.map { it.jsonObject }

            fun script(name: String) =
                properties
                    .single {
                        it.getValue("binding").jsonObject["name"]?.jsonPrimitive?.content == name
                    }.getValue("value")
                    .jsonPrimitive.content

            fun run(
                name: String,
                variables: Map<String, Any?>,
            ): Any? = GroovyShell(Binding(mapOf("execution" to FakeExecution(variables)))).evaluate(script(name))

            val program =
                mapOf("ttr_source" to "release_on_placement", "ttr_deployment" to "release_event", "ttrInput" to mapOf("key" to 4711))
            run("url", program) shouldBe "$egress/recipes/release_order/runs"
            Json.parseToJsonElement(run("payload", program) as String) shouldBe
                Json.parseToJsonElement("""{"deployment":"release_event","port":{"key":4711},"origin":"engine"}""")
            val batch = mapOf("ttr_source" to "morning_release", "ttr_deployment" to "release_morning")
            Json.parseToJsonElement(run("payload", batch) as String) shouldBe
                Json.parseToJsonElement("""{"deployment":"release_morning","port":{"none":true},"origin":"engine"}""")
            val query = mapOf("ttr_source" to "er.query.order_totals", "limit" to 10)
            run("url", query) shouldBe "$egress/query"
            Json.parseToJsonElement(run("payload", query) as String) shouldBe
                Json.parseToJsonElement("""{"query":"er.query.order_totals","parameters":{"limit":10}}""")
            shouldThrow<IllegalArgumentException> { run("payload", mapOf("ttr_source" to "nope")) }
            script("headers") shouldContain "application/json"
            properties.single { it.getValue("binding").jsonObject["name"]?.jsonPrimitive?.content == "connectorId" }
                .getValue("value") shouldBe JsonPrimitive("http-connector")
        }

        "the engine rendering is deterministic" {
            emit().files.mapValues { it.value.decodeToString() } shouldBe emit().files.mapValues { it.value.decodeToString() }
        }
    })
