// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

/** S1.5.1: export-facing specifications over a fresh H-1 projection. */
class TemplatesDmnSpec :
    StringSpec({
        val world =
            WorldChoices(
                programs =
                    listOf(
                        ProgramChoice(
                            "release_on_placement",
                            "release_order",
                            "key",
                            listOf(ParameterMeta("tolerance", "decimal", "0.01")),
                        ),
                    ),
                queries =
                    listOf(
                        QueryMeta(
                            "er.query.order_totals",
                            listOf(ParameterMeta("limit", "decimal", null)),
                            listOf(ColumnMeta("total", "decimal"), ColumnMeta("approved", "boolean")),
                        ),
                    ),
            )

        fun result(target: String) =
            (if (target == "camunda_7") Camunda7EmitPlugin() else Camunda8EmitPlugin()).emitRecipe(
                RecipeEmitRequest(
                    authoringGraph(target),
                    """{"world":"hartland.worlds.demo"}""",
                    "",
                    "{}",
                    world,
                ),
            )
        "the offline structure driver rejects missing artifacts, wrong bindings and unsafe DMN" {
            val files = result("camunda_7").files
            val folder =
                java.nio.file.Files
                    .createTempDirectory("p15-artifact-spec")
            try {
                files.forEach { (relative, bytes) ->
                    val path = folder.resolve(relative)
                    java.nio.file.Files
                        .createDirectories(path.parent)
                    java.nio.file.Files
                        .write(path, bytes)
                }
                val manifest = """{
                  "authoring": {
                    "dialect": "c7",
                    "choices": ["er.query.order_totals", "release_on_placement"],
                    "steps": [
                      {"name":"release_on_placement","params":["tolerance"],"columns":{}},
                      {"name":"er.query.order_totals","params":["limit"],
                       "columns":{"total":"decimal","approved":"boolean","row_count":"integer"},
                       "dmn":"dmn/er.query.order_totals_decision.dmn"}
                    ]
                  }
                }"""
                java.nio.file.Files
                    .writeString(folder.resolve("manifest.json"), manifest)

                fun differences() =
                    org.tatrman.ttrp.conform.replay.CamundaStructureDriver
                        .compareArtifacts(folder)
                differences() shouldBe emptyList()
                val template = folder.resolve("templates/c7/release_on_placement.json")
                java.nio.file.Files
                    .delete(template)
                differences().isNotEmpty() shouldBe true
                java.nio.file.Files
                    .write(template, files.getValue("templates/c7/release_on_placement.json"))
                java.nio.file.Files.writeString(
                    template,
                    java.nio.file.Files
                        .readString(template)
                        .replace("camunda:inputParameter", "zeebe:input"),
                )
                differences().isNotEmpty() shouldBe true
                java.nio.file.Files
                    .write(template, files.getValue("templates/c7/release_on_placement.json"))
                java.nio.file.Files.writeString(
                    folder.resolve("dmn/er.query.order_totals_decision.dmn"),
                    """<!DOCTYPE definitions [<!ENTITY unsafe SYSTEM "file:///etc/passwd">]><definitions>&unsafe;</definitions>""",
                )
                differences().isNotEmpty() shouldBe true
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        for ((target, dialect, input, output) in listOf(
            listOf("camunda_7", "c7", "camunda:inputParameter", "camunda:outputParameter"),
            listOf("camunda_8", "c8", "zeebe:input", "zeebe:output"),
        )) {
            "dialect $dialect uses the world's choices and named parameter/output bindings" {
                val files = result(target).files
                val template =
                    Json
                        .parseToJsonElement(
                            files.getValue("templates/$dialect/release_on_placement.json").decodeToString(),
                        ).jsonObject
                template.getValue("id") shouldBe JsonPrimitive("tatrman.hartland.worlds.demo.release_on_placement")
                val schemaStream =
                    requireNotNull(javaClass.getResourceAsStream("/schema/$dialect-template-schema.json"))
                val schema =
                    com.networknt.schema.JsonSchemaFactory
                        .getInstance(
                            com.networknt.schema.SpecVersion.VersionFlag.V7,
                        ).getSchema(schemaStream)
                schema
                    .validate(
                        com.fasterxml.jackson.databind
                            .ObjectMapper()
                            .readTree(template.toString()),
                    ).map { it.message } shouldBe
                    emptyList()
                val properties = template.getValue("properties").jsonArray.map { it.jsonObject }
                properties
                    .flatMap {
                        it["choices"]?.jsonArray.orEmpty()
                    }.map {
                        it.jsonObject
                            .getValue("value")
                            .jsonPrimitive.content
                    }.toSet() shouldBe
                    setOf("release_on_placement", "er.query.order_totals")
                properties.any {
                    it.getValue("binding").jsonObject.let { b ->
                        b["type"] == JsonPrimitive(input) &&
                            (b["name"] ?: b["target"]) == JsonPrimitive("tolerance")
                    }
                } shouldBe
                    true
                val query = files.getValue("templates/$dialect/er.query.order_totals.json").decodeToString()
                query shouldContain output
                query shouldContain "total"
                query shouldContain "row_count"
            }
            "dialect $dialect emits a typed empty FIRST DMN for each declared query" {
                val dmn = result(target).files.getValue("dmn/er.query.order_totals_decision.dmn").decodeToString()
                dmn shouldContain "https://www.omg.org/spec/DMN/20191111/MODEL/"
                dmn shouldContain "id=\"er.query.order_totals_decision\""
                dmn shouldContain "hitPolicy=\"FIRST\""
                dmn shouldContain "typeRef=\"number\""
                dmn shouldContain "typeRef=\"boolean\""
                dmn shouldContain "row_count"
                dmn.contains("<rule") shouldBe false
                val factory =
                    javax.xml.validation.SchemaFactory
                        .newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI)
                factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
                factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file")
                factory
                    .newSchema(requireNotNull(javaClass.getResource("/schema/dmn/DMN13.xsd")))
                    .newValidator()
                    .validate(
                        javax.xml.transform.stream
                            .StreamSource(java.io.StringReader(dmn)),
                    )
            }
            "dialect $dialect exports deterministically and retains the H-1 BPMN" {
                val first = result(target).files
                val second = result(target).files
                first.keys shouldBe second.keys
                first.forEach { (path, bytes) -> bytes.contentEquals(second.getValue(path)) shouldBe true }
                validateAuthoringBpmn(first.getValue("release_order.$dialect.bpmn").decodeToString())
            }
        }
    })

private fun authoringGraph(target: String): RecipeGraph =
    RecipeGraph(
        RecipeMeta(
            "release_order",
            listOf("order.order_number"),
            "now",
            emptyList(),
            mapOf(
                "text" to "sha256:text",
                "values" to "sha256:values",
            ),
            "per_candidate",
            listOf("sql"),
        ),
        ProgramMeta2("release_on_placement", "key", "on_event", "event", emptyMap(), emptyMap()),
        DeploymentMeta(
            "release_bpmn",
            target,
            target,
            "1",
            "identity.automation_decider",
            "parts",
            true,
            false,
            emptyMap(),
        ),
        Roles("placed_orders", listOf(ContextMeta("shortfall", "per_candidate", "sql")), "verdict", "overlapping"),
        listOf(RouteMeta("release", null, emptyList(), false, false, false)),
        listOf(WiringMeta(listOf("verdict.release"), "release", mapOf("order" to "order_number"), null)),
        mapOf("release" to ActionMeta(emptyMap(), mapOf("camunda" to "door"), null, false, null, emptyMap(), null)),
        RunApiMeta(
            "/automation",
            mapOf(
                "open" to "/recipes/{recipe}/runs",
                "evaluate" to "/runs/{id}/evaluate",
                "part" to "/runs/{id}/parts/{part}",
                "status" to "/runs/{id}",
                "route" to "/runs/{id}/routes/{route}",
                "perform" to "/runs/{id}/actions/{action}/perform",
                "close" to "/runs/{id}/close",
            ),
        ),
    )
