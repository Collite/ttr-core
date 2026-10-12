// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.parser.model.LayoutPoint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/**
 * VL-P2 T2 (contracts V-37, V-38, §6; A-VL-1…A-VL-3) — the layout index built from a really loaded tree
 * (`src/test/resources/layout-fixture`: packages `hartland` and `outlet`, eight `.ttrg` files under `layouts/`).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LayoutIndexSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-metadata)
        val fixture = Path.of("src/test/resources/layout-fixture").toAbsolutePath()
        val reconciler = ModelReconciler(ModelDescriptor("t", "t"))

        fun index(root: Path = fixture): LayoutIndex {
            val snaps = listOf(FileBasedSource("fixture", 100, LocalFsStorage("fixture", root)).load())
            return LayoutIndex.build(snaps, reconciler.reconcile(snaps).model)
        }

        val idx = index()

        "six layouts indexed, by schema then name; broken and the second dup_a are issues (V-38)" {
            idx.all.map { "${it.schema}·${it.name}" } shouldBe
                listOf("db·dup_a", "er·authored", "er·auto_residue", "er·main_canvas", "er·missing", "er·sidecar")
            idx.issues.map { it.path } shouldBe listOf("layouts/broken.ttrg", "layouts/dup_b.ttrg")
            idx.issues[1].reason shouldBe "the name dup_a is already taken by layouts/dup_a.ttrg"
            idx.issues[0].toString() shouldContain "layout layouts/broken.ttrg: "
            idx["dup_a"]!!.source shouldBe "layouts/dup_a.ttrg"
        }

        "authored: in-file positions, a bare name resolved, the relation ignored" {
            val a = idx["authored"]!!
            a.objects shouldBe listOf("hartland.er.entity.item", "hartland.er.entity.store", "hartland.er.entity.store_sales")
            a.positions shouldBe
                mapOf(
                    "hartland.er.entity.item" to LayoutPoint(320.0, 180.0),
                    "hartland.er.entity.store" to LayoutPoint(580.0, 180.0),
                    "hartland.er.entity.store_sales" to LayoutPoint(320.0, 400.0),
                )
            a.mode shouldBe "manual"
            a.positionsFrom shouldBe "in-file"
            a.ignored shouldBe listOf("hartland.er.entity.rel_store_item")
            a.missing.shouldBeEmpty()
            a.ambiguous.shouldBeEmpty()
            a.skin shouldBe null
            a.collapsed.shouldBeEmpty()
        }

        "sidecar: the sidecar's positions and skin win; its extra key is dropped with a warning, never an issue (A-VL-3 a)" {
            val s = idx["sidecar"]!!
            s.positionsFrom shouldBe "sidecar"
            s.positions shouldBe
                mapOf(
                    "hartland.er.entity.item" to LayoutPoint(100.0, 120.0),
                    "hartland.er.entity.store" to LayoutPoint(300.0, 120.0),
                )
            s.skin shouldBe "er.crow"
            idx.warnings.filter { it.path == "layouts/sidecar.ttrg" }.single().reason shouldContain
                "hartland.er.entity.store_returns"
        }

        "main_canvas: a sidecar with only `canvas main` is read (A-VL-2)" {
            val m = idx["main_canvas"]!!
            m.positionsFrom shouldBe "sidecar"
            m.positions["hartland.er.entity.store_sales"] shouldBe LayoutPoint(240.0, 60.0)
        }

        "auto_residue: indexed with its in-file positions, the residue a warning, no issue (A-VL-3 b)" {
            val r = idx["auto_residue"]!!
            r.positionsFrom shouldBe "in-file"
            r.positions["hartland.er.entity.item"] shouldBe LayoutPoint(10.0, 20.0)
            r.skin shouldBe "er.chen"
            idx.warnings.filter { it.path == "layouts/auto_residue.ttrg" }.single().reason shouldContain "mode: auto"
            idx.issues.map { it.path }.contains("layouts/auto_residue.ttrg") shouldBe false
        }

        "missing: an unknown ref is missing, a bare name two packages define is ambiguous with both candidates" {
            val m = idx["missing"]!!
            m.objects shouldBe listOf("hartland.er.entity.store")
            m.missing shouldBe listOf("hartland.er.entity.customer")
            m.ambiguous shouldBe listOf(LayoutAmbiguity("item", listOf("hartland.er.entity.item", "outlet.er.outlet.item")))
            m.mode shouldBe "auto"
            m.positionsFrom shouldBe null
        }

        "dup_a: a db layout — tables as package + catalog qname (A-VL-6), the foreign key ignored" {
            val d = idx["dup_a"]!!
            d.objects shouldBe listOf("hartland.db.dbo.item", "hartland.db.dbo.store_sales")
            d.ignored shouldBe listOf("hartland.db.dbo.fk_ss_item")
        }

        "no layout files ⇒ the empty index" {
            val bare = createTempDirectory("vl-index-bare")
            try {
                fixture.copyToRecursively(bare, followLinks = false, overwrite = true)
                bare.resolve("layouts").deleteRecursively()
                index(bare).all.shouldBeEmpty()
            } finally {
                bare.deleteRecursively()
            }
        }

        "the refresher publishes the layouts in the same swap as the model (V-37)" {
            val tree = createTempDirectory("vl-refresh")
            try {
                fixture.copyToRecursively(tree, followLinks = false, overwrite = true)
                val source = FileBasedSource("fixture", 100, LocalFsStorage("fixture", tree))
                val registry = MetadataRegistry()
                val first = listOf(source.load())
                val model = reconciler.reconcile(first).model
                registry.swap(model, ModelGraph.build(model), layouts = LayoutIndex.build(first, model))
                val refresher = MetadataRefresher(listOf(source), listOf("fixture"), reconciler, registry)
                refresher.recordInitial(first)
                registry.read()!!.layouts["broken"] shouldBe null

                val fixed = tree.resolve("layouts/broken.ttrg")
                Files.writeString(fixed, "graph broken {\n  model: er,\n  objects: [ hartland.er.entity.item ]\n}\n")
                // a later mtime for certain — the version is a hash of path=mtime
                Files.setLastModifiedTime(fixed, FileTime.fromMillis(System.currentTimeMillis() + 10_000))
                runBlocking { refresher.refresh("", force = true) }
                val after = registry.read()!!
                after.layouts["broken"]!!.objects shouldBe listOf("hartland.er.entity.item")
                after.layouts.issues.map { it.path } shouldBe listOf("layouts/dup_b.ttrg")
            } finally {
                tree.deleteRecursively()
            }
        }
    })
