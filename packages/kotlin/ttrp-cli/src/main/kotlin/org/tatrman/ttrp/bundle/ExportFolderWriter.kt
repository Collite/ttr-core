// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import org.tatrman.ttrp.emit.spi.CoreOwnedPaths
import org.tatrman.ttrp.emit.spi.EmitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Validate first; publish a complete export directory only after all bytes have been written. */
object ExportFolderWriter {
    fun write(
        folder: Path,
        manifest: String,
        result: EmitResult,
        fixtures: Map<String, ByteArray>,
    ) {
        CoreOwnedPaths.check(result)
        require(result.files.keys.containsAll(listOf("README.md", "conformance.json"))) {
            "recipe plugin omitted README.md or conformance.json"
        }
        require(result.files.keys.none { it.startsWith("tests/") }) { "plugin must not overwrite golden fixtures" }
        require(fixtures.keys.all { it.startsWith("tests/") }) { "bundle fixture must be under tests/" }
        (result.files.keys + fixtures.keys).forEach { safePath(folder, it) }
        require(!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            "export folder already exists: $folder (choose an empty --out)"
        }
        Files.createDirectories(folder.parent)
        val stage = Files.createTempDirectory(folder.parent, ".ttrp-export-")
        try {
            (
                result.files + fixtures + (
                    "manifest.json" to
                        manifest.toByteArray(
                            Charsets.UTF_8,
                        )
                )
            ).toSortedMap().forEach { (relative, bytes) ->
                val path = safePath(stage, relative)
                Files.createDirectories(path.parent)
                Files.write(path, bytes)
            }
            Files.move(stage, folder)
        } finally {
            stage.toFile().deleteRecursively()
        }
    }

    fun safePath(
        root: Path,
        relative: String,
    ): Path {
        val rel = Path.of(relative)
        require(
            relative.isNotBlank() &&
                !relative.contains('\\') &&
                !rel.isAbsolute &&
                rel.none { it.toString() in setOf(".", "..") },
        ) { "invalid export path $relative" }
        val path = root.resolve(rel).normalize()
        require(path.startsWith(root.normalize()) && path != root.normalize()) { "path escapes export: $relative" }
        return path
    }

    fun bundleFile(
        root: Path,
        relative: String,
    ): Path {
        val path = safePath(root, relative)
        require(Files.isRegularFile(path) && path.toRealPath().startsWith(root.toRealPath())) {
            "bundle path escapes bundle or is not a file: $relative"
        }
        return path
    }
}
