// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.layout.LayoutResolver
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
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
     * into one model — one file source per root, so each keeps its own package = directory rule.
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
        val sources =
            roots.mapIndexed { i, root ->
                // Resolve symlinks: `Files.walk` does not descend a symlink used as the walk
                // root, so a `models/` symlink (the test fixture's link to the shared models)
                // must be canonicalised first.
                val realRoot = runCatching { root.toRealPath() }.getOrDefault(root)
                val id = if (i == 0) "ttrp" else "ttrp-$i"
                FileBasedSource(
                    sourceId = id,
                    priority = 100 - i,
                    storage = LocalFsStorage(id = id, rootPath = realRoot),
                )
            }
        return runCatching {
            val snapshots = sources.map { it.load() }
            val reconciled = ModelReconciler(ModelDescriptor(id = "model", name = "model")).reconcile(snapshots)
            val model = reconciled.model
            val snapshot =
                RegistrySnapshot(
                    model = model,
                    graph = ModelGraph.build(model),
                    swappedAt = Instant.EPOCH,
                    warnings = emptyList(),
                )
            ModelIndex(snapshot, LayoutResolver.packagesFromFiles(reconciled.files))
        }.getOrNull()
    }
}
