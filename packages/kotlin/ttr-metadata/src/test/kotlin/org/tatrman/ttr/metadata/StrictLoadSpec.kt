// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelSource
import java.nio.file.Files

class StrictLoadSpec :
    StringSpec({
        fun loader(files: Map<String, String>): MetadataLoader {
            val root = Files.createTempDirectory("strict-load")
            files.forEach { (name, text) -> Files.writeString(root.resolve(name), text) }
            return MetadataLoader(FileBasedSource("test", 100, LocalFsStorage("test", root)))
        }
        val item = "model er\ndef entity item { attributes: [def attribute id { type: int }] }"

        "strict load collects dropped files and keeps the good part of the model" {
            val loader = loader(mapOf("good.ttrm" to item, "bad.ttrm" to "model er\ndef entity broken {"))
            val legacy = loader.load()
            val strict = loader.load(LoadOptions(strict = true))
            strict.report!!.droppedFiles.map {
                java.nio.file.Path
                    .of(it)
                    .fileName
                    .toString()
            } shouldBe listOf("bad.ttrm")
            strict.report!!.issues.any { it.code == "dropped-file" && it.severity == LoadIssue.Severity.ERROR } shouldBe
                true
            strict.model!!.objectByQname().keys shouldBe legacy.model!!.objectByQname().keys
            strict.issues shouldBe legacy.issues
        }
        "strict report grades package mismatches without changing legacy issues" {
            val loader = loader(mapOf("item.ttrm" to "package hartland\n$item"))
            val legacy = loader.load()
            legacy.report shouldBe null
            for (layout in PackageLayout.entries) {
                val result = loader.load(LoadOptions(strict = true, packageLayout = layout))
                result.issues shouldBe legacy.issues
                result.model!!.objectByQname().keys shouldBe legacy.model!!.objectByQname().keys
                val issues = result.report!!.issues.filter { it.code == "package-mismatch" }
                when (layout) {
                    PackageLayout.Off -> issues shouldBe emptyList()
                    PackageLayout.Flexible -> issues.single().severity shouldBe LoadIssue.Severity.WARNING
                    PackageLayout.Strict -> issues.single().severity shouldBe LoadIssue.Severity.ERROR
                }
                result.report!!.ok shouldBe (layout != PackageLayout.Strict)
            }
        }
        "inline pairs do not add definition duplicates over existing mapping diagnostics" {
            val loader =
                loader(
                    mapOf(
                        "db.ttrm" to
                            "model db namespace dbo\ndef table item { columns: [def column id { type: int }] }",
                        "er.ttrm" to
                            "model er\ndef entity item { binding: { target: { table: db.dbo.item } }, " +
                            "attributes: [def attribute id { type: int }] }",
                        "binding.ttrm" to
                            "model binding\ndef er2db_entity item { entity: er.entity.item, target: { table: db.dbo.item } }",
                    ),
                )
            val report = loader.load(LoadOptions(strict = true)).report!!
            report.issues.filter { it.message.startsWith("Duplicate definition") } shouldBe emptyList()
            // The loader's separate duplicate-mapping policy still rejects the two authored bindings.
            report.issues.count {
                it.code == "duplicate-qname" &&
                    it.message.startsWith(
                        "ttr/duplicate-mapping",
                    )
            } shouldBe
                2
        }
        "semantic errors keep their category code" {
            val result =
                loader(
                    mapOf(
                        "er.ttrm" to
                            "model er\ndef entity item { attributes: [def attribute id { type: int, " +
                            "semantics { role: nonsense } }] }",
                    ),
                ).load(LoadOptions(strict = true))
            result.report!!.issues.any {
                it.code == "semantics-invalid" && it.severity == LoadIssue.Severity.ERROR
            } shouldBe
                true
        }
        "different priority sources still override without becoming strict duplicates" {
            val rootA = Files.createTempDirectory("strict-priority-a")
            val rootB = Files.createTempDirectory("strict-priority-b")
            Files.writeString(rootA.resolve("a.ttrm"), item)
            Files.writeString(rootB.resolve("b.ttrm"), item)
            val a = FileBasedSource("a", 100, LocalFsStorage("a", rootA))
            val b = FileBasedSource("b", 50, LocalFsStorage("b", rootB))
            val result = MetadataLoader(listOf(a, b)).load(LoadOptions(strict = true))
            result.report!!.ok shouldBe true
        }
        "an unreadable source reports a failure rather than an apparently clean strict load" {
            val result = MetadataLoader(ModelSource { error("storage unavailable") }).load(LoadOptions(strict = true))
            result.report!!.ok shouldBe false
            result.report!!
                .issues
                .single()
                .message
                .contains("storage unavailable") shouldBe true
            (result.model == null) shouldBe true
        }
        "strict detects duplicates lost by source maps; permissive loading is unchanged" {
            val loader = loader(mapOf("a.ttrm" to item, "b.ttrm" to item))
            loader.load().report shouldBe null
            val strict = loader.load(LoadOptions(strict = true))
            strict.report!!.issues.any { it.code == "duplicate-qname" && it.message.contains("item") } shouldBe true
        }
    })
