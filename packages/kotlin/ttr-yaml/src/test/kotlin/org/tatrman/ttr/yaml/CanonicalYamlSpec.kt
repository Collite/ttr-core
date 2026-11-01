// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

class CanonicalYamlSpec :
    FunSpec({
        fun roundTrip(text: String): CanonicalDocument {
            val original = CanonicalYaml.fromTtr(text, "invented.ttrm")
            val yaml = CanonicalYaml.write(original)
            val read = CanonicalYaml.read(yaml, "invented.yaml")
            read.value shouldBe original.value
            CanonicalYaml.write(read) shouldBe yaml
            CanonicalYaml.fromTtr(CanonicalYaml.toTtr(read)).value shouldBe original.value
            read.ast.errors shouldBe emptyList()
            return read
        }
        test("document context, definition order, graph and security block boundaries") {
            val doc =
                roundTrip(
                    """
                    package demo.sales
                    import demo.common.*
                    import demo.shared
                    model er schema inventory locale en
                    def entity Z {}
                    def entity A {}
                    security { own Z: admin, classify Z: secret }
                    security { grant read on A to staff, mask Z.code }
                    """.trimIndent(),
                )
            doc.ast.packageName shouldBe "demo.sales"
            doc.ast.imports.map { it.wildcard } shouldBe listOf(true, false)
            doc.ast.definitions.map { it.name } shouldBe listOf("Z", "A")
            doc.ast.securityBlocks.size shouldBe 2
            roundTrip(
                "graph main { model: er, description: { en: \"Graph\" }, tags: [\"x\"], objects: [Z, A], layout: { mode: tree } }",
            )
        }
        test("every current kind and nested structural family has a two-way adapter") {
            val metadata =
                Json
                    .parseToJsonElement(
                        javaClass.getResource("/schemas/canonical-metadata.json")!!.readText(),
                    ).jsonObject
            for (kind in metadata
                .getValue(
                    "kinds",
                ).jsonArray
                .map {
                    it.jsonObject
                        .getValue("kind")
                        .jsonPrimitive.content
                }) {
                val model =
                    when (kind) {
                        "role", "kind", "subkind", "event", "phase", "quality", "relation_type" -> "cn"
                        "term", "pattern", "example" -> "lexicon"
                        else -> "er"
                    }
                roundTrip("model $model\ndef $kind sample {}")
            }
            roundTrip(
                """
                model world
                def world estate {
                  def storage warehouse { type: postgres, def schema sales { code: text, amount: { type: decimal, precision: 2 } } }
                  def engine pg { type: postgres, version: "16" }
                  def grant { who: ops, program: *, as: admin }
                  def process nightly { steps: [def step export { program: ship }] }
                }
                """.trimIndent(),
            )
            roundTrip(
                """
                model er
                def entity Customer { attributes: [def attribute code { type: text, isKey: true }] }
                def query customers { parameters: [{ name: p, type: text, defaultValue: "x", binds: [Customer.code] }], skipSecurity: true, sourceText: "select 1" }
                """.trimIndent(),
            )
        }
        test("generic wrappers, repeated ordered properties and automation numeric lexemes") {
            roundTrip(
                """
                model automation
                def program ship {
                  values: { item: 1.0, item: 1, ref: none }
                  note: "none"
                  note: none
                  delay: 2 working_days
                  trigger: schedule "06:00"
                  task: run { input: none }
                  any: *
                }
                """.trimIndent(),
            )
            roundTrip("model cn\ndef kind Order { arbitrary: { ref: value, call: other }, definition: \"x\" }")
            roundTrip("model md\ndef domain Count { restrict: { range: 1..12, pattern: \"[0-9]+\" } }")
        }
        test("safe YAML 1.2, quoted strings, located rejection and approved pl sugar") {
            val sugar =
                CanonicalYaml.read(
                    """
                    ttr: "0.21"
                    model: cn
                    defs:
                      - kind: kind
                        id: Order
                        properties:
                          definition: !pl |
                            x + y
                    """.trimIndent(),
                    "sugar.yaml",
                )
            CanonicalYaml.write(sugar) shouldContain "tag:"
            val bad =
                listOf(
                    "ttr: \"0.21\"\nttr: \"0.21\"",
                    "ttr: \"0.21\"\n---\nttr: \"0.21\"",
                    "ttr: \"0.21\"\nmodel: wrong",
                    "ttr: \"0.20\"",
                    "ttr: \"0.21\"\ndefs: &defs []",
                    "ttr: \"0.21\"\ndefs: !java/object []",
                    "ttr: \"0.21\"\ndefs: [{kind: entity, id: A, properties: {typo: true}}]",
                )
            bad.forEach { text ->
                shouldThrowAny { CanonicalYaml.read(text, "invalid.yaml") }.message!! shouldContain "invalid.yaml:"
            }
        }
        test("canonical byte formatting and reserved collisions are explicit") {
            val doc =
                roundTrip("model lexicon locale cs\ndef pattern invoice { for: er.entity.Order, match: \"^\\\\d{4}\" }")
            val yaml = CanonicalYaml.write(doc)
            yaml.endsWith("\n") shouldBe true
            yaml.contains("\r") shouldBe false
            listOf(
                "model md\ndef domain D { restrict: { members: { \"range\": { en: \"x\" } } } }",
                "model md\ndef domain D { restrict: { members: { \"object\": { en: \"x\" } } } }",
                "model world\ndef world W { def storage S { schemas: \"x\" } }",
            ).forEach { text ->
                shouldThrowAny { CanonicalYaml.fromTtr(text, "collision.ttrm") }.message!! shouldContain
                    "collision.ttrm:"
            }
        }
    })
