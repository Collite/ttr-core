// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttrp.conform.EmitDeterminismKit
import org.tatrman.ttrp.emit.spi.*
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import io.kotest.assertions.throwables.shouldThrow

/** Fresh H-1 projection from the public recipe fixture. No legacy/DFP BPMN is read or reused. */
internal fun releaseGraph(
    target: String = "camunda_7",
    batch: Boolean = false,
): RecipeGraph =
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
        ProgramMeta2(
            if (batch) "morning_release" else "release_on_placement",
            if (batch) "none" else "key",
            if (batch) "batch" else "on_event",
            if (batch) "now" else "event",
            emptyMap(),
            emptyMap(),
        ),
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
        Roles(
            "placed_orders",
            listOf("shortfall", "price_differences", "overdue").map {
                ContextMeta(it, "per_candidate", "sql")
            },
            "verdict",
            "overlapping",
        ),
        listOf("hold_sales", "hold_ar", "release_confirmed", "release").map {
            RouteMeta(it, null, emptyList(), false, false, false)
        },
        listOf(
            WiringMeta(listOf("verdict.release"), "release", mapOf("order" to "order_number"), null),
            WiringMeta(
                listOf("verdict.release_confirmed"),
                "approve_release",
                mapOf("who" to "role.sales_manager"),
                null,
            ),
            WiringMeta(listOf("verdict.hold_sales"), "hold", mapOf("order" to "order_number"), null),
            WiringMeta(listOf("verdict.hold_sales"), "notify", mapOf("who" to "role.sales"), null),
            WiringMeta(listOf("verdict.hold_ar"), "hold", mapOf("order" to "order_number"), null),
            WiringMeta(listOf("verdict.hold_ar"), "notify", mapOf("who" to "role.ar"), null),
        ),
        mapOf(
            "release" to ActionMeta(emptyMap(), mapOf("camunda" to "door"), null, false, null, emptyMap(), null),
            "hold" to ActionMeta(emptyMap(), mapOf("camunda" to "door"), null, false, null, emptyMap(), null),
            "approve_release" to
                ActionMeta(
                    mapOf("who" to "role"),
                    mapOf("camunda" to "user task", "native" to "task"),
                    "required",
                    false,
                    "2 working_days",
                    emptyMap(),
                    null,
                    by = "role.process_owner",
                    candidateGroup = "hartland-process-owners",
                ),
            "notify" to
                ActionMeta(mapOf("who" to "role"), mapOf("camunda" to "send task"), null, true, null, emptyMap(), null),
        ),
        RunApiMeta(
            "/automation",
            mapOf(
                "open" to "/recipes/{recipe}/runs",
                "part" to "/runs/{id}/parts/{part}",
                "evaluate" to "/runs/{id}/evaluate",
                "status" to "/runs/{id}",
                "route" to "/runs/{id}/routes/{route}",
                "perform" to "/runs/{id}/actions/{action}/perform",
                "outcome" to "/runs/{id}/actions/{action}/outcome",
                "close" to "/runs/{id}/close",
            ),
        ),
    )

