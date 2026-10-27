// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.metadata.cn.CnFixtures.cn
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Basis
import org.tatrman.ttr.metadata.model.Binding
import org.tatrman.ttr.metadata.model.CnHierarchy
import org.tatrman.ttr.metadata.model.CnLift
import org.tatrman.ttr.metadata.model.CnParticipant
import org.tatrman.ttr.metadata.model.CnRelation
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.Concept
import org.tatrman.ttr.metadata.model.EndState
import org.tatrman.ttr.metadata.model.Extent
import org.tatrman.ttr.metadata.model.GroundingGrade
import org.tatrman.ttr.metadata.model.GroundingGrade.ER_GROUNDED
import org.tatrman.ttr.metadata.model.GroundingGrade.MD_GROUNDED
import org.tatrman.ttr.metadata.model.GroundingGrade.MD_GROUNDED_HOP_MISSING
import org.tatrman.ttr.metadata.model.GroundingGrade.UNGROUNDED
import org.tatrman.ttr.metadata.model.GroundingLayer
import org.tatrman.ttr.metadata.model.GroundingPeriod
import org.tatrman.ttr.metadata.model.HierarchyRanges
import org.tatrman.ttr.metadata.model.Imprecision
import org.tatrman.ttr.metadata.model.LexiconOrigin
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.Via
import org.tatrman.ttr.metadata.model.Via.DIRECT
import org.tatrman.ttr.metadata.model.Via.MD_HOP
import org.tatrman.ttr.metadata.model.VocabularyVersion
import org.tatrman.ttr.metadata.query.MetadataQuery
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.search.searchableObjects
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.semantics.cn.GroundingOrigin
import java.nio.file.Files
import java.time.Instant

/**
 * Grammar 0.17 — the typed conceptual tier built over real models (CN-P2 S2.5 T2, T4–T6): conformance fixtures 75
 * (`model cn`) and 76 (`model binding`) loaded as one package beside the er / md / db faces they target, and a project
 * whose md groundings hop to E-R through explicit `md2er_cubelet`s.
 */
