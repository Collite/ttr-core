// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.source.LoadedFile
import org.tatrman.ttr.metadata.source.StorageFile
import org.tatrman.ttr.parser.loader.TtrLoader

internal fun graphOf(text: String): DefinitionGraph {
    val parsed = TtrLoader.parseString(text)
    parsed.errors shouldBe emptyList()
    val file = LoadedFile(StorageFile("test.ttrm", 0), "test", "test", emptyList(), parsed.definitions, "", "")
    return DefinitionGraph.build(DefinitionSet.from(listOf(file), null, "0.18", "test"), "northline")
}

class DefinitionGraphSpec :
    StringSpec({
        "the world has exactly the reviewed node identities and kinds" {
            val expected =
                mapOf(
                    "area.retail" to "area",
                    "db.retail.buyers" to "table",
                    "db.retail.buyers.id" to "column",
                    "db.retail.buyers.name" to "column",
                    "db.retail.products" to "table",
                    "db.retail.products.id" to "column",
                    "db.retail.products.name" to "column",
                    "db.retail.sales" to "table",
                    "db.retail.sales.id" to "column",
                    "db.retail.sales.buyer_id" to "column",
                    "db.retail.sales.amount" to "column",
                    "db.retail.sales.sold_at" to "column",
                    "db.retail.recent_sales" to "view",
                    "db.retail.recent_sales.id" to "column",
                    "db.retail.sale_buyer" to "foreign_key",
                    "er.entity.buyer" to "entity",
                    "er.entity.buyer.id" to "attribute",
                    "er.entity.buyer.name" to "attribute",
                    "er.entity.product" to "entity",
                    "er.entity.product.id" to "attribute",
                    "er.entity.sale" to "entity",
                    "er.entity.sale.id" to "attribute",
                    "er.entity.sale.buyer_id" to "attribute",
                    "er.entity.sale.amount" to "attribute",
                    "er.entity.sale.sold_at" to "attribute",
                    "er.relation.sale_buyer" to "relation",
                    "md.domain.Money" to "domain",
                    "md.dimension.Buyer" to "dimension",
                    "md.dimension.Buyer.id" to "attribute",
                    "md.dimension.Calendar" to "dimension",
                    "md.dimension.Calendar.day" to "attribute",
                    "md.dimension.Calendar.month" to "attribute",
                    "md.hierarchy.CalendarRollup" to "hierarchy",
                    "md.measure.revenue" to "measure",
                    "md.measure.units" to "measure",
                    "md.measure.balance" to "measure",
                    "md.cubelet.purchases" to "cubelet",
                    "cn.kind.Party" to "kind",
                    "cn.kind.Prospect" to "kind",
                    "cn.kind.Product" to "kind",
                    "cn.role.Customer" to "role",
                    "cn.event.Purchase" to "event",
                    "cn.phase.LargePurchase" to "phase",
                    "cn.quality.sold_at" to "quality",
                    "cn.relation_type.recommends" to "relation_type",
                )
            DefinitionGraph
                .build(xmSet(), "northline")
                .nodes
                .filter { it.external != true && it.kind != "package" }
                .associate { it.id to it.kind } shouldBe expected
        }
        "labels, synonyms, IRI, definition links and sugar survive the bodiless projection" {
            val graph = DefinitionGraph.build(xmSet(), "northline")
            val party = graph.nodes.single { it.id == "cn.kind.Party" }
            party.iri shouldBe "https://example.org/northline/Party"
            party.displayLabel shouldBe mapOf("cs" to "Osoba", "en" to "Party")
            party.synonyms shouldBe mapOf("cs" to listOf("osoba", "partner"), "en" to listOf("partner", "party"))
            graph.nodes.single { it.id == "cn.kind.Product" }.props["declaredBy"] shouldBe "sugar"
            graph.edges.any {
                it.from == "cn.kind.Product" && it.to == "er.entity.product" && it.props["via"] == "sugar"
            } shouldBe
                true
            graph.nodes.single { it.id == "cn.phase.LargePurchase" }.definitionRef shouldBe
                "defs.json#cn.phase.LargePurchase"
            graph.nodes.single { it.id == "md.measure.balance" }.props["aggregationPerDimension"] shouldBe
                mapOf("Calendar" to "last")
            graph.nodes.single { it.kind == "view" }.props["sql"] shouldBe false
            graph.nodes.none { it.model == "bindings" || it.kind in setOf("world", "engine", "query") } shouldBe true
            graph.edges.single { it.id == "cn2er_kind.party_entity" }.kind shouldBe "grounds"
        }
        "unresolved references have explicit minimal stubs" {
            val graph = graphOf("model er\ndef relation missing { from: er.entity.a, to: er.entity.b }")
            graph.nodes.single { it.id == "er.entity.b" } shouldBe
                Node("er.entity.b", kind = "unresolved", external = true)
            graph.edges.all { edge ->
                graph.nodes.any { it.id == edge.from } && graph.nodes.any { it.id == edge.to }
            } shouldBe
                true
        }
        "package and area scopes retain crossing edges and strip stub metadata" {
            val set = xmSet()
            val graph = DefinitionGraph.build(set, "northline")
            val scoped = ExportScope(packages = setOf("er")).apply(graph, set)
            scoped.nodes.single { it.id == "db.retail.buyers" } shouldBe
                Node("db.retail.buyers", kind = "table", external = true)
            scoped.edges.any { it.id == "er2db_entity.buyer_table" } shouldBe true
            val area = ExportScope(areas = setOf("retail")).apply(graph, set)
            area.nodes.single { it.id == "md.measure.revenue" }.external shouldBe false
            ExportScope(packages = emptySet()).apply(graph, set).nodes shouldBe emptyList()
        }
    })
