// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.WorkspaceService
import org.tatrman.ttrp.lsp.analysis.AnalysisEngine
import org.tatrman.ttrp.lsp.analysis.AnalysisScheduler
import org.tatrman.ttrp.lsp.docs.DocumentStore
import org.tatrman.ttrp.lsp.project.FilesystemProjectResolver
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.tatrman.ttrp.lsp.protocol.AuthoringContextParams
import org.tatrman.ttrp.lsp.protocol.AuthoringContextResult
import org.tatrman.ttrp.lsp.protocol.ExplainParams
import org.tatrman.ttrp.lsp.protocol.ExplainResult
import org.tatrman.ttrp.lsp.protocol.GetGraphParams
import org.tatrman.ttrp.lsp.protocol.GetGraphResult
import org.tatrman.ttrp.lsp.protocol.GetLayoutParams
import org.tatrman.ttrp.lsp.protocol.GetLayoutResult
import org.tatrman.ttrp.lsp.protocol.GetWorldParams
import org.tatrman.ttrp.lsp.protocol.GetWorldResult
import org.tatrman.ttrp.lsp.protocol.SetLayoutParams
import org.tatrman.ttrp.lsp.protocol.SetLayoutResult
import org.tatrman.ttrp.lsp.protocol.RunParams
import org.tatrman.ttrp.lsp.protocol.RunResult
import org.tatrman.ttrp.lsp.protocol.TranspileParams
import org.tatrman.ttrp.lsp.protocol.TranspileResult
import org.tatrman.ttrp.lsp.protocol.TtrpLanguageServerApi
import org.tatrman.ttrp.lsp.protocol.ValidateParams
import org.tatrman.ttrp.lsp.protocol.ValidateResult
import org.tatrman.ttrp.lsp.viewstate.SidecarRenameParticipant
import org.tatrman.ttrp.lsp.viewstate.ViewStateRenameParticipant
import java.util.concurrent.CompletableFuture

/**
 * The single TTR-P language server (G-b/G-f: one Kotlin LSP across hosts). Stage 4.1
 * serves diagnostics, hover, definition, and rename over the injected [projects]
 * resolver; the transport is external (stdio `main` here; a WS launcher in Stage 5.1
 * reuses the exact same server object). The five `ttrp/…` custom methods are declared
 * now (contracts §4) and answer `MethodNotFound` until Stage 4.2.
 */
class TtrpLanguageServer(
    projects: ProjectResolver = FilesystemProjectResolver(),
    // Default: the sidecar-atomic ζ participant (Stage 5.2) — a rename rewrites the paired
    // `.ttrl` in the same operation. The in-process harness swaps in a recording participant.
    renameParticipants: List<ViewStateRenameParticipant> = listOf(SidecarRenameParticipant()),
    // What to do on the LSP `exit` notification. Default is a no-op so the in-process test
    // harness (which shares this JVM) is never killed; the stdio `main` injects a real
    // process exit (standard LSP: `exit` terminates the server process).
    private val exitHandler: () -> Unit = {},
) : TtrpLanguageServerApi,
    LanguageClientAware {
    private var client: LanguageClient? = null

    val docs = DocumentStore()
    val engine = AnalysisEngine(projects)
    private val scheduler = AnalysisScheduler(docs, engine, { client })

    private val hoverService = HoverService()
    private val definitionService = DefinitionService()
    private val renameService = RenameService(renameParticipants)
    private val methods =
        org.tatrman.ttrp.lsp.methods
            .TtrpMethods(docs, projects)

    private val textDocumentService =
        TtrpTextDocumentService(this, docs, engine, scheduler, hoverService, definitionService, renameService)
    private val workspaceService = TtrpWorkspaceService(docs, engine, scheduler)

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    fun client(): LanguageClient? = client

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities()
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
        capabilities.setHoverProvider(true)
        capabilities.setDefinitionProvider(true)
        capabilities.renameProvider =
            org.eclipse.lsp4j.jsonrpc.messages.Either
                .forRight(RenameOptions(true))
        capabilities.setDocumentFormattingProvider(true)
        // AG B5: `container … from "<file>"` — the clause links to the fragment file.
        capabilities.documentLinkProvider = org.eclipse.lsp4j.DocumentLinkOptions(false)
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> {
        scheduler.close()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        scheduler.close()
        exitHandler()
    }

    override fun getTextDocumentService() = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    // ---- custom ttrp/ methods (contracts §4) — 4.2 (transpile/run/explain/validate/authoringContext) + 5.1 (getGraph/getWorld) ----

    override fun getGraph(params: GetGraphParams): CompletableFuture<GetGraphResult> = methods.getGraph(params)

    override fun getWorld(params: GetWorldParams): CompletableFuture<GetWorldResult> = methods.getWorld(params)

    override fun getLayout(params: GetLayoutParams): CompletableFuture<GetLayoutResult> = methods.getLayout(params)

    override fun setLayout(params: SetLayoutParams): CompletableFuture<SetLayoutResult> = methods.setLayout(params)

    override fun applyGraphEdit(
        params: org.tatrman.ttrp.lsp.protocol.ApplyGraphEditParams,
    ): CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> = methods.applyGraphEdit(params)

    override fun transpile(params: TranspileParams): CompletableFuture<TranspileResult> = methods.transpile(params)

    override fun run(params: RunParams): CompletableFuture<RunResult> = methods.run(params)

    override fun explain(params: ExplainParams): CompletableFuture<ExplainResult> = methods.explain(params)

    override fun validate(params: ValidateParams): CompletableFuture<ValidateResult> = methods.validate(params)

    override fun authoringContext(params: AuthoringContextParams): CompletableFuture<AuthoringContextResult> =
        methods.authoringContext(params)
}
