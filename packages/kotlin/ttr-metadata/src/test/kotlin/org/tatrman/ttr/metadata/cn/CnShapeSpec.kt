// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.CnLift
import org.tatrman.ttr.metadata.model.CnPeriod
import org.tatrman.ttr.metadata.model.CnRelation
import org.tatrman.ttr.metadata.model.CnRelationType
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.EndState
import org.tatrman.ttr.metadata.model.Extent
import org.tatrman.ttr.metadata.model.Grounding
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.SchemaContents
import org.tatrman.ttr.metadata.model.Via
import org.tatrman.ttr.metadata.model.VocabularyVersion
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.model.schemaCodeToToken
import org.tatrman.ttr.semantics.cn.GroundingOrigin
import java.time.Instant

/**
 * Grammar 0.17 — the typed conceptual tier's SHAPE (CN-P2 S2.5 T1; cn contracts §1.1–§1.3, §5): what every field
 * defaults to, the core / open split (CN-D7), the derived facts (the effective extent, the reverse index). The
 * builder over real models is `CnModelSpec`.
 */
class CnShapeSpec :
    StringSpec({
        fun cn(
            name: String,
            pkg: String = "hartland.stores",
        ) = QualifiedName(SchemaCode.CN, "concept", name, pkg)

        fun concept(
            name: String,
            stereotype: String,
            grounding: Grounding? = null,
            erTarget: String? = null,
            via: Via? = null,
            grade: GroundingGrade = GroundingGrade.UNGROUNDED,
        ) = Concept(
            internalId = "cn.concept:$name",
            qname = cn(name),
            stereotype = stereotype,
            grounding = grounding,
            erTarget = erTarget,
            via = via,
            grade = grade,
        )

        fun grounding(
            concept: String,
            bindingKind: String,
            layer: GroundingLayer,
            target: String?,
        ) = Grounding(
            internalId = "cn.binding:${concept}_g",
            qname = QualifiedName(SchemaCode.CN, bindingKind, "${concept}_g", "hartland.stores"),
            bindingKind = bindingKind,
            concept = cn(concept),
            layer = layer,
            origin = GroundingOrigin.Binding,
            target = target,
        )

        "the cn tier is a schema of its own, library-only like automation" {
            val schema: SchemaContents = CnSchema()
            schema.schemaCode shouldBe "cn"
            schema.objects().toList() shouldBe emptyList()
            parseSchemaCode("cn") shouldBe SchemaCode.CN
            schemaCodeToToken(SchemaCode.CN) shouldBe "cn"
            CnSchema().vocabularyVersions shouldBe emptyList() // Q15-5: present; set by the registering service
        }

        "two tiers merge as two snapshots' maps do — the later wins a qname, the version history appends (S4.2)" {
            val at = Instant.parse("2026-10-22T10:00:00Z")
            val v1 = VocabularyVersion("sukl", "2026.09", registeredAt = at)
            val v2 = VocabularyVersion("sukl", "2026.10", registeredAt = at.plusSeconds(60))
            val first =
                CnSchema(
                    concepts =
                        mapOf(
                            cn("Customer") to concept("Customer", "kind"),
                            cn("Item") to concept("Item", "kind"),
                        ),
                    vocabularyVersions = listOf(v1),
                )
            val second =
                CnSchema(
                    concepts = mapOf(cn("Customer") to concept("Customer", "role")),
                    vocabularyVersions = listOf(v1, v2),
                )

            val merged = first + second

            merged.concepts.mapValues { it.value.stereotype } shouldBe
                mapOf(cn("Customer") to "role", cn("Item") to "kind")
            merged.vocabularyVersions shouldContainExactly listOf(v1, v2)
        }

        "a concept defaults to ungrounded, core, with no landing" {
            val c = concept("Customer", "role")
            c.kind shouldBe "concept"
            c.ref shouldBe "cn.Customer"
            c.qname.dotted() shouldBe "cn.concept.Customer"
            c.grade shouldBe GroundingGrade.UNGROUNDED
            c.grounding.shouldBeNull()
            c.erTarget.shouldBeNull()
            c.via.shouldBeNull()
            c.openTier shouldBe false // CN-D7: every stereotype concept is core
            c.binding shouldBe Binding.BoundReal
            c.lexiconLinks shouldBe emptyList()
            c.definition.shouldBeNull()
        }

        "the open tier: relation types and relations: edges (CN-D7, contracts §1.3)" {
            val rt =
                CnRelationType(
                    internalId = "cn.relation_type:supplies",
                    qname = cn("supplies"),
                )
            rt.openTier shouldBe true
            rt.kind shouldBe "relation_type"
            CnRelation(
                typeName = "supplies",
                type = rt.qname,
                toName = "Warehouse",
                to = cn("Warehouse"),
            ).openTier shouldBe
                true
        }

        "an event's effective extent: as written, else from its period (AM-39)" {
            val at = concept("Sale", "event").copy(period = CnPeriod(at = "sold_date"))
            at.effectiveExtent shouldBe Extent.INSTANTANEOUS
            val span = concept("Order", "event").copy(period = CnPeriod(start = "placed", end = "delivered"))
            span.effectiveExtent shouldBe Extent.DURATIVE
            span.copy(extent = Extent.INSTANTANEOUS).effectiveExtent shouldBe Extent.INSTANTANEOUS
            concept("Outage", "event").effectiveExtent.shouldBeNull()
        }

        "a period's end state: present unless end_open says open or unknown (A-CN-19)" {
            CnPeriod(start = "a", end = "b", endState = EndState.PRESENT).endState shouldBe EndState.PRESENT
            CnPeriod(at = "x").endState.shouldBeNull()
        }

        "objects(): concepts, relation types and bindings, each by its qname" {
            val g = grounding("Party", "cn2er_kind", GroundingLayer.ER, "er.entity.party")
            val party = concept("Party", "kind", g, "er.entity.party", Via.DIRECT, GroundingGrade.ER_GROUNDED)
            val rt = CnRelationType(internalId = "cn.relation_type:supplies", qname = cn("supplies"))
            val schema =
                CnSchema(
                    concepts = mapOf(party.qname to party),
                    bindings = mapOf(g.qname to g),
                    relationTypes = mapOf(rt.qname to rt),
                )
            schema.objects().map { it.qname.dotted() }.toList() shouldContainExactly
                listOf("cn.concept.Party", "cn.concept.supplies", "cn.cn2er_kind.Party_g")
            schema.byName("Party") shouldBe party
            g.kind shouldBe "cn2er_kind"
        }

        "the reverse index: per md / er element, the concepts on it, stamped via; phases never (L17-2)" {
            val item =
                concept(
                    "Item",
                    "kind",
                    grounding("Item", "cn2md_kind", GroundingLayer.MD, "md.dimension.Product"),
                    erTarget = "er.entity.product",
                    via = Via.MD_HOP,
                    grade = GroundingGrade.MD_GROUNDED,
                )
            val brand =
                concept(
                    "Brand",
                    "kind",
                    grounding("Brand", "cn2md_kind", GroundingLayer.MD, "md.domain.Brand"),
                    via = Via.MD_HOP,
                    grade = GroundingGrade.MD_GROUNDED_HOP_MISSING,
                )
            val channel =
                concept(
                    "Channel",
                    "kind",
                    grounding("Channel", "cn2er_kind", GroundingLayer.ER, "er.entity.store.channel"),
                    erTarget = "er.entity.store.channel",
                    via = Via.DIRECT,
                    grade = GroundingGrade.ER_GROUNDED,
                )
            val placed =
                concept(
                    "PlacedOrder",
                    "phase",
                    grounding("PlacedOrder", "cn2er_phase", GroundingLayer.ER, "er.entity.placed_order"),
                    erTarget = "er.entity.placed_order",
                    via = Via.DIRECT,
                    grade = GroundingGrade.ER_GROUNDED,
                )
            val legacy =
                concept("Legacy", "kind", grounding("Legacy", "cn2db_concept", GroundingLayer.DB, "db.dbo.legacy"))
            val schema = CnSchema(concepts = listOf(item, brand, channel, placed, legacy).associateBy { it.qname })

            schema.reverseIndex.keys.toList() shouldContainExactly
                listOf("er.entity.product", "er.entity.store.channel", "md.dimension.Product", "md.domain.Brand")
            schema.liftsTo("md.dimension.Product") shouldBe listOf(CnLift(item.qname, "kind", Via.MD_HOP))
            schema.liftsTo("er.entity.product") shouldBe listOf(CnLift(item.qname, "kind", Via.MD_HOP))
            // a value kind lands on its attribute, never on the entity around it
            schema.liftsTo("er.entity.store.channel") shouldBe listOf(CnLift(channel.qname, "kind", Via.DIRECT))
            schema.liftsTo("er.entity.store") shouldBe emptyList()
            // a phase is never in it, a db grounding reaches no consumer
            schema.liftsTo("er.entity.placed_order") shouldBe emptyList()
            schema.liftsTo("db.dbo.legacy") shouldBe emptyList()
            CnLift(item.qname, "kind", Via.MD_HOP).ref shouldBe "cn.Item"
        }
    })
