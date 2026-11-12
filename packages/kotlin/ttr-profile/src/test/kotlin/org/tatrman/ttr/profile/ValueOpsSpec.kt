// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

/** S3.3 T1: `values` and `shape` (contracts §3, ✅D-MW-67 (b)). */
class ValueOpsSpec :
    FunSpec({
        val typ =
            entityOps + "- {op: kinds, in: entity, at: /sloupce, mapping: {sloupec: attribute}}\n" +
                "- {op: values, in: attribute, at: /typ, to: {kind: attribute, property: type}, " +
                "mapping: {číslo: integer, text: string}"

        fun attributes(result: ApplyResult) =
            result
                .properties()
                .getValue("attributes")
                .jsonArray
                .associate { it.jsonObject.string("id") to it.jsonObject.getValue("properties").jsonObject }

        test("values maps a scalar, a qname string and a localized map") {
            val result =
                runOps(
                    "entity:\n  E:\n    druh: obj\n    sloupce:\n      a: {typ: číslo, domena: mena}\n",
                    "$typ}\n" +
                        "- {op: values, in: attribute, at: /domena, to: {kind: attribute, property: domain}, " +
                        "mapping: {mena: demo.md.domain.Mena}}\n" +
                        "- {op: values, in: entity, at: /druh, to: {kind: entity, property: description}, " +
                        "mapping: {obj: {cs: Objednávka, en: Order}}}",
                )
            attributes(result).getValue("a") shouldBe
                buildJsonObject {
                    put("type", "integer")
                    put("domain", "demo.md.domain.Mena")
                }
            result.properties().getValue("description") shouldBe
                buildJsonObject {
                    put("cs", "Objednávka")
                    put("en", "Order")
                }
        }
        test("default fills a missing key and an explicit null; without default a missing key yields nothing") {
            val source = "entity:\n  E:\n    sloupce:\n      a: {}\n      b: {typ: null}\n      c: {typ: text}\n"
            attributes(runOps(source, "$typ, default: string}")).mapValues { it.value["type"] } shouldBe
                mapOf("a" to JsonPrimitive("string"), "b" to JsonPrimitive("string"), "c" to JsonPrimitive("string"))
            attributes(runOps(source, "$typ}")).mapValues { it.value["type"] } shouldBe
                mapOf("a" to null, "b" to null, "c" to JsonPrimitive("string"))
        }
        test("an unmapped string is ys/value-unmapped at the authored key unless default; default covers it") {
            val source = "entity:\n  E:\n    sloupce:\n      a: {typ: datum}\n"
            val error = shouldThrow<ProfileException> { runOps(source, "$typ}") }
            error.code shouldBe "ys/value-unmapped"
            error.location.path shouldBe "/entity/E/sloupce/a/typ"
            error.location.line shouldBe 4
            error.location.column shouldBe 11
            attributes(runOps(source, "$typ, default: string}")).getValue("a").string("type") shouldBe "string"
        }
        test("a structure is ys/value-unmapped at the authored key even with a default (review-251 F1)") {
            listOf("{a: 1}", "[a]").forEach { value ->
                val error =
                    shouldThrow<ProfileException> {
                        runOps(
                            "entity:\n  E:\n    druh: $value\n",
                            entityOps +
                                "- {op: values, in: entity, at: /druh, to: {kind: entity, property: description}, " +
                                "mapping: {x: X}, default: D}",
                        )
                    }
                error.code shouldBe "ys/value-unmapped"
                error.location.path shouldBe "/entity/E/druh"
                error.location.line shouldBe 3
                error.location.column shouldBe 5
            }
        }

        val patterns =
            entityOps + "- {op: shape, in: entity, at: '', to: {kind: entity, path: [search, patterns]}, " +
                "template: [{input: /vzor}, {input: /vzor_alt}]}"

        fun searchPatterns(result: ApplyResult) =
            result
                .defs()
                .map {
                    it
                        .getValue("properties")
                        .jsonObject["search"]
                        ?.jsonObject
                        ?.getValue("patterns")
                        ?.jsonArray
                        ?.map { p -> p.jsonPrimitive.content }
                }
        test("shape substitutes {input} and drops a list element whose input is missing (✅D-MW-67 (b))") {
            searchPatterns(
                runOps(
                    "entity:\n  A: {vzor: x, vzor_alt: y}\n  B: {vzor: z}\n  C: {vzor_alt: null, vzor: w}\n",
                    patterns,
                ),
            ) shouldBe listOf(listOf("x", "y"), listOf("z"), listOf("w"))
        }
        test("all inputs missing yields nothing; an object member is dropped; a literal template is written as is") {
            searchPatterns(runOps("entity:\n  A: {}\n", patterns)) shouldBe listOf(null)
            val filter =
                entityOps + "- {op: shape, in: entity, at: /zdroj, to: {kind: entity, path: [semantics, filtr]}, " +
                    "template: {sql: {input: /filtr}, kind: sql, other: {input: /jiny}}}\n" +
                    "- {op: shape, in: entity, at: /aktivni, to: {kind: entity, path: [semantics, flag]}, " +
                    "template: {active: 1}}"
            val result = runOps("entity:\n  A: {zdroj: {filtr: STAV = 1}, aktivni: ano}\n", filter)
            result.properties().getValue("semantics") shouldBe
                Json.parseToJsonElement("""{"filtr":{"sql":"STAV = 1","kind":"sql"},"flag":{"active":1}}""")
        }
        test("a container whose inputs are all missing is dropped, recursively; a literal {} stays (✅R251-B)") {
            fun shaped(
                source: String,
                template: String,
            ) = runOps(
                "entity:\n  E: $source\n",
                entityOps +
                    "- {op: shape, in: entity, at: '', to: {kind: entity, path: [semantics, s]}, template: $template}",
            ).properties()["semantics"]
                ?.jsonObject
                ?.get("s")
            shaped("{en: Hello}", "{cs: {x: {input: /chybi}}, en: {input: /en}}") shouldBe
                Json.parseToJsonElement("""{"en":"Hello"}""")
            shaped("{x: 7}", "[{a: {input: /chybi}}, {input: /x}]") shouldBe Json.parseToJsonElement("[7]")
            shaped("{x: 7}", "{lit: {}, v: {input: /x}}") shouldBe Json.parseToJsonElement("""{"lit":{},"v":7}""")
            shaped("{en: Hello}", "{a: {lit: {}, v: {input: /chybi}}, en: {input: /en}}") shouldBe
                Json.parseToJsonElement("""{"en":"Hello"}""")
            shaped("{}", "{a: {b: [{input: /chybi}]}, c: [{input: /nic}]}") shouldBe null
        }
        test("a template object with input and another key is refused at load (S3.1 schema): no expressions") {
            listOf(
                "template: {input: /a, concat: /b}",
                "template: [{input: /a, default: x}]",
                "template: {input: [/a]}",
            ).forEach { template ->
                val error =
                    shouldThrow<ProfileException> {
                        recordProfile(
                            "  - {op: shape, in: entity, at: '', to: {kind: entity, property: description}, $template}",
                        )
                    }
                error.code shouldBe "ys/profile-invalid"
            }
        }
    })
