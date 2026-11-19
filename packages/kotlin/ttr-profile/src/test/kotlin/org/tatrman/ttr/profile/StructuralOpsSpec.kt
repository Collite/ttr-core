// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.*

internal val demoRoot = Path.of("src/test/resources/demo-cs")

internal fun demoChain(profile: String = "demo-cs.yaml") =
    ProfileResolver(
        demoRoot,
        emptyMap(),
    ).resolve("./profiles/$profile", demoRoot.resolve("ttr.yaml").toAbsolutePath())

/** S3.3: the cross-file, cross-package link group, read as one run. */
internal fun linkInputs() =
    demoRoot
        .resolve("links/input")
        .listDirectoryEntries("*.yaml")
        .sorted()
        .map { ForeignTree.read(it.readText(), it.name) }

internal fun runOps(
    source: String,
    operations: String,
    file: String = "one.yaml",
) = ProfileEngine.apply(listOf(ForeignTree.read(source, file)), listOf(recordProfile(operations)))

internal fun ApplyResult.defs() = documents.flatMap { it.value.getValue("defs").jsonArray }.map { it.jsonObject }

internal fun ApplyResult.properties() = defs().first().getValue("properties").jsonObject

internal val entityOps =
    "- {op: package, mapping: {}, default: demo}\n" +
        "- {op: kinds, at: /entity, mapping: {entita: entity}}\n"

