// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.model.*
import org.tatrman.ttr.parser.conformance.ConformanceDump
import kotlinx.serialization.json.*

class CanonicalYamlEdgeSpec :
    FunSpec({
        test("YAML strings retain common indentation and triple delimiters") {
            val content = "  first\n  \"\"\"\n  last\n"
            val value =
                obj(
                    "ttr" to text("0.21"),
                    "model" to text("cn"),
                    "defs" to
                        JsonArray(
                            listOf(
                                obj(
                                    "kind" to text("kind"),
                                    "id" to text("Order"),
                                    "properties" to
                                        obj("definition" to obj("block" to text(content), "tag" to text("pl"))),
                                ),
                            ),
                        ),
                )
            val doc = CanonicalYaml.read(value.toString(), "content.yaml")
            val definition = doc.ast.definitions.single() as ConceptDef
            val block =
                definition.body.properties
                    .filterIsInstance<ConceptProperty.DefinitionBlock>()
                    .single()
                    .block
            block.text shouldBe content
            block.form shouldBe "tagged"
            val bytes = CanonicalYaml.write(doc)
            CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
        }
        test("automation triple wrappers preserve arbitrary text and its form") {
            val content = "  a\n  \"\"\"\n"
            val value =
                obj(
                    "ttr" to text("0.21"),
                    "model" to text("automation"),
                    "defs" to
                        JsonArray(
                            listOf(
                                obj(
                                    "kind" to text("program"),
                                    "id" to text("Ship"),
                                    "properties" to obj("note" to obj("triple" to text(content))),
                                ),
                            ),
                        ),
                )
            val doc = CanonicalYaml.read(value.toString())
            val auto = doc.ast.definitions.single() as AutomationDef
            (
                auto.body.entries
                    .single()
                    .value as AutoValue.Str
            ).triple shouldBe true
            (
                auto.body.entries
                    .single()
                    .value as AutoValue.Str
            ).raw shouldBe content
            CanonicalYaml.write(CanonicalYaml.read(CanonicalYaml.write(doc))) shouldBe CanonicalYaml.write(doc)
        }
        test("calc argument text cannot inject declarations") {
            val error =
                shouldThrowAny {
                    CanonicalYaml.read(
                        """
                        ttr: "0.21"
                        model: md
                        defs:
                          - kind: map
                            id: Convert
                            properties:
                              calc:
                                name: convert
                                args:
                                  input: '1) } def entity Injected {} def map Rest { calc: f('
                        """.trimIndent(),
                        "calc.yaml",
                    )
                }
            (error as CanonicalYamlException).line shouldBe 10
        }
        test("structural rule changes and unknown reached values fail the handler gate") {
            val m = CanonicalGrammar.metadata
            val changedRules = m.getValue("rules").jsonObject.toMutableMap()
            changedRules["participant"] = text("id")
            shouldThrowAny { GrammarCoverage.check(JsonObject(m + ("rules" to JsonObject(changedRules)))) }
            val bodies = m.getValue("bodies").jsonObject.toMutableMap()
            val entity = bodies.getValue("entityDef").jsonObject
            val props = entity.getValue("properties").jsonArray.toMutableList()
            props +=
                obj(
                    "key" to text("invented"),
                    "rule" to text("inventedProperty"),
                    "value" to obj("symbol" to text("unhandledValue")),
                )
            bodies["entityDef"] = JsonObject(entity + ("properties" to JsonArray(props)))
            shouldThrowAny { GrammarCoverage.check(JsonObject(m + ("bodies" to JsonObject(bodies)))) }
        }
        test("ordinary map order is canonical while ordered objects keep occurrences") {
            val a = CanonicalYaml.fromTtr("model er\ndef entity A { description: { cs: \"A\", en: \"B\" } }")
            val b = CanonicalYaml.fromTtr("model er\ndef entity A { description: { en: \"B\", cs: \"A\" } }")
            CanonicalYaml.write(a) shouldBe CanonicalYaml.write(b)
        }
        test("YAML 1.2 ambiguous strings remain strings and nonfinite numbers fail") {
            val doc =
                CanonicalYaml.read(
                    """
                    ttr: "0.21"
                    model: automation
                    defs:
                      - kind: program
                        id: Ship
                        properties: {note: yes, flag: on, count: 1.0}
                    """.trimIndent(),
                )
            val auto = doc.ast.definitions.single() as AutomationDef
            (auto.body.entries[0].value as AutoValue.Str).raw shouldBe "yes"
            (auto.body.entries[1].value as AutoValue.Str).raw shouldBe "on"
            (auto.body.entries[2].value as AutoValue.Num).text shouldBe "1.0"
            shouldThrowAny {
                CanonicalYaml.read(
                    "ttr: \"0.21\"\nmodel: automation\ndefs: [{kind: program, id: P, properties: {x: .inf}}]",
                    "inf.yaml",
                )
            }
        }

        test("automation numeric spellings include negative zero and leading zeros") {
            val original = CanonicalYaml.fromTtr("model automation\ndef program P { a: -0.0, b: 01, c: 1.00 }")
            val bytes = CanonicalYaml.write(original)
            val read = CanonicalYaml.read(bytes)
            val auto = read.ast.definitions.single() as AutomationDef
            auto.body.entries.map { (it.value as AutoValue.Num).text } shouldBe listOf("-0.0", "01", "1.00")
            CanonicalYaml.write(read) shouldBe bytes
        }
        test("ambiguous YAML mapping keys are quoted") {
            val original = CanonicalYaml.fromTtr("model automation\ndef program P { True: \"x\", Null: \"y\" }")
            CanonicalYaml.write(CanonicalYaml.read(CanonicalYaml.write(original))) shouldBe
                CanonicalYaml.write(original)
        }

        test("ordinary block and triple keys remain generic data") {
            val original =
                CanonicalYaml.fromTtr(
                    "model cn\ndef kind K { custom: { block: \"text\", triple: \"word\", q: \"other\" } }",
                )
            val restored = CanonicalYaml.read(CanonicalYaml.write(original))
            ConformanceDump.dumpTree(restored.ast) shouldBe ConformanceDump.dumpTree(original.ast)
        }
        test("TTR emission reports a YAML location for an unrepresentable triple literal") {
            val doc =
                CanonicalYaml.read(
                    """
                    ttr: "0.21"
                    model: cn
                    defs:
                      - kind: kind
                        id: K
                        properties:
                          definition:
                            block: 'contains <fence> literal'
                    """.trimIndent().replace("<fence>", "\"\"\""),
                    "triple.yaml",
                )
            val error = shouldThrowAny { CanonicalYaml.toTtr(doc) } as CanonicalYamlException
            error.line shouldBe 8
            error.file shouldBe "triple.yaml"
        }
    })
