// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.YamlFileSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** Review-388 F1: compare the closure's card ids with the authored layout and catalog on the same files. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ClosurePackageProvenanceSpec :
    StringSpec({
        fun Path.put(
            path: String,
            text: String,
        ) {
            resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent())
        }

        fun verify(source: SourceSnapshot) {
            source.errors.shouldBeEmpty()
            val loaded = ModelReconciler(ModelDescriptor("provenance", "provenance")).reconcile(listOf(source))
            loaded.errors.shouldBeEmpty()
            val authored = LayoutIndex.build(listOf(source), loaded.model)
            authored.issues.shouldBeEmpty()
            val registry = MetadataRegistry()
            registry.swap(loaded.model, ModelGraph.build(loaded.model), layouts = authored, files = loaded.files)
            val snapshot = registry.readOrError()
            val selected =
                snapshot.model.objectByQname().values.filter {
                    it.kind == "entity" &&
                        it.qname.name == "widget"
                }
            selected.size shouldBe 1
            val catalog = LayoutResolver.indexOf(snapshot, LayoutResolver.packagesFromFiles(loaded.files))
            val expected = listOf("sales.er.entity.widget")
            catalog.cards.filter { it.kind == "entity" }.map { it.fullQname } shouldBe expected
            authored["sales"]!!.objects shouldBe expected
            closureLayout(snapshot, selected).objects shouldBe expected
            closureLayout(snapshot, selected).objects shouldBe authored["sales"]!!.objects
        }

        fun Path.layout() =
            put(
                "layouts/sales.ttrg",
                """
            graph sales { model: er, objects: [sales.er.entity.widget] }
            """,
            )
        "a dialect file's second model keeps the storage-path package in closure card ids" {
            val root = Files.createTempDirectory("mw-f1-fanout")
            try {
                root.put("ttr.yaml", "profile: ./profiles/carry.yaml\ngrammar: '>=0.22 <1.0'\n")
                root.put("model.ignore", "profiles/\n")
                root.put(
                    "profiles/carry.yaml",
                    """
                    profile: carry/1
                    grammar: '>=0.22 <1.0'
                    operations:
                      - {op: package, mapping: {}, default: sales}
                      - {op: kinds, at: /tables, mapping: {table: table}}
                      - {op: kinds, at: /entities, mapping: {entity: entity}}
                    """,
                )
                root.put("sales/model.yaml", "tables:\n  row: {}\nentities:\n  widget: {}\n")
                root.put("layouts/sales.yaml", "layout: 1\nname: sales\nmodel: er\nobjects: [sales.er.entity.widget]\n")
                val source = YamlFileSource("fixture", 100, LocalFsStorage("fixture", root), layouts = true).load()
                source.errors.shouldBeEmpty()
                source.loadedFiles.map { it.schemaCode } shouldBe listOf("db", "er")
                source.loadedFiles
                    .last()
                    .storageFile.path shouldEndWith "model.yaml#er"
                verify(source)
            } finally {
                root.deleteRecursively()
            }
        }
        "a plain single-model TTR file keeps the same closure, layout and catalog card ids" {
            val root = Files.createTempDirectory("mw-f1-ttr")
            try {
                root.put("sales/model.ttr", "package sales\nmodel er\ndef entity widget { }\n")
                root.layout()
                verify(FileBasedSource("fixture", 100, LocalFsStorage("fixture", root), layouts = true).load())
            } finally {
                root.deleteRecursively()
            }
        }
    })
