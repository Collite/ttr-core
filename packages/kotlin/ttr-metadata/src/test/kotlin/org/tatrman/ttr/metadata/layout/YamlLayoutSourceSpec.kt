// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.*
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
                root.put("layouts/ignored.ttrg", "broken graph")
                root.put("model.ignore", "layouts/\n") // content ignores must not suppress the separate layout reader
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
