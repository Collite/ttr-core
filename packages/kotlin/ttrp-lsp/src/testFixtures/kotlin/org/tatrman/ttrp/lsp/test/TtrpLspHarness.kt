// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.test

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.tatrman.ttrp.lsp.TtrpLanguageServer
import org.tatrman.ttrp.lsp.protocol.TtrpLanguageServerApi
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * The Kotlin twin of the TS `PassThrough` paired-connection harness (CLAUDE.md
 * §Cross-package integration tests): two piped-stream pairs, one LSP4J launcher per
 * side, fully in-process — no sockets, no child process. The server binds the shared
 * erp-project world via [FixtureProjectResolver] and records ζ remaps via [renameParticipant].
 */
class TtrpLspHarness(
    /** Project resolution; default the shared erp-project fixture world, or e.g. a real walk-up resolver. */
    projects: org.tatrman.ttrp.lsp.project.ProjectResolver = FixtureProjectResolver(),
) : AutoCloseable {
    val renameParticipant = RecordingRenameParticipant()
    val server = TtrpLanguageServer(projects, listOf(renameParticipant))
    val client = RecordingLanguageClient()
    val remote: TtrpLanguageServerApi

    private val serverListening: java.util.concurrent.Future<Void>
    private val clientListening: java.util.concurrent.Future<Void>

    init {
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient)

        val serverLauncher =
            Launcher
                .Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(serverIn)
                .setOutput(serverToClient)
                .create()
        server.connect(serverLauncher.remoteProxy)

        val clientLauncher =
            Launcher
                .Builder<TtrpLanguageServerApi>()
                .setLocalService(client)
                .setRemoteInterface(TtrpLanguageServerApi::class.java)
                .setInput(clientIn)
                .setOutput(clientToServer)
                .create()
        remote = clientLauncher.remoteProxy

        serverListening = serverLauncher.startListening()
        clientListening = clientLauncher.startListening()
    }

    fun initialize(rootUri: String? = null): InitializeResult {
        val params = InitializeParams()
        params.rootUri = rootUri
        return remote.initialize(params).get()
    }

    fun open(
        uri: String,
        text: String,
        languageId: String = "ttrp",
        version: Int = 1,
    ) {
        client.resetLatch(uri)
        remote.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, languageId, version, text)),
        )
    }

    /** Incremental change: splice [newText] into [range] at [version]. */
    fun change(
        uri: String,
        version: Int,
        range: Range,
        newText: String,
    ) {
        client.resetLatch(uri)
        val change = TextDocumentContentChangeEvent(range, newText)
        remote.textDocumentService.didChange(
            DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, version), listOf(change)),
        )
    }

    /** Full-document change (whole text replaced) at [version]. */
    fun replace(
        uri: String,
        version: Int,
        newText: String,
    ) {
        client.resetLatch(uri)
        remote.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, version),
                listOf(TextDocumentContentChangeEvent(newText)),
            ),
        )
    }

    fun awaitDiagnostics(
        uri: String,
        timeoutMs: Long = 5_000,
    ): List<Diagnostic> = client.awaitDiagnostics(uri, timeoutMs).diagnostics

    override fun close() {
        remote.textDocumentService // touch to keep proxy referenced
        runCatching { remote.shutdown().get() }
        runCatching { remote.exit() }
        serverListening.cancel(true)
        clientListening.cancel(true)
    }
}
