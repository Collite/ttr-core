// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.extract

import io.kotest.matchers.collections.shouldBeEmpty
import org.tatrman.concept.DefinitionFixtures
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.concept.cli.load
import java.nio.file.Files
import java.nio.file.Path

/** DX-P0 S0.3 — the extraction slice world (`resources/worlds/hartland-dx/`) and throwaway worlds written from text. */
internal object ExtractWorlds {
    val dir: Path =
        DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources/worlds/hartland-dx")

    /** The world as `ttr cn render-types` loads it (the stock vocabulary ahead of it) — it must load clean. */
    fun model(root: Path = dir): ResolvedCnModel {
        val (model, errors) = load(root, "extract-spec")
        errors.shouldBeEmpty()
        return ResolvedCnModel.of(model)
    }

    /** A world written from [files] (path → model text) into a fresh temp dir. */
    fun of(files: Map<String, String>): ResolvedCnModel {
        val root = Files.createTempDirectory("extract-world")
        for ((rel, text) in files) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }
        return model(root)
    }
}
