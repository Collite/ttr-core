// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.spi

import java.util.SortedMap

/**
 * The E-1/EQ-1 emit-plugin SPI (contracts §8, PL-P5.S1). A plugin owns the **orchestration layer only** — it
 * renders the per-target launcher/flow (bash `run.sh`, a Kestra/Airflow flow, …) from the projected
 * [OrchestrationGraph]; the island/transfer payloads are handed in **finished** ([EmitRequest.islandPayloads])
 * and MUST be emitted byte-verbatim (the core has already written them, so a plugin normally references them by
 * path and never re-materialises them).
 *
 * **Discovery.** Hosts load plugins via `java.util.ServiceLoader` — each plugin jar ships a
 * `META-INF/services/org.tatrman.ttrp.emit.spi.TtrEmitPlugin` entry and a no-arg constructor — in an isolated
 * classloader (the compiler core's classpath never leaks into a plugin).
 *
 * **Determinism is a CONTRACT obligation.** Same [EmitRequest] ⇒ byte-identical [EmitResult]. No timestamps, no
 * random ids, no env reads, no filesystem/network access — the request is the plugin's ONLY input (there are no
 * ambient handles on this interface). Verified by the H-6 determinism kit (`ttrp conform emit-determinism`).
 *
 * The bash emitter is **extracted** as the proving plugin (`org.tatrman:ttr-emit-bash`): the SPI is proven by
 * extraction, not invented — if bash cannot be byte-identical to the pre-extraction emitter, the surface is wrong.
 */
interface TtrEmitPlugin {
    /** The orchestration target this plugin emits for: `"bash"` | `"kestra"` | `"airflow3"` | … */
    val targetId: String

    /** The SPI major version this plugin was built against; a host rejects a plugin whose version is outside [SUPPORTED_VERSIONS]. */
    val spiVersion: Int get() = SPI_VERSION

    /** Explicitly opt in after rebuilding against the D-140 data classes. Old binaries default to revision 0. */
    val spiRevision: Int get() = 0

    val supportsProcesses: Boolean get() = false

    val supportsRecipes: Boolean get() = false

    fun emitRecipe(request: RecipeEmitRequest): EmitResult =
        throw UnsupportedOperationException("$targetId does not support recipes")

    /** TTR-M text of the executor TYPE manifest this plugin ships (contracts §7, the T6-ownership amendment). */
    fun executorTypeManifest(): String

    /** Render the orchestration files for [request]. MUST be deterministic (see the class doc). */
    fun emit(request: EmitRequest): EmitResult

    companion object {
        /** The current SPI major version. */
        const val SPI_VERSION: Int = 2
        const val SPI_REVISION: Int = 1
        val SUPPORTED_VERSIONS: Set<Int> = setOf(1, 2)
    }
}

/** Program identity (contracts §8). [name] is the bare program file name; [qname] the world it targets. */
data class ProgramMeta(
    val name: String,
    val qname: String,
    val toolchain: String,
)

/**
 * The orchestration projection a launcher renders from (contracts §8) — the fields a per-target emitter needs,
 * kept **dependency-free** (no `RunManifest` leak into the SPI). [waves] reference island names / transfer
 * tokens; [connectionByIsland] gives each psql island the `TTR_CONN_*` env var name it reads (the island entry
 * is connection-agnostic by contract §5).
 */
data class OrchestrationGraph(
    val waves: List<List<String>>,
    val islands: List<EmitIsland>,
    val transfers: List<EmitTransfer>,
    val connections: List<String>,
    val displays: List<EmitDisplay>,
    val connectionByIsland: Map<String, String>,
)

data class EmitIsland(
    val name: String,
    val engine: String,
    /** `"psql"` | `"python3"`. */
    val invocation: String,
    /** The payload's path within the bundle (e.g. `islands/crunch.sql`). */
    val file: String,
    val connections: List<String> = emptyList(),
    val retries: Int? = null,
    val onFailureOf: String? = null,
    val params: List<String> = emptyList(),
)

data class EmitTransfer(
    val from: String,
    val to: String,
    val via: String,
    val file: String,
    val connections: List<String> = emptyList(),
)

data class EmitDisplay(
    val name: String,
    val file: String,
)

/**
 * A finished island/transfer payload the core already emitted (contracts §8): [bytes] are the exact file
 * content, [sha256] the `sha256:<hex>` the core recorded in `manifest.files`. Carried so a plugin CAN inspect
 * or embed a payload, but it MUST emit it verbatim — a plugin re-rendering a payload is a contract violation the
 * determinism kit + [sha256] pin catch. A regular class (not `data`) so the [bytes] array doesn't land in a
 * generated `equals`/`hashCode`; identity is by ([relPath], [sha256]).
 */
class IslandPayload(
    val name: String,
    val relPath: String,
    val bytes: ByteArray,
    val sha256: String,
) {
    override fun equals(other: Any?): Boolean =
        other is IslandPayload && relPath == other.relPath && sha256 == other.sha256

    override fun hashCode(): Int = 31 * relPath.hashCode() + sha256.hashCode()
}

/** A resolved TTR-M manifest, as text (the plugin's own executor TYPE manifest, or the world INSTANCE overlay). */
data class ResolvedManifest(
    val text: String,
)

/**
 * Everything a plugin's [TtrEmitPlugin.emit] sees (contracts §8) — the ONLY input (no ambient handles). Carries
 * the program identity, the projected [graph], the VERBATIM payloads (sha256-pinned), this plugin's resolved
 * type + world-instance manifests, and the finished `manifest.json` text ([manifestJson]) plugins embed or
 * reference but NEVER edit.
 */
data class EmitRequest(
    val program: ProgramMeta,
    val graph: OrchestrationGraph,
    val islandPayloads: List<IslandPayload>,
    val transferPayloads: List<IslandPayload>,
    val executorType: ResolvedManifest,
    val executorInstance: ResolvedManifest,
    val manifestJson: String,
)

/**
 * A plugin's orchestration output (contracts §8): [files] are paths (relative to the bundle root) → bytes. It is
 * a [SortedMap] so iteration order is deterministic by construction. The core writes `manifest.json`, the
 * `islands/`, `transfers/`, `schemas/` trees, and the compile-record sidecar ITSELF — a plugin returning any
 * of those paths is a contract violation ([CoreOwnedPaths.check], enforced by the host before writing).
 */
class EmitResult(
    val files: SortedMap<String, ByteArray>,
)

/** Raised when a plugin's [EmitResult] violates the §8 file-ownership contract (a core-owned path collision). */
class EmitContractException(
    message: String,
) : RuntimeException(message)

/**
 * The bundle paths the CORE owns (contracts §8) — a plugin's [EmitResult] may not return any of them. The
 * launcher a plugin emits (`run.sh`, a flow YAML, a DAG `.py`) is plugin territory; everything the core writes
 * is off-limits.
 */
object CoreOwnedPaths {
    fun isCoreOwned(path: String): Boolean =
        path == "manifest.json" ||
            path.startsWith("islands/") ||
            path.startsWith("transfers/") ||
            path.startsWith("schemas/") ||
            path.endsWith(".compile-record.json")

    /** Throw [EmitContractException] naming every core-owned path the plugin tried to return. */
    fun check(result: EmitResult) {
        val bad = result.files.keys.filter(::isCoreOwned)
        if (bad.isNotEmpty()) {
            throw EmitContractException(
                "emit plugin returned ${bad.size} core-owned path(s) $bad — the core writes manifest.json / " +
                    "islands/* / transfers/* / schemas/* / *.compile-record.json itself; a plugin owns only the " +
                    "orchestration launcher.",
            )
        }
    }
}