class CnModelSpec :
    StringSpec({
        val concepts = CnFixtures.concepts()
        val hop = CnFixtures.hop()
        val pkg = "hartland.concepts"

        // ---- T2: builder + wiring ---------------------------------------------------------------------------------

        "the cn tier rides the snapshot and the model: concepts, groundings, relation types" {
            concepts.snapshot.cn shouldBe concepts.cn
            val cn =
                concepts.result.model.schemas
                    .getValue("cn")
                    .shouldBeInstanceOf<CnSchema>()
            cn.concepts.keys
                .map { it.name }
                .toSet() shouldBe
                setOf(
                    "DebtorCustomer",
                    "HomeCategory",
                    "OutOfStock",
                    "OverdueInvoice",
                    "PendingOrder",
                    "PlacedOrder",
                    "StockCondition",
                    "Channel",
                    "Customer",
                    "Invoicing",
                    "Item",
                    "LegacyStock",
                    "Order",
                    "Orderer",
                    "Party",
                    "ProductCategory",
                    "Sale",
                    "StockTransfer",
                    "Supplier",
                    "Warehouse",
                    "WarehouseOutage",
                    "category",
                    "open_amount",
                    "revenue",
                    "stock_level",
                    "transfer_delay",
                )
            cn.relationTypes.keys shouldContainExactly setOf(cn("supplies", pkg))
            cn.concepts.keys.all { it == QualifiedName(SchemaCode.CN, "concept", it.name, pkg) } shouldBe true
            // 75's eleven `grounds:` and 76's bindings whose concept resolves (cn.brand, cn.Return and cn.state are
            // declared nowhere) — the second groundings of a concept included
            cn.bindings.size shouldBe 23
            cn.bindings.values.count { it.origin == GroundingOrigin.Grounds } shouldBe 11
        }

        "a concept is a model object: GetObject finds it, the graph has it, search indexes it by label" {
            val r = concepts.result
            val snapshot = RegistrySnapshot(r.model, ModelGraph.build(r.model), Instant.EPOCH, emptyList())
            val customer = MetadataQuery(snapshot).getObject(cn("Customer", pkg))
            customer.shouldBeInstanceOf<Concept>().stereotype shouldBe "role"
            val found = snapshot.searchableObjects().filter { it.kind == "concept" }.toList()
            found.map { it.qname.name } shouldContain "Item"
            found.single { it.qname.name == "Item" }.displayLabel.byLanguage shouldBe
                mapOf("en" to "Product", "cs" to "Produkt")
            snapshot.searchableObjects().any { it.kind == "relation_type" && it.qname.name == "supplies" } shouldBe true
        }

        "the model-level cn errors are load errors; hygiene and advisory ones ride the grades" {
            // 76 grounds eight concepts 75 already grounds — the second of each is the error, in 76's file
            concepts.result.errors.map { it.message.substringBefore(':') } shouldBe
                List(3) { "cn/durative-event" } + List(8) { "cn/duplicate-grounding" }
            concepts.result.errors
                .filter {
                    it.message.startsWith("cn/duplicate-grounding")
                }.all { it.file.endsWith("d-bindings.ttrm") } shouldBe
                true
            (concepts.result.warnings + concepts.result.errors).none {
                it.message.startsWith("cn/ungrounded") || it.message.startsWith("cn/db-grounding")
            } shouldBe true
        }

        "a source with no cn content gets an empty cn tier" {
            val root = Files.createTempDirectory("no-cn")
            Files.createDirectories(root.resolve("shop"))
            Files.writeString(
                root.resolve("shop/er.ttrm"),
                "package shop\nmodel er\ndef entity order { attributes: [ def attribute id { type: int, isKey: true } ] }\n",
            )
            val s = FileBasedSource("x", 1, LocalFsStorage(id = "x", rootPath = root)).load()
            s.cn shouldBe CnSchema()
            ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(s)).model.schemas["cn"] shouldBe
                CnSchema()
        }

        "registered vocabulary versions merge as one history: precedence order, a version registered twice kept once" {
            val at = Instant.parse("2026-10-22T10:00:00Z")
            val mkn2025 = VocabularyVersion("uzis-mkn10", "2025", registeredAt = at)
            val mkn2026 =
                VocabularyVersion("uzis-mkn10", "2026", bundle = "uzis-2026-01", registeredAt = at.plusSeconds(60))
            val sukl = VocabularyVersion("sukl", "2026.10", registeredAt = at)

            fun source(
                id: String,
                priority: Int,
                vararg versions: VocabularyVersion,
            ) = SourceSnapshot(id, priority, "v1", cn = CnSchema(vocabularyVersions = versions.toList()))

            val cn =
                ModelReconciler(ModelDescriptor(id = "d", name = "d"))
                    .reconcile(listOf(source("low", 10, mkn2025, sukl), source("high", 100, mkn2025, mkn2026)))
                    .model.schemas["cn"] as CnSchema

            cn.vocabularyVersions shouldContainExactly listOf(mkn2025, mkn2026, sukl)
        }

        "a cn def in an er file is a wrong-file-kind, named by its stereotype or binding keyword" {
            val root = Files.createTempDirectory("cn-wrong")
            Files.createDirectories(root.resolve("shop"))
            Files.writeString(
                root.resolve("shop/er.ttrm"),
                "package shop\nmodel er\ndef kind Item { }\ndef cn2er_kind item_g { kind: cn.Item, target: er.entity.item }\n",
            )
            val s = FileBasedSource("x", 1, LocalFsStorage(id = "x", rootPath = root)).load()
            s.errors.map { it.message } shouldContainExactly
                listOf(
                    "ttr/wrong-file-kind: 'kind' definition not allowed in 'schema er' file",
                    "ttr/wrong-file-kind: 'cn2er_kind' definition not allowed in 'schema er' file",
                )
        }

        // ---- T4: erTarget + via per concept ------------------------------------------------------------------------

        "every concept's landing, grade and route — fixtures 75 + 76 (T4 table)" {
            data class Row(
                val erTarget: String?,
                val via: Via?,
                val grade: GroundingGrade,
                val from: String? = null,
            )
            val expected =
                mapOf(
                    // md groundings: no md2er_* in this package, so every hop is missing
                    "Item" to Row(null, MD_HOP, MD_GROUNDED_HOP_MISSING),
                    "Warehouse" to Row(null, MD_HOP, MD_GROUNDED_HOP_MISSING),
                    "Customer" to Row(null, MD_HOP, MD_GROUNDED_HOP_MISSING),
                    "revenue" to Row(null, MD_HOP, MD_GROUNDED_HOP_MISSING),
                    // er groundings: the landing is the target — an entity, or the attribute of a value kind / quality
                    "ProductCategory" to Row("er.entity.category_code", DIRECT, ER_GROUNDED),
                    "Channel" to Row("er.entity.channel_sales.channel", DIRECT, ER_GROUNDED),
                    "Sale" to Row("er.entity.channel_sales", DIRECT, ER_GROUNDED),
                    "Invoicing" to Row("er.entity.invoice", DIRECT, ER_GROUNDED),
                    "category" to Row("er.entity.item.category", DIRECT, ER_GROUNDED),
                    "stock_level" to Row("er.entity.inventory.quantity_on_hand", DIRECT, ER_GROUNDED),
                    // grounded by 76's binding alone
                    "Order" to Row("er.entity.order", DIRECT, ER_GROUNDED),
                    "StockTransfer" to Row("er.entity.stock_transfer", DIRECT, ER_GROUNDED),
                    // a phase grounded to a stacked entity lands on it
                    "PlacedOrder" to Row("er.entity.placed_order", DIRECT, ER_GROUNDED),
                    // a defined phase inherits its bearer's
                    "PendingOrder" to Row("er.entity.order", DIRECT, ER_GROUNDED, "Order"),
                    "OverdueInvoice" to Row("er.entity.invoice", DIRECT, ER_GROUNDED, "Invoicing"),
                    "DebtorCustomer" to Row(null, MD_HOP, MD_GROUNDED_HOP_MISSING, "Customer"),
                    "OutOfStock" to Row(null, MD_HOP, MD_GROUNDED_HOP_MISSING, "Item"),
                    // a role on its base kind's entity (the defining event's path is the grounding's `via:`)
                    "Orderer" to Row("er.entity.party", DIRECT, ER_GROUNDED),
                    // db: documentation only, no consumer reaches it
                    "LegacyStock" to Row(null, null, UNGROUNDED),
                    // ungrounded — and the defined quality, the member-restricted kind, the abstract phase
                    "Party" to Row(null, null, UNGROUNDED),
                    "Supplier" to Row(null, null, UNGROUNDED),
                    "WarehouseOutage" to Row(null, null, UNGROUNDED),
                    "HomeCategory" to
                        Row("er.entity.category_code", Via.DIRECT, GroundingGrade.ER_GROUNDED, "ProductCategory"),
                    "open_amount" to Row(null, null, UNGROUNDED),
                    "transfer_delay" to Row(null, null, UNGROUNDED),
                    "StockCondition" to Row(null, null, UNGROUNDED),
                )
            val actual =
                concepts.cn.concepts.values.associate {
                    it.qname.name to Row(it.erTarget, it.via, it.grade, it.landingFrom?.name)
                }
            actual shouldBe expected
        }

        "md groundings with the hop present (an explicit md2er_cubelet) — T4" {
            val c = hop.cn

            fun row(name: String) = c.byName(name)!!.let { Triple(it.erTarget, it.via, it.grade) }
            row("WebSale") shouldBe Triple("er.entity.web_sale", MD_HOP, MD_GROUNDED)
            row("WebReturn") shouldBe Triple("er.entity.web_return", MD_HOP, MD_GROUNDED)
            // two cubelets map Shopper.code; the quality's bearer picks one (AM-29)
            row("shopper_code") shouldBe Triple("er.entity.web_sale.shopper_id", MD_HOP, MD_GROUNDED)
            row("returning") shouldBe Triple("er.entity.web_return.shopper_id", MD_HOP, MD_GROUNDED)
            row("item_sku") shouldBe Triple("er.entity.web_sale.product_sku", MD_HOP, MD_GROUNDED)
            // P2's hop: no dimension or measure maps yet
            row("Shopper") shouldBe Triple(null, MD_HOP, MD_GROUNDED_HOP_MISSING)
            row("net_sales") shouldBe Triple(null, MD_HOP, MD_GROUNDED_HOP_MISSING)
            // a defined phase inherits the hop
            c.byName("BigWebSale")!!.let {
                Triple(it.erTarget, it.via, it.grade) shouldBe Triple("er.entity.web_sale", MD_HOP, MD_GROUNDED)
                it.landingFrom shouldBe cn("WebSale", "hartland.web")
            }
            hop.result.errors shouldBe emptyList()
        }

        "the grounding read: its target, kind and layer; a desugared one is synthetic" {
            val g = concepts.concept("ProductCategory").grounding!!
            g.qname shouldBe QualifiedName(SchemaCode.CN, "cn2er_kind", "ProductCategory.grounds", pkg)
            g.target shouldBe "er.entity.category_code"
            g.targetKind shouldBe "entity"
            g.layer shouldBe GroundingLayer.ER
            g.origin shouldBe GroundingOrigin.Grounds
            g.binding shouldBe Binding.BoundSynthetic("desugared from grounds:")
            // `hierarchy:` rides onto the grounding (Q15-1); 75 writes `ranges: node`, 76's second grounding `lexical`
            g.hierarchy shouldBe
                CnHierarchy(
                    closure = "er.entity.category_closure",
                    ancestor = "er.entity.category_closure.ancestor_code",
                    descendant = "er.entity.category_closure.descendant_code",
                    ranges = HierarchyRanges.NODE,
                )
            concepts.cn.bindings
                .getValue(QualifiedName(SchemaCode.CN, "cn2er_kind", "category_g", pkg))
                .hierarchy
                ?.ranges shouldBe HierarchyRanges.LEXICAL
            concepts.concept("LegacyStock").grounding!!.let {
                it.layer shouldBe GroundingLayer.DB
                it.target shouldBe "db.dbo.legacy_stock"
                it.binding shouldBe Binding.BoundReal
                it.tags shouldBe listOf("legacy")
            }
        }

        "a cn2er_event's participant paths and period; a valued state's filter; a role's via path" {
            val transfer = concepts.concept("StockTransfer").grounding!!
            transfer.participants shouldBe
                mapOf(
                    "item" to listOf("er.relation.rel_transfer_item"),
                    "from" to listOf("er.relation.rel_transfer_from"),
                    "to" to listOf("er.relation.rel_transfer_to", "er.relation.rel_warehouse_region"),
                )
            transfer.period shouldBe GroundingPeriod(at = "er.entity.stock_transfer.shipped_at")
            concepts.concept("Order").grounding!!.period shouldBe
                GroundingPeriod(start = "er.entity.order.placed_date", end = "er.entity.order.delivered_date")
            val stock = concepts.cn.bindings.getValue(QualifiedName(SchemaCode.CN, "cn2er_quality", "stock_g", pkg))
            stock.filter!!.text shouldBe "warehouse = 1"
            stock.concept shouldBe cn("stock_level", pkg)
            // 75's grounding of stock_level is read, not 76's
            concepts
                .concept("stock_level")
                .grounding!!
                .filter
                .shouldBeNull()
            // a role grounded to its base kind's entity with the defining event's path (76 `orderer_g`)
            concepts.concept("Orderer").grounding!!.let {
                it.target shouldBe "er.entity.party"
                it.viaPath shouldBe listOf("er.relation.rel_order_customer")
            }
        }

        "what a concept states: the def's properties, typed" {
            concepts.concept("Item").let {
                it.label.byLanguage shouldBe mapOf("en" to "Product", "cs" to "Produkt")
                it.description shouldBe "A product the stores, the web shop and the marketplace sell."
                it.sources shouldBe listOf("doc://hartland/handbook#products")
                it.qualities shouldBe listOf("category", "brand")
            }
            concepts.concept("Customer").let {
                it.of shouldBe cn("Party", pkg)
                it.event shouldBe cn("Order", pkg)
            }
            concepts.concept("Order").let {
                it.involves shouldBe
                    listOf(
                        CnParticipant("customer", cn("Customer", pkg)),
                        CnParticipant("fulfilling", cn("Warehouse", pkg)),
                    )
                it.extent shouldBe Extent.DURATIVE
                it.period!!.endState shouldBe EndState.OPEN
            }
            concepts.concept("StockTransfer").let {
                it.involves.map { p -> p.label } shouldContainExactly listOf("item", "from", "to")
                it.period!!.precision shouldBe "day"
                it.period!!.endState.shouldBeNull()
                it.effectiveExtent shouldBe Extent.INSTANTANEOUS
            }
            concepts.concept("WarehouseOutage").period!!.endState shouldBe EndState.UNKNOWN
            concepts.concept("Invoicing").let {
                it.period!!.endState shouldBe EndState.PRESENT
                it.extent.shouldBeNull()
                it.effectiveExtent shouldBe Extent.DURATIVE
            }
            concepts.concept("HomeCategory").let {
                it.isA shouldBe listOf(cn("ProductCategory", pkg))
                it.members!!.text shouldBe "descendants(ProductCategory, 'H10-H19')"
            }
            concepts.concept("category").valueSpace shouldBe cn("ProductCategory", pkg)
            concepts.concept("stock_level").valuedState shouldBe true
            concepts.concept("transfer_delay").definition!!.let {
                it.form shouldBe "tagged"
                it.text.trim() shouldBe "this.period.at - this.from.period.at"
                it.lineOffsets.isNotEmpty() shouldBe true // the source map S2.6 reads diagnostics back through
            }
            concepts.concept("DebtorCustomer").imprecision shouldBe Imprecision.CERTAINLY
            concepts.concept("OutOfStock").let {
                it.basis shouldBe Basis.CURRENT_STATE
                it.isA shouldBe listOf(cn("StockCondition", pkg))
            }
            concepts.concept("StockCondition").abstract shouldBe true
            concepts.concept("Warehouse").tags shouldBe listOf("logistics")
        }

        "the open tier: relations edges and relation types (CN-D7); an unresolved is_a reads as absent" {
            val supplier = concepts.concept("Supplier")
            supplier.relations shouldBe
                listOf(CnRelation("supplies", cn("supplies", pkg), "Warehouse", cn("Warehouse", pkg)))
            supplier.relations.all { it.openTier } shouldBe true
            supplier.isA shouldBe listOf(cn("Party", pkg)) // `Organization` is declared nowhere
            supplier.openTier shouldBe false
            concepts.cn.relationTypes.values.single().let {
                it.openTier shouldBe true
                it.description shouldBe "A supplier supplies a distribution center."
                it.label.byLanguage shouldBe mapOf("en" to "supplies")
            }
        }

        // ---- T5: the reverse index ---------------------------------------------------------------------------------

        "the reverse index over fixtures 75 + 76: md and er elements, stamped via; phases never (T5)" {
            val index = concepts.cn.reverseIndex.mapValues { (_, lifts) -> lifts.map { "${it.ref}/${it.via}" } }
            index shouldBe
                mapOf(
                    "er.entity.category_code" to listOf("cn.ProductCategory/DIRECT"),
                    "er.entity.channel_sales" to listOf("cn.Sale/DIRECT"),
                    "er.entity.channel_sales.channel" to listOf("cn.Channel/DIRECT"),
                    "er.entity.inventory.quantity_on_hand" to listOf("cn.stock_level/DIRECT"),
                    "er.entity.invoice" to listOf("cn.Invoicing/DIRECT"),
                    "er.entity.item.category" to listOf("cn.category/DIRECT"),
                    "er.entity.order" to listOf("cn.Order/DIRECT"),
                    "er.entity.party" to listOf("cn.Orderer/DIRECT"),
                    "er.entity.stock_transfer" to listOf("cn.StockTransfer/DIRECT"),
                    "md.dimension.Customer" to listOf("cn.Customer/MD_HOP"),
                    "md.dimension.DistributionCentre" to listOf("cn.Warehouse/MD_HOP"),
                    "md.dimension.Product" to listOf("cn.Item/MD_HOP"),
                    "md.measure.revenue" to listOf("cn.revenue/MD_HOP"),
                )
            // PlacedOrder grounds er.entity.placed_order, PendingOrder inherits er.entity.order: neither is a lift
            concepts.cn.liftsTo("er.entity.placed_order") shouldBe emptyList()
            concepts.cn.liftsTo("er.entity.order").map { it.stereotype } shouldBe listOf("event")
        }

        "an md-hop concept is indexed under its md element and, the hop present, its er one (T5)" {
            val c = hop.cn
            val ws = CnLift(cn("WebSale", "hartland.web"), "event", MD_HOP)
            c.liftsTo("md.cubelet.web_sales") shouldBe listOf(ws)
            c.liftsTo("er.entity.web_sale") shouldBe listOf(ws)
            c.liftsTo("md.dimension.Shopper.code").map { it.ref } shouldContainExactly
                listOf("cn.returning", "cn.shopper_code")
            c.liftsTo("er.entity.web_sale.shopper_id").map { it.ref } shouldBe listOf("cn.shopper_code")
            c.liftsTo("er.entity.web_return.shopper_id").map { it.ref } shouldBe listOf("cn.returning")
            // the hop missing: the md element only
            c.liftsTo("md.dimension.Shopper").map { it.ref } shouldBe listOf("cn.Shopper")
            // BigWebSale (a phase) lands on web_sale but lifts nowhere
            c.reverseIndex.values
                .flatten()
                .none { it.stereotype == "phase" } shouldBe true
        }

        // ---- T6: lexicon links -------------------------------------------------------------------------------------

        "lexicon links: canonical entries with their sense fields and locale, inline terms (T6)" {
            val lex = CnFixtures.lexicon()

            fun links(name: String) =
                lex.concept(name).lexiconLinks.map {
                    listOf(it.origin, it.entryKind, it.name, it.locale, it.forms, it.default, it.sensePriority)
                }
            links("Customer") shouldBe
                listOf(
                    listOf(
                        LexiconOrigin.CANONICAL,
                        "term",
                        "customer",
                        "en",
                        listOf("customer", "customers", "client"),
                        true,
                        null,
                    ),
                )
            links("DebtorCustomer") shouldContainExactlyInAnyOrder
                listOf(
                    listOf(
                        LexiconOrigin.CANONICAL,
                        "term",
                        "debtor",
                        "en",
                        listOf("debtor", "customer with overdue invoices"),
                        null,
                        2.0,
                    ),
                    listOf(LexiconOrigin.CANONICAL, "term", "customer_phase", "en", listOf("customer"), false, 1.0),
                )
            links("revenue") shouldBe
                listOf(listOf(LexiconOrigin.CANONICAL, "term", "revenue", "en", listOf("revenue", "sales"), true, 3.0))
            // fixture 75's inline `lexicon: { terms: [...] }` on `def kind Item` — the base locale
            links("Item") shouldBe
                listOf(listOf(LexiconOrigin.INLINE, "term", "Item", null, listOf("product", "item", "SKU"), null, null))
            // `def example web_revenue { for: cn.WebSale }` names no declared concept; `for: md.Product` no concept
            lex.cn.concepts.values
                .sumOf { it.lexiconLinks.size } shouldBe 6
            // `vocabularyVersions` is present and empty: no model text states one (Q15-5; CN-P5 S5.3 sets them)
            lex.cn.vocabularyVersions shouldBe emptyList()
            // S2.7 — `for: md.Product` is an element term, kept by element ref for the hygiene report's redundant terms
            lex.cn.elementTerms.mapValues { (_, links) -> links.map { it.name to it.forms } } shouldBe
                mapOf("md.dimension.Product" to listOf("product" to listOf("product")))
        }
    })
