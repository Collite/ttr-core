// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.methods

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.tatrman.ttrp.bundle.BundleAssembler
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.lsp.docs.DocumentStore
import org.tatrman.ttrp.lsp.docs.OpenDocument
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.lsp.edit.GraphEditSynthesizer
import org.tatrman.ttrp.lsp.protocol.ApplyGraphEditParams
import org.tatrman.ttrp.lsp.protocol.AuthoringContextParams
import org.tatrman.ttrp.lsp.protocol.AuthoringContextResult
import org.tatrman.ttrp.lsp.protocol.EngineView
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
import org.tatrman.ttrp.lsp.protocol.StorageView
import org.tatrman.ttrp.lsp.viewstate.LayoutService
import org.tatrman.ttrp.lsp.protocol.RunParams
import org.tatrman.ttrp.lsp.protocol.RunResult
import org.tatrman.ttrp.lsp.protocol.TranspileParams
import org.tatrman.ttrp.lsp.protocol.TranspileResult
import org.tatrman.ttrp.lsp.protocol.ValidateDiagnostic
import org.tatrman.ttrp.lsp.protocol.ValidateParams
import org.tatrman.ttrp.lsp.protocol.ValidateResult
import org.tatrman.ttrp.resolve.TtrpChecker
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The five `ttrp/…` custom methods (contracts §4). Each delegates to the Phase-2/3
 * library it mirrors (S4: the LSP serializes their output, never recomputing its own
 * semantics). Versioned methods (transpile/run/explain) enforce the versioning
 * discipline: a stale `{uri, version}` is rejected with `ContentModified` (-32801) +
 * replay data, and the client replays.
 */
