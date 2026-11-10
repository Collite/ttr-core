// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.PackageLayout
import org.tatrman.ttr.metadata.layout.LayoutResolver
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttrp.project.ProjectModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Loads a TTR-M model repo into a ttr-metadata [RegistrySnapshot] — offline, no
 * service (D-g). ALL `.ttrm` reading in ttrp-frontend goes through ttr-metadata;
 * this is the one place that touches the filesystem for models. Mirrors the shared
 * `MetadataFixtures.snapshotOf`, kept in main so the CLI can load a project's models
 * without depending on test fixtures.
 */
object ModelRepo {
    /**
     * Loads [modelsRoot] (the `models/` dir) plus any [extraRoots] (`[ttrp] extra-model-roots`, AG-P0)
     * into one model through [ProjectModel] — one file source per root, the stock roles ahead of them.
     * Returns null when none of the roots exists.
     */
    fun snapshotOf(
        modelsRoot: Path,
        extraRoots: List<Path> = emptyList(),
    ): RegistrySnapshot? = indexOf(modelsRoot, extraRoots)?.snapshot

    fun indexOf(
        modelsRoot: Path,
        extraRoots: List<Path> = emptyList(),
    ): ModelIndex? {
        val roots = (listOf(modelsRoot) + extraRoots).filter { Files.isDirectory(it) }
        if (roots.isEmpty()) return null
        // The checker is tolerant: it reports its own diagnostics, so the load verdict is not a gate here. The
        // model is the one `ttrp emit` gates on (S1.5.4): the stock roles first, every root a peer source.
        val loaded = ProjectModel.load(roots, PackageLayout.Flexible)
        val model = loaded.model ?: return null
        val snapshot =
            RegistrySnapshot(
                model = model,
                graph = ModelGraph.build(model),
                swappedAt = Instant.EPOCH,
                warnings = emptyList(),
            )
        return ModelIndex(snapshot, LayoutResolver.packagesFromFiles(loaded.files))
    }
}
