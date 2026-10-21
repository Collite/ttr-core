// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.protocol

import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import java.util.concurrent.CompletableFuture

/**
 * The custom `ttrp/…` methods (contracts §4), declared in Stage 4.1 and implemented
 * in Stage 4.2. Wire names are `ttrp/<method>` via [JsonSegment].
 *
 * **Versioning discipline (contracts §4):** every versioned method carries `{uri,
 * version}`; a request whose `version` no longer matches the open document is
 * rejected with `ResponseError(ContentModified (-32801), …)` and the client
 * replays — see `requireVersion` in the Stage-4.2 implementation.
 */
@JsonSegment("ttrp")
interface TtrpCustomApi {
    /** The authored graph + derived orchestration overlay the Designer canvas renders (Stage 5.1). */
    @JsonRequest
    fun getGraph(params: GetGraphParams): CompletableFuture<GetGraphResult>

    /** The resolved world (engine/target palette) for the document (Stage 5.1). */
    @JsonRequest
    fun getWorld(params: GetWorldParams): CompletableFuture<GetWorldResult>

    /** Read the `.ttrl` view-state sidecar (parsed) + orphan/pair-integrity flags (Stage 5.2). */
    @JsonRequest
    fun getLayout(params: GetLayoutParams): CompletableFuture<GetLayoutResult>

    /** Rewrite the `.ttrl` sidecar wholesale from the payload (Stage 5.2). */
    @JsonRequest
    fun setLayout(params: SetLayoutParams): CompletableFuture<SetLayoutResult>

    /** β edit vocabulary → formatter-owned WorkspaceEdit (Stage 5.4); stale version ⇒ TTRP-EDIT-001. */
    @JsonRequest
    fun applyGraphEdit(params: ApplyGraphEditParams): CompletableFuture<WorkspaceEdit>

    @JsonRequest
    fun transpile(params: TranspileParams): CompletableFuture<TranspileResult>

    @JsonRequest
    fun run(params: RunParams): CompletableFuture<RunResult>

    @JsonRequest
    fun explain(params: ExplainParams): CompletableFuture<ExplainResult>

    @JsonRequest
    fun validate(params: ValidateParams): CompletableFuture<ValidateResult>

    @JsonRequest
    fun authoringContext(params: AuthoringContextParams): CompletableFuture<AuthoringContextResult>
}

/** The single LSP surface both transports (stdio, WS) expose: standard LSP + `ttrp/…`. */
interface TtrpLanguageServerApi :
    LanguageServer,
    TtrpCustomApi

// ---- param/result shapes (mirror contracts §4 exactly) ----

data class TranspileParams(
    val uri: String = "",
    val version: Int = 0,
)

data class TranspileResult(
    val bundlePath: String,
    /** The parsed `manifest.json` object (contracts §5), as a generic JSON tree. */
    val manifest: com.google.gson.JsonObject?,
)

data class RunParams(
    val uri: String = "",
    val version: Int = 0,
)

data class RunResult(
    val runId: String,
    val exitCode: Int,
    val out: List<String>,
)

data class ExplainParams(
    val uri: String = "",
    val version: Int = 0,
    val node: String? = null,
)

data class ExplainResult(
    val text: String,
    val ok: Boolean,
)

data class ValidateParams(
    val source: String? = null,
    val uri: String? = null,
    val dialect: String? = null,
)

data class ValidateDiagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val suggestedAlternative: String?,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    /**
     * AU-core-P2 · S2.4: the recipe block the finding sits in — a role container's or the decision's name, `actions`,
     * or `recipe` for a header; absent outside a recipe (the generate→validate→repair loop repairs block by block).
     */
    val block: String? = null,
)

data class ValidateResult(
    val diagnostics: List<ValidateDiagnostic>,
)

data class AuthoringContextParams(
    val uri: String? = null,
    val position: org.eclipse.lsp4j.Position? = null,
)

/** The authoring-context bundle (contracts §7); the concrete schema is finalized in Stage 4.2. */
data class AuthoringContextResult(
    val bundle: com.google.gson.JsonObject,
)