class TtrpMethods(
    private val docs: DocumentStore,
    private val projects: ProjectResolver,
) {
    private val gson = Gson()
    private val layoutService = LayoutService()

    /** ContentModified per LSP; LSP4J's ResponseErrorCode does not expose it as a constant. */
    private val contentModified = -32801

    fun explain(params: ExplainParams): CompletableFuture<ExplainResult> =
        CompletableFuture.supplyAsync {
            val doc = requireVersion(params.uri, params.version)
            val ctx = projects.resolve(doc.uri)
            val out = TtrpPipeline(ctx.manifest, ctx.modelsRoot).explain(doc.text, fileNameOf(doc.uri))
            ExplainResult(out.text, out.ok)
        }

    fun getGraph(params: GetGraphParams): CompletableFuture<GetGraphResult> =
        CompletableFuture.supplyAsync {
            val doc = requireVersion(params.uri, params.version)
            val ctx = projects.resolve(doc.uri)
            val fileName = fileNameOf(doc.uri)
            val plan = TtrpPipeline(ctx.manifest, ctx.modelsRoot).plan(doc.text, fileName)
            GraphViewBuilder.build(fileName, plan, params.elaborated)
        }

    fun applyGraphEdit(params: ApplyGraphEditParams): CompletableFuture<WorkspaceEdit> =
        CompletableFuture.supplyAsync {
            val doc =
                docs.get(params.uri)
                    ?: throw responseError(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams,
                        "no open document: ${params.uri}",
                    )
            // Versioning (C1-d-iii): a stale version carries TTRP-EDIT-001 so the client re-pulls + replays.
            if (doc.version != params.version) {
                val data =
                    JsonObject().apply {
                        addProperty("code", TtrpDiagnosticId.EDIT_001.id)
                        addProperty("uri", params.uri)
                        addProperty("requested", params.version)
                        addProperty("current", doc.version)
                    }
                throw ResponseErrorException(
                    ResponseError(contentModified, "${TtrpDiagnosticId.EDIT_001.id}: stale version — replay", data),
                )
            }
            when (val r = GraphEditSynthesizer().apply(doc.text, params.edits, fileNameOf(doc.uri))) {
                is GraphEditSynthesizer.Result.Ok -> wholeDocumentEdit(doc.uri, doc.version, doc.text, r.newText)
                is GraphEditSynthesizer.Result.Err ->
                    throw ResponseErrorException(
                        ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed,
                            "${r.id.id}: ${r.message}",
                            JsonObject().apply { addProperty("code", r.id.id) },
                        ),
                    )
            }
        }

    fun getLayout(params: GetLayoutParams): CompletableFuture<GetLayoutResult> =
        CompletableFuture.supplyAsync {
            layoutService.getLayout(params.uri, currentGraph(params.uri))
        }

    fun setLayout(params: SetLayoutParams): CompletableFuture<SetLayoutResult> =
        CompletableFuture.supplyAsync {
            layoutService.setLayout(params.uri, params.layout, currentGraph(params.uri))
        }

    /** The authored graph for [uri], or null when it does not compile / is not open. */
    private fun currentGraph(uri: String): org.tatrman.ttrp.graph.model.TtrpGraph? {
        val text = docs.get(uri)?.text ?: return null
        val ctx = projects.resolve(uri)
        return TtrpPipeline(ctx.manifest, ctx.modelsRoot).plan(text, fileNameOf(uri)).authoredGraph
    }

    fun getWorld(params: GetWorldParams): CompletableFuture<GetWorldResult> =
        CompletableFuture.supplyAsync {
            // Unversioned (contracts §4): resolve the resolved world for the open (or on-disk) document.
            val text = docs.get(params.uri)?.text ?: ""
            val ctx = projects.resolve(params.uri)
            val report = TtrpChecker(ctx.manifest, ctx.modelsRoot).check(text, fileNameOf(params.uri))
            val world =
                report.world
                    ?: throw ResponseErrorException(
                        ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed,
                            "no resolved world for ${params.uri}",
                            null,
                        ),
                    )
            GetWorldResult(
                world = world.qname.name,
                fingerprint = world.fingerprint,
                engines = world.engines.map { EngineView(it.qname.name, it.type, it.version) },
                executors = world.executors.map { EngineView(it.qname.name, it.type, it.version) },
                storages = world.storages.map { StorageView(it.qname.name, it.type, it.staging) },
                staging = world.staging?.qname?.name,
            )
        }

    fun transpile(params: TranspileParams): CompletableFuture<TranspileResult> =
        CompletableFuture.supplyAsync {
            val doc = requireVersion(params.uri, params.version)
            val result = buildBundle(doc)
            val manifestJson =
                gson.fromJson(Files.readString(result.dir.resolve("manifest.json")), JsonObject::class.java)
            TranspileResult(result.dir.toString(), manifestJson)
        }

    fun run(params: RunParams): CompletableFuture<RunResult> =
        CompletableFuture.supplyAsync {
            val doc = requireVersion(params.uri, params.version)
            val bundle = buildBundle(doc).dir
            // Redirect stdout+stderr to a file, never a pipe: an undrained pipe deadlocks `waitFor()`
            // once the child writes past the OS buffer (~64 KB). The merged log stays in the bundle.
            val logFile = bundle.resolve("run.log").toFile()
            val proc =
                ProcessBuilder("bash", "run.sh")
                    .directory(bundle.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                    .start()
            val exit = proc.waitFor()
            val outDir = bundle.resolve("out")
            val outputs =
                if (Files.isDirectory(outDir)) {
                    Files.list(outDir).use { s -> s.map { it.toString() }.sorted().toList() }
                } else {
                    emptyList()
                }
            RunResult(runId = bundle.fileName.toString(), exitCode = exit, out = outputs)
        }

    fun validate(params: ValidateParams): CompletableFuture<ValidateResult> =
        CompletableFuture.supplyAsync {
            // Candidate text: explicit `source`, else the current document (side-effect-free).
            val uri = params.uri ?: "<candidate>"
            val text =
                params.source
                    ?: params.uri?.let { docs.get(it)?.text }
                    ?: throw ResponseErrorException(
                        ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams,
                            "ttrp/validate needs `source` or an open `uri`",
                            null,
                        ),
                    )
            val ctx = projects.resolve(uri)
            val report = TtrpChecker(ctx.manifest, ctx.modelsRoot).check(text, fileNameOf(uri))
            ValidateResult(report.diagnostics.map { toValidateDiagnostic(it) })
        }

    fun authoringContext(params: AuthoringContextParams): CompletableFuture<AuthoringContextResult> =
        CompletableFuture.supplyAsync {
            val uri = params.uri
            val text = uri?.let { docs.get(it)?.text } ?: ""
            val resolveUri = uri ?: "<memory>"
            val ctx = projects.resolve(resolveUri)
            val report = TtrpChecker(ctx.manifest, ctx.modelsRoot).check(text, fileNameOf(resolveUri))
            AuthoringContextResult(AuthoringContextBuilder.build(report, params.position))
        }

    // ---- helpers ----

    private fun buildBundle(doc: OpenDocument): BundleAssembler.BundleResult {
        val ctx = projects.resolve(doc.uri)
        val filePath = runCatching { Path.of(URI(doc.uri)) }.getOrNull()
        val outDir =
            filePath?.parent?.takeIf { Files.isWritable(it) }
                ?: Files.createTempDirectory("ttrp-bundle")
        return BundleAssembler(org.tatrman.ttrp.cli.ToolchainVersion.current)
            .build(doc.text, fileNameOf(doc.uri), ctx.manifest, ctx.modelsRoot, outDir)
    }

    private fun requireVersion(
        uri: String,
        version: Int,
    ): OpenDocument {
        val doc =
            docs.get(uri)
                ?: throw ResponseErrorException(
                    ResponseError(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams,
                        "no open document: $uri",
                        null,
                    ),
                )
        if (doc.version != version) {
            val data =
                JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("requested", version)
                    addProperty("current", doc.version)
                }
            throw ResponseErrorException(
                ResponseError(contentModified, "stale version $version, current ${doc.version} — replay", data),
            )
        }
        return doc
    }

    /** A `WorkspaceEdit` that replaces the whole document with [newText] (formatter-owned placement). */
    private fun wholeDocumentEdit(
        uri: String,
        version: Int,
        oldText: String,
        newText: String,
    ): WorkspaceEdit {
        val lines = oldText.split("\n")
        val endLine = lines.size - 1
        val endChar = lines.last().length
        val range = Range(Position(0, 0), Position(endLine, endChar))
        val textDocEdit =
            TextDocumentEdit(VersionedTextDocumentIdentifier(uri, version), listOf(TextEdit(range, newText)))
        return WorkspaceEdit(listOf(Either.forLeft(textDocEdit)))
    }

    private fun responseError(
        code: org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode,
        message: String,
    ) = ResponseErrorException(ResponseError(code, message, null))

    private fun toValidateDiagnostic(d: TtrpDiagnostic): ValidateDiagnostic =
        ValidateDiagnostic(
            code = d.id.id,
            severity = d.severity.name.lowercase(),
            message = d.message,
            suggestedAlternative = d.suggestedAlternative,
            line = d.location.line,
            column = d.location.column,
            endLine = d.location.endLine,
            endColumn = d.location.endColumn,
        )

    private fun fileNameOf(uri: String): String = runCatching { Path.of(URI(uri)).toString() }.getOrNull() ?: uri
}
