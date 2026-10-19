// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandJoinModel.er
import org.tatrman.translator.framework.ModelRelation

/** MJ-P3·S1 — codes, severities and the exact texts of contracts §4, plus the two-carrier reconciliation. */
class JoinerMessagesSpec :
    StringSpec({
        val shopperHousehold = HartlandJoinModel.relations.first { it.toEntity == er("household") }
        val calendarHops = HartlandJoinModel.relations.filter { it.toEntity == er("calendar") }

        "JOIN_NO_RELATION — cz wording verbatim, WARNING; a multi-entity side is rendered as the list" {
            val w = JoinerWarning.NoRelation(er("sale"), er("household"))
            JoinerMessages.code(w) shouldBe "JOIN_NO_RELATION"
            JoinerMessages.severity(w) shouldBe JoinerMessages.Severity.WARNING
            JoinerMessages.text(w) shouldBe
                "no declared relation between entity.sale and entity.household; Cartesian product preserved"
            val chain =
                JoinerWarning.NoRelation(
                    er("sale"),
                    er("calendar"),
                    leftEntities = listOf(er("sale"), er("shopper")),
                    rightEntities = listOf(er("calendar")),
                )
            JoinerMessages.text(chain) shouldBe
                "no declared relation between entity.sale, entity.shopper and entity.calendar; " +
                "Cartesian product preserved"
        }

        "JOIN_NO_RELATION — an empty side is a derived table (review-097 R2)" {
            val w =
                JoinerWarning.NoRelation(
                    er("sale"),
                    org.tatrman.plan.v1.QualifiedName
                        .getDefaultInstance(),
                    leftEntities = listOf(er("sale")),
                    rightEntities = emptyList(),
                )
            JoinerMessages.text(w) shouldBe
                "no declared relation between entity.sale and a derived table; Cartesian product preserved"
        }

        "JOIN_AMBIGUOUS_RELATIONS — lists the candidates as from → to (pairs); write the ON" {
            val w = JoinerWarning.AmbiguousRelations(er("shopper"), er("calendar"), calendarHops)
            JoinerMessages.code(w) shouldBe "JOIN_AMBIGUOUS_RELATIONS"
            JoinerMessages.severity(w) shouldBe JoinerMessages.Severity.WARNING
            JoinerMessages.text(w) shouldBe
                "2 relations could join entity.shopper and entity.calendar: " +
                "shopper → calendar (first_purchase_date = date_id), " +
                "shopper → calendar (first_shipment_date = date_id); write the ON"
        }

        "JOIN_AMBIGUOUS_RELATIONS with `repeated` — the appears-more-than-once text (⚑MJ-6)" {
            val w =
                JoinerWarning.AmbiguousRelations(
                    er("shopper"),
                    er("shopper"),
                    emptyList(),
                    repeated = setOf(er("shopper")),
                )
            JoinerMessages.text(w) shouldBe "entity entity.shopper appears more than once in the join; write the ON"
        }

        "JOIN_AMBIGUOUS_RELATIONS from the physical carrier (no relations, only FKs)" {
            val w = JoinerWarning.AmbiguousRelations(HartlandJoinModel.db("A"), HartlandJoinModel.db("C"), emptyList())
            JoinerMessages.text(w) shouldBe "more than one foreign key could join dbo.A and dbo.C; write the ON"
        }

        "JOIN_RELATION_WITHOUT_PAIRS — INFO" {
            val rel = ModelRelation(er("a"), er("b"), emptyList())
            val w = JoinerWarning.RelationWithoutJoinPairs(er("a"), er("b"), rel)
            JoinerMessages.code(w) shouldBe "JOIN_RELATION_WITHOUT_PAIRS"
            JoinerMessages.severity(w) shouldBe JoinerMessages.Severity.INFO
            JoinerMessages.text(w) shouldBe "relation a → b declares no join pairs; left to its foreign key"
        }

        "JOIN_KEY_NAME_COLLISION — wire carrier only, names the key and the side" {
            val w =
                JoinerWarning.KeyNameCollision(
                    er("shopper"),
                    er("household"),
                    shopperHousehold,
                    "current_household",
                    JoinerWarning.Side.LEFT,
                )
            JoinerMessages.code(w) shouldBe "JOIN_KEY_NAME_COLLISION"
            JoinerMessages.severity(w) shouldBe JoinerMessages.Severity.WARNING
            JoinerMessages.text(w) shouldBe
                "join key current_household exists on more than one entity of the left side; Cartesian product preserved — write the ON"
        }

        // ---- JoinerWarnings.merge (one verdict per join) ----

        val plan = PlanNode.getDefaultInstance()
        val noRel = JoinerWarning.NoRelation(er("a"), er("b"))
        val noFk = JoinerWarning.NoRelation(HartlandJoinModel.db("A"), HartlandJoinModel.db("B"))
        val withoutPairs =
            JoinerWarning.RelationWithoutJoinPairs(
                er("a"),
                er("b"),
                ModelRelation(er("a"), er("b"), emptyList()),
            )

        "merge: both carriers warned on the same join → the logical (entity-level) warning wins" {
            val logical = JoinerResult(plan, listOf(noRel), listOf(JoinOutcome(listOf(0), noRel)))
            val physical = JoinerResult(plan, listOf(noFk), listOf(JoinOutcome(listOf(0), noFk)))
            JoinerWarnings.merge(logical, physical) shouldBe listOf(noRel)
        }

        "merge: the physical carrier conditioned a join the logical one warned on → the stale warning is dropped" {
            val logical = JoinerResult(plan, listOf(noRel), listOf(JoinOutcome(listOf(0), noRel)))
            val physical = JoinerResult(plan, emptyList(), listOf(JoinOutcome(listOf(0), null)))
            JoinerWarnings.merge(logical, physical) shouldBe emptyList()
        }

        "merge: RelationWithoutJoinPairs stays when the FK filled the join, else only the physical NoRelation (R3)" {
            val logical = JoinerResult(plan, listOf(withoutPairs), listOf(JoinOutcome(listOf(0), withoutPairs)))
            JoinerWarnings.merge(
                logical,
                JoinerResult(plan, emptyList(), listOf(JoinOutcome(listOf(0), null))),
            ) shouldBe
                listOf(withoutPairs)
            JoinerWarnings.merge(
                logical,
                JoinerResult(plan, listOf(noFk), listOf(JoinOutcome(listOf(0), noFk))),
            ) shouldBe
                listOf(noFk)
        }

        "merge: joins only one carrier saw are reported as they are, logical first" {
            val logical = JoinerResult(plan, listOf(noRel), listOf(JoinOutcome(listOf(0, 0), noRel)))
            val physical = JoinerResult(plan, listOf(noFk), listOf(JoinOutcome(listOf(0, 1), noFk)))
            JoinerWarnings.merge(logical, physical) shouldBe listOf(noRel, noFk)
        }

        // ttr-core#204 — the physical carrier's "a side I cannot see a table through" (that side empty).
        val blind =
            JoinerWarning.NoRelation(
                HartlandJoinModel.db("A"),
                QualifiedName.getDefaultInstance(),
                listOf(HartlandJoinModel.db("A")),
                emptyList(),
            )

        "merge: left to its FK, and the physical carrier could not see into a side → the WARNING, not INFO" {
            val logical = JoinerResult(plan, listOf(withoutPairs), listOf(JoinOutcome(listOf(0), withoutPairs)))
            val physical = JoinerResult(plan, listOf(blind), listOf(JoinOutcome(listOf(0), blind)))
            JoinerWarnings.merge(logical, physical) shouldBe listOf(blind)
        }

        "merge: a blind side anywhere else is not reported (a pure-DB derived table, a logical warning)" {
            val physical = JoinerResult(plan, listOf(blind), listOf(JoinOutcome(listOf(0), blind)))
            JoinerWarnings.merge(JoinerResult(plan), physical) shouldBe emptyList()
            val logical = JoinerResult(plan, listOf(noRel), listOf(JoinOutcome(listOf(0), noRel)))
            JoinerWarnings.merge(logical, physical) shouldBe listOf(noRel)
        }
    })
