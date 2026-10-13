// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
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
 * VL-P2 T2 (contracts V-37, V-38, §6; A-VL-1…A-VL-3, A-VL-7) — the layout index built from a really loaded tree
 * (`src/test/resources/layout-fixture`: packages `hartland` and `outlet`, nine `.ttrg` files under `layouts/`).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LayoutIndexSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-metadata)
        val fixture = Path.of("src/test/resources/layout-fixture").toAbsolutePath()
        val reconciler = ModelReconciler(ModelDescriptor("t", "t"))

        fun source(root: Path) = FileBasedSource("fixture", 100, LocalFsStorage("fixture", root), layouts = true)

        fun index(root: Path = fixture): LayoutIndex {
            val snaps = listOf(source(root).load())
            return LayoutIndex.build(snaps, reconciler.reconcile(snaps).model)
        }

        fun copyOfFixture(
            prefix: String,
            body: (Path) -> Unit,
        ) {
            val tree = createTempDirectory(prefix)
            try {
                fixture.copyToRecursively(tree, followLinks = false, overwrite = true)
                body(tree)
            } finally {
                tree.deleteRecursively()
            }
        }

        val idx = index()

        "six layouts indexed, by schema then name; broken is an issue (V-38)" {
            idx.all.map { "${it.schema}·${it.name}" } shouldBe
                listOf("db·tables", "er·authored", "er·auto_residue", "er·main_canvas", "er·missing", "er·sidecar")
            idx.issues.map { it.path } shouldBe
                listOf("layouts/broken.ttrg", "layouts/dup_a.ttrg", "layouts/dup_b.ttrg")
            idx.issues[0].toString() shouldBe "layout layouts/broken.ttrg: line 6: expected `,` or `]` in objects"
        }

        "A-VL-7: a name two files declare is served by neither, each an issue naming the other" {
            idx["dup_a"] shouldBe null
            idx.issues.drop(1).map { it.toString() } shouldBe
                listOf(
                    "layout layouts/dup_a.ttrg: the name dup_a is also declared by layouts/dup_b.ttrg",
                    "layout layouts/dup_b.ttrg: the name dup_a is also declared by layouts/dup_a.ttrg",
                )
        }

        "authored: in-file positions, a bare name resolved, the relation ignored" {
            val a = idx["authored"]!!
            a.objects shouldBe
                listOf("hartland.er.entity.item", "hartland.er.entity.store", "hartland.er.entity.store_sales")
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

        "sidecar: its positions and skin win; its extra key is dropped with a warning, never an issue (A-VL-3 a)" {
            val s = idx["sidecar"]!!
            s.positionsFrom shouldBe "sidecar"
            s.positions shouldBe
                mapOf(
                    "hartland.er.entity.item" to LayoutPoint(100.0, 120.0),
                    "hartland.er.entity.store" to LayoutPoint(300.0, 120.0),
                )
            s.skin shouldBe "er.crow"
            // the finding is the sidecar's, so it names the .ttrl, not the .ttrg
            idx.warnings
                .filter { it.path == "layouts/sidecar.ttrl" }
                .single()
                .reason shouldContain
                "hartland.er.entity.store_returns"
            idx.warnings.filter { it.path == "layouts/sidecar.ttrg" }.shouldBeEmpty()
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
            idx.warnings
                .filter { it.path == "layouts/auto_residue.ttrl" }
                .single()
                .reason shouldContain "mode: auto"
            idx.issues.map { it.path }.contains("layouts/auto_residue.ttrg") shouldBe false
        }

        "missing: an unknown ref is missing, a bare name two packages define is ambiguous with both candidates" {
            val m = idx["missing"]!!
            m.objects shouldBe listOf("hartland.er.entity.store")
            m.missing shouldBe listOf("hartland.er.entity.customer")
            m.ambiguous shouldBe
                listOf(LayoutAmbiguity("item", listOf("hartland.er.entity.item", "outlet.er.outlet.item")))
            m.mode shouldBe "auto"
            m.positionsFrom shouldBe null
        }

        "tables: a db layout — tables as package + catalog qname (A-VL-6), the foreign key ignored" {
            val d = idx["tables"]!!
            d.objects shouldBe listOf("hartland.db.dbo.item", "hartland.db.dbo.store_sales")
            d.ignored shouldBe listOf("hartland.db.dbo.fk_ss_item")
        }

        "a sidecar error is reported under the .ttrl, with the .ttrl's line, on one line" {
            copyOfFixture("vl-index-sidecar-error") { tree ->
                Files.writeString(
                    tree.resolve("layouts/sidecar.ttrl"),
                    "ttrl 1\n\ncanvas sidecar {\n  mode: sideways\n}\n",
                )
                val i = index(tree)
                i["sidecar"] shouldBe null
                i.issues.single { it.path == "layouts/sidecar.ttrl" }.toString() shouldBe
                    "layout layouts/sidecar.ttrl: line 4: `mode` must be `auto` or `manual`"
            }
        }

        "V-38: an unreadable layout file is an issue; the model and the other layouts are served" {
            copyOfFixture("vl-index-unreadable") { tree ->
                Files.write(tree.resolve("layouts/authored.ttrg"), byteArrayOf(0x67, 0xE9.toByte(), 0x0A))
                Files.write(tree.resolve("layouts/main_canvas.ttrl"), byteArrayOf(0x74, 0xE9.toByte(), 0x0A))
                val i = index(tree)
                i["authored"] shouldBe null
                i["main_canvas"] shouldBe null
                i["sidecar"]!!.positionsFrom shouldBe "sidecar"
                i.issues.map { it.toString() } shouldContainAll
                    listOf(
                        "layout layouts/authored.ttrg: could not be read: not UTF-8 text",
                        "layout layouts/main_canvas.ttrl: could not be read: not UTF-8 text",
                    )
            }
        }

        "the viewer's lenient sidecar shapes are served, not refused" {
            copyOfFixture("vl-index-lenient") { tree ->
                Files.writeString(
                    tree.resolve("layouts/sidecar.ttrl"),
                    "ttrl 1\ncanvas sidecar {\n  viewport: { x: 0, y: 0, zoom: 1 }\n  mode: manual\n" +
                        "  nodes: { hartland.er.entity.item: { x: 7, y: 8 } }\n  collapsed: [hartland.er.entity.item]\n}\n",
                )
                val s = index(tree)["sidecar"]!!
                s.positions shouldBe mapOf("hartland.er.entity.item" to LayoutPoint(7.0, 8.0))
                s.collapsed shouldBe listOf("hartland.er.entity.item")
            }
        }

        "no layout files ⇒ the empty index" {
            copyOfFixture("vl-index-bare") { bare ->
                bare.resolve("layouts").deleteRecursively()
                index(bare).all.shouldBeEmpty()
            }
        }

        "a source not asked for layouts ⇒ the empty index, and no layout issue or warning" {
            val snaps = listOf(FileBasedSource("fixture", 100, LocalFsStorage("fixture", fixture)).load())
            val i = LayoutIndex.build(snaps, reconciler.reconcile(snaps).model)
            i shouldBe LayoutIndex.EMPTY
        }

        "the refresher publishes the layouts in the same swap as the model (V-37)" {
            val tree = createTempDirectory("vl-refresh")
            try {
                fixture.copyToRecursively(tree, followLinks = false, overwrite = true)
                val source = source(tree)
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
                after.layouts.issues.map { it.path } shouldBe listOf("layouts/dup_a.ttrg", "layouts/dup_b.ttrg")
            } finally {
                tree.deleteRecursively()
            }
        }
    })
