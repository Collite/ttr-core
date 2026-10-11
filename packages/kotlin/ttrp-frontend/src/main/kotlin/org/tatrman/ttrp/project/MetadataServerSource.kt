// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.project

import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.snapshot.SnapshotArchiveStorage
import org.tatrman.ttr.snapshot.SnapshotCache
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/** Compile mode against a connected binding (contracts §3 flags). */
enum class LockMode { CONNECTED, FROZEN, OFFLINE }

/** Recorded on the compile record (PL-P1.S3, contracts §5): was this an offline compile, from what. */
data class Staleness(
    val offline: Boolean,
    val servedFromCache: List<String> = emptyList(),
)

data class MetadataServerLoad(
    val snapshot: SourceSnapshot,
    val diagnostics: List<TtrpDiagnostic>,
    /** Pinned-but-absent archive ids (the `--frozen` failure set). */
    val missing: List<String>,
    val staleness: Staleness,
)

/**
 * The ② connected `ModelSource` (contracts §3, B-5): resolves canon **only from the cache** per the
 * `ttr.lock` pins — **no network in `load()`, ever** (`ttr fetch` populates the cache out-of-band).
 * A pinned archive missing from the cache is a structured `TTRP-LCK-002` naming the id, never a throw.
 */
class MetadataServerSource(
    private val lock: TtrLock,
    private val cache: SnapshotCache,
    private val mode: LockMode = LockMode.CONNECTED,
    private val priority: Int = 0,
) : ModelSource {
    override fun load(): SourceSnapshot {
        val result = loadResult()
        // The bare ModelSource.load() would otherwise DROP the LCK-002 diagnostics + `missing` list and
        // hand back a partial world silently. Fold missing pins into the snapshot's load errors so a
        // caller on the interface path (no access to loadResult()) still sees them. Callers that need the
        // structured diagnostics/staleness use loadResult() directly.
        if (result.missing.isEmpty()) return result.snapshot
        val errs =
            result.missing.map {
                LoadWarning("veles", "ttr.lock", 1, 0, "pinned archive absent from cache: $it — run `ttr fetch`")
            }
        return result.snapshot.copy(errors = result.snapshot.errors + errs)
    }

    fun loadResult(): MetadataServerLoad {
        val diags = mutableListOf<TtrpDiagnostic>()
        val missing = mutableListOf<String>()
        val served = mutableListOf<String>()
        var merged = emptySnapshot()

        for (id in lock.archiveIds()) {
            val bytes = cache.get(id)
            if (bytes == null) {
                missing += id
                diags += lck002(id)
                continue
            }
            val storage =
                SnapshotArchiveStorage.of("veles:$id", bytes).getOrElse {
                    missing += id
                    diags += lck002(id) // corrupt archive == unusable pin; structured, not thrown
                    null
                } ?: continue
            served += id
            merged = merge(merged, FileBasedSource("veles:$id", priority, storage).load())
        }

        if (mode == LockMode.OFFLINE) diags += lck003()
        // PL-P1.S3: `staleness` is written verbatim into the compile record's `staleness` block.
        return MetadataServerLoad(
            snapshot = merged,
            diagnostics = diags,
            missing = missing,
            staleness = Staleness(offline = mode == LockMode.OFFLINE, servedFromCache = served),
        )
    }

    private fun emptySnapshot() = SourceSnapshot(sourceId = "veles", priority = priority, version = lock.world.archive)

    private fun merge(
        a: SourceSnapshot,
        b: SourceSnapshot,
    ): SourceSnapshot =
        a.copy(
            tables = a.tables + b.tables,
            views = a.views + b.views,
            procedures = a.procedures + b.procedures,
            foreignKeys = a.foreignKeys + b.foreignKeys,
            entities = a.entities + b.entities,
            relations = a.relations + b.relations,
            mappings = a.mappings + b.mappings,
            queries = a.queries + b.queries,
            roles = a.roles + b.roles,
            drillMaps = a.drillMaps + b.drillMaps,
            areas = a.areas + b.areas,
            rowSchemas = a.rowSchemas + b.rowSchemas,
            worlds = a.worlds + b.worlds,
            warnings = a.warnings + b.warnings,
            errors = a.errors + b.errors,
            protectedQnames = a.protectedQnames + b.protectedQnames,
            loadedFiles = a.loadedFiles + b.loadedFiles,
        )

    private fun loc() = SourceLocation("ttr.lock", 1, 0, 1, 1, -1, -1)

    private fun lck002(id: String) =
        TtrpDiagnostic(
            id = TtrpDiagnosticId.LCK_002,
            severity = Severity.ERROR,
            message = "pinned archive absent from cache: $id — run `ttr fetch`",
            location = loc(),
            suggestedAlternative = TtrpDiagnosticId.LCK_002.suggestedAlternative,
        )

    private fun lck003() =
        TtrpDiagnostic(
            id = TtrpDiagnosticId.LCK_003,
            severity = Severity.WARNING,
            message = "--offline: compiling from cache; staleness recorded in the compile record",
            location = loc(),
            suggestedAlternative = TtrpDiagnosticId.LCK_003.suggestedAlternative,
        )
}
