// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.translator.framework.DfpJoinModel
import org.tatrman.translator.framework.DfpJoinModel.er
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.joiner.JoinPolicy.SideRef
import org.tatrman.translator.joiner.JoinPolicy.Verdict

/** MJ-P0·S2 — the shared join policy over the DFP-shaped fixture (contracts §2–§3). */
class JoinPolicySpec :
    StringSpec({
        val model = DfpJoinModel.handle()

        fun side(vararg names: String): List<SideRef<String>> = names.map { SideRef(er(it), it) }

        "chain: (kp, dm) × z resolves through dodací_místo → zákazník, not kumulovaný_prodej" {
            val v =
                JoinPolicy
                    .resolve(model, side("kumulovaný_prodej", "dodací_místo"), side("zákazník"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.entity shouldBe er("dodací_místo")
            v.right.entity shouldBe er("zákazník")
            v.pairs shouldContainExactly listOf("id_subjektu" to "id_zákazníka")
        }

        "chain: (kp, dm, z) × produkt resolves through kumulovaný_prodej → produkt" {
            val v =
                JoinPolicy
                    .resolve(model, side("kumulovaný_prodej", "dodací_místo", "zákazník"), side("produkt"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.entity shouldBe er("kumulovaný_prodej")
            v.pairs shouldContainExactly listOf("id_produktu" to "id_produktu")
        }

        "orientation: the `from` entity on the RIGHT swaps the pair" {
            val v =
                JoinPolicy
                    .resolve(model, side("zákazník"), side("dodací_místo"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.pairs shouldContainExactly listOf("id_zákazníka" to "id_subjektu")
        }

        "no relation: kp × zákazník (no path inference in v1)" {
            val v =
                JoinPolicy
                    .resolve(model, side("kumulovaný_prodej"), side("zákazník"))
                    .shouldBeInstanceOf<Verdict.NoRelation<String>>()
            v.left shouldContainExactly listOf(er("kumulovaný_prodej"))
            v.right shouldContainExactly listOf(er("zákazník"))
        }

        "ambiguous: dm × uživatel has two relations (OZ + VOT), nothing repeated" {
            val v =
                JoinPolicy
                    .resolve(model, side("dodací_místo"), side("uživatel"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.candidates shouldHaveSize 2
            v.repeated.shouldBeEmpty()
        }

        "ambiguous: (kp, dm) × obchodní_kanál — both sides relate, no proximity tie-break (⚑MJ-8)" {
            val v =
                JoinPolicy
                    .resolve(model, side("kumulovaný_prodej", "dodací_místo"), side("obchodní_kanál"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.candidates.map { it.left.entity.name }.toSet() shouldBe setOf("kumulovaný_prodej", "dodací_místo")
            v.repeated.shouldBeEmpty()
        }

        "repeated entity: zákazník × zákazník steps aside (⚑MJ-6)" {
            val v =
                JoinPolicy
                    .resolve(model, side("zákazník"), side("zákazník"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.repeated shouldBe setOf(er("zákazník"))
        }

        "repeated entity on one side drops only its candidates" {
            // (dm, dm) × zákazník — the two dm aliases could each join z; refuse to guess.
            val v =
                JoinPolicy
                    .resolve(model, side("dodací_místo", "dodací_místo"), side("zákazník"))
                    .shouldBeInstanceOf<Verdict.Ambiguous<String>>()
            v.repeated shouldBe setOf(er("dodací_místo"))
        }

        "composite relation: dodatek × smlouva carries both pairs, oriented (⚑MJ-7)" {
            val v =
                JoinPolicy
                    .resolve(model, side("smlouva"), side("dodatek"))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.pairs shouldContainExactly listOf("číslo_smlouvy" to "číslo_smlouvy", "rok" to "rok")
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
                    .resolve(model, listOf(SideRef(er("kumulovaný_prodej"), "kp")), listOf(SideRef(er("produkt"), "p")))
                    .shouldBeInstanceOf<Verdict.Resolved<String>>()
            v.left.handle shouldBe "kp"
            v.right.handle shouldBe "p"
        }
    })
