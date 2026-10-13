// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/**
 * VL-P2 T1 (contracts V-37) — a source asked for layouts collects the layout files under the model root: every
 * `.ttrg` with its sibling `.ttrl`, never parsed as model. A source not asked (the default) reads none of them,
 * and its version ignores them.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LayoutFilesSourceSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-metadata)
        val fixture = Path.of("src/test/resources/layout-fixture").toAbsolutePath()

        fun load(
            root: Path,
            layouts: Boolean = true,
        ) = FileBasedSource("fixture", 100, LocalFsStorage("fixture", root), layouts = layouts).load()

        fun objectCount(snapshot: SourceSnapshot) =
            ModelReconciler(ModelDescriptor("t", "t"))
                .reconcile(listOf(snapshot))
                .model
                .objectByQname()
                .size

        "the layout files are collected beside the model, each .ttrg with its sibling .ttrl" {
            val snap = load(fixture)
            snap.layoutFiles.map { it.source } shouldBe
                listOf(
                    "layouts/authored.ttrg",
                    "layouts/auto_residue.ttrg",
                    "layouts/broken.ttrg",
                    "layouts/dup_a.ttrg",
                    "layouts/dup_b.ttrg",
                    "layouts/main_canvas.ttrg",
                    "layouts/missing.ttrg",
                    "layouts/sidecar.ttrg",
                    "layouts/tables.ttrg",
                )
            snap.layoutFiles.filter { it.sidecarPath != null }.map { it.sidecarSource } shouldBe
                listOf("layouts/auto_residue.ttrl", "layouts/main_canvas.ttrl", "layouts/sidecar.ttrl")
            val sidecar = snap.layoutFiles.single { it.source == "layouts/sidecar.ttrg" }
            sidecar.sidecarPath shouldBe fixture.resolve("layouts/sidecar.ttrl").toString()
            sidecar.content shouldStartWith "graph sidecar {"
            sidecar.sidecarContent!! shouldStartWith "ttrl 1"
            snap.layoutFiles.mapNotNull { it.unreadable }.shouldBeEmpty()
        }

        "a source not asked for layouts (the default) collects none" {
            val snap = load(fixture, layouts = false)
            snap.layoutFiles.shouldBeEmpty()
            snap.errors shouldBe emptyList()
            objectCount(snap) shouldBe objectCount(load(fixture))
        }

        "V-38: a layout file that cannot be read is that file's failure, never the load's" {
            val tree = createTempDirectory("vl-unreadable")
            try {
                fixture.copyToRecursively(tree, followLinks = false, overwrite = true)
                // Latin-1 bytes for "graph café { … }" — not UTF-8
                Files.write(
                    tree.resolve("layouts/latin1.ttrg"),
                    "graph caf".toByteArray() + byteArrayOf(0xE9.toByte()) + " {\n  model: er\n}\n".toByteArray(),
                )
                Files.write(
                    tree.resolve("layouts/sidecar.ttrl"),
                    byteArrayOf(0x74, 0x74, 0x72, 0x6C, 0x20, 0xE8.toByte()),
                )
                val snap = load(tree)
                snap.errors shouldBe emptyList()
                snap.layoutFiles.single { it.source == "layouts/latin1.ttrg" }.unreadable shouldBe
                    LayoutReadFailure("layouts/latin1.ttrg", "not UTF-8 text")
                snap.layoutFiles.single { it.source == "layouts/sidecar.ttrg" }.unreadable shouldBe
                    LayoutReadFailure("layouts/sidecar.ttrl", "not UTF-8 text")
                snap.layoutFiles.single { it.source == "layouts/authored.ttrg" }.unreadable shouldBe null
            } finally {
                tree.deleteRecursively()
            }
        }

        "the model is the one a tree without the layout files gives — no .ttrg/.ttrl is parsed as model" {
            val bare = createTempDirectory("vl-no-layouts")
            try {
                fixture.copyToRecursively(bare, followLinks = false, overwrite = true)
                bare.resolve("layouts").deleteRecursively()
                val with = load(fixture)
                val without = load(bare)
                without.layoutFiles.shouldBeEmpty()
                objectCount(with) shouldBe objectCount(without)
                with.errors shouldBe emptyList()
                with.loadedFiles.map { it.storageFile.path.substringAfterLast('/') }.sorted() shouldBe
                    listOf("db.ttr", "er.ttr", "er.ttr")
            } finally {
                bare.deleteRecursively()
            }
        }

        "a changed layout file is a new version for a source that reads layouts, and only for one" {
            val tree = createTempDirectory("vl-version")
            try {
                fixture.copyToRecursively(tree, followLinks = false, overwrite = true)

                fun touch(path: String) {
                    val f = tree.resolve(path)
                    Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5_000))
                }
                val withLayouts = load(tree).version
                val modelOnly = load(tree, layouts = false).version
                LocalFsStorage("fixture", tree).fetchVersion() shouldBe modelOnly

                touch("layouts/authored.ttrg")
                val afterTtrg = load(tree).version
                afterTtrg shouldNotBe withLayouts
                touch("layouts/sidecar.ttrl")
                load(tree).version shouldNotBe afterTtrg
                // a designer's .ttrg/.ttrl write is no model change where nobody serves layouts
                load(tree, layouts = false).version shouldBe modelOnly

                touch("hartland/er.ttr")
                load(tree, layouts = false).version shouldNotBe modelOnly
            } finally {
                tree.deleteRecursively()
            }
        }
    })
