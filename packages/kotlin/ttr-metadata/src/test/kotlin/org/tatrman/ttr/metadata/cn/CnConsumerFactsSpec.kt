// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.model.HopResult
import org.tatrman.ttr.metadata.model.MdErHop
import org.tatrman.ttr.metadata.source.CnBuilder
import org.tatrman.ttr.metadata.source.ExplicitMdErHop

/**
 * CN-P3 — what the consumers read off the typed tier that P2 did not (`resources/cn/consumer`): the **reverse hop**
 * (⚑CN-P8's default — the md elements whose hop lands on an er-grounded concept's landing, how `statementValidate`
 * finds a value kind's md face), and the two ⚑CN-P10 spellings (ruled 2026-10-22) — a `cn2er_quality`'s `unit:` and a
 * grounding period's stored upper bounds `start_hi:` / `at_hi:`.
 */
class CnConsumerFactsSpec :
    StringSpec({
        val project = CnFixtures.consumer()
        val cn = project.cn

        "the explicit hop answers in reverse: the md elements an md2er_cubelet maps onto an er element" {
            val hop =
                CnBuilder.project(project.snapshot.loadedFiles).let { (docs, resolver) ->
                    ExplicitMdErHop(docs, resolver)
                }
            hop.mdElementsOn("er.entity.channel_sale") shouldBe listOf("md.cubelet.channelSales")
            hop.mdElementsOn("er.entity.channel_sale.channel") shouldBe listOf("md.dimension.Channel.code")
            hop.mdElementsOn("er.entity.channel_sale.amount").shouldBeEmpty()
        }

        "a hop that cannot answer in reverse reaches nothing that way (the seam's default)" {
            val forwardOnly =
                object : MdErHop {
                    override fun erTargetOf(
                        mdElement: String,
                        bearerEntity: String?,
                    ): HopResult = HopResult.unmapped("none")
                }
            forwardOnly.mdElementsOn("er.entity.channel_sale").shouldBeEmpty()
        }

        "an er-grounded concept carries its reverse hop; a defined phase its bearer's; an md grounding none" {
            project.concept("Channel").reverseHop shouldBe listOf("md.dimension.Channel.code")
            project.concept("Sale").reverseHop shouldBe listOf("md.cubelet.channelSales")
            project.concept("WebSale").reverseHop shouldBe listOf("md.cubelet.channelSales")
            project.concept("amount").reverseHop.shouldBeEmpty()
            project.concept("Onset").reverseHop.shouldBeEmpty()
            project.concept("revenue").reverseHop.shouldBeEmpty()
        }

        "a cn2er_quality's unit: as written, a string or an id (⚑CN-P10)" {
            project.concept("amount").grounding!!.unit shouldBe "EUR"
            project.concept("weight").grounding!!.unit shouldBe "kg"
            project
                .concept("Sale")
                .grounding!!
                .unit
                .shouldBeNull()
        }

        "a grounding period's stored upper bounds start_hi: / at_hi: name er attributes (⚑CN-P10)" {
            project.concept("Sale").grounding!!.period!!.let {
                it.start shouldBe "er.entity.channel_sale.sold_at"
                it.startHi shouldBe "er.entity.channel_sale.sold_at_hi"
                it.atHi.shouldBeNull()
            }
            project
                .concept("Onset")
                .grounding!!
                .period!!
                .atHi shouldBe "er.entity.onset_record.onset_hi"
        }

        "a bound that does not resolve is absent and dangling, like every grounding ref" {
            project.concept("Return").grounding!!.let {
                it.period!!.startHi.shouldBeNull()
                it.dangling shouldContain "er.entity.channel_sale.nope"
            }
        }
    })
