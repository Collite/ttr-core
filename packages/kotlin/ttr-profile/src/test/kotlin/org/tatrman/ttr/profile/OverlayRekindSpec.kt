// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.*
import org.tatrman.ttr.writer.TtrRenderer

/**
 * MW-P5 S5.2b T1 (⚑MW-P5-S52-3 (h) → ✅D-MW-84; profiles contracts §3 amendment 2026-11-17): an overlay `kind:` whose value
 * is a canonical kind of the record's model re-kinds the definition (the keyword), reported `{property: kind, from, to}`;
 * any other `kind:` value stays a property; every added property is reported `{from: null, to}`.
 */
class OverlayRekindSpec :
    FunSpec({
        val source = "pojmy:\n  Customer: {popis: Zákazník}\n  Person: {}\n"
        val operations =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /pojmy, mapping: {pojem: kind}}\n" +
                "- {op: rename, in: kind, at: /popis, to: {kind: kind, property: description}}\n"
        val customer = "demo.cn.kind.Customer"

        fun overlaid(overlay: String) =
            ProfileEngine.apply(
                listOf(ForeignTree.read(source, "one.yaml")),
                listOf(
                    ProfileReader.read(
                        "profile: demo-rekind/1\ngrammar: '>=0.22 <1.0'\noverlay:\n$overlay\noperations:\n$operations",
                        "profile.yaml",
                    ),
                ),
            )

        fun ApplyResult.def(id: String) = defs().single { it.string("id") == id }

        // A cn body is ordered: canonical `properties` is a list of one-key objects.
        fun JsonObject.props(): JsonObject =
            when (val value = getValue("properties")) {
                is JsonArray -> JsonObject(value.flatMap { it.jsonObject.entries }.associate { it.key to it.value })
                else -> value.jsonObject
            }

        test("{kind: role, base: Person} on a kind record → def role, same cn document; kind and base reported") {
            val result = overlaid("  $customer: {kind: role, base: Person}")
            result.documents.map { it.value.string("model") } shouldBe listOf("cn")
            result.def("Customer").string("kind") shouldBe "role"
            result.def("Customer").props() shouldBe
                Json.parseToJsonElement("""{"description":"Zákazník","base":"Person"}""")
            result.def("Person").string("kind") shouldBe "kind"
            val role =
                result.documents
                    .single()
                    .canonical.ast.definitions
                    .single { it.name == "Customer" }
            TtrRenderer.renderDef(role).let {
                it shouldStartWith "def role Customer"
                it shouldContain "base"
            }
            result.report.overridden shouldBe
                listOf(
                    Overridden(customer, "kind", JsonPrimitive("kind"), JsonPrimitive("role")),
                    Overridden(customer, "base", JsonNull, JsonPrimitive("Person")),
                )
        }

        test("kind: <not a canonical kind> stays the cn stereotype-ref property: no re-kind, reported as an addition") {
            val result = overlaid("  $customer: {kind: Person}")
            result.def("Customer").string("kind") shouldBe "kind"
            result.def("Customer").props().string("kind") shouldBe "Person"
            result.report.overridden shouldBe listOf(Overridden(customer, "kind", JsonNull, JsonPrimitive("Person")))
        }

        test("a re-kind to the record's own kind changes nothing and reports nothing") {
            val result = overlaid("  $customer: {kind: kind}")
            result.def("Customer").string("kind") shouldBe "kind"
            result.def("Customer").props() shouldBe
                Json.parseToJsonElement("""{"description":"Zákazník"}""")
            result.report.overridden shouldBe emptyList()
        }

        test("an added property is reported {from: null, to}; a replaced one keeps its from") {
            val result = overlaid("  demo.cn.kind.Person: {description: Osoba}\n  $customer: {description: Zákazník!}")
            result.report.overridden shouldBe
                listOf(
                    Overridden("demo.cn.kind.Person", "description", JsonNull, JsonPrimitive("Osoba")),
                    Overridden(customer, "description", JsonPrimitive("Zákazník"), JsonPrimitive("Zákazník!")),
                )
            result.reportJson().getValue("overridden").jsonArray.first().jsonObject.let {
                it["from"] shouldBe JsonNull
                it.string("to") shouldBe "Osoba"
            }
        }

        test("schema: a profile whose overlay re-kinds loads strictly") {
            ProfileReader
                .read(
                    "profile: demo-rekind/1\ngrammar: '>=0.22 <1.0'\noverlay:\n" +
                        "  $customer: {kind: role, base: Person}\noperations:\n$operations",
                    "profile.yaml",
                ).overlay shouldBe Json.parseToJsonElement("""{"$customer":{"kind":"role","base":"Person"}}""")
        }
    })
