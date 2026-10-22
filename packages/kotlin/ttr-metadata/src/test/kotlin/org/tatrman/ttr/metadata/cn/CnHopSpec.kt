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
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolTable
import org.tatrman.ttr.semantics.Resolver
import java.nio.file.Files

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

        fun explicitSources(sources: Map<String, String>): ExplicitMdErHop {
            val table = SymbolTable()
            val docs =
                sources.map { (uri, src) ->
                    val parsed = TtrLoader.parseString(src, uri)
                    parsed.errors shouldBe emptyList()
                    val schema = parsed.modelDirective?.modelCode.orEmpty()
                    val namespace = parsed.modelDirective?.schema.orEmpty()
                    val pkg = parsed.packageName.orEmpty()
                    table.upsertDocument(uri, parsed.definitions, schema, namespace, pkg)
                    SemanticDocument(uri, parsed.definitions, schema, namespace, pkg, parsed.imports)
                }
            return ExplicitMdErHop(docs, Resolver(table))
        }

        "HP-P1: explicit dimensions, one-hop attributes and structural measures" {
            val sources =
                mapOf(
                    "md.ttrm" to
                        "model md\ndef domain Code { type: string }\ndef domain Customer { type: string }\ndef dimension Customer { key: code, attributes: [def attribute code { domain: md.Code }, def attribute state { domain: md.Code }] }\ndef measure revenue { domain: md.Code, aggregation: sum }\ndef cubelet revenue { measures: [revenue] }",
                    "er.ttrm" to
                        "model er\ndef entity customer { attributes: [def attribute sk { type: int }, def attribute amount { type: decimal }] }\ndef entity addr { attributes: [def attribute state { type: text }] }\ndef relation address { from: er.entity.customer, to: er.entity.addr, cardinality: { from: \"N\", to: \"1\" } }",
                    "binding.ttrm" to
                        "model binding\ndef md2er_dimension c { dimension: md.Customer, entity: er.entity.customer, attributes: { code: sk, state: address.state } }\ndef md2er_cubelet m { cubelet: md.revenue, target: er.entity.customer, measures: { revenue: amount } }",
                )
            val explicit = explicitSources(sources)
            explicit.erTargetOf("md.dimension.Customer") shouldBe HopResult("er.entity.customer", HopVia.EXPLICIT)
            explicit.erTargetOf("md.dimension.Customer.state") shouldBe
                HopResult("er.entity.addr.state", HopVia.EXPLICIT)
            explicit.erTargetOf("md.measure.revenue") shouldBe HopResult("er.entity.customer.amount", HopVia.EXPLICIT)
        }

        "R7: dimension ownership follows bearer selection and preserves cubelet ambiguity" {
            val md =
                """
                model md
                def domain Code { type: string }
                def dimension Customer { key: code, attributes: [def attribute code { domain: md.Code }] }
                def cubelet sales { grain: [Customer.code] }
                """.trimIndent()
            val er =
                """
                model er
                def entity customer { attributes: [def attribute sk { type: int }] }
                def entity sale { attributes: [def attribute cust { type: int }] }
                """.trimIndent()
            val grain =
                """
                def md2er_cubelet g {
                    cubelet: md.sales, target: er.entity.sale,
                    attributes: { Customer.code: cust }
                }
                """.trimIndent()
            val own =
                """
                def md2er_dimension d {
                    dimension: md.Customer, entity: er.entity.customer,
                    attributes: { code: sk }
                }
                """.trimIndent()
            val second =
                """
                def md2er_cubelet h {
                    cubelet: md.sales, target: er.entity.customer,
                    attributes: { Customer.code: sk }
                }
                """.trimIndent()

            fun explicit(binding: String) =
                explicitSources(
                    mapOf(
                        "md" to md,
                        "er" to er,
                        "binding" to "model binding\n$binding",
                    ),
                )
            explicit(grain).erTargetOf("md.dimension.Customer.code").erTarget shouldBe "er.entity.sale.cust"
            val hop = explicit("$grain\n$own")
            hop.erTargetOf("md.dimension.Customer.code") shouldBe HopResult("er.entity.customer.sk", HopVia.EXPLICIT)
            hop.erTargetOf("md.dimension.Customer.code", "er.entity.sale").erTarget shouldBe "er.entity.sale.cust"
            hop.erTargetOf("md.dimension.Customer.code", "er.entity.other").erTarget shouldBe "er.entity.customer.sk"
            explicit("$grain\n$second").erTargetOf("md.dimension.Customer.code").diagnostic shouldBe HopResult.AMBIGUOUS
            explicit("$second\n$grain\n$own").erTargetOf("md.dimension.Customer.code").erTarget shouldBe
                "er.entity.customer.sk"
        }

        "R11: only imported or same-package relations contribute to the hop" {
            val root = CnFixtures.conformance.resolve("111-md2er-path-ambiguous")
            val sources =
                Files.list(root).use { paths ->
                    paths.toList().associate {
                        it.fileName.toString() to
                            Files.readString(it)
                    }
                }
            val hop = explicitSources(sources)
            hop.erTargetOf("md.dimension.Customer.code").diagnostic shouldBe HopResult.AMBIGUOUS
            hop.erTargetOf("md.dimension.CustomerEast.code").erTarget shouldBe "er.entity.addr.state"
            hop.erTargetOf("md.dimension.CustomerNamed.code").erTarget shouldBe "er.entity.addr.state"
            hop.erTargetOf("md.dimension.CustomerHidden.code").diagnostic shouldBe HopResult.UNMAPPED
        }

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

        "two packages declare the same md and er names: a grounding lands in its own package (ttr-core#228)" {
            val cn =
                CnFixtures
                    .load(
                        CnFixtures.dir(CnFixtures.resources.resolve("samename/east"), "hartland/east") +
                            CnFixtures.dir(CnFixtures.resources.resolve("samename/west"), "hartland/west"),
                    ).cn
            cn.byName("Sale")!!.let {
                it.grade shouldBe GroundingGrade.MD_GROUNDED
                it.erTarget shouldBe "er.entity.sale"
                it.hop shouldBe HopResult("er.entity.sale", HopVia.EXPLICIT)
            }
            cn.byName("store_code")!!.let {
                it.grade shouldBe GroundingGrade.MD_GROUNDED
                it.erTarget shouldBe "er.entity.sale.store_code"
            }
            // a dimension has no md2er_cubelet: resolved, and the hop answers md/er-unmapped
            cn.byName("Store")!!.hop!!.diagnostic shouldBe HopResult.UNMAPPED
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
