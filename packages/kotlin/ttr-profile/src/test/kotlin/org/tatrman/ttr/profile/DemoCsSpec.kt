// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlin.io.path.readText
import java.nio.file.Path
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
            inputs.size shouldBe 9
            inputs.forEach { input ->
                // S3.3: values.yaml extends demo-cs/1; the root supplies demo.sources.json for the links.
                val result =
                    ProfileEngine.apply(
                        listOf(ForeignTree.read(input.readText(), input.name)),
                        demoChain("values.yaml"),
                        demoRoot,
                    )
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
                            demoChain("values.yaml"),
                            demoRoot,
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
            val result =
                ProfileEngine.apply(
                    inputs.map { ForeignTree.read(it.readText(), it.name) },
                    demoChain("values.yaml"),
                    demoRoot,
                )
            val er = result.documents.single { it.value.string("model") == "er" }
            er.value
                .getValue("defs")
                .jsonArray
                .map { it.jsonObject.string("id") } shouldBe
                listOf(
                    "Customer",
                    "Invoice",
                    "Supplier",
                    "Order",
                    "Product",
                    "Odberatel",
                    "Dodavka",
                    "Sklad",
                    "Objednavka",
                    "Zakaznik",
                    "ObjednavkaZakaznika",
                )
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
        test("file pattern validation is located and identity diagnostic is in the one diagnostic-code catalogue") {
            shouldThrow<ProfileException> { recordProfile("- {op: ignore, files: ['!x']}") }
                .location.path shouldBe "/operations/0/files/0"
            // S3.7 T0 (S3.5 Found (h)): one list — ttr-metadata-cli's generated `diagnostic-codes.json` (`lint --codes`);
            // ttr-profile ships no second copy.
            val catalogue =
                Json
                    .parseToJsonElement(
                        Path
                            .of(
                                "../ttr-metadata-cli/src/main/resources/org/tatrman/ttr/metadata/cli/diagnostic-codes.json",
                            ).readText(),
                    ).jsonArray
            catalogue
                .single { it.jsonObject.string("code") == "ys/record-id-missing" }
                .jsonObject
                .string("raisedBy") shouldBe "ttr-profile RecordPlan (at the authored record)"
            ProfileEngine::class.java.getResource("/profiles/diagnostics.json") shouldBe null
        }
        test("S3.3 links group: cross-file links by sourceName and by path, a cross-package import, as one run") {
            val result = ProfileEngine.apply(linkInputs(), demoChain("values.yaml"), demoRoot)
            result.documents.map { it.value.string("package") + "-" + it.value.string("model") } shouldBe
                listOf("prodej-er", "demo-er", "demo-binding")
            result.documents.forEach { document ->
                val name = document.value.string("package") + "-" + document.value.string("model")
                document.write() shouldBe demoRoot.resolve("links/expected/$name.yaml").readText()
                CanonicalYaml.write(CanonicalYaml.read(document.write())) shouldBe document.write()
            }
        }
        test("S3.3 overlay on the links group: the overlay wins and overridden[] is reported") {
            val result = ProfileEngine.apply(linkInputs(), demoChain("overlay-inline.yaml"), demoRoot)
            result.documents
                .single { it.value.string("package") == "demo" && it.value.string("model") == "er" }
                .value
                .getValue("defs")
                .jsonArray
                .first()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .string("description") shouldBe "Objednávka (overlay)"
            result.report.overridden.map { it.qname } shouldBe
                listOf("demo.er.entity.Objednavka", "prodej.er.entity.Zakaznik")
        }
    })
