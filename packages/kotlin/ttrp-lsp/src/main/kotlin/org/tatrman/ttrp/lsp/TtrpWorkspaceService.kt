// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService
import org.tatrman.ttrp.lsp.analysis.AnalysisEngine
import org.tatrman.ttrp.lsp.analysis.AnalysisScheduler
import org.tatrman.ttrp.lsp.docs.DocumentStore

/**
 * Workspace surface. When a watched model / world / manifest file changes on disk, the
 * cached model snapshots (held per-modelsRoot in [engine]) are dropped and every open
 * TTR-P document is re-analyzed, so a model edit is reflected without a server restart.
 */
class TtrpWorkspaceService(
    private val docs: DocumentStore,
    private val engine: AnalysisEngine,
    private val scheduler: AnalysisScheduler,
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        // Only model/world/manifest edits invalidate resolution; open source docs (.ttrp and
        // fragments) already re-sync through didChange, so they need no cache drop here.
        val touchesModel =
            params.changes.any { c ->
                c.uri.endsWith(".ttrm") || c.uri.endsWith(".ttrg") || c.uri.endsWith(".toml")
            }
        // AG B5: a fragment file (`container … from "<file>"`) changed on disk — re-analyze the open
        // programs (their cached analyses read the old file); the model snapshots stay.
        val touchesFragment =
            params.changes.any { c ->
                FRAGMENT_SUFFIXES.any { c.uri.endsWith(it) }
            }
        if (touchesFragment && !touchesModel) {
            for (uri in docs.openUris()) {
                if (docs.get(uri)?.languageId == "ttrp") {
                    engine.evict(uri)
                    scheduler.schedule(uri)
                }
            }
            return
        }
        if (!touchesModel) return
        engine.invalidateAll()
        for (uri in docs.openUris()) {
            if (docs.get(uri)?.languageId == "ttrp") scheduler.schedule(uri)
        }
    }

    private companion object {
        val FRAGMENT_SUFFIXES = listOf(".ttrb", ".ttrb-cs", ".ttr.sql", ".ttr.py")
    }
}