class StructuralOpsSpec :
    FunSpec({
        test("kinds iterates sequence, mapping and nested records; IDs and discriminator keys are consumed") {
            val inputs =
                listOf("01-sequence.yaml", "02-mapping.yaml", "03-mixed.yaml", "06-table.yaml")
                    .map { ForeignTree.read(demoRoot.resolve("input/$it").readText(), it) }
            val result = ProfileEngine.apply(inputs, demoChain())
            result.defs().map { it.getValue("id").jsonPrimitive.content } shouldBe
                listOf("Customer", "Invoice", "Supplier", "SupplierStorage", "Warehouse")
            result
                .defs()
                .first()
                .getValue("properties")
                .jsonObject
                .getValue("attributes")
                .jsonArray
                .first()
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content shouldBe "kod"
            result.documents.map {
                it.value
                    .getValue("model")
                    .jsonPrimitive.content
            } shouldBe listOf("er", "binding", "db")
            result.documents.all { it.value.getValue("imports") == JsonArray(emptyList()) } shouldBe true
        }
        test("kinds reports unknown discriminator at its key and missing non-string or absent identity at the record") {
            val unknown =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(
                        listOf(
                            ForeignTree.read(
                                demoRoot.resolve("negative/unknown-kind.yaml").readText(),
                                "unknown-kind.yaml",
                            ),
                        ),
                        demoChain(),
                    )
                }
            unknown.code shouldBe "ys/unknown-kind"
            unknown.location.line shouldBe 2
            unknown.location.column shouldBe 6
            listOf("- {x: 1}", "- {name: 12}", "- {name: null}").forEach { record ->
                val error =
                    shouldThrow<ProfileException> {
                        runOps("entity:\n  $record", "- {op: kinds, at: /entity, id: /name, mapping: {e: entity}}")
                    }
                error.code shouldBe "ys/record-id-missing"
                error.location.line shouldBe 2
            }
        }
        test("kinds empty pointer makes a whole-file record and $ key addresses mapping entries") {
            val result =
                runOps(
                    "nazev: Whole\npopis: Celý\n",
                    """
                    - {op: kinds, at: '', id: /nazev, mapping: {e: entity}}
                    - {op: package, mapping: {}, default: demo}
                    - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}
                    """.trimIndent(),
                )
            result.defs().single().getValue("id") shouldBe JsonPrimitive("Whole")
            runOps("entity: {Customer: {}}", entityOps).defs().single().getValue("id") shouldBe
                JsonPrimitive("Customer")
        }
        test("package uses full stem or explicit string, falls back to default, and groups files in author order") {
            val sources =
                listOf("pt02_first.yaml", "second.yaml").map {
                    ForeignTree.read("entity: {${if (it.startsWith("pt")) "First" else "Second"}: {}}", it)
                }
            val ops =
                recordProfile(
                    entityOps.replace("mapping: {}, default: demo", "mapping: {pt02_first: shop, second: shop}"),
                )
            val result = ProfileEngine.apply(sources, listOf(ops))
            result.documents.size shouldBe 1
            result.documents
                .single()
                .value
                .getValue("package") shouldBe JsonPrimitive("shop")
            result.defs().map { it.getValue("id").jsonPrimitive.content } shouldBe listOf("First", "Second")
            runOps(
                "oblast: Sales\nentity: {}",
                "- {op: package, at: /oblast, mapping: {Sales: shop}}\n" +
                    "- {op: kinds, at: /entity, mapping: {e: entity}}",
            ).documents.size shouldBe 0
            val error =
                shouldThrow<ProfileException> {
                    runOps(
                        "entity: {Customer: {}}",
                        entityOps.replace("mapping: {}, default: demo", "mapping: {}"),
                        "pt02_new.yaml",
                    )
                }
            error.code shouldBe "ys/value-unmapped"
            error.location.line shouldBe 1
            error.location.column shouldBe 1
            error.detail.contains("pt02_new") shouldBe true
        }
        test("S5.3a package verbatim: the string at `at` is the package path, its `/` the only change, to `.`") {
            val ops =
                recordProfile(
                    "- {op: package, at: /model/package, verbatim: true}\n" +
                        "- {op: kinds, at: /entity, mapping: {e: entity}}",
                )
            val result =
                ProfileEngine.apply(
                    listOf(
                        ForeignTree.read("model: {package: sales}\nentity: {Customer: {}}", "x/sales"),
                        ForeignTree.read("model: {package: sales/orders}\nentity: {Order: {}}", "x/sales/orders"),
                        // A tree that holds no record names no package, valid or not (the XM writer's `Types`).
                        ForeignTree.read("model: {package: Types}\nentity: {}", "x/Types"),
                    ),
                    listOf(ops),
                )
            result.documents.map {
                it.value
                    .getValue("package")
                    .jsonPrimitive.content
            } shouldBe
                listOf("sales", "sales.orders")
            result.defs().map { it.getValue("id").jsonPrimitive.content } shouldBe listOf("Customer", "Order")
        }
        test("S5.3a package verbatim: refused beside mapping or default; an invalid path is ys/package-invalid") {
            for (extra in listOf(", mapping: {}", ", default: demo", "")) {
                val at = if (extra.isEmpty()) "" else " at: /model/package,"
                withClue(extra.ifEmpty { "no at" }) {
                    shouldThrow<ProfileException> {
                        recordProfile("- {op: package,$at verbatim: true$extra}")
                    }.code shouldBe "ys/profile-invalid"
                }
            }
            val ops =
                "- {op: package, at: /model/package, verbatim: true}\n- {op: kinds, at: /entity, mapping: {e: entity}}"
            for ((model, detail) in listOf(
                "{package: Sales}" to "Sales",
                "{package: 'sales/2024'}" to "sales/2024",
                "{package: 'sales orders'}" to "sales orders",
                "{name: sales}" to "no package path at /model/package",
            )) {
                withClue(model) {
                    val error =
                        shouldThrow<ProfileException> { runOps("model: $model\nentity:\n  Customer: {}", ops) }
                    error.code shouldBe "ys/package-invalid"
                    error.location.line shouldBe 3
                    error.detail shouldContain detail
                }
            }
        }
        test("rename and nest unnest read original values, preserve nested order and write typed paths") {
            val result =
                runOps(
                    "entity:\n  Customer:\n    popis: Zákazník\n    role: buyer\n    obalka: {jiny: Další}\n",
                    entityOps +
                        """
                        - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}
                        - {op: nest, in: entity, at: /role, to: {kind: entity, path: [semantics, role]}}
                        - {op: rename, in: entity, at: /popis, to: {kind: entity, path: [semantics, sourceName]}}
                        - {op: unnest, in: entity, at: /obalka/jiny, to: {kind: entity, property: labelPlural}}
                        """.trimIndent(),
                )
            result.properties()["description"] shouldBe JsonPrimitive("Zákazník")
            result.properties()["semantics"] shouldBe
                Json.parseToJsonElement("""{"role":"buyer","sourceName":"Zákazník"}""")
            result.properties()["labelPlural"] shouldBe JsonPrimitive("Další")
            val reversed = recordProfile(entityOps + recordProfileOpsReversed())
            val left =
                runOps(
                    "entity: {Customer: {a: A, b: B}}",
                    entityOps + recordProfileOpsReversed().lines().reversed().joinToString("\n"),
                )
            ProfileEngine
                .apply(
                    listOf(ForeignTree.read("entity: {Customer: {a: A, b: B}}")),
                    listOf(reversed),
                ).properties() shouldBe
                left.properties()
        }
        test("writers reject unequal target kind at profile load and unknown properties through the schema") {
            val error =
                shouldThrow<ProfileException> {
                    recordProfile("- {op: rename, in: entity, at: /x, to: {kind: attribute, property: type}}")
                }
            error.code shouldBe "ys/profile-invalid"
            error.location.path shouldBe "/operations/0/to/kind"
            shouldThrow<ProfileException> {
                recordProfile("- {op: rename, in: entity, at: /x, to: {kind: entity, property: typo}}")
            }.code shouldBe "ys/profile-invalid"
        }
        test("ignore unions across ancestors and records reasons for key and whole file matches") {
            val result =
                ProfileEngine.apply(
                    listOf(
                        ForeignTree.read(demoRoot.resolve("input/01-sequence.yaml").readText(), "01-sequence.yaml"),
                        ForeignTree.read("bad: whatever", "x/layouts/view.yaml"),
                    ),
                    demoChain(),
                )
            result.ignored.map { it.reason } shouldBe listOf("Layout source", "Other consumer")
            result.ignored.map { it.location.path }.toSet() shouldBe setOf("", "/entity/0/poznamka")
        }
        test("codes is profile scoped and merges ancestor to child") {
            ProfileEngine.apply(emptyList(), demoChain()).report.codes shouldBe
                mapOf(
                    "E_BASE" to "ys/unknown-key",
                    "E_MIDDLE" to "ys/unknown-kind",
                    "E_SAME" to "ys/value-unmapped",
                )
        }
        test("consumption checks file and record keys, traverses nested containers and leaves null absent") {
            listOf(
                "entity: {Customer: {typo: bad}}",
                "extra: bad\nentity: {Customer: {}}",
                "entity: {Customer: {wrapper: {known: yes, typo: no}}}",
            ).forEach { source ->
                val error =
                    shouldThrow<ProfileException> {
                        runOps(
                            source,
                            entityOps +
                                "- {op: rename, in: entity, at: /wrapper/known, " +
                                "to: {kind: entity, property: description}}",
                        )
                    }
                error.code shouldBe "ys/unknown-key"
                (error.location.path.endsWith("typo") || error.location.path == "/extra") shouldBe true
            }
            runOps(
                "entity: {Customer: {optional: null}}",
                entityOps + "- {op: rename, in: entity, at: /optional, to: {kind: entity, property: description}}",
            ).properties() shouldBe JsonObject(emptyMap())
        }
        test("conflict ledger detects equal and prefix writes across inheritance at the authored second key") {
            val base =
                recordProfile(
                    entityOps + "- {op: rename, in: entity, at: /a, to: {kind: entity, property: description}}",
                )
            val child = recordProfile("- {op: rename, in: entity, at: /b, to: {kind: entity, property: description}}")
            val error =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(
                        listOf(
                            ForeignTree.read("entity:\n  Customer:\n    a: first\n    b: second\n", "conflict.yaml"),
                        ),
                        listOf(base, child),
                    )
                }
            error.code shouldBe "ys/profile-conflict"
            error.location.line shouldBe 4
            error.location.column shouldBe 5
            shouldThrow<ProfileException> {
                runOps(
                    "entity: {Customer: {a: {role: x}, b: y}}",
                    entityOps +
                        """
                        - {op: rename, in: entity, at: /a, to: {kind: entity, property: semantics}}
                        - {op: nest, in: entity, at: /b, to: {kind: entity, path: [semantics, role]}}
                        """.trimIndent(),
                )
            }.code shouldBe "ys/profile-conflict"
        }

        test("unconsumed scalar values in sequences and scalar records are located instead of dropped") {
            val rename = "- {op: rename, in: entity, at: /tags/0, to: {kind: entity, property: description}}"
            val nested =
                "- {op: kinds, in: entity, at: /sloupce, mapping: {a: attribute}}\n" +
                    "- {op: rename, in: attribute, at: /typ, to: {kind: attribute, property: type}}"
            listOf(
                Triple("entity:\n  A: {tags: [x, y]}\n", entityOps + rename, Triple(2, 17, "/entity/A/tags/1")),
                Triple("meta: [a, b, c]\n", "- {op: ignore, at: /meta/0}", Triple(1, 11, "/meta/1")),
                Triple("entity:\n  Invoice: Faktura\n", entityOps, Triple(2, 12, "/entity/Invoice")),
                Triple(
                    "entity:\n  A:\n    sloupce: {kod: {typ: string}, _poznamka: text}\n",
                    entityOps + nested,
                    Triple(3, 46, "/entity/A/sloupce/_poznamka"),
                ),
            ).forEach { (source, ops, expected) ->
                val error = shouldThrow<ProfileException> { runOps(source, ops) }
                error.code shouldBe "ys/unknown-key"
                Triple(error.location.line, error.location.column, error.location.path) shouldBe expected
                error.detail shouldBe "Unconsumed value ${expected.third}"
            }
            runOps("entity: {Invoice: ~}", entityOps).defs().single().string("id") shouldBe "Invoice"
            runOps(
                "entity: {Invoice: {tags: []}}",
                entityOps +
                    "- {op: kinds, in: entity, at: /tags, mapping: {a: attribute}}",
            ).properties()
                .isEmpty() shouldBe true
            runOps(
                "entity: {Invoice: Faktura}",
                entityOps +
                    "- {op: rename, in: entity, at: '', to: {kind: entity, property: description}}",
            ).properties()
                .string("description") shouldBe "Faktura"
        }

        test("✅D-MW-98 required: warn — rename, shape and link warn ys/key-missing at a record whose at is absent") {
            val ops =
                entityOps +
                    "- {op: kinds, at: /vztahy, mapping: {vztah: relation}}\n" +
                    "- {op: rename, in: relation, at: /popis, to: {kind: relation, property: description}, " +
                    "required: warn}\n" +
                    "- {op: shape, in: relation, at: /od, to: {kind: relation, property: from}, " +
                    "template: {ref: {input: /jmeno}}, required: warn}\n" +
                    "- {op: link, in: relation, at: /do, to: {kind: relation, property: to}, by: sourceName, " +
                    "required: warn}"
            val source = "entity:\n  A: {}\nvztahy:\n  R: {}\n  S: {popis: s, od: {jmeno: A}, do: A}\n"
            val result = runOps(source, ops)
            result.report.warnings.map { Triple(it.location.line, it.location.path, it.describe()) } shouldBe
                listOf(
                    Triple(4, "/vztahy/R", "ys/key-missing: R has no /popis, so relation.description is not written"),
                    Triple(4, "/vztahy/R", "ys/key-missing: R has no /od, so relation.from is not written"),
                    Triple(4, "/vztahy/R", "ys/key-missing: R has no /do, so relation.to is not written"),
                )
            // The run goes on: R has no ends, S has all three.
            result.defs().map { it.string("id") to it.getValue("properties").jsonObject.keys } shouldBe
                listOf("A" to emptySet(), "R" to emptySet(), "S" to setOf("description", "from", "to"))
            // Without the operand an absent key stays silent; the operand takes no other value.
            runOps(source, ops.replace(", required: warn", "")).report.warnings shouldBe emptyList()
            shouldThrow<ProfileException> { recordProfile(ops.replace("required: warn", "required: error")) }
                .code shouldBe "ys/profile-invalid"
            // review-378 F7: an explicit null writes nothing too, so it warns (rename, shape), and stays consumed.
            val nulls = runOps("entity:\n  A: {}\nvztahy:\n  T: {popis: ~, od: ~, do: A}\n", ops)
            nulls.report.warnings.map { it.describe() } shouldBe
                listOf(
                    "ys/key-missing: T has no /popis, so relation.description is not written",
                    "ys/key-missing: T has no /od, so relation.from is not written",
                )
        }
    })

private fun recordProfileOpsReversed() =
    """
    - {op: rename, in: entity, at: /a, to: {kind: entity, property: description}}
    - {op: rename, in: entity, at: /b, to: {kind: entity, property: labelPlural}}
    """.trimIndent()
