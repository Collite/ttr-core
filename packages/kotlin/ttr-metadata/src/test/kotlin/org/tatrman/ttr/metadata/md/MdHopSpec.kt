// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.md

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.model.HopResult
import org.tatrman.ttr.metadata.model.HopVia
import org.tatrman.ttr.metadata.source.CompositeMdErHop
import org.tatrman.ttr.metadata.source.DerivedMdErHop
import org.tatrman.ttr.metadata.source.ExplicitMdErHop
import org.tatrman.ttr.metadata.source.PhysicalIndex
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.semantics.Resolver
import org.tatrman.ttr.semantics.SemanticDocument
import org.tatrman.ttr.semantics.SymbolTable
import java.nio.file.Files
import java.nio.file.Path

/** One fixture, read in place by the Kotlin and TS hop/report tests. */
object MdHopFixture {
    val root: Path = Path.of("src/test/resources/md-hop")

    fun sources(): Map<String, String> =
        Files.list(root).use { paths ->
            paths.filter { it.toString().endsWith(".ttrm") }.sorted().toList().associate {
                it.fileName.toString() to
                    Files.readString(it)
            }
        }

    fun project(sources: Map<String, String> = sources()): Pair<List<SemanticDocument>, Resolver> {
        val table = SymbolTable()
        val docs =
            sources.map { (uri, text) ->
                val p = TtrLoader.parseString(text, uri)
                p.errors shouldBe emptyList()
                val schema = p.modelDirective?.modelCode.orEmpty()
                val ns = p.modelDirective?.schema.orEmpty()
                val pkg = p.packageName.orEmpty()
                table.upsertDocument(uri, p.definitions, schema, ns, pkg)
                SemanticDocument(uri, p.definitions, schema, ns, pkg, p.imports)
            }
        return docs to Resolver(table)
    }
}

