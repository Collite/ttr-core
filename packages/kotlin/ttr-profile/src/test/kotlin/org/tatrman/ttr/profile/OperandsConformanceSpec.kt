// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.*

/** Shared S5.3a operands: replay the same inputs and committed oracles as the TS profile tests. */
class OperandsConformanceSpec :
    FunSpec({
        val root = Path.of("../../../tests/conformance/dialect/demo-cs-links/operands")
        val cases =
            root
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.name }
                .sorted()

        test("all four behaviours and their refusal/null variants are present") {
            cases.isNotEmpty() shouldBe true
            cases shouldBe
                listOf(
                    "as-ref",
                    "as-ref-names-refused",
                    "package-empty",
                    "package-invalid",
                    "package-missing",
                    "package-null",
                    "package-trailing-newline",
                    "required-silent",
                    "required-warn",
                    "run-first",
                    "run-only",
                    "schema-array",
                    "schema-object",
                    "schema-string",
                    "verbatim",
                    "verbatim-default",
                    "verbatim-dotted",
                    "verbatim-mapping",
                    "verbatim-no-records",
                    "xmi-multi",
                )
        }

        cases.forEach { name ->
            test("$name: matches the committed Kotlin oracle") {
                val directory = root.resolve(name)
                val tree = directory.resolve("tree")
                val expected = directory.resolve("expected")
                val config = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject

                fun run(): ApplyResult {
                    val inputs =
                        (config["trees"] as? JsonArray)?.map { it.jsonObject }
                            ?: tree.listDirectoryEntries("*.yaml").sorted().map {
                                buildJsonObject { put("input", it.name) }
                            }
                    val trees =
                        inputs.map { item ->
                            val input = item.getValue("input").jsonPrimitive.content
                            ForeignTree.read(
                                tree.resolve(input).readText(),
                                item["file"]?.jsonPrimitive?.content ?: input,
                                item["label"]?.jsonPrimitive?.content ?: input,
                            )
                        }
                    val profile = ProfileReader.read(directory.resolve("profile.yaml").readText(), "profile.yaml")
                    return ProfileEngine.apply(
                        trees,
                        listOf(profile),
                        tree,
                        perFile = true,
                        sourceName = config["sourceName"]?.jsonPrimitive?.content,
                    )
                }

                val refusal = expected.resolve("error.json")
                if (refusal.exists()) {
                    val oracle = Json.parseToJsonElement(refusal.readText()).jsonObject
                    val error = shouldThrow<ProfileException> { run() }
                    error.code shouldBe oracle.getValue("code").jsonPrimitive.content
                    error.message shouldBe oracle.getValue("message").jsonPrimitive.content
                    error.location.file shouldBe oracle.getValue("file").jsonPrimitive.content
                    error.location.line shouldBe oracle.getValue("line").jsonPrimitive.int
                    error.location.column shouldBe oracle.getValue("column").jsonPrimitive.int
                    error.location.path shouldBe oracle.getValue("path").jsonPrimitive.content
                } else {
                    val result = run()
                    val actual =
                        result.documents.associate { document ->
                            val pkg =
                                document.value
                                    .getValue("package")
                                    .jsonPrimitive.content
                            val model =
                                document.value
                                    .getValue("model")
                                    .jsonPrimitive.content
                            "$pkg.$model.yaml" to document.write().toByteArray(Charsets.UTF_8)
                        }
                    actual.size shouldBe result.documents.size
                    val twins = expected.listDirectoryEntries("*.yaml").sorted()
                    actual.keys.sorted() shouldBe twins.map { it.name }
                    twins.forEach { twin ->
                        withClue(twin.name) {
                            actual.getValue(twin.name).toList() shouldBe twin.readBytes().toList()
                        }
                    }
                    result.reportJson() shouldBe Json.parseToJsonElement(expected.resolve("report.json").readText())
                }
            }
        }
    })