class CamundaEmitSpec :
    StringSpec({
        fun emit(graph: RecipeGraph): String {
            val plugin = if (graph.deployment.type == "camunda_7") Camunda7EmitPlugin() else Camunda8EmitPlugin()
            val suffix = if (graph.deployment.type == "camunda_7") "c7" else "c8"
            return plugin
                .emitRecipe(
                    RecipeEmitRequest(graph, "{}", "", "{}"),
                ).files
                .getValue("${graph.recipe.name}.$suffix.bpmn")
                .decodeToString()
        }

        fun document(xml: String) =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                }.newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

        "fresh H-1 and batch goldens validate offline, have DI and are deterministic in both dialects" {
            for (target in listOf("camunda_7", "camunda_8")) {
                for (batch in listOf(false, true)) {
                    val graph = releaseGraph(target, batch)
                    val xml = emit(graph)
                    val suffix = if (target == "camunda_7") "c7" else "c8"
                    val path =
                        Path.of(
                            "src/test/resources/goldens/release_order${if (batch) "_batch" else ""}.$suffix.bpmn",
                        )
                    if (System.getenv("UPDATE_AU_EXPORT_GOLDENS") == "1") {
                        Files.createDirectories(path.parent)
                        Files.writeString(path, xml)
                    }
                    Files.readString(path) shouldBe xml
                    validateBpmn(xml)
                    val doc = document(xml)
                    doc
                        .getElementsByTagNameNS(
                            "http://www.omg.org/spec/BPMN/20100524/DI",
                            "BPMNShape",
                        ).length
                        .let { it > 10 } shouldBe
                        true
                    val plugin = if (target == "camunda_7") Camunda7EmitPlugin() else Camunda8EmitPlugin()
                    EmitDeterminismKit
                        .checkRecipes(
                            plugin,
                            listOf(RecipeEmitRequest(graph, "{}", "", "{}")),
                        ).deterministic shouldBe
                        true
                    if (batch) {
                        xml shouldContain "Run batch"
                        xml shouldContain "bounded restart"
                        xml.contains("timeCycle") shouldBe false
                    } else {
                        xml shouldContain "id=\"release_on_placement\""
                        xml shouldContain "key"
                    }
                }
            }
        }
        "H-1 has a parallel context fork, inclusive routes, renamed approval and governed perform calls" {
            for (target in listOf("camunda_7", "camunda_8")) {
                val xml = emit(releaseGraph(target))
                val doc = document(xml)
                doc
                    .getElementsByTagNameNS(
                        "http://www.omg.org/spec/BPMN/20100524/MODEL",
                        "parallelGateway",
                    ).length shouldBe
                    2
                doc
                    .getElementsByTagNameNS(
                        "http://www.omg.org/spec/BPMN/20100524/MODEL",
                        "inclusiveGateway",
                    ).length shouldBe
                    2
                val all = doc.getElementsByTagName("*")
                val elements = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }

                fun x(name: String): Int {
                    val id = elements.single { it.getAttribute("name") == name }.getAttribute("id")
                    val shape = elements.single { it.localName == "BPMNShape" && it.getAttribute("bpmnElement") == id }
                    val bounds =
                        shape
                            .getElementsByTagNameNS(
                                "http://www.omg.org/spec/DD/20100524/DC",
                                "Bounds",
                            ).item(0) as org.w3c.dom.Element
                    return bounds.getAttribute("x").toInt()
                }
                for (context in listOf("shortfall", "price_differences", "overdue")) {
                    (x("contexts fork") < x("context $context")) shouldBe true
                    (x("context $context") < x("contexts join")) shouldBe true
                }
                xml shouldContain "approve_release"
                xml shouldContain "/actions/release/perform"
                xml shouldContain "export outdated"
                xml shouldContain "recipeFingerprint"
                xml.contains("SELECT ") shouldBe false
                emit(
                    releaseGraph(target).copy(roles = releaseGraph(target).roles.copy(switchMode = "exclusive")),
                ) shouldContain
                    "exclusiveGateway"
                if (target ==
                    "camunda_7"
                ) {
                    xml shouldContain "camunda:connector"
                } else {
                    xml shouldContain "io.camunda:http-json:1"
                }
            }
        }
        "schema validation checks supported extension shapes and named run-API paths are preserved" {
            for (target in listOf("camunda_7", "camunda_8")) {
                val graph = releaseGraph(target)
                val xml = emit(graph.copy(runApi = graph.runApi.copy(basePath = "/custom/run-api/v2")))
                xml shouldContain "/custom/run-api/v2/recipes/release_order/runs"
                xml.contains("/automation/") shouldBe false
                val damaged =
                    if (target == "camunda_7") {
                        xml.replaceFirst("name=\"method\"", "unexpected=\"method\"")
                    } else {
                        xml.replaceFirst("type=\"io.camunda:http-json:1\"", "unexpected=\"io.camunda:http-json:1\"")
                    }
                shouldThrow<org.xml.sax.SAXParseException> { validateBpmn(damaged) }
            }
        }
        "unsupported modes and granularities refuse before writing an artifact" {
            val graph = releaseGraph()
            shouldThrow<IllegalArgumentException> { emit(graph.copy(program = graph.program.copy(mode = "windowed"))) }
            shouldThrow<IllegalArgumentException> {
                emit(
                    graph.copy(deployment = graph.deployment.copy(granularity = "islands")),
                )
            }
            for (dialect in Dialect.entries) {
                CamundaSchedule.parse("Mon 09:00 Europe/Prague", dialect).zone shouldBe "Europe/Prague"
                CamundaSchedule.parse("1st 09:00 Europe/Prague", dialect).zone shouldBe "Europe/Prague"
                CamundaSchedule.parse("*/60", dialect).cycle shouldBe "R/PT60M"
            }
        }
    })
