// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader

/**
 * MD dot-path (S5C-B) — surface goldens for the MD renderers ([TtrRenderer] `renderMeasure` /
 * `renderCubelet` / `renderMd2dbCubelet`). The [RoundTripSpec] proves render∘parse is a fixed point;
 * these pin the exact canonical text so the materialize-generated `.ttrm` (S5C-B.2) is deterministic —
 * map-valued `attributes`/`measures` render in key order.
 */
class MdRenderSpec :
    StringSpec({

        fun render(src: String): String = TtrRenderer.render(TtrLoader.parseString(src).definitions).trim()

        "HP-P1: both explicit hop bindings round-trip" {
            for (src in listOf(
                "model binding\ndef md2er_dimension c { tags: [\"example\"], description: { en: \"x\", cs: \"y\" }, dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk, state: address.state } }",
                "model binding\ndef md2er_cubelet c { tags: [\"example\"], description: { en: \"x\", cs: \"y\" }, cubelet: md.sales, target: er.entity.sales, attributes: { Customer.code: id }, measures: { revenue: amount } }",
            )) {
                val once = render(src)
                TtrLoader.parseString(once).errors shouldBe emptyList()
                render(once) shouldBe once
            }
        }

        "R3: explicit map strings quote spaces, keywords and punctuation" {
            val src =
                """
                model binding
                def md2er_dimension c {
                    dimension: md.Customer, entity: er.entity.customer,
                    attributes: { code: "order id", state: "string", číslo: "true", note: "a-b" }
                }
                def md2er_cubelet c {
                    cubelet: md.sales, target: er.entity.sales, measures: { revenue: "order id" }
                }
                """.trimIndent()
            val once = render(src)
            TtrLoader.parseString(once).errors shouldBe emptyList()
            render(once) shouldBe once
            val dims =
                TtrLoader
                    .parseString(
                        once,
                    ).definitions
                    .filterIsInstance<org.tatrman.ttr.parser.model.Md2erDimensionDef>()
            dims.single().attributes shouldBe
                TtrLoader
                    .parseString(
                        src,
                    ).definitions
                    .filterIsInstance<org.tatrman.ttr.parser.model.Md2erDimensionDef>()
                    .single()
                    .attributes
        }

        "R3 R17: invalid value forms and physical props are deliberately omitted" {
            render(
                "def md2er_cubelet c { cubelet: md.sales, target: er.entity.sales, attributes: { code: 1 }, shape: wide, journaling: overwrite, allocation: equal }",
            ) shouldBe
                "def md2er_cubelet c { cubelet: md.sales, target: er.entity.sales }"
        }

        "a logical measure renders its domain / class / aggregation" {
            render("def measure net { class: additive, domain: md.Money, aggregation: sum }") shouldBe
                "def measure net { domain: md.Money, class: additive, aggregation: sum }"
        }

        "a logical cubelet renders grain + measure refs" {
            render("def cubelet plan { measures: [net], grain: [Customer.name, Time.month] }") shouldBe
                "def cubelet plan { grain: [Customer.name, Time.month], measures: [net] }"
        }

        "a wide binding renders target + wide shape + key-ordered attributes + allocation" {
            val out =
                render(
                    """
                    def md2db_cubelet sales_binding {
                        target: db.dbo.f_sales,
                        cubelet: md.sales,
                        shape: wide,
                        measures: { net: { column: net } },
                        attributes: { Time.day: { column: sale_date }, Customer.name: { column: customer_name } },
                        allocation: proportional
                    }
                    """.trimIndent(),
                )
            out shouldBe
                "def md2db_cubelet sales_binding { cubelet: md.sales, target: db.dbo.f_sales, shape: wide, " +
                "attributes: { Customer.name: { column: customer_name }, Time.day: { column: sale_date } }, " +
                "measures: { net: { column: net } }, allocation: proportional }"
        }

        "a long binding renders the long shape, code measure, invalidate journaling, per-dim allocation" {
            val out =
                render(
                    """
                    def md2db_cubelet plan_binding {
                        cubelet: md.plan,
                        target: db.dbo.f_plan,
                        shape: { long: { codeColumn: measure_code, valueColumn: amount } },
                        attributes: { Customer.name: { column: customer_name } },
                        measures: { net: { code: NET } },
                        journaling: { invalidate: { validColumn: is_current } },
                        allocation: { Time: equal }
                    }
                    """.trimIndent(),
                )
            out shouldBe
                "def md2db_cubelet plan_binding { cubelet: md.plan, target: db.dbo.f_plan, " +
                "shape: { long: { codeColumn: measure_code, valueColumn: amount } }, " +
                "attributes: { Customer.name: { column: customer_name } }, measures: { net: { code: NET } }, " +
                "journaling: { invalidate: { validColumn: is_current } }, allocation: { Time: equal } }"
        }
    })
