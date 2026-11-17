// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.*
import org.tatrman.ttr.writer.TtrRenderer

/**
 * MW-P5 S5.2b T1 (⚑MW-P5-S52-3 (h) → ✅D-MW-84; profiles contracts §3 amendment 2026-11-17): an overlay `kind:` whose value
 * is a canonical kind of the record's model re-kinds the definition (the keyword), reported `{property: kind, from, to}`;
 * any other `kind:` value stays a property; every added property is reported `{from: null, to}`. Review-346: the re-kind
 * is cn only (✅R346-A), a fan-out steers `kind:` to the record it re-kinds (F2), a failed load names the re-kind (F3),
 * and the role is the ruled shape `{kind: role, of: Person, event: Purchase}` (F4, S5.2b Found (a)).
 */
class OverlayRekindSpec :
    FunSpec({
        val source = "pojmy:\n  Customer: {popis: Zákazník}\n  Person: {}\n  Purchase: {}\n"
        val operations =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /pojmy, mapping: {pojem: kind}}\n" +
                "- {op: rename, in: kind, at: /popis, to: {kind: kind, property: description}}\n"
        val customer = "demo.cn.kind.Customer"

        fun overlaid(
            overlay: String,
            input: String = source,
            ops: String = operations,
        ) = ProfileEngine.apply(
            listOf(ForeignTree.read(input, "one.yaml")),
            listOf(
                ProfileReader.read(
                    "profile: demo-rekind/1\ngrammar: '>=0.22 <1.0'\noverlay:\n$overlay\noperations:\n$ops",
                    "profile.yaml",
                ),
            ),
        )
        val role = "  $customer: {kind: role, of: Person, event: Purchase}\n  demo.cn.kind.Purchase: {kind: event}"

        fun ApplyResult.def(
            id: String,
            model: String = "cn",
        ) = documents
            .single { it.value.string("model") == model }
            .value
            .getValue("defs")
            .jsonArray
            .map { it.jsonObject }
            .single { it.string("id") == id }

        // A cn body is ordered: canonical `properties` is a list of one-key objects.
        fun JsonObject.props(): JsonObject =
            when (val value = getValue("properties")) {
                is JsonArray -> JsonObject(value.flatMap { it.jsonObject.entries }.associate { it.key to it.value })
                else -> value.jsonObject
            }

        test("{kind: role, of: Person, event: Purchase} on a kind record → def role, same cn document; all reported") {
            val result = overlaid(role)
            result.documents.map { it.value.string("model") } shouldBe listOf("cn")
            result.def("Customer").string("kind") shouldBe "role"
            result.def("Customer").props() shouldBe
                Json.parseToJsonElement("""{"description":"Zákazník","of":"Person","event":"Purchase"}""")
            result.def("Person").string("kind") shouldBe "kind"
            result.def("Purchase").string("kind") shouldBe "event"
            val rendered =
                result.documents
                    .single()
                    .canonical.ast.definitions
                    .single { it.name == "Customer" }
            TtrRenderer.renderDef(rendered).let {
                it shouldStartWith "def role Customer"
                it shouldContain "of: \"Person\""
                it shouldContain "event: Purchase"
            }
            result.report.overridden shouldBe
                listOf(
                    Overridden(customer, "kind", JsonPrimitive("kind"), JsonPrimitive("role")),
                    Overridden(customer, "of", JsonNull, JsonPrimitive("Person")),
                    Overridden(customer, "event", JsonNull, JsonPrimitive("Purchase")),
                    Overridden("demo.cn.kind.Purchase", "kind", JsonPrimitive("kind"), JsonPrimitive("event")),
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
                    "profile: demo-rekind/1\ngrammar: '>=0.22 <1.0'\noverlay:\n$role\noperations:\n$operations",
                    "profile.yaml",
                ).overlay shouldBe
                Json.parseToJsonElement(
                    """{"$customer":{"kind":"role","of":"Person","event":"Purchase"},""" +
                        """"demo.cn.kind.Purchase":{"kind":"event"}}""",
                )
        }

        // Review-346 F1 (✅R346-A): only a cn record re-kinds. An er entity's or a db table's `kind:` is a property, and
        // the strict load refuses it at the overlay entry, not as `def relation` / `def project`.
        test("kind: relation on an er entity and kind: project on a db table stay properties: refused at the overlay") {
            listOf(
                Triple("entity", "demo.er.entity.A", "relation"),
                Triple("table", "demo.db.dbo.A", "project"),
            ).forEach { (kind, qname, to) ->
                val error =
                    shouldThrow<ProfileException> {
                        overlaid(
                            "  $qname: {kind: $to}",
                            input = "veci:\n  A: {}\n",
                            ops =
                                "- {op: package, mapping: {}, default: demo}\n" +
                                    "- {op: kinds, at: /veci, mapping: {v: $kind}}\n",
                        )
                    }
                error.code shouldBe "ys/profile-invalid"
                error.location.file shouldBe "profile.yaml"
                error.location.line shouldBe 4
            }
        }

        // Review-346 F2: one source name fanned out to a cn concept and an md domain (both bodies define `kind`) used
        // to be ambiguous; a `kind:` the cn record can re-kind with goes to it.
        test("a sourceName fan-out steers a re-kinding kind: to the top-level cn record") {
            val domains = operations + "- {op: kinds, at: /pojmy, mapping: {pojem: domain}}\n"
            val result = overlaid("  Customer: {kind: role}", ops = domains)
            result.def("Customer").string("kind") shouldBe "role"
            result.def("Customer", "md").string("kind") shouldBe "domain"
            result.report.overridden shouldBe
                listOf(Overridden(customer, "kind", JsonPrimitive("kind"), JsonPrimitive("role")))
        }

        // Review-346 F3: an error inside a re-kinded definition keeps the dialect location and names the overlay's re-kind.
        test("a canonical error inside a re-kinded definition names the overlay that re-kinded it") {
            val error =
                shouldThrow<ProfileException> {
                    overlaid(
                        "  $customer: {kind: role, of: Person, event: Person}",
                        input = "pojmy:\n  Customer: {def: Kdo nakupuje}\n  Person: {}\n",
                        ops =
                            operations +
                                "- {op: rename, in: kind, at: /def, to: {kind: kind, property: definition}}\n",
                    )
                }
            error.code shouldBe "ys/profile-invalid"
            error.location.file shouldBe "one.yaml"
            error.location.path shouldBe "/pojmy/Customer/def"
            error.detail shouldContain "re-kinded $customer from kind to role at profile.yaml:4:"
        }
    })