class MdHopSpec :
    StringSpec({
        "whole model report and roles match the cross-runtime JSON and markdown goldens" {
            val (docs, resolver) = MdHopFixture.project()
            val report =
                org.tatrman.ttr.metadata.source
                    .mdHopReport(docs, resolver, all = true)
            report.roles.find { it.entity == "er.entity.store_sales" }!!.role shouldBe "fact"
            report.roles.find { it.entity == "er.entity.customer" }!!.role shouldBe "dimension"
            report.roles.find { it.entity == "er.entity.customer_item" }!!.role shouldBe "bridge"
            org.tatrman.ttr.metadata.source
                .mdHopReport(docs, resolver)
                .issues shouldBe emptyList()
            val root = Path.of("../../../tests/conformance/md-hop")
            val json =
                org.tatrman.ttr.metadata.source.MdHopRender
                    .json(report)
            val markdown =
                org.tatrman.ttr.metadata.source.MdHopRender
                    .markdown(report)
            if (System.getenv("MD_HOP_REGEN") == "1") {
                Files.createDirectories(root)
                Files.writeString(root.resolve("expected.json"), json)
                Files.writeString(root.resolve("expected.md"), markdown)
            }
            json shouldBe Files.readString(root.resolve("expected.json"))
            markdown shouldBe Files.readString(root.resolve("expected.md"))
        }

        "deprecated CNC role claims are compared only with derived warehouse roles" {
            val sources = MdHopFixture.sources().toMutableMap()
            sources["er.ttrm"] =
                sources.getValue("er.ttrm").replace("def entity customer {", "def entity customer { roles: [fact],")
            val (docs, resolver) = MdHopFixture.project(sources)
            val report =
                org.tatrman.ttr.metadata.source
                    .mdHopReport(docs, resolver)
            report.issues.filter { it.slug == "md/role-claim-mismatch" }.map { it.element } shouldBe
                listOf("er.entity.customer")
        }

        "the real loader derives CN landings and scopes MD load issues to grounded elements" {
            val source =
                org.tatrman.ttr.metadata.source.FileBasedSource(
                    "hop",
                    100,
                    org.tatrman.ttr.metadata.source
                        .LocalFsStorage("hop", MdHopFixture.root.toAbsolutePath()),
                )
            val snapshot = source.load()
            snapshot.errors shouldBe emptyList()
            snapshot.cn.byName("revenue")!!.hop shouldBe
                HopResult("er.entity.sales.ext_sales_price", HopVia.DERIVED_BEARER)
            snapshot.mdHopIssues shouldBe emptyList()
            val all =
                org.tatrman.ttr.metadata.source.CnBuilder
                    .build(snapshot.loadedFiles, allMdHopIssues = true)
            all.mdIssues.any { it.element == "md.cubelet.orphan" && it.slug == HopResult.UNMAPPED } shouldBe true
            all.mdIssues.any { it.element == "md.measure.revenue" && it.slug == HopResult.AMBIGUOUS } shouldBe true
        }

        "R6 bound domains use their source, while an opaque computed query reports information" {
            val sources = MdHopFixture.sources().toMutableMap()
            sources["binding.ttrm"] =
                sources.getValue("binding.ttrm") +
                "\ndef md2db_domain born { domain: md.BirthYear, source: { table: db.dbo.customer, column: born } }"
            sources["er.ttrm"] =
                sources
                    .getValue(
                        "er.ttrm",
                    ).replace(
                        "customer, item, sold, ext_sales_price FROM",
                        "customer, item, sold, ext_sales_price * 2 AS ext_sales_price FROM",
                    )
            val (docs, resolver) = MdHopFixture.project(sources)
            val hop = DerivedMdErHop(docs, resolver)
            hop.erTargetOf("md.domain.BirthYear") shouldBe HopResult("er.entity.customer.birthYear", HopVia.DERIVED)
            hop.lineageIssues.any {
                it.element == "er.entity.sales.ext_sales_price" && it.slug == "md/er-lineage-opaque"
            } shouldBe
                true
            hop.erTargetOf("md.measure.revenue", "er.entity.sales").diagnostic shouldBe HopResult.AMBIGUOUS
        }

        "identically named MD elements in different packages keep separate landings" {
            val sources = MdHopFixture.sources().toMutableMap()
            for (pkg in listOf("east", "west")) {
                for ((file, src) in MdHopFixture.sources()) {
                    val text =
                        src
                            .replace(Regex("er\\.entity\\.customer(?=[.,\\s])"), "er.entity.${pkg}_customer")
                            .replace("def entity customer {", "def entity ${pkg}_customer {")
                    sources["$pkg/$file"] = "package $pkg\n$text"
                }
            }
            val (docs, resolver) = MdHopFixture.project(sources)
            val hop = CompositeMdErHop(docs, resolver)
            hop.erTargetOf("east.md.dimension.Customer").erTarget shouldBe "er.entity.east_customer"
            hop.erTargetOf("east.md.cubelet.storeSales.Customer.customerCode").erTarget shouldBe
                "er.entity.store_sales.customer"
            hop.erTargetOf("west.md.dimension.Customer").erTarget shouldBe "er.entity.west_customer"
            hop.erTargetOf("md.dimension.Customer").erTarget shouldBe "er.entity.customer"
        }

        "an explicit override resolves the same-package ER owner before a matching root name" {
            val sources =
                mapOf(
                    "root-er" to "model er\ndef entity sale { attributes: [def attribute rootKey { type: int }] }",
                    "east-er" to
                        "package east\nmodel er\ndef entity sale { attributes: [def attribute localKey { type: int }] }",
                    "east-md" to
                        "package east\nmodel md\ndef domain Code { type: string }\ndef dimension D { key: code, attributes: [def attribute code { domain: md.Code }] }",
                    "east-pin" to
                        "package east\nmodel binding\ndef md2er_dimension pin { dimension: md.D, entity: er.entity.sale, attributes: { code: localKey } }",
                )
            val (docs, resolver) = MdHopFixture.project(sources)
            CompositeMdErHop(docs, resolver).erTargetOf("east.md.dimension.D.code") shouldBe
                HopResult("er.entity.sale.localKey", HopVia.EXPLICIT)
        }

        "unresolved composite FK positions never shift into a false relation pair" {
            val sources = MdHopFixture.sources().toMutableMap()
            sources["db.ttrm"] =
                sources
                    .getValue("db.ttrm")
                    .replace(
                        "from: [db.dbo.store_sales.cust], to: [db.dbo.customer.sk]",
                        "from: [db.dbo.store_sales.cust, db.dbo.store_sales.missing], " +
                            "to: [db.dbo.customer.missing, db.dbo.customer.sk]",
                    )
            val (docs, resolver) = MdHopFixture.project(sources)
            PhysicalIndex(
                docs,
                resolver,
            ).relations.single { it.key == "er.relation.store_sales_customer" }.pairs shouldBe
                emptyList()
        }

        "only an actual many-to-many map target is flagged as a bridge" {
            val sources = MdHopFixture.sources().toMutableMap()
            sources["md.ttrm"] =
                sources
                    .getValue("md.ttrm")
                    .replace("cardinality: { from: \"N\", to: \"N\" }", "cardinality: { from: \"1\", to: \"N\" }")
            val (docs, resolver) = MdHopFixture.project(sources)
            org.tatrman.ttr.metadata.source
                .mdHopReport(docs, resolver)
                .roles
                .single { it.entity == "er.entity.customer_item" }
                .role shouldBe "unclassified"
        }

        "the physical inverse indexes include tables, views and renamed attributes" {
            val (docs, resolver) = MdHopFixture.project()
            val index = PhysicalIndex(docs, resolver)
            index.tableToErEntity["db.dbo.table.store_sales"] shouldBe setOf("er.entity.store_sales")
            index.tableToErEntity["db.dbo.view.sales_view"] shouldBe setOf("er.entity.view_sales")
            index.columnToErAttr["db.dbo.table.store_sales.amount"] shouldBe
                setOf("er.entity.store_sales.ext_sales_price")
        }

        "R1 R3 R4: every cubelet dimension and attribute in the landing table derives without md2er" {
            val (docs, resolver) = MdHopFixture.project()
            val hop = DerivedMdErHop(docs, resolver)
            for (line in Files.readAllLines(MdHopFixture.root.resolve("landings.tsv"))) {
                val (md, er) = line.split('\t')
                hop.erTargetOf(md) shouldBe HopResult(er, HopVia.DERIVED)
            }
        }

        "R2 R5 R7: measure candidates include the union entity, and the bearer picks it" {
            val (docs, resolver) = MdHopFixture.project()
            val hop = DerivedMdErHop(docs, resolver)
            hop.erTargetOf("md.measure.revenue").diagnostic shouldBe HopResult.AMBIGUOUS
            hop.erTargetOf("md.measure.revenue", "er.entity.sales") shouldBe
                HopResult("er.entity.sales.ext_sales_price", HopVia.DERIVED_BEARER)
            hop.erTargetOf("md.cubelet.storeSales.Customer.customerCode") shouldBe
                HopResult("er.entity.store_sales.customer", HopVia.DERIVED)
            hop.erTargetOf("md.cubelet.orphan").message shouldContain "no er2db"
            hop.erTargetOf("md.domain.Money").message shouldContain "no md2db"
            hop.mdElementsOn("er.entity.sales.ext_sales_price") shouldBe listOf("md.measure.revenue")
        }

        "two facts pointing at different dimension entities stay ambiguous; explicit overrides" {
            val sources =
                MdHopFixture.sources() +
                    ("conflict" to Files.readString(MdHopFixture.root.resolve("conflicting.ttrm.variant")))
            val (docs, resolver) = MdHopFixture.project(sources)
            DerivedMdErHop(docs, resolver).erTargetOf("md.dimension.Customer").let {
                it.diagnostic shouldBe HopResult.AMBIGUOUS
                it.message shouldContain "er.entity.household"
                it.message shouldContain "er.entity.customer"
            }
            val (pinnedDocs, pinnedResolver) =
                MdHopFixture.project(
                    sources + ("pin" to Files.readString(MdHopFixture.root.resolve("override.ttrm.variant"))),
                )
            val hop =
                CompositeMdErHop(
                    ExplicitMdErHop(pinnedDocs, pinnedResolver),
                    DerivedMdErHop(pinnedDocs, pinnedResolver),
                )
            hop.erTargetOf("md.dimension.Customer") shouldBe HopResult("er.entity.household", HopVia.EXPLICIT)
            hop.issues("md.dimension.Customer").map { it.slug } shouldBe listOf("md/er-hop-override")
        }
    })
