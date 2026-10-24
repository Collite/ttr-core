// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.analysis

import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.bundle.RecipeBundleCheck
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.doc.DocExtractor
import org.tatrman.ttrp.doc.ExtractedRecipe
import org.tatrman.ttrp.doc.utf16Offset
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.lsp.docs.OpenDocument
import org.tatrman.ttrp.lsp.project.ProjectContext
import org.tatrman.ttrp.resolve.TtrpChecker

/** A checked recipe and, for literate sources, its derived positional mapping. No sidecar is read or written. */
data class EditorRecipe(
    val extracted: ExtractedRecipe?,
    val plan: TtrpPipeline.PlanResult,
)

object RecipeEditorAnalysis {
    fun markdown(doc: OpenDocument) = doc.languageId == "markdown" || doc.uri.endsWith(".md")

    fun location(
        text: String,
        uri: String,
        span: List<Int>,
    ): SourceLocation {
        fun point(offset: Int): Pair<Int, Int> {
            val at = offset.coerceIn(0, text.length)
            val before = text.substring(0, at)
            return (before.count { it == '\n' } + 1) to (at - (before.lastIndexOf('\n') + 1))
        }
        val start = point(span[0])
        val end = point(span[1])
        return SourceLocation(uri, start.first, start.second, end.first, end.second, span[0], span[1])
    }

    fun checked(
        doc: OpenDocument,
        ctx: ProjectContext,
        report: TtrpChecker.Report,
    ): Analysis {
        if (report.document.statements.none { it is RecipeDecl }) return Analysis(doc.uri, doc.version, report)
        val plan = TtrpPipeline(ctx.manifest, ctx.modelsRoot).plan(doc.text, doc.uri)
        return Analysis(
            doc.uri,
            doc.version,
            report.copy(diagnostics = diagnostics(plan)),
            recipes = listOf(EditorRecipe(null, plan)),
        )
    }

    private fun diagnostics(plan: TtrpPipeline.PlanResult) =
        plan.diagnostics + if (plan.ok) RecipeBundleCheck.check(plan) else emptyList()

    private fun emptyReport(doc: OpenDocument) =
        TtrpChecker.Report(
            TtrpDocument(emptyList(), location(doc.text, doc.uri, listOf(0, 0))),
            emptyList(),
            null,
            emptyList(),
        )

    fun literate(
        doc: OpenDocument,
        ctx: ProjectContext,
    ): Analysis {
        val extraction = DocExtractor.extract(doc.text, doc.uri.substringAfterLast('/'))
        val roleHeader = Regex("^(?:candidates|context|decision)\\s+[\\p{L}_]")
        val recipeDocument =
            extraction.model.blocks.any { block ->
                (
                    block.tag == "ttrp" &&
                        (block.info == "preamble" || Regex("\\brecipe\\s*(?:\\{|:)").containsMatchIn(block.body))
                ) ||
                    roleHeader.containsMatchIn(block.info) ||
                    roleHeader.containsMatchIn(block.body.trimStart())
            }
        // Markdown activation also sees ordinary README/tutorial code fences. Recipe diagnostics belong only
        // to documents that opt into literate recipe authoring; the extractor remains strict when invoked directly.
        if (!recipeDocument) return Analysis(doc.uri, doc.version, emptyReport(doc))
        val pipeline = TtrpPipeline(ctx.manifest, ctx.modelsRoot)
        val recipes = extraction.recipes.map { EditorRecipe(it, pipeline.plan(it.text, it.path)) }
        val findings =
            extraction.diagnostics.map { d ->
                TtrpDiagnostic(
                    TtrpDiagnosticId.entries.single {
                        it.id == d.id
                    },
                    Severity.ERROR,
                    d.message,
                    location(doc.text, doc.uri, d.span),
                )
            } +
                recipes.flatMap { entry ->
                    val recipe = requireNotNull(entry.extracted)
                    diagnostics(entry.plan).map { finding ->
                        val mapped =
                            recipe.sidecar.nodes.values.firstOrNull {
                                utf16Offset(recipe.text, finding.location.offsetStart) in
                                    it.generatedSpan[0] until it.generatedSpan[1]
                            }
                        val span = mapped?.span ?: recipe.skeletonSpan
                        finding.copy(location = location(doc.text, doc.uri, span))
                    }
                }
        val first = extraction.recipes.firstOrNull()
        val report =
            if (first != null) {
                TtrpChecker(ctx.manifest, ctx.modelsRoot).check(first.text, first.path)
            } else {
                emptyReport(doc)
            }
        return Analysis(doc.uri, doc.version, report.copy(diagnostics = findings), extraction, recipes)
    }
}
