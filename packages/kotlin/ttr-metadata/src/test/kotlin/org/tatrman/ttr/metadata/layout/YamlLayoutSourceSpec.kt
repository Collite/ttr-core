// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.*
import org.tatrman.ttr.parser.model.LayoutPoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** MW S4.3 T1–T3: invented ids; no client model/layout content is copied into fixtures. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class YamlLayoutSourceSpec :
    StringSpec({
        val model =
            """
            ttr: '0.22'
            package: demo.widgets
            model: er
            defs:
              - {kind: entity, id: widget, properties: {}}
              - {kind: entity, id: crate, properties: {}}
            """.trimIndent()
        val authored =
            """
            layout: 1
            name: inventory
            model: er
            objects: [demo.widgets.er.entity.widget, demo.widgets.er.entity.crate]
            positions:
              demo.widgets.er.entity.widget: {x: 12.5, y: -5.5}
              demo.widgets.er.entity.crate: {x: 300, y: 20}
            """.trimIndent()
        val twin =
            """
            graph inventory {
              model: er,
              objects: [demo.widgets.er.entity.widget, demo.widgets.er.entity.crate],
              layout: { nodes: {
                demo.widgets.er.entity.widget: {x: 12.5, y: -5.5},
                demo.widgets.er.entity.crate: {x: 300, y: 20}
              } }
            }
            """.trimIndent()

        fun Path.put(
            name: String,
            text: String,
        ) {
            resolve(name).also { it.parent.createDirectories() }.writeText(text)
        }

        fun tree(block: (Path) -> Unit) {
            val root = Files.createTempDirectory("mw-s43")
            try {
                root.put("demo/widgets/model.yaml", model)
                block(root)
            } finally {
                root.deleteRecursively()
            }
        }

        fun load(
            root: Path,
            layouts: Boolean = true,
        ): Pair<SourceSnapshot, LayoutIndex> {
            val source = YamlFileSource("t", 100, LocalFsStorage("t", root), layouts = layouts).load()
            source.errors.shouldBeEmpty()
            val result = ModelReconciler(ModelDescriptor("t", "t")).reconcile(listOf(source))
            return source to LayoutIndex.build(listOf(source), result.model)
        }
        "a native named layout equals its ttrg twin and the loaded model excludes layouts" {
            tree { root ->
                root.put("layouts/nested/inventory.yaml", authored)
                val (source, index) = load(root)
                source.loadedFiles.size shouldBe 1
                source.entities.size shouldBe 2
                source.layoutFiles.map { it.source } shouldBe listOf("layouts/nested/inventory.yaml")
                val model = ModelReconciler(ModelDescriptor("t", "t")).reconcile(listOf(source)).model
                val ttr = source.copy(layoutFiles = listOf(LayoutFileRef("inventory.ttrg", "inventory.ttrg", twin)))
                val expected = LayoutIndex.build(listOf(ttr), model)["inventory"]!!
                index["inventory"]!!.copy(source = expected.source) shouldBe expected
                index.issues.shouldBeEmpty()
                index.warnings.shouldBeEmpty()
            }
        }
        "an unresolved card warns at the authored span and the remaining layout is served" {
            tree { root ->
                root.put(
                    "layouts/inventory.yaml",
                    authored.replace("demo.widgets.er.entity.widget", "demo.widgets.er.entity.absent"),
                )
                val (_, index) = load(root)
                index["inventory"]!!.objects shouldBe listOf("demo.widgets.er.entity.crate")
                index["inventory"]!!.positions.keys shouldBe setOf("demo.widgets.er.entity.crate")
                index["inventory"]!!.missing shouldBe listOf("demo.widgets.er.entity.absent")
                index.issues.shouldBeEmpty()
                val warning = index.warnings.single()
                warning.path shouldBe "layouts/inventory.yaml"
                warning.reason shouldContain "line 4, column 11: vl/layout-unresolved: 'demo.widgets.er.entity.absent'"
            }
        }
        "agent layouts and hidden paths are never read; named YAML layouts respect the opt-in" {
            tree { root ->
                root.put("layouts/agent/broken.yaml", "not: a layout")
                root.put("layouts/.hidden/broken.yaml", "not: a layout")
                root.put("layouts/nested/inventory.yml", authored)
                val reads = mutableListOf<String>()
                val delegate = LocalFsStorage("t", root)
                val recording =
                    object : ModelStorage by delegate {
                        override fun read(file: StorageFile) = delegate.read(file).also { reads += file.underRoot() }
                    }
                val source = YamlFileSource("t", 100, recording, layouts = true).load()
                source.errors.shouldBeEmpty()
                source.layoutFiles.map { it.source } shouldBe listOf("layouts/nested/inventory.yml")
                reads.any { "/agent/" in it || "/.hidden/" in it || it.endsWith(".ttrg") } shouldBe false
                load(root, layouts = false).second shouldBe LayoutIndex.EMPTY
            }
        }
        "a yaml source serves YAML and TTR graphs together with sidecar positions" {
            tree { root ->
                root.put("layouts/a.yaml", authored.replace("name: inventory", "name: a"))
                root.put("layouts/b.ttrg", twin.replace("graph inventory", "graph b"))
                root.put(
                    "layouts/b.ttrl",
                    """
                    ttrl 1
                    canvas b {
                      mode: manual
                      nodes: { "demo.widgets.er.entity.widget": {x: 99, y: 101} }
                    }
                    """.trimIndent(),
                )
                val (source, index) = load(root)
                source.layoutFiles.map { it.format } shouldBe listOf(LayoutFileFormat.YAML, LayoutFileFormat.TTRG)
                index.all.map { it.name } shouldBe listOf("a", "b")
                index["b"]!!.positions["demo.widgets.er.entity.widget"] shouldBe LayoutPoint(99.0, 101.0)
                index["b"]!!.positionsFrom shouldBe "sidecar"
                index.issues.shouldBeEmpty()
            }
        }
        "a duplicate name across YAML and TTR graphs is an issue on both files and neither is served" {
            tree { root ->
                root.put("layouts/a.yaml", authored.replace("name: inventory", "name: a"))
                root.put("layouts/a.ttrg", twin.replace("graph inventory", "graph a"))
                val (_, index) = load(root)
                index.all.shouldBeEmpty()
                index.issues.map { it.path }.toSet() shouldBe setOf("layouts/a.yaml", "layouts/a.ttrg")
                index.issues.forEach { it.reason shouldContain "the name a is also declared by" }
            }
        }
        "layout collection reuses model.ignore and the tree profile whole-file ignores before reading layouts" {
            tree { root ->
                root.put("layouts/main.yaml", authored)
                root.put("layouts/drafts/d.yaml", "not: [valid")
                root.put("layouts/skipped/bad.yaml", "not: [valid")
                root.put("layouts/skipped/bad.ttrg", "not a graph")
                root.put("model.ignore", "profiles/\nlayouts/drafts/**\n")
                root.put("ttr.yaml", "profile: ./profiles/layouts.yaml\ngrammar: '>=0.22 <1.0'\n")
                root.put(
                    "profiles/layouts.yaml",
                    """
                    profile: layouts/1
                    grammar: '>=0.22 <1.0'
                    operations:
                      - {op: ignore, files: [layouts/skipped/**]}
                    """.trimIndent(),
                )
                val delegate = LocalFsStorage("t", root)
                val reads = mutableListOf<String>()
                val storage =
                    object : ModelStorage by delegate {
                        override fun read(file: StorageFile): String {
                            reads += file.underRoot()
                            return delegate.read(file)
                        }
                    }
                val source = YamlFileSource("t", 100, storage, layouts = true).load()
                source.errors.shouldBeEmpty()
                source.layoutFiles.map { it.source } shouldBe listOf("layouts/main.yaml")
                val result = ModelReconciler(ModelDescriptor("t", "t")).reconcile(listOf(source))
                val index = LayoutIndex.build(listOf(source), result.model)
                index.all.map { it.name } shouldBe listOf("inventory")
                index.issues.shouldBeEmpty()
                reads.count { it == "model.ignore" } shouldBe 1
                reads.none { it.startsWith("layouts/drafts/") || it.startsWith("layouts/skipped/") } shouldBe true
            }
        }
        listOf(false, true).forEach { unreadable ->
            "an ${if (unreadable) "unreadable" else "invalid"} model.ignore fails closed for every layout format" {
                tree { root ->
                    root.put("layouts/main.yaml", authored)
                    root.put("layouts/main.ttrg", twin)
                    root.put("model.ignore", "!unsupported-reinclude\n")
                    val delegate = LocalFsStorage("t", root)
                    val reads = mutableListOf<String>()
                    val storage =
                        object : ModelStorage by delegate {
                            override fun read(file: StorageFile): String {
                                val path = file.underRoot()
                                reads += path
                                if (path == "model.ignore" && unreadable) throw IOException("fixture unreadable")
                                return delegate.read(file)
                            }
                        }
                    val source = YamlFileSource("t", 100, storage, layouts = true).load()
                    source.layoutFiles.shouldBeEmpty()
                    source.errors.first { it.file == root.resolve("model.ignore").toString() }.message shouldContain
                        "ys/profile-invalid"
                    reads.count { it == "model.ignore" } shouldBe 1
                    reads.none { it.startsWith("layouts/") } shouldBe true
                }
            }
        }
        "a yaml source without layouts serves zero layouts without an error" {
            tree { root -> load(root).second shouldBe LayoutIndex.EMPTY }
        }
        "a malformed layout is isolated and duplicate names are never chosen by file order" {
            tree { root ->
                root.put("layouts/inventory.yaml", authored)
                root.put("layouts/other.yaml", authored)
                root.put("layouts/bad.yaml", authored + "\ntitle: unsupported\n")
                val (source, index) = load(root)
                source.entities.size shouldBe 2
                index.all.shouldBeEmpty()
                index.issues.size shouldBe 3
                index.issues.single { it.path == "layouts/bad.yaml" }.reason shouldContain "line 8, column 1"
            }
        }
    })
