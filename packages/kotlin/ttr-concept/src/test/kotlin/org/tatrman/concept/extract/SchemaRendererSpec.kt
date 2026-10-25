// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * DX-P0 S0.3 T1–T3 — `Statement[review]` and `Record.review` rendered from the extraction slice world are byte-equal
 * (canonical JSON) to the goldens hand-written from the DX contracts §6; the type mapping's edges; the refusals.
 */
class SchemaRendererSpec :
    FunSpec({
        val world = ExtractWorlds.model()
        val renderer = SchemaRenderer(world)

        fun golden(name: String) = Files.readString(ExtractWorlds.dir.resolve("expected/$name"))

        test("Statement[review] is byte-equal to its golden") {
            SchemaJson.canonical(renderer.render("review", TypeKind.STATEMENT)) shouldBe
                golden("Statement.review.schema.json")
        }

        test("Record.review is byte-equal to its golden") {
            SchemaJson.canonical(renderer.render("review", TypeKind.RECORD)) shouldBe
                golden("Record.review.schema.json")
        }

        test("an entity is named bare, as an er ref, or package-qualified when it has a package") {
            SchemaJson.canonical(renderer.render("er.entity.review", TypeKind.STATEMENT)) shouldBe
                golden("Statement.review.schema.json")
            shouldThrow<SchemaRenderException> { renderer.render("hartland.review", TypeKind.STATEMENT) }.slug shouldBe
                SchemaRenderer.UNKNOWN_ENTITY // the slice world's files declare no package
        }

        test("modifiers come from the concept's qualities tagged \"modifier\" and leave the attributes") {
            val claim = renderer.render("claim", TypeKind.STATEMENT)["properties"]!!.jsonObject
            claim["modifiers"]!!.jsonObject["properties"]!!.jsonObject.keys shouldContainExactly setOf("certainty")
            claim["attributes"]!!.jsonObject["properties"]!!.jsonObject shouldNotContainKey "certainty"
            claim["concept"]!!.jsonObject["enum"] shouldBe
                JsonArray(listOf(JsonPrimitive("cn.Claim"), JsonPrimitive("cn.OpenClaim")))
        }

        test("a kind without modifier qualities renders an empty modifier group; keys never render") {
            val sheet = renderer.render("product_sheet", TypeKind.RECORD)["properties"]!!.jsonObject
            sheet["modifiers"]!!.jsonObject["properties"] shouldBe JsonObject(emptyMap())
            sheet["attributes"]!!.jsonObject["properties"]!!.jsonObject shouldNotContainKey "sheet_id"
            sheet["attributes"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf("item", "section", "spec_name", "spec_value") // `unit` is optional
        }

        test("an unknown entity is refused, naming it") {
            val e = shouldThrow<SchemaRenderException> { renderer.render("refund", TypeKind.STATEMENT) }
            e.slug shouldBe SchemaRenderer.UNKNOWN_ENTITY
            e.message shouldBe "unknown entity 'refund'"
        }

        test(
            "vocabularies: a large closed set and a searchable attribute become x-ttr-vocabulary; int codes stay ints",
        ) {
            val many = (1..201).joinToString(", ") { "s$it" }
            val m =
                ExtractWorlds.of(
                    mapOf(
                        "er.ttrm" to
                            """
                            model er
                            def entity ticket {
                                attributes: [
                                    def attribute id { type: int, isKey: true },
                                    def attribute stage { type: text, states: [$many] },
                                    def attribute topic { type: text, search { searchable method: TOKENS } },
                                    def attribute grade { type: int, valueLabels: { "1": { en: "low" }, "2": { en: "high" } } }
                                ]
                            }
                            """.trimIndent(),
                    ),
                )
            val props =
                SchemaRenderer(m)
                    .render("ticket", TypeKind.STATEMENT)["properties"]!!
                    .jsonObject["attributes"]!!
                    .jsonObject["properties"]!!
                    .jsonObject
            props["stage"]!!.jsonObject["x-ttr-vocabulary"] shouldBe JsonPrimitive("er.entity.ticket.stage")
            props["stage"]!!.jsonObject shouldNotContainKey "enum"
            props["topic"]!!.jsonObject["x-ttr-vocabulary"] shouldBe JsonPrimitive("er.entity.ticket.topic")
            // no concept lands on `ticket`: no concept enum, no modifier group
            SchemaRenderer(m).render("ticket", TypeKind.STATEMENT)["properties"]!!.jsonObject shouldNotContainKey
                "modifiers"
            props["grade"]!!.jsonObject["enum"] shouldBe JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(2L)))
        }

        test("a modifier landing on another entity is refused") {
            val m =
                ExtractWorlds.of(
                    mapOf(
                        "er.ttrm" to
                            """
                            model er
                            def entity note { attributes: [ def attribute id { type: int, isKey: true }, def attribute body { type: text } ] }
                            def entity flag { attributes: [ def attribute id { type: int, isKey: true }, def attribute certainty { type: text } ] }
                            """.trimIndent(),
                        "cn.ttrm" to
                            """
                            model cn
                            def kind Note { label: { en: "Note" } }
                            def quality certainty { of: Note, tags: ["modifier"] }
                            """.trimIndent(),
                        "bindings.ttrm" to
                            """
                            model binding
                            def cn2er_kind note_g { kind: cn.Note, target: er.entity.note }
                            def cn2er_quality certainty_g { quality: cn.certainty, target: er.entity.flag.certainty }
                            """.trimIndent(),
                    ),
                )
            shouldThrow<SchemaRenderException> { SchemaRenderer(m).render("note", TypeKind.STATEMENT) }.slug shouldBe
                SchemaRenderer.MODIFIER_OFF_ENTITY
        }

        test("two concepts grounded on one entity are refused") {
            val m =
                ExtractWorlds.of(
                    mapOf(
                        "er.ttrm" to
                            "model er\ndef entity note { attributes: [ def attribute id { type: int, isKey: true } ] }",
                        "cn.ttrm" to
                            "model cn\ndef kind Note { label: { en: \"Note\" } }\ndef kind Memo { label: { en: \"Memo\" } }",
                        "bindings.ttrm" to
                            "model binding\ndef cn2er_kind note_g { kind: cn.Note, target: er.entity.note }\n" +
                            "def cn2er_kind memo_g { kind: cn.Memo, target: er.entity.note }",
                    ),
                )
            shouldThrow<SchemaRenderException> { SchemaRenderer(m).render("note", TypeKind.STATEMENT) }.slug shouldBe
                SchemaRenderer.AMBIGUOUS_CONCEPT
        }
    })
