// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GraphRelationshipsSpec :
    StringSpec({
        "fixture references resolve with the complete C-6 semantic edge inventory" {
            val graph = DefinitionGraph.build(xmSet(), "northline")
            graph.nodes.filter { it.kind == "unresolved" } shouldBe emptyList()
            graph.edges
                .filter { it.kind !in setOf("contains", "area") }
                .map { listOf(it.from, it.to, it.kind, it.label.orEmpty(), it.id.orEmpty()) }
                .toSet() shouldBe
                setOf(
                    listOf("er.entity.sale", "er.entity.buyer", "relation", "", ""),
                    listOf("db.retail.sales", "db.retail.buyers", "foreign_key", "", ""),
                    listOf("md.cubelet.purchases", "md.dimension.Buyer.id", "grain", "", ""),
                    listOf("md.cubelet.purchases", "md.dimension.Calendar.day", "grain", "", ""),
                    listOf("md.cubelet.purchases", "md.measure.revenue", "measures", "", ""),
                    listOf("md.cubelet.purchases", "md.measure.units", "measures", "", ""),
                    listOf("md.cubelet.purchases", "md.measure.balance", "measures", "", ""),
                    listOf("md.hierarchy.CalendarRollup", "md.dimension.Calendar.day", "level", "", ""),
                    listOf("md.hierarchy.CalendarRollup", "md.dimension.Calendar.month", "level", "", ""),
                    listOf("cn.kind.Prospect", "cn.kind.Party", "is_a", "", ""),
                    listOf("cn.event.Purchase", "cn.role.Customer", "involves", "buyer", ""),
                    listOf("cn.event.Purchase", "cn.kind.Product", "involves", "item", ""),
                    listOf("cn.event.Purchase", "cn.quality.sold_at", "period_of", "at", ""),
                    listOf("er.entity.buyer", "db.retail.buyers", "er2db", "", "er2db_entity.buyer_table"),
                    listOf("er.entity.sale", "db.retail.sales", "er2db", "", "er2db_entity.sale_table"),
                    listOf("er.entity.buyer.id", "db.retail.buyers.id", "er2db", "", "er2db_attribute.buyer_id"),
                    listOf("md.cubelet.purchases", "db.retail.sales", "md2db", "", "md2db_cubelet.purchases_table"),
                    listOf("cn.role.Customer", "md.dimension.Buyer", "grounds", "", "cn2md_role.customer_dimension"),
                    listOf("cn.kind.Party", "er.entity.buyer", "grounds", "", "cn2er_kind.party_entity"),
                    listOf("cn.event.Purchase", "er.entity.sale", "grounds", "", "cn2er_event.purchase_entity"),
                    listOf(
                        "cn.quality.sold_at",
                        "er.entity.sale.sold_at",
                        "grounds",
                        "",
                        "cn2er_quality.sold_at_attribute",
                    ),
                    listOf("cn.kind.Product", "er.entity.product", "grounds", "", ""),
                )
            graph.edges.single { it.kind == "foreign_key" }.props["fromColumns"] shouldBe
                listOf("db.retail.sales.buyer_id")
            graph.edges.filter { it.kind == "level" }.map { it.props["order"] } shouldBe listOf(0, 1)
            graph.edges.single { it.id == "cn2er_event.purchase_entity" }.definitionRef shouldBe
                "defs.json#cn2er_event.purchase_entity"
            graph.edges.any {
                it.kind == "contains" && it.from == "er.entity.buyer" && it.to == "er.entity.buyer.id"
            } shouldBe
                true
            graph.edges.any { it.kind == "contains" && it.from == "db.retail" && it.to == "db.retail.buyers" } shouldBe
                true
        }
        "value space, direct grounds and is_a lists resolve without leaking expression bodies" {
            val graph =
                graphOf(
                    """
                    def entity code {}
                    def kind Value { grounds: er.entity.code }
                    def kind Parent {}
                    def kind Child { is_a: [Value, Parent] }
                    def quality category { value_space: Value }
                    """.trimIndent(),
                )
            graph.edges
                .filter { it.kind == "is_a" }
                .map { it.to }
                .toSet() shouldBe setOf("cn.kind.Parent", "cn.kind.Value")
            graph.edges.single { it.kind == "value_space" }.to shouldBe "cn.kind.Value"
            graph.edges.single { it.kind == "grounds" }.props shouldBe mapOf("via" to "direct", "layer" to "er")
        }
        "area entity selection includes attributes but only stubs for neighboring entities" {
            val set = xmSet()
            val area = set.areas.getValue("retail").copy(packages = emptyList(), entities = listOf("buyer"))
            val scoped =
                ExportScope(areas = setOf("buyer")).apply(
                    DefinitionGraph.build(set),
                    set.copy(areas = mapOf("buyer" to area)),
                )
            scoped.nodes
                .filter { !it.external }
                .map { it.id }
                .toSet() shouldBe
                setOf("er.entity.buyer", "er.entity.buyer.id", "er.entity.buyer.name")
            scoped.nodes.single { it.id == "er.entity.sale" } shouldBe
                Node("er.entity.sale", kind = "entity", external = true)
            scoped.nodes.none { it.id == "md.measure.revenue" } shouldBe true
            ExportScope().header shouldBe null
            ExportScope(packages = setOf("z", "a")).header shouldBe mapOf("packages" to listOf("a", "z"))
        }
        "legacy CNC and automation kinds, queries and world plumbing stay out of the graph" {
            val graph =
                graphOf(
                    """
                    model cnc
                    def role fact {}
                    def query q {}
                    def world demo { def engine pg { type: postgres } }
                    def program batch {}
                    """.trimIndent(),
                )
            graph.nodes shouldBe emptyList()
            graph.edges shouldBe emptyList()
        }
    })
