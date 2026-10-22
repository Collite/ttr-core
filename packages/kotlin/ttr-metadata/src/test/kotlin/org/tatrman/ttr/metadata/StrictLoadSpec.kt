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
            strict.report.droppedFiles.map {
                java.nio.file.Path
                    .of(it)
                    .fileName
                    .toString()
            } shouldBe listOf("bad.ttrm")
            strict.report.issues.any { it.code == "dropped-file" && it.severity == LoadIssue.Severity.ERROR } shouldBe
                true
            strict.model!!.objectByQname().keys shouldBe legacy.model!!.objectByQname().keys
            strict.issues shouldBe legacy.issues
        }
        "different priority sources still override without becoming strict duplicates" {
            val rootA = Files.createTempDirectory("strict-priority-a")
            val rootB = Files.createTempDirectory("strict-priority-b")
            Files.writeString(rootA.resolve("a.ttrm"), item)
            Files.writeString(rootB.resolve("b.ttrm"), item)
            val a = FileBasedSource("a", 100, LocalFsStorage("a", rootA))
            val b = FileBasedSource("b", 50, LocalFsStorage("b", rootB))
            val result = MetadataLoader(listOf(a, b)).load(LoadOptions(strict = true))
            result.report.ok shouldBe true
        }
        "an unreadable source reports a failure rather than an apparently clean strict load" {
            val result = MetadataLoader(ModelSource { error("storage unavailable") }).load(LoadOptions(strict = true))
            result.report.ok shouldBe false
            result.report.issues
                .single()
                .message
                .contains("storage unavailable") shouldBe true
            (result.model == null) shouldBe true
        }
        "strict detects duplicates lost by source maps; permissive loading is unchanged" {
            val loader = loader(mapOf("a.ttrm" to item, "b.ttrm" to item))
            loader.load().report.issues shouldBe emptyList()
            val strict = loader.load(LoadOptions(strict = true))
            strict.report.issues.any { it.code == "reconcile-error" && it.message.contains("item") } shouldBe true
        }
    })
