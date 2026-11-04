// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.*

internal val demoRoot = Path.of("src/test/resources/demo-cs")
internal fun demoChain() = ProfileResolver(demoRoot, emptyMap()).resolve("./profiles/demo-cs.yaml", demoRoot.resolve("ttr.yaml").toAbsolutePath())
internal fun runOps(source: String, operations: String, file: String = "one.yaml") =
    ProfileEngine.apply(listOf(ForeignTree.read(source, file)), listOf(recordProfile(operations)))
internal fun ApplyResult.defs() = documents.flatMap { it.value.getValue("defs").jsonArray }.map { it.jsonObject }
internal fun ApplyResult.properties() = defs().first().getValue("properties").jsonObject
internal val entityOps = "- {op: package, mapping: {}, default: demo}\n" +
    "- {op: kinds, at: /entity, mapping: {entita: entity}}\n"

class StructuralOpsSpec : FunSpec({
    test("kinds iterates sequence, mapping and nested records; IDs and discriminator keys are consumed") {
        val inputs = listOf("01-sequence.yaml", "02-mapping.yaml", "03-mixed.yaml", "06-table.yaml")
            .map { ForeignTree.read(demoRoot.resolve("input/$it").readText(), it) }
        val result = ProfileEngine.apply(inputs, demoChain())
        result.defs().map { it.getValue("id").jsonPrimitive.content } shouldBe
            listOf("Customer", "Invoice", "Supplier", "SupplierStorage", "Warehouse")
        result.defs().first().getValue("properties").jsonObject.getValue("attributes").jsonArray
            .first().jsonObject.getValue("id").jsonPrimitive.content shouldBe "kod"
        result.documents.map { it.value.getValue("model").jsonPrimitive.content } shouldBe listOf("er", "binding", "db")
        result.documents.all { it.value.getValue("imports") == JsonArray(emptyList()) } shouldBe true
    }
    test("kinds reports unknown discriminator at its key and missing non-string or absent identity at the record") {
        val unknown = shouldThrow<ProfileException> {
            ProfileEngine.apply(listOf(ForeignTree.read(demoRoot.resolve("negative/unknown-kind.yaml").readText(), "unknown-kind.yaml")), demoChain())
        }
        unknown.code shouldBe "ys/unknown-kind"
        unknown.location.line shouldBe 2
        unknown.location.column shouldBe 6
        listOf("- {x: 1}", "- {name: 12}", "- {name: null}").forEach { record ->
            val error = shouldThrow<ProfileException> {
                runOps("entity:\n  $record", "- {op: kinds, at: /entity, id: /name, mapping: {e: entity}}")
            }
            error.code shouldBe "ys/record-id-missing"
            error.location.line shouldBe 2
        }
    }
    test("kinds empty pointer makes a whole-file record and $ key addresses mapping entries") {
        val result = runOps("nazev: Whole\npopis: Celý\n", """
          - {op: kinds, at: '', id: /nazev, mapping: {e: entity}}
          - {op: package, mapping: {}, default: demo}
          - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}
        """.trimIndent())
        result.defs().single().getValue("id") shouldBe JsonPrimitive("Whole")
        runOps("entity: {Customer: {}}", entityOps).defs().single().getValue("id") shouldBe JsonPrimitive("Customer")
    }
    test("package uses full stem or explicit string, falls back to default, and groups files in author order") {
        val sources = listOf("pt02_first.yaml", "second.yaml").map { ForeignTree.read("entity: {${if (it.startsWith("pt")) "First" else "Second"}: {}}", it) }
        val ops = recordProfile(entityOps.replace("mapping: {}, default: demo", "mapping: {pt02_first: shop, second: shop}"))
        val result = ProfileEngine.apply(sources, listOf(ops))
        result.documents.size shouldBe 1
        result.documents.single().value.getValue("package") shouldBe JsonPrimitive("shop")
        result.defs().map { it.getValue("id").jsonPrimitive.content } shouldBe listOf("First", "Second")
        runOps("oblast: Sales\nentity: {}", "- {op: package, at: /oblast, mapping: {Sales: shop}}")
            .documents.size shouldBe 0
        val error = shouldThrow<ProfileException> {
            runOps("entity: {Customer: {}}", entityOps.replace("mapping: {}, default: demo", "mapping: {}"), "pt02_new.yaml")
        }
        error.code shouldBe "ys/value-unmapped"
        error.location.line shouldBe 1
        error.location.column shouldBe 1
        error.detail.contains("pt02_new") shouldBe true
    }
    test("rename and nest unnest read original values, preserve nested order and write typed paths") {
        val result = runOps("entity:\n  Customer:\n    popis: Zákazník\n    role: buyer\n    obalka: {jiny: Další}\n", entityOps + """
          - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}
          - {op: nest, in: entity, at: /role, to: {kind: entity, path: [semantics, role]}}
          - {op: rename, in: entity, at: /popis, to: {kind: entity, path: [semantics, sourceName]}}
          - {op: unnest, in: entity, at: /obalka/jiny, to: {kind: entity, property: displayLabel}}
        """.trimIndent())
        result.properties()["description"] shouldBe JsonPrimitive("Zákazník")
        result.properties()["semantics"] shouldBe Json.parseToJsonElement("""{"role":"buyer","sourceName":"Zákazník"}""")
        result.properties()["displayLabel"] shouldBe JsonPrimitive("Další")
        val reversed = recordProfile(entityOps + recordProfileOpsReversed())
        val left = runOps("entity: {Customer: {a: A, b: B}}", entityOps + recordProfileOpsReversed().lines().reversed().joinToString("\n"))
        ProfileEngine.apply(listOf(ForeignTree.read("entity: {Customer: {a: A, b: B}}")), listOf(reversed)).properties() shouldBe left.properties()
    }
    test("writers reject unequal target kind at profile load and unknown properties through the schema") {
        val error = shouldThrow<ProfileException> {
            recordProfile("- {op: rename, in: entity, at: /x, to: {kind: attribute, property: type}}")
        }
        error.code shouldBe "ys/profile-invalid"
        error.location.path shouldBe "/operations/0/to/kind"
        shouldThrow<ProfileException> {
            recordProfile("- {op: rename, in: entity, at: /x, to: {kind: entity, property: typo}}")
        }.code shouldBe "ys/profile-invalid"
    }
    test("ignore unions across ancestors and records reasons for key and whole file matches") {
        val result = ProfileEngine.apply(listOf(
            ForeignTree.read(demoRoot.resolve("input/01-sequence.yaml").readText(), "01-sequence.yaml"),
            ForeignTree.read("bad: whatever", "x/layouts/view.yaml"),
        ), demoChain())
        result.ignored.map { it.reason } shouldBe listOf("Layout source", "Other consumer")
        result.ignored.map { it.location.path }.toSet() shouldBe setOf("", "/entity/0/poznamka")
    }
    test("codes is profile scoped and merges ancestor to child") {
        ProfileEngine.apply(emptyList(), demoChain()).report.codes shouldBe mapOf(
            "E_BASE" to "ys/unknown-key", "E_MIDDLE" to "ys/unknown-kind", "E_SAME" to "ys/value-unmapped",
        )
    }
    test("consumption checks file and record keys, traverses nested containers and leaves null absent") {
        listOf("entity: {Customer: {typo: bad}}", "extra: bad\nentity: {Customer: {}}", "entity: {Customer: {wrapper: {known: yes, typo: no}}}").forEach { source ->
            val error = shouldThrow<ProfileException> {
                runOps(source, entityOps + "- {op: rename, in: entity, at: /wrapper/known, to: {kind: entity, property: description}}")
            }
            error.code shouldBe "ys/unknown-key"
            (error.location.path.endsWith("typo") || error.location.path == "/extra") shouldBe true
        }
        runOps("entity: {Customer: {optional: null}}", entityOps + "- {op: rename, in: entity, at: /optional, to: {kind: entity, property: description}}")
            .properties() shouldBe JsonObject(emptyMap())
    }
    test("conflict ledger detects equal and prefix writes across inheritance at the authored second key") {
        val base = recordProfile(entityOps + "- {op: rename, in: entity, at: /a, to: {kind: entity, property: description}}")
        val child = recordProfile("- {op: rename, in: entity, at: /b, to: {kind: entity, property: description}}")
        val error = shouldThrow<ProfileException> {
            ProfileEngine.apply(listOf(ForeignTree.read("entity:\n  Customer:\n    a: first\n    b: second\n", "conflict.yaml")), listOf(base, child))
        }
        error.code shouldBe "ys/profile-conflict"
        error.location.line shouldBe 4
        error.location.column shouldBe 5
        shouldThrow<ProfileException> { runOps("entity: {Customer: {a: {role: x}, b: y}}", entityOps + """
          - {op: rename, in: entity, at: /a, to: {kind: entity, property: semantics}}
          - {op: nest, in: entity, at: /b, to: {kind: entity, path: [semantics, role]}}
        """.trimIndent()) }.code shouldBe "ys/profile-conflict"
    }
})

private fun recordProfileOpsReversed() = """
  - {op: rename, in: entity, at: /a, to: {kind: entity, property: description}}
  - {op: rename, in: entity, at: /b, to: {kind: entity, property: displayLabel}}
""".trimIndent()
