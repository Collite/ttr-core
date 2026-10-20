// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** CN-P2 S2.6 — cn projects on disk, loaded the way Veles loads one (one `FileBasedSource`, reconciled). */
internal object DefinitionFixtures {
    /** The repository root (the directory holding `tests/conformance/fixtures`). */
    val repo: Path by lazy {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("tests/conformance/fixtures"))) return@lazy dir
            dir = dir.parent
        }
        error("no repo root above ${Paths.get("").toAbsolutePath()}")
    }

    /** Load [files] (path under the model root → source file) as one source and reconcile it. */
    fun load(files: Map<String, Path>): Model {
        val root = Files.createTempDirectory("cn-defs")
        for ((rel, from) in files) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, Files.readString(from))
        }
        val snapshot = FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = root)).load()
        return ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(snapshot)).model
    }

    /** Every file of [dir] under [pkgDir] (`hartland/defs`). */
    fun dir(
        dir: Path,
        pkgDir: String,
    ): Map<String, Path> = Files.list(dir).use { s -> s.sorted().toList() }.associateBy { "$pkgDir/${it.fileName}" }

    /** This module's definition fixtures (`resources/definitions`, package `hartland.defs`). */
    val defs: ResolvedCnModel by lazy {
        ResolvedCnModel.of(
            load(dir(repo.resolve("packages/kotlin/ttr-concept/src/test/resources/definitions"), "hartland/defs")),
        )
    }
}
