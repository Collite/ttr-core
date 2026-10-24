// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.emit.spi.EmitResult
import java.nio.file.Files

class ExportFolderWriterSpec :
    StringSpec({
        fun result(vararg extra: Pair<String, ByteArray>) =
            EmitResult(
                sortedMapOf(
                    "README.md" to "native".toByteArray(),
                    "conformance.json" to "{}".toByteArray(),
                    *extra,
                ),
            )
        "export publishes fixtures and refuses to overwrite an existing directory" {
            val folder = Files.createTempDirectory("export-writer").resolve("native.export")
            ExportFolderWriter.write(folder, "{}", result(), mapOf("tests/pinned.yaml" to "case: pinned".toByteArray()))
            Files.readString(folder.resolve("tests/pinned.yaml")) shouldBe "case: pinned"
            shouldThrow<IllegalArgumentException> { ExportFolderWriter.write(folder, "changed", result(), emptyMap()) }
            Files.readString(folder.resolve("manifest.json")) shouldBe "{}"
        }
        "invalid plugin paths fail before the output directory is created" {
            val root = Files.createTempDirectory("export-paths")
            val folder = root.resolve("native.export")
            shouldThrow<IllegalArgumentException> {
                ExportFolderWriter.write(
                    folder,
                    "{}",
                    result(
                        "../escaped" to byteArrayOf(),
                    ),
                    emptyMap(),
                )
            }
            Files.exists(folder) shouldBe false
            Files.exists(root.resolve("escaped")) shouldBe false
            Files.list(root).use { it.count() } shouldBe 0L
        }
        "bundle file symlinks cannot escape the bundle" {
            val root = Files.createTempDirectory("export-bundle")
            val outside = Files.createTempFile("outside-bundle", ".yaml")
            Files.createSymbolicLink(root.resolve("pinned.yaml"), outside)
            shouldThrow<IllegalArgumentException> { ExportFolderWriter.bundleFile(root, "pinned.yaml") }
        }
    })
