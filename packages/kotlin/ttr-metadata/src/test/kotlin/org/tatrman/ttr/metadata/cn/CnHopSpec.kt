// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.HopResult
import org.tatrman.ttr.metadata.model.HopVia
import org.tatrman.ttr.metadata.model.MdErHop
import org.tatrman.ttr.metadata.source.CnBuilder
import org.tatrman.ttr.metadata.source.ExplicitMdErHop

/**
 * Grammar 0.17 — the md → er hop seam (CN-P2 S2.5 T3; cn contracts §8.1, the md/hop demand §3). P2 fills it with the
 * explicit `md2er_cubelet` only: a cubelet lands on the binding's entity, an md attribute the binding maps on that
 * entity's attribute; every other md element answers `md/er-unmapped`. The md/hop sub-arc plugs its derivation in.
 */
class CnHopSpec :
    StringSpec({
        val project = CnFixtures.hop()
        val hop =
            CnBuilder.project(project.snapshot.loadedFiles).let { (docs, resolver) -> ExplicitMdErHop(docs, resolver) }

        "a cubelet with an md2er_cubelet hops to the binding's entity" {
            hop.erTargetOf("md.cubelet.web_sales") shouldBe HopResult("er.entity.web_sale", HopVia.EXPLICIT)
            hop.erTargetOf("md.cubelet.web_returns") shouldBe HopResult("er.entity.web_return", HopVia.EXPLICIT)
        }

        "an md attribute the binding maps hops to the entity's attribute" {
            hop.erTargetOf("md.dimension.Product.sku") shouldBe
                HopResult("er.entity.web_sale.product_sku", HopVia.EXPLICIT)
        }

        "an attribute two cubelets map: ambiguous, unless the bearer's entity picks one (AM-29)" {
            hop.erTargetOf("md.dimension.Shopper.code").let {
                it.erTarget.shouldBeNull()
                it.diagnostic shouldBe HopResult.AMBIGUOUS
                it.message shouldContain "er.entity.web_sale.shopper_id"
                it.message shouldContain "er.entity.web_return.shopper_id"
            }
            hop.erTargetOf("md.dimension.Shopper.code", bearerEntity = "er.entity.web_return") shouldBe
                HopResult("er.entity.web_return.shopper_id", HopVia.EXPLICIT)
        }

        "a dimension with no md2er does not hop: md/er-unmapped" {
            hop.erTargetOf("md.dimension.Shopper").let {
                it.erTarget.shouldBeNull()
                it.via.shouldBeNull()
                it.diagnostic shouldBe HopResult.UNMAPPED
            }
        }

        "a measure, and an md attribute no binding maps, do not hop in P2" {
            hop.erTargetOf("md.measure.net_sales").diagnostic shouldBe HopResult.UNMAPPED
            hop.erTargetOf("md.dimension.Product.color").diagnostic shouldBe HopResult.UNMAPPED
        }

        "the builder keeps the hop's answer on an md-grounded concept — the hygiene report's hop column (S2.7)" {
            val cn = project.cn
            cn.byName("WebSale")!!.hop shouldBe HopResult("er.entity.web_sale", HopVia.EXPLICIT)
            cn.byName("Item")!!.hop!!.diagnostic shouldBe HopResult.UNMAPPED
            // a defined phase inherits its bearer's landing, not its hop; an er grounding has none
            cn.byName("BigWebSale")!!.hop.shouldBeNull()
        }

        "the seam is pluggable: the builder reads whatever hop it is given" {
            val derived =
                object : MdErHop {
                    override fun erTargetOf(
                        mdElement: String,
                        bearerEntity: String?,
                    ): HopResult =
                        if (mdElement == "md.dimension.Shopper") {
                            HopResult("er.entity.shopper", HopVia.DERIVED)
                        } else {
                            HopResult.unmapped("none")
                        }
                }
            val cn = CnBuilder.build(project.snapshot.loadedFiles) { _, _ -> derived }.schema
            cn.byName("Shopper")!!.let {
                it.erTarget shouldBe "er.entity.shopper"
                it.grade shouldBe GroundingGrade.MD_GROUNDED
            }
            cn.byName("WebSale")!!.grade shouldBe GroundingGrade.MD_GROUNDED_HOP_MISSING
        }
    })
