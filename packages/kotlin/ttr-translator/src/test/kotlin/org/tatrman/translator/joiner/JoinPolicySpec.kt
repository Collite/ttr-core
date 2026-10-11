// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.HartlandJoinModel.er
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.joiner.JoinPolicy.SideRef
import org.tatrman.translator.joiner.JoinPolicy.Verdict

/** MJ-P0·S2 — the shared join policy over the Hartland join fixture (contracts §2–§3). */
class JoinPolicySpec :
    StringSpec({
        val model = HartlandJoinModel.handle()

        fun side(vararg names: String): List<SideRef<String>> = names.map { SideRef(er(it), it) }

        "chain: (s, sh) × h resolves through shopper → household, not sale" {
            val v =
                JoinPolicy
                    .resolve(model, side("sale", "shopper"), side("household"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.entity shouldBe er("shopper")
            v.right.entity shouldBe er("household")
            v.pairs shouldContainExactly listOf("current_household" to "household_id")
        }

        "chain: (s, sh, h) × product resolves through sale → product" {
            val v =
                JoinPolicy
                    .resolve(model, side("sale", "shopper", "household"), side("product"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.entity shouldBe er("sale")
            v.pairs shouldContainExactly listOf("product_id" to "product_id")
        }

        "orientation: the `from` entity on the RIGHT swaps the pair" {
            val v =
                JoinPolicy
                    .resolve(model, side("household"), side("shopper"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.pairs shouldContainExactly listOf("household_id" to "current_household")
        }

        "no relation: s × household (no path inference in v1)" {
            val v =
                JoinPolicy
                    .resolve(model, side("sale"), side("household"))
                    .shouldBeInstanceOf<Verdict.NoRelation<String>>()
            v.left shouldContainExactly listOf(er("sale"))
            v.right shouldContainExactly listOf(er("household"))
        }

        "ambiguous: sh × calendar has two relations (first purchase + first shipment), nothing repeated" {
            val v =
                JoinPolicy
                    .resolve(model, side("shopper"), side("calendar"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.candidates shouldHaveSize 2
            v.repeated.shouldBeEmpty()
        }

        "ambiguous: (s, sh) × address — both sides relate, no proximity tie-break (⚑MJ-8)" {
            val v =
                JoinPolicy
                    .resolve(model, side("sale", "shopper"), side("address"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.candidates.map { it.left.entity.name }.toSet() shouldBe setOf("sale", "shopper")
            v.repeated.shouldBeEmpty()
        }

        "repeated entity: shopper × shopper steps aside (⚑MJ-6)" {
            val v =
                JoinPolicy
                    .resolve(model, side("shopper"), side("shopper"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.repeated shouldBe setOf(er("shopper"))
        }

        "repeated entity on one side drops only its candidates" {
            // (sh, sh) × household — the two sh aliases could each join h; refuse to guess.
            val v =
                JoinPolicy
                    .resolve(model, side("shopper", "shopper"), side("household"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.repeated shouldBe setOf(er("shopper"))
        }

        "C-1: a repeated entity no candidate names does not block the join (review-097 R1)" {
            // (sh, calendar c1, calendar c2) × s — the two calendar aliases got their ONs; s ↔ sh is unique.
            val v =
                JoinPolicy
                    .resolve(model, side("shopper", "calendar", "calendar"), side("sale"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.entity shouldBe er("shopper")
            v.right.entity shouldBe er("sale")
            v.pairs shouldContainExactly listOf("shopper_id" to "shopper_id")
        }

        "C-1: a repeated entity that IS party to a candidate still steps aside" {
            // (sh, calendar, calendar) × address — sh ↔ address is the only candidate and calendar names none,
            // so it resolves; (calendar, calendar) × sh — every candidate names calendar → ambiguous, repeated.
            JoinPolicy
                .resolve(model, side("shopper", "calendar", "calendar"), side("address"))
                .shouldBeInstanceOf<Verdict.Resolved<String>>()
            val v =
                JoinPolicy
                    .resolve(model, side("calendar", "calendar"), side("shopper"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.repeated shouldBe setOf(er("calendar"))
        }

        "C-1: a repeat within one side and no candidate → NoRelation; across the join → still a self-join" {
            JoinPolicy
                .resolve(model, side("calendar", "calendar"), side("product"))
                .shouldBeInstanceOf<Verdict.NoRelation<String>>()
            JoinPolicy
                .resolve(model, side("product"), side("product"))
                .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
                .repeated shouldBe setOf(er("product"))
        }

        "composite relation: order_return × order_line carries both pairs, oriented (⚑MJ-7)" {
            val v =
                JoinPolicy
                    .resolve(model, side("order_line"), side("order_return"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.pairs shouldContainExactly listOf("order_number" to "order_number", "product_id" to "product_id")
        }

        "relation without join pairs → WithoutPairs, never a crash" {
            val fkOnly = ModelRelation(fromEntity = er("a"), toEntity = er("b"), joinPairs = emptyList())
            val m =
                org.tatrman.translator.framework.InMemoryModelHandle(
                    tables = emptyList(),
                    relations = listOf(fkOnly),
                )
            JoinPolicy
                .resolve(m, side("a"), side("b"))
                .shouldBeInstanceOf<Verdict.WithoutPairs<String>>()
                .relation shouldBe fkOnly
        }

        "handles ride through untouched (the carrier's alias / scan)" {
            val v =
                JoinPolicy
                    .resolve(model, listOf(SideRef(er("sale"), "s")), listOf(SideRef(er("product"), "p")))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.handle shouldBe "s"
            v.right.handle shouldBe "p"
        }
    })
