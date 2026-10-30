// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.ttr.metadata.model.ErSchema
import java.nio.file.Files

/**
 * DX-P0 S0.3 T1–T3 — `Statement[review]` and `Record.review` rendered from the extraction slice world are byte-equal
 * (canonical JSON) to the goldens hand-written from the DX contracts §6; the type mapping's edges; the refusals. And
 * review-158's fixes: decimals carry `x-ttr-scale`, never `multipleOf` (F7); an fk-only relation's attribute is a `Ref`
 * (F6, ⚑R158-C); a name two entities share is refused (F9); an ancestor's modifier off the entity is skipped (F10).
 */
class SchemaRendererSpec :
    FunSpec({
        val world = ExtractWorlds.model()
        val renderer = SchemaRenderer(world)

        fun golden(name: String) = Files.readString(ExtractWorlds.dir.resolve("expected/$name"))

        fun attributes(
            m: ResolvedCnModel,
            entity: String,
            kind: TypeKind = TypeKind.STATEMENT,
        ): JsonObject =
            SchemaRenderer(m)
                .render(entity, kind)["properties"]!!
                .jsonObject["attributes"]!!
                .jsonObject["properties"]!!
                .jsonObject

        fun modifiers(
            m: ResolvedCnModel,
            entity: String,
        ): Set<String> =
            SchemaRenderer(m)
                .render(entity, TypeKind.STATEMENT)["properties"]!!
                .jsonObject["modifiers"]!!
                .jsonObject["properties"]!!
                .jsonObject.keys

        // review-158 F6 — `review` → `item` with db tables, the fk and the er2db bindings; only the relation's own
        // binding varies (`item` binds to the `item_sk` column, so the fk is followed through the binding).
        val itemReviewEr =
            """
            model er
            def entity item { attributes: [ def attribute sk { type: int, isKey: true }, def attribute product_name { type: text } ] }
            def entity review { attributes: [ def attribute review_id { type: int, isKey: true }, def attribute item { type: int }, def attribute body { type: text } ] }
            """.trimIndent()
        val join = "join: [{ from: er.entity.review.item, to: er.entity.item.sk }]"
        val fk = "binding: { fk: db.dbo.fk_review_item }"

        fun reviewWorld(relation: String): ResolvedCnModel =
            ExtractWorlds.of(
                mapOf(
                    "er.ttrm" to
                        "$itemReviewEr\ndef relation rel_review_item { from: er.entity.review, to: er.entity.item, $relation }\n",
                    "db.ttrm" to
                        """
                        model db schema dbo
                        def table item { columns: [ def column sk { type: int }, def column product_name { type: text } ] }
                        def table review { columns: [ def column review_id { type: int }, def column item_sk { type: int }, def column body { type: text } ] }
                        def fk fk_review_item { from: [db.dbo.review.item_sk], to: [db.dbo.item.sk] }
                        """.trimIndent(),
                    "binding.ttrm" to
                        """
                        model binding
                        def er2db_entity review_g { entity: er.entity.review, target: { table: db.dbo.review } }
                        def er2db_attribute review_item_g { attribute: er.entity.review.item, target: { column: db.dbo.review.item_sk } }
                        """.trimIndent(),
                ),
            )

        fun canonical(
            m: ResolvedCnModel,
            entity: String,
        ): List<String> = TypeKind.entries.map { SchemaJson.canonical(SchemaRenderer(m).render(entity, it)) }

        val itemRef =
            JsonObject(
                mapOf(
                    "\$ref" to JsonPrimitive("#/\$defs/Ref"),
                    "x-ttr-attribute" to JsonPrimitive("er.entity.review.item"),
                    "x-ttr-references" to JsonPrimitive("er.entity.item"),
                ),
            )

        test("Statement[review] is byte-equal to its golden") {
            SchemaJson.canonical(renderer.render("review", TypeKind.STATEMENT)) shouldBe
                golden("Statement.review.schema.json")
        }

        test("Record.review is byte-equal to its golden") {
            SchemaJson.canonical(renderer.render("review", TypeKind.RECORD)) shouldBe
                golden("Record.review.schema.json")
        }

        test("an entity is named bare or as an er ref; a qualifier that is not its package names nothing") {
            SchemaJson.canonical(renderer.render("er.entity.review", TypeKind.STATEMENT)) shouldBe
                golden("Statement.review.schema.json")
            shouldThrow<SchemaRenderException> { renderer.render("hartland.review", TypeKind.STATEMENT) }.slug shouldBe
                SchemaRenderer.UNKNOWN_ENTITY // the slice world's files declare no package
        }

        test("F9: a name two packages' entities share is refused, even package-qualified — the identities are bare") {
            // The file loader stamps no package on an er qname (two packages' `review` load as one), so the two are
            // built here: the slice world's `review` twice, in packages `a` and `b`.
            val er = world.model.schemas["er"] as ErSchema
            val review = er.entities.values.single { it.qname.name == "review" }
            val twins = listOf("a", "b").map { review.copy(qname = review.qname.copy(`package` = it)) }
            val entities = er.entities.filterKeys { it.name != "review" } + twins.associateBy { it.qname }
            val m =
                ResolvedCnModel.of(
                    world.model.copy(
                        schemas =
                            world.model.schemas + ("er" to er.copy(entities = entities)),
                    ),
                )
            for (ref in listOf("a.review", "b.review", "review", "er.entity.review")) {
                val e = shouldThrow<SchemaRenderException> { SchemaRenderer(m).entity(ref) }
                e.slug shouldBe SchemaRenderer.AMBIGUOUS_ENTITY
                e.message!! shouldContain "identities are bare"
            }
            SchemaRenderer(m).entity("claim").qname.name shouldBe "claim" // an unshared name still renders
        }

        test("F7: a decimal carries its scale as x-ttr-scale, never multipleOf") {
            val price = attributes(world, "review")["price_paid"]!!.jsonObject // decimal(10, 2)
            price shouldNotContainKey "multipleOf"
            price["x-ttr-scale"] shouldBe JsonPrimitive(2)
            price["type"] shouldBe JsonPrimitive("number")
        }

        test("F7: a JSON Schema validator accepts price_paid 19.99 against the rendered Record.review") {
            val schema =
                JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(SchemaJson.canonical(renderer.render("review", TypeKind.RECORD)))
            val mapper = ObjectMapper()

            fun record(pricePaid: String) =
                """
                {
                  "attributes": {
                    "item": { "qname": "AAAAAAAABAAAAAAA", "display": "Trailhead 2 jacket" },
                    "rating": 4, "body": "Warm and light.", "sentiment": "positive", "price_paid": $pricePaid,
                    "reviewed_on": "2026-10-01", "recorded_at": "2026-10-02T09:30:00Z"
                  },
                  "modifiers": { "verification": "verified_purchase", "temporal_context": "current" },
                  "evidence": [ { "annotationId": 7, "role": "creates" } ],
                  "consolidationRun": "run-1"
                }
                """.trimIndent()
            schema.validate(mapper.readTree(record("19.99"))).shouldBeEmpty()
            schema.validate(mapper.readTree(record("\"19.99\""))).shouldNotBeEmpty() // the validator does run
        }

        test("F7: no rendered schema of the slice world contains an exponent numeral") {
            val exponent = Regex("[0-9][eE][+-]?[0-9]")
            for (entity in listOf("review", "claim", "product_sheet", "item", "customer")) {
                for (text in canonical(world, entity)) exponent.containsMatchIn(text) shouldBe false
            }
        }

        test("F6: an fk-only relation makes its fk attribute a Ref, byte-equal to the join: rendering") {
            val fkOnly = reviewWorld(fk)
            attributes(fkOnly, "review")["item"] shouldBe itemRef
            attributes(fkOnly, "review", TypeKind.RECORD)["item"] shouldBe itemRef
            canonical(fkOnly, "review") shouldBe canonical(reviewWorld(join), "review")
        }

        test("F6: a two-column fk renders plain") {
            val m =
                ExtractWorlds.of(
                    mapOf(
                        "er.ttrm" to
                            """
                            model er
                            def entity item { attributes: [ def attribute sk { type: int, isKey: true }, def attribute variant { type: int, isKey: true } ] }
                            def entity review { attributes: [ def attribute review_id { type: int, isKey: true }, def attribute item { type: int }, def attribute variant { type: int } ] }
                            def relation rel_review_item { from: er.entity.review, to: er.entity.item, binding: { fk: db.dbo.fk_review_item } }
                            """.trimIndent(),
                        "db.ttrm" to
                            """
                            model db schema dbo
                            def table item { columns: [ def column sk { type: int }, def column variant { type: int } ] }
                            def table review { columns: [ def column review_id { type: int }, def column item { type: int }, def column variant { type: int } ] }
                            def fk fk_review_item { from: [db.dbo.review.item, db.dbo.review.variant], to: [db.dbo.item.sk, db.dbo.item.variant] }
                            """.trimIndent(),
                    ),
                )
            val props = attributes(m, "review")
            props["item"]!!.jsonObject["type"] shouldBe JsonPrimitive("integer")
            props["item"]!!.jsonObject shouldNotContainKey "\$ref"
            props["variant"]!!.jsonObject shouldNotContainKey "\$ref"
            SchemaRenderer(m).render("review", TypeKind.STATEMENT)["\$defs"]!!.jsonObject shouldNotContainKey "Ref"
        }

        test("F6: a relation with both join: and fk: renders byte-equal to the join:-only one") {
            canonical(reviewWorld("$join, $fk"), "review") shouldBe canonical(reviewWorld(join), "review")
        }

        test("F6: an attribute with no er2db binding binds to the column of its own name (TTR-M's convention)") {
            val m =
                ExtractWorlds.of(
                    mapOf(
                        "er.ttrm" to
                            "$itemReviewEr\ndef relation rel_review_item { from: er.entity.review, to: er.entity.item, $fk }\n",
                        "db.ttrm" to
                            """
                            model db schema dbo
                            def table item { columns: [ def column sk { type: int }, def column product_name { type: text } ] }
                            def table review { columns: [ def column review_id { type: int }, def column item { type: int }, def column body { type: text } ] }
                            def fk fk_review_item { from: [db.dbo.review.item], to: [db.dbo.item.sk] }
                            """.trimIndent(),
                    ),
                )
            attributes(m, "review")["item"] shouldBe itemRef
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

        // DX PX T2b — the record ETL's own columns (tagged `provenance`) are queryable on the entity, never extracted.
        test("an attribute tagged provenance is left out of both kinds, as a key is") {
            val m =
                ExtractWorlds.of(
                    mapOf(
                        "er.ttrm" to
                            """
                            model er
                            def entity review {
                                attributes: [
                                    def attribute review_id { type: int, isKey: true },
                                    def attribute body { type: text },
                                    def attribute annotation_refs { type: text, tags: ["provenance"] },
                                    def attribute consolidation_run { type: text, optional: true, tags: ["provenance"] },
                                    def attribute rating { type: int, optional: true, tags: ["measured"] }
                                ]
                            }
                            """.trimIndent(),
                    ),
                )
            for (kind in TypeKind.entries) {
                attributes(m, "review", kind).keys shouldContainExactly setOf("body", "rating")
            }
            SchemaRenderer(m)
                .render("review", TypeKind.RECORD)["properties"]!!
                .jsonObject["attributes"]!!
                .jsonObject["required"]!!
                .jsonArray
                .map { it.jsonPrimitive.content } shouldBe listOf("body")
        }

        test("a concept's own modifier landing on another entity is refused") {
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

        // review-158 F10 — role `Reviewer of: Customer` on `reviewer`; `Customer`'s modifier `vip_status` lands on
        // `customer`, where it qualifies customers, not a reviewer's statements.
        val reviewerEr =
            """
            model er
            def entity customer { attributes: [ def attribute sk { type: int, isKey: true }, def attribute name { type: text }, def attribute vip_status { type: text, states: [gold, silver, none] } ] }
            def entity reviewer { attributes: [ def attribute sk { type: int, isKey: true }, def attribute nickname { type: text }, def attribute trust { type: text, states: [high, low] }, def attribute badge { type: text } ] }
            """.trimIndent()

        fun reviewerWorld(
            cn: String,
            bindings: String,
        ): ResolvedCnModel =
            ExtractWorlds.of(
                mapOf(
                    "er.ttrm" to reviewerEr,
                    "cn.ttrm" to "model cn\n$cn",
                    "bindings.ttrm" to "model binding\n$bindings",
                ),
            )

        val reviewerCn =
            """
            def kind Customer { label: { en: "Customer" } }
            def role Reviewer { of: Customer, event: Review, label: { en: "Reviewer" } }
            def event Review { label: { en: "Review" }, involves: [Reviewer] }
            def quality vip_status { of: Customer, tags: ["modifier"] }
            """.trimIndent()
        val reviewerBindings =
            """
            def cn2er_kind customer_g { kind: cn.Customer, target: er.entity.customer }
            def cn2er_role reviewer_g { role: cn.Reviewer, target: er.entity.reviewer }
            def cn2er_quality vip_status_g { quality: cn.vip_status, target: er.entity.customer.vip_status }
            """.trimIndent()

        test("F10: an ancestor's modifier landing on the ancestor's entity is skipped, not refused") {
            val m = reviewerWorld(reviewerCn, reviewerBindings)
            modifiers(m, "reviewer") shouldBe emptySet()
            attributes(m, "reviewer") shouldNotContainKey "vip_status"
            modifiers(m, "customer") shouldBe setOf("vip_status") // still the customer's own modifier
        }

        test("F10: the concept's own modifier off its entity still refuses, under a chain") {
            val m =
                reviewerWorld(
                    "$reviewerCn\ndef quality badge { of: Reviewer, tags: [\"modifier\"] }",
                    "$reviewerBindings\ndef cn2er_quality badge_g { quality: cn.badge, target: er.entity.customer.name }",
                )
            shouldThrow<SchemaRenderException> {
                SchemaRenderer(
                    m,
                ).render("reviewer", TypeKind.STATEMENT)
            }.slug shouldBe
                SchemaRenderer.MODIFIER_OFF_ENTITY
        }

        test("F10: an inherited modifier landing on the rendered entity is kept") {
            val m =
                reviewerWorld(
                    """
                    def kind Party { label: { en: "Party" } }
                    def role Reviewer { of: Party, event: Review, label: { en: "Reviewer" } }
                    def event Review { label: { en: "Review" }, involves: [Reviewer] }
                    def quality trust { of: Party, tags: ["modifier"] }
                    """.trimIndent(),
                    """
                    def cn2er_role reviewer_g { role: cn.Reviewer, target: er.entity.reviewer }
                    def cn2er_quality trust_g { quality: cn.trust, target: er.entity.reviewer.trust }
                    """.trimIndent(),
                )
            modifiers(m, "reviewer") shouldBe setOf("trust")
            attributes(m, "reviewer") shouldNotContainKey "trust"
        }
    })
