// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

class StructureDriverSpec :
    StringSpec({
        val fixture =
            FixtureLoader.parse(
                """
                recipe: release_order
                as_of: "2026-11-01"
                keys: [4711]
                expected: {verdicts: [{key: 4711, route: release}], actions: [{key: 4711, action: release, columns: {order: 4711}}]}
                pinned_from: {run: fixture, at: now, by: test}
                """.trimIndent(),
            )

        fun check(
            suffix: String,
            batch: Boolean = false,
            mutate: (String, JsonObject) -> String = { xml, _ -> xml },
        ): ReplayReport {
            val source =
                Path.of(
                    "../ttr-emit-camunda/src/test/resources/goldens/release_order${if (batch) "_batch" else ""}.$suffix.bpmn",
                )
            val xml = Files.readString(source)
            val contract = CamundaStructureDriver.contract(xml)
            val folder = Files.createTempDirectory("camunda-structure-")
            try {
                Files.writeString(folder.resolve("release_order.$suffix.bpmn"), mutate(xml, contract))
                Files.writeString(
                    folder.resolve("manifest.json"),
                    buildJsonObject {
                        put("target", buildJsonObject { put("type", if (suffix == "c7") "camunda_7" else "camunda_8") })
                        put("deployment", contract.getValue("deployment"))
                        put("calls", contract.getValue("calls"))
                    }.toString(),
                )
                val driver = CamundaStructureDriver(listOf(folder))
                shouldThrow<IllegalStateException> { driver.run(fixture, "camunda_7.on_event") }
                return ReplayRunner.run(
                    fixture,
                    listOf("camunda_${if (suffix == "c7") 7 else 8}.${if (batch) "batch" else "on_event"}"),
                ) {
                    driver
                }
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
        "both dialects and modes agree structurally without claiming executed verdicts or actions" {
            for (suffix in listOf("c7", "c8")) {
                for (batch in listOf(false, true)) {
                    val report = check(suffix, batch)
                    report.exitCode shouldBe 0
                    report.cells.single().status shouldBe "structure-agree"
                }
            }
        }
        "a removed HTTP task disagrees with exit three and names the missing task" {
            for (suffix in listOf("c7", "c8")) {
                val report =
                    check(suffix) { xml, contract ->
                        val id =
                            contract
                                .getValue("calls")
                                .jsonArray
                                .first()
                                .jsonObject
                                .getValue("task")
                                .jsonPrimitive.content
                        xml.replace(Regex("<bpmn:serviceTask[^>]*id=\"$id\"[\\s\\S]*?</bpmn:serviceTask>"), "")
                    }
                report.exitCode shouldBe 3
                report.summary() shouldContain "structure.calls"
                report.summary() shouldContain "open"
            }
        }
        "a removed route branch and a changed connector URL disagree" {
            for (suffix in listOf("c7", "c8")) {
                val branch =
                    check(suffix) { xml, contract ->
                        val id =
                            contract
                                .getValue("routes")
                                .jsonArray
                                .first()
                                .jsonObject
                                .getValue("flow")
                                .jsonPrimitive.content
                        xml.replace(Regex("<bpmn:sequenceFlow[^>]*id=\"$id\"[\\s\\S]*?</bpmn:sequenceFlow>"), "")
                    }
                branch.exitCode shouldBe 3
                branch.summary() shouldContain "structure.routes"
                check(
                    suffix,
                ) { xml, _ ->
                    xml.replace(
                        "/automation/recipes/release_order/runs",
                        "/automation/recipes/wrong/runs",
                    )
                }.exitCode shouldBe
                    3
            }
        }
        "unsafe XML is a driver error and external entities are not read" {
            for (suffix in listOf("c7", "c8")) {
                val report =
                    check(
                        suffix,
                    ) { xml, _ ->
                        xml.replace(
                            "?>",
                            "?><!DOCTYPE definitions [<!ENTITY external SYSTEM 'file:///no-such-file'>]>",
                        )
                    }
                report.exitCode shouldBe 2
                report.summary() shouldContain "DOCTYPE"
            }
        }
    })
