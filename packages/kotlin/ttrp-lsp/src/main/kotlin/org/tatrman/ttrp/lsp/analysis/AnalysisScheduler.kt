// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.analysis

import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.tatrman.ttrp.lsp.docs.DocumentStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Debounced, latest-version-wins diagnostics publication (T4.1.4). On open/change the
 * scheduler (re)arms a per-uri 250 ms timer; when it fires it runs the front-half on a
 * single worker and publishes — but only if the document version has not moved on since
 * the run was scheduled (a superseded version publishes nothing). `didClose` publishes
 * an empty set (LSP hygiene).
 */
class AnalysisScheduler(
    private val docs: DocumentStore,
    private val engine: AnalysisEngine,
    private val client: () -> LanguageClient?,
    private val debounceMs: Long = 250,
) : AutoCloseable {
    private val worker =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ttrp-analysis").apply {
                isDaemon =
                    true
            }
        }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun schedule(uri: String) {
        pending.remove(uri)?.cancel(false)
        pending[uri] = worker.schedule({ publish(uri) }, debounceMs, TimeUnit.MILLISECONDS)
    }

    /** Run synchronously (tests use this to avoid racing the debounce). */
    fun runNow(uri: String) {
        pending.remove(uri)?.cancel(false)
        publish(uri)
    }

    fun clear(uri: String) {
        pending.remove(uri)?.cancel(false)
        engine.evict(uri)
        client()?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    private fun publish(uri: String) {
        val doc = docs.get(uri) ?: return
        val analysis = engine.analyze(doc)
        // Latest-version-wins: if the document advanced while we analyzed, drop this set.
        val now = docs.get(uri) ?: return
        if (now.version != doc.version) return
        // A finding inside a file-backed fragment (AG B5) is shown on the `from "<path>"` clause.
        val links =
            org.tatrman.ttrp.lsp.nav.FragmentLinks
                .of(uri, analysis.report.document)
        val diagnostics =
            analysis.report.diagnostics.map {
                DiagnosticMapping.toLsp(
                    org.tatrman.ttrp.lsp.nav.FragmentLinks
                        .anchor(it, uri, links),
                )
            }
        client()?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics, doc.version))
    }

    override fun close() {
        worker.shutdownNow()
    }
}
