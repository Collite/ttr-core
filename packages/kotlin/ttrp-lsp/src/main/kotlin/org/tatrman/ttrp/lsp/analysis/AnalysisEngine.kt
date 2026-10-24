// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.analysis

import org.tatrman.ttrp.lsp.docs.OpenDocument
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.tatrman.ttrp.resolve.TtrpChecker
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.lsp.project.ProjectContext
import java.util.concurrent.ConcurrentHashMap

/** A resolved front-half analysis of one document version. */
data class Analysis(
    val uri: String,
    val version: Int,
    /** Canonical recipe positions are not valid positions in a Markdown document. */
    val report: TtrpChecker.Report,
    val literate: org.tatrman.ttrp.doc.DocExtraction? = null,
    val recipes: List<EditorRecipe> = emptyList(),
)

/**
 * Runs the Phase-1 front-half (`TtrpChecker`) for a document and caches the result by
 * document version. Deterministic: the same text always yields the same report (P2 —
 * no shortcut caching that skips re-resolution; the cache is keyed on version, and a
 * new version always re-runs). Feature services (hover/definition/rename) and the
 * diagnostics scheduler share one engine so a hover never re-parses what a just-run
 * diagnostics pass already computed.
 */
class AnalysisEngine(
    private val projects: ProjectResolver,
    private val pipelineFactory: (ProjectContext) -> TtrpPipeline = { TtrpPipeline(it.manifest, it.modelsRoot) },
) {
    private val analysisCache = ConcurrentHashMap<String, Analysis>()
    private val pipelineCache = ConcurrentHashMap<String, TtrpPipeline>()

    fun analyze(doc: OpenDocument): Analysis {
        analysisCache[doc.uri]?.let { if (it.version == doc.version) return it }
        return analyzeSource(doc).also { analysisCache[doc.uri] = it }
    }

    /** Validate supplied text without a document-version cache, while reusing project metadata. */
    fun analyzeSource(doc: OpenDocument): Analysis {
        val ctx = projects.resolve(doc.uri)
        val pipeline = pipelineCache.computeIfAbsent(ctx.manifest.manifestDir.toString()) { pipelineFactory(ctx) }
        if (RecipeEditorAnalysis.markdown(doc)) {
            return RecipeEditorAnalysis.literate(doc, ctx, pipeline)
        }
        val report =
            ttrbSentenceCheck(doc, ctx)
                ?: pipeline.check(doc.text, doc.uri, ctx.manifestDiagnostics)
        return RecipeEditorAnalysis.checked(doc, ctx, report, pipeline)
    }

    /**
     * AG B6 — a bare TTR-B document (`ttrb` / `ttrb-cs`) in a project WITHOUT a `[ttrp] bare-target`
     * (typically a rules file a program pulls in with `from "…"`) gets the fragment's own sentence check —
     * the skin's reject table, its messages and suggested sentences — instead of a bare-program
     * wrapper that could only report the missing bare-target. With a bare-target it is a full program.
     */
    private fun ttrbSentenceCheck(
        doc: org.tatrman.ttrp.lsp.docs.OpenDocument,
        ctx: org.tatrman.ttrp.lsp.project.ProjectContext,
    ): TtrpChecker.Report? {
        if (doc.languageId !in TTRB_LANGUAGES || ctx.manifest.bareTarget != null) return null
        val dialect =
            org.tatrman.ttrp.dialect.bare.DialectMarker
                .resolve(doc.uri, doc.text) ?: doc.languageId
        val skin =
            org.tatrman.ttrp.dialect.b.TtrbSkin
                .forTag(dialect) ?: return null
        val whole =
            org.tatrman.ttrp.ast
                .SourceLocation(doc.uri, 1, 0, 1, 0, 0, doc.text.length)
        val decomposition =
            org.tatrman.ttrp.dialect.b.TtrB
                .decompose(doc.text, whole, outPort = null, skin = skin)
        return TtrpChecker.Report(
            document =
                org.tatrman.ttrp.ast
                    .TtrpDocument(emptyList(), whole),
            diagnostics = decomposition.diagnostics,
            world = null,
            rewrites = emptyList(),
        )
    }

    fun evict(uri: String) {
        analysisCache.remove(uri)
    }

    /**
     * Drop every cached checker and analysis. Called when a watched model / world / manifest
     * file changes on disk: the per-project [pipelineCache] holds a snapshot loaded from
     * disk, so without this a model edit stays invisible until the server restarts.
     */
    fun invalidateAll() {
        analysisCache.clear()
        pipelineCache.clear()
    }

    companion object {
        /** The TTR-B language ids (English / Czech skin) the editor analyses sentence by sentence. */
        val TTRB_LANGUAGES = setOf("ttrb", "ttrb-cs")
    }
}
