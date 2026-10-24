// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.registry

import org.tatrman.ttr.metadata.export.DefinitionSet
import org.tatrman.ttr.metadata.export.DefinitionGraph
import org.tatrman.ttr.metadata.export.ExportGrammar
import org.tatrman.ttr.metadata.source.LoadedFile
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.layout.LayoutIndex
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.LoadWarning
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the current [RegistrySnapshot] under an `AtomicReference` for
 * lock-free reads. All writes go through [swap], which atomically replaces
 * the snapshot and notifies listeners.
 *
 * Per-request consumers should capture the snapshot once at the start of the
 * request and hold the reference for its lifetime — that way an in-flight
 * read isn't disturbed by a refresh-driven swap mid-call.
 */
class MetadataRegistry {
    private val ref = AtomicReference<RegistrySnapshot?>(null)
    private val listeners = CopyOnWriteArrayList<(RegistrySnapshot) -> Unit>()

    fun read(): RegistrySnapshot? = ref.get()

    @Synchronized
    fun swap(
        model: Model,
        graph: ModelGraph,
        lastWarnings: List<LoadWarning> = emptyList(),
        layouts: LayoutIndex = LayoutIndex.EMPTY,
        files: List<LoadedFile> = emptyList(),
        exportSidecars: Map<String, String> = emptyMap(),
    ) {
        val definitions = DefinitionSet.from(files, model, ExportGrammar.version, model.version.value)
        // The id history is a set. Legacy typed estates can contain colliding external qnames;
        // do not let export identity validation block their normal registry refresh.
        val definitionGraph =
            DefinitionGraph.build(
                definitions.copy(entries = definitions.entries.distinctBy { it.qname }),
            )
        val ids = (definitionGraph.nodes.map { it.id } + definitionGraph.edges.mapNotNull { it.id }).toSet()
        // Publish history with the snapshot, so in-flight requests never observe a newer ring.
        val history = LinkedHashMap(ref.get()?.recentIdSets.orEmpty())
        history.remove(model.version.value)
        history[model.version.value] = ids
        while (history.size > 8) history.remove(history.keys.first())
        val snapshot =
            RegistrySnapshot(
                model = model,
                graph = graph,
                swappedAt = Instant.now(),
                warnings = lastWarnings,
                layouts = layouts,
                definitions = definitions,
                recentIdSets = history.toMap(),
                exportSidecars = exportSidecars.toMap(),
            )
        ref.set(snapshot)
        listeners.forEach { it.runCatching { invoke(snapshot) } }
    }

    fun addListener(listener: (RegistrySnapshot) -> Unit) {
        listeners += listener
    }

    /** Convenience for callers that require a snapshot to exist. */
    fun readOrError(): RegistrySnapshot = read() ?: error("MetadataRegistry has no snapshot — service is not ready yet")
}

data class RegistrySnapshot(
    val model: Model,
    val graph: ModelGraph,
    val swappedAt: Instant,
    val warnings: List<LoadWarning>,
    /** the layouts of [model] (VL V-37), built from the same loads and published in the same swap */
    val layouts: LayoutIndex = LayoutIndex.EMPTY,
    val definitions: DefinitionSet = DefinitionSet.from(emptyList(), model, ExportGrammar.version, model.version.value),
    val recentIdSets: Map<String, Set<String>> = emptyMap(),
    val exportSidecars: Map<String, String> = emptyMap(),
)
