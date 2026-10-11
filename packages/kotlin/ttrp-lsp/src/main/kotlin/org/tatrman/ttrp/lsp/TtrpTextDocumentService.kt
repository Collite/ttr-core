// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.services.TextDocumentService
import org.tatrman.ttrp.lsp.analysis.AnalysisEngine
import org.tatrman.ttrp.lsp.analysis.AnalysisScheduler
import org.tatrman.ttrp.lsp.docs.DocumentStore
import java.util.concurrent.CompletableFuture

/**
 * The `textDocument/…` surface (T4.1.3–4.1.7). Sync + diagnostics run through the
 * [scheduler]; hover / definition / rename analyze the current document on demand
 * (deterministic re-check; the [engine] caches by version so a hover reuses the
 * diagnostics pass's report).
 */
class TtrpTextDocumentService(
    private val server: TtrpLanguageServer,
    private val docs: DocumentStore,
    private val engine: AnalysisEngine,
    private val scheduler: AnalysisScheduler,
    private val hoverService: HoverService,
    private val definitionService: DefinitionService,
    private val renameService: RenameService,
    private val formatter: org.tatrman.ttrp.lsp.format.TtrpFormatter =
        org.tatrman.ttrp.lsp.format
            .TtrpFormatter(),
) : TextDocumentService {
    private var fragmentNoticeLogged = false

    private companion object {
        const val TTRP_LANGUAGE_ID = "ttrp"

        fun analysed(languageId: String): Boolean =
            languageId == TTRP_LANGUAGE_ID || languageId in AnalysisEngine.TTRB_LANGUAGES
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val d = params.textDocument
        docs.open(d.uri, d.text, d.version, d.languageId)
        // .ttrp is TTR-P; bare TTR-B (ttrb / ttrb-cs) is checked as a bare program or sentence by
        // sentence (AG B6). ttr-sql / ttr-pandas share this server for sync only.
        if (analysed(d.languageId)) scheduler.schedule(d.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val id = params.textDocument
        val updated = docs.change(id.uri, id.version, params.contentChanges)
        if (updated != null && analysed(updated.languageId)) scheduler.schedule(id.uri)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        docs.close(params.textDocument.uri)
        scheduler.clear(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // No-op: text is canonical and already synced via didChange.
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<MutableList<out TextEdit>> =
        CompletableFuture.supplyAsync {
            val doc = docs.get(params.textDocument.uri) ?: return@supplyAsync mutableListOf<TextEdit>()
            // Non-.ttrp language ids (bare fragments) are never formatted (C2-f); notify once.
            if (org.tatrman.ttrp.lsp.format.TtrpFormatter
                    .isBareFragmentFile(params.textDocument.uri) ||
                doc.languageId != TTRP_LANGUAGE_ID
            ) {
                if (!fragmentNoticeLogged) {
                    fragmentNoticeLogged = true
                    server.client()?.logMessage(
                        MessageParams(MessageType.Info, "TTR-P fragments are never formatted — C2-f"),
                    )
                }
                return@supplyAsync mutableListOf<TextEdit>()
            }
            val formatted = formatter.format(doc.text, params.textDocument.uri)
            // Empty edit list when already canonical → hosts skip the write (format-on-save friendly).
            if (formatted == doc.text) {
                mutableListOf()
            } else {
                mutableListOf(TextEdit(wholeDocumentRange(doc.text), formatted))
            }
        }

    private fun wholeDocumentRange(text: String): org.eclipse.lsp4j.Range {
        val lines = text.split("\n")
        val lastLine = lines.size - 1
        val lastCol = lines.last().length
        return org.eclipse.lsp4j.Range(Position(0, 0), Position(lastLine, lastCol))
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> =
        CompletableFuture.supplyAsync {
            val doc = docs.get(params.textDocument.uri) ?: return@supplyAsync null
            val analysis = engine.analyze(doc)
            hoverService.hover(analysis.report, params.position)
        }

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        CompletableFuture.supplyAsync {
            val doc = docs.get(params.textDocument.uri) ?: return@supplyAsync emptyLocations()
            val analysis = engine.analyze(doc)
            // AG B5: on a `from "<file>"` clause, definition opens the fragment file.
            org.tatrman.ttrp.lsp.nav.FragmentLinks
                .of(params.textDocument.uri, analysis.report.document)
                .firstOrNull {
                    org.tatrman.ttrp.lsp.nav.SourceNav
                        .contains(it.clause, params.position)
                }?.let { link ->
                    val start = Position(0, 0)
                    return@supplyAsync Either.forLeft(mutableListOf(Location(link.target, Range(start, start))))
                }
            val loc = definitionService.define(params.textDocument.uri, analysis.report.document, params.position)
            if (loc == null) emptyLocations() else Either.forLeft(mutableListOf(loc))
        }

    override fun prepareRename(
        params: PrepareRenameParams,
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> =
        CompletableFuture.supplyAsync {
            val doc = docs.get(params.textDocument.uri) ?: return@supplyAsync null
            val analysis = engine.analyze(doc)
            when (val prep = renameService.prepareRename(doc.text, analysis.report.document, params.position)) {
                is RenameService.Prepare.Valid -> Either3.forFirst(prep.range)
                is RenameService.Prepare.Invalid ->
                    throw ResponseErrorException(ResponseError(ResponseErrorCode.InvalidRequest, prep.reason, null))
            }
        }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> =
        CompletableFuture.supplyAsync {
            val doc = docs.get(params.textDocument.uri) ?: return@supplyAsync WorkspaceEdit(emptyList())
            val analysis = engine.analyze(doc)
            renameService.rename(
                params.textDocument.uri,
                doc.text,
                analysis.report.document,
                doc.version,
                params.position,
                params.newName,
            ) ?: WorkspaceEdit(emptyList())
        }

    /** AG B5: one link per `container … from "<file>"` clause, targeting the fragment file. */
    override fun documentLink(
        params: org.eclipse.lsp4j.DocumentLinkParams,
    ): CompletableFuture<MutableList<org.eclipse.lsp4j.DocumentLink>> =
        CompletableFuture.supplyAsync {
            val doc = docs.get(params.textDocument.uri) ?: return@supplyAsync mutableListOf()
            if (doc.languageId != TTRP_LANGUAGE_ID) return@supplyAsync mutableListOf()
            val analysis = engine.analyze(doc)
            org.tatrman.ttrp.lsp.nav.FragmentLinks
                .of(doc.uri, analysis.report.document)
                .map {
                    org.eclipse.lsp4j.DocumentLink(
                        org.tatrman.ttrp.lsp.analysis.DiagnosticMapping
                            .rangeOf(it.clause),
                        it.target,
                    )
                }.toMutableList()
        }

    private fun emptyLocations(): Either<MutableList<out Location>, MutableList<out LocationLink>> =
        Either.forLeft(mutableListOf())
}
