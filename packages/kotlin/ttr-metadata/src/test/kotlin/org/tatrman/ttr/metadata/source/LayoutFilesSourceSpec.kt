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
 * VL-P2 T1 (contracts V-37) — the source load collects the layout files under the model root: every `.ttrg`
 * with its sibling `.ttrl`, never parsed as model.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class LayoutFilesSourceSpec :
    StringSpec({
        // the test working dir is the module dir (packages/kotlin/ttr-metadata)
        val fixture = Path.of("src/test/resources/layout-fixture").toAbsolutePath()

        fun load(root: Path) = FileBasedSource("fixture", 100, LocalFsStorage("fixture", root)).load()

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
                )
            snap.layoutFiles.filter { it.sidecarPath != null }.map { it.source } shouldBe
                listOf("layouts/auto_residue.ttrg", "layouts/main_canvas.ttrg", "layouts/sidecar.ttrg")
            val sidecar = snap.layoutFiles.single { it.source == "layouts/sidecar.ttrg" }
            sidecar.sidecarPath shouldBe fixture.resolve("layouts/sidecar.ttrl").toString()
            sidecar.content shouldStartWith "graph sidecar {"
            sidecar.sidecarContent!! shouldStartWith "ttrl 1"
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

        "a changed layout file changes the filesystem version, so a refresh picks it up" {
            val tree = createTempDirectory("vl-version")
            try {
                fixture.copyToRecursively(tree, followLinks = false, overwrite = true)
                val storage = LocalFsStorage("fixture", tree)
                val before = storage.fetchVersion()
                val ttrg = tree.resolve("layouts/authored.ttrg")
                Files.setLastModifiedTime(ttrg, FileTime.fromMillis(Files.getLastModifiedTime(ttrg).toMillis() + 5_000))
                storage.fetchVersion() shouldNotBe before
                val ttrl = tree.resolve("layouts/sidecar.ttrl")
                val mid = storage.fetchVersion()
                Files.setLastModifiedTime(ttrl, FileTime.fromMillis(Files.getLastModifiedTime(ttrl).toMillis() + 5_000))
                storage.fetchVersion() shouldNotBe mid
            } finally {
                tree.deleteRecursively()
            }
        }
    })
