// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttrp.ast.ImportDecl
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.lsp.analysis.Analysis
import org.tatrman.ttrp.lsp.analysis.DiagnosticMapping
import org.tatrman.ttrp.lsp.analysis.RecipeEditorAnalysis
import org.tatrman.ttrp.lsp.docs.OpenDocument
import org.tatrman.ttrp.resolve.ModelIndex

/** Shared editor features consume the compiler/metadata result; hosts carry no recipe semantics. */
object RecipeEditorFeatures {
    private fun offset(
        doc: OpenDocument,
        pos: Position,
    ): Int =
        doc.text
            .lineSequence()
            .take(pos.line)
            .sumOf { it.length + 1 } + pos.character

    private fun objects(analysis: Analysis) =
        analysis.report.modelIndex
            ?.automationInScope(
                analysis.report.document.statements.filterIsInstance<ImportDecl>().map {
                    ModelIndex.importScope(it.qname.parts, it.qname.text)
                },
            ).orEmpty()

    fun hover(
        doc: OpenDocument,
        analysis: Analysis,
        pos: Position,
    ): Hover? {
        val at = offset(doc, pos)
        val match =
            Regex(":[A-Za-z_][A-Za-z0-9_]*").findAll(doc.text).firstOrNull { at in it.range.first..it.range.last + 1 }
                ?: return null
        val name = match.value.drop(1)
        val p =
            objects(analysis).filterIsInstance<AutomationParameter>().singleOrNull { it.qname.name == name }
                ?: return null
        val value =
            when (val v = p.value) {
                is AutoValue.Num -> v.text
                is AutoValue.Str -> v.raw
                is AutoValue.Bool -> v.raw.toString()
                else -> p.value.toString()
            }
        val labels =
            p.label.byLanguage
                .toSortedMap()
                .entries
                .joinToString(" · ") { (lang, text) -> "$lang: $text" }
        val text = "**`:$name`** = `$value` (${p.type})" + if (labels.isEmpty()) "" else "\n\n$labels"
        return Hover(
            MarkupContent("markdown", text),
            DiagnosticMapping.rangeOf(
                RecipeEditorAnalysis.location(
                    doc.text,
                    doc.uri,
                    listOf(
                        match.range.first,
                        match.range.last + 1,
                    ),
                ),
            ),
        )
    }

    fun completions(
        doc: OpenDocument,
        analysis: Analysis,
        pos: Position,
    ): MutableList<CompletionItem> {
        val prefix = doc.text.take(offset(doc, pos).coerceIn(0, doc.text.length))
        val actions = Regex("\\bactions\\s*\\{").findAll(prefix).lastOrNull() ?: return mutableListOf()
        // Nested action argument braces are allowed; only the enclosing actions block closes the scope.
        var depth = 1
        var quoted = false
        prefix.substring(actions.range.last + 1).forEachIndexed { i, c ->
            if (c == '"' && (i == 0 || prefix[actions.range.last + i] != '\\')) quoted = !quoted
            if (!quoted) {
                if (c == '{') depth++
                if (c == '}') depth--
            }
        }
        if (depth <= 0) return mutableListOf()
        return objects(analysis)
            .filterIsInstance<AutomationAction>()
            .sortedBy { it.qname.name }
            .map { a ->
                CompletionItem(a.qname.name).apply {
                    kind = CompletionItemKind.Function
                    detail = a.columns.joinToString(", ") { "${it.name}: ${it.type}${if (it.optional) "?" else ""}" }
                    insertText = a.qname.name
                }
            }.toMutableList()
    }

    fun lenses(
        doc: OpenDocument,
        analysis: Analysis,
    ): MutableList<CodeLens> =
        analysis.recipes
            .mapNotNull { entry ->
                val model = entry.plan.recipe ?: return@mapNotNull null
                val span = entry.extracted?.skeletonSpan
                val location =
                    span?.let { RecipeEditorAnalysis.location(doc.text, doc.uri, it) }
                        ?: analysis.report.document.statements
                            .filterIsInstance<RecipeDecl>()
                            .firstOrNull()
                            ?.location
                        ?: return@mapNotNull null
                val title =
                    model.mode.spelling +
                        model.contexts.joinToString(", ", prefix = " · ") { "${it.name}: ${it.contextClass.spelling}" }
                CodeLens(DiagnosticMapping.rangeOf(location), Command(title, "ttrp.explain"), null)
            }.toMutableList()
}
