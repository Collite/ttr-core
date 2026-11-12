// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalYaml
import org.tatrman.ttr.yaml.YamlData
import kotlin.io.path.*

class DemoCsSpec :
    FunSpec({
        test("every positive demo-cs file matches its independently authored canonical bytes") {
            val inputs = demoRoot.resolve("input").listDirectoryEntries("*.yaml").sorted()
            inputs.size shouldBe 7
            inputs.forEach { input ->
                val result = ProfileEngine.apply(listOf(ForeignTree.read(input.readText(), input.name)), demoChain())
                val expected =
                    demoRoot
                        .resolve(
                            "expected/${input.nameWithoutExtension}",
                        ).listDirectoryEntries("*.yaml")
                        .sorted()
                result.documents.size shouldBe expected.size
                result.documents.forEach { document ->
                    val model =
                        document.value
                            .getValue("model")
                            .jsonPrimitive.content
                    document.write().toByteArray(Charsets.UTF_8).toList() shouldBe
                        expected.single { it.name == "$model.yaml" }.readBytes().toList()
                    // P2 fixed point: the returned document is accepted by the canonical codec with stable bytes.
                    CanonicalYaml.write(CanonicalYaml.read(document.write())) shouldBe document.write()
                }
            }
        }
        test("negative fixtures pin codes and authored key or record positions") {
            val expected = YamlData.read(demoRoot.resolve("negative/expect.yaml").readText()).value
            expected.forEach { (file, item) ->
                val error =
                    shouldThrow<ProfileException> {
                        ProfileEngine.apply(
                            listOf(ForeignTree.read(demoRoot.resolve("negative/$file").readText(), file)),
                            demoChain(),
                        )
                    }
                val fields = item.jsonObject
                error.code shouldBe fields.string("code")
                error.location.file shouldBe file
                error.location.line shouldBe fields.getValue("line").jsonPrimitive.int
                error.location.column shouldBe fields.getValue("column").jsonPrimitive.int
            }
        }
        test("combined files retain author order in each package and model and expose original source locations") {
            val inputs = demoRoot.resolve("input").listDirectoryEntries("*.yaml").sorted()
            val result = ProfileEngine.apply(inputs.map { ForeignTree.read(it.readText(), it.name) }, demoChain())
            val er = result.documents.single { it.value.string("model") == "er" }
            er.value
                .getValue("defs")
                .jsonArray
                .map { it.jsonObject.string("id") } shouldBe
                listOf("Customer", "Invoice", "Supplier", "Order", "Product", "Odberatel", "Dodavka", "Sklad")
            er.locations.getValue("/defs/0").file shouldBe "01-sequence.yaml"
            er.locations.getValue("/defs/0/properties/description").path shouldBe "/entity/0/popis"
            er.locations.getValue("/defs/0/properties/attributes/0").path shouldBe "/entity/0/sloupce/kod"
            er.locations.getValue("/defs/4/properties/description").file shouldBe "05-unnest.yaml"
        }
        test(
            "package at overrides filename; default handles unknown key; missing sequence identity is refused",
        ) {
            val result =
                runOps(
                    "oblast: Sales\nentity: {Customer: {}}",
                    entityOps.replace(
                        "mapping: {}, default: demo",
                        "at: /oblast, mapping: {Sales: shop}",
                    ),
                    "unmapped-stem.yaml",
                )
            result.documents
                .single()
                .value
                .string("package") shouldBe "shop"
            runOps("entity: {Customer: {}}", entityOps, "unmapped-stem.yaml")
                .documents
                .single()
                .value
                .string("package") shouldBe "demo"
            shouldThrow<ProfileException> {
                runOps("entity: [{nazev: Customer}]", entityOps)
            }.code shouldBe "ys/record-id-missing"
        }
        test("empty and null sources do not write; an ignore consumes a selected subtree without hiding siblings") {
            runOps(
                "entity: {Customer: {a: null}}",
                entityOps + "- {op: rename, in: entity, at: /a, to: {kind: entity, property: description}}",
            ).properties() shouldBe JsonObject(emptyMap())
            val ignored =
                runOps(
                    "entity: {Customer: {x: {nested: anything}}}",
                    entityOps + "- {op: ignore, in: entity, at: /x, reason: Fixture}",
                )
            ignored.ignored.single().reason shouldBe "Fixture"
            shouldThrow<ProfileException> {
                runOps("entity: {Customer: {x: {}, sibling: value}}", entityOps + "- {op: ignore, in: entity, at: /x}")
            }.location.path shouldBe "/entity/Customer/sibling"
        }
        test("file pattern validation is located and identity diagnostic ships in the library catalogue") {
            shouldThrow<ProfileException> { recordProfile("- {op: ignore, files: ['!x']}") }
                .location.path shouldBe "/operations/0/files/0"
            val catalogue =
                Json
                    .parseToJsonElement(
                        requireNotNull(ProfileEngine::class.java.getResourceAsStream("/profiles/diagnostics.json"))
                            .bufferedReader()
                            .use { it.readText() },
                    ).jsonArray
            catalogue
                .single { it.jsonObject.string("code") == "ys/record-id-missing" }
                .jsonObject
                .string("where") shouldBe "RecordPlan (at the authored record)"
        }
    })
