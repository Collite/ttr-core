// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.spi

import java.util.SortedMap
import java.util.TreeMap

/**
 * A deterministic fake [TtrEmitPlugin] (published test fixture, PL-P5.S1) for host wiring, isolation, and
 * H-6 determinism-kit suites. It records the last [EmitRequest] it saw and emits a single, boring launcher
 * file (`run.fake`) listing island names in wave order — a pure function of the request (no ambient input),
 * so it PASSES the determinism kit. [targetId] is injectable so a suite can register two distinct fakes.
 */
class RecordingFakePlugin(
    override val targetId: String = "fake",
) : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val spiVersion: Int = TtrEmitPlugin.SPI_VERSION

    /** The last request handed to [emit] — for host-side assertions on what the core passed. */
    var lastRequest: EmitRequest? = null
        private set

    override fun executorTypeManifest(): String = "def executor $targetId { type: $targetId }\n"

    override fun emit(request: EmitRequest): EmitResult {
        lastRequest = request
        val body =
            buildString {
                appendLine("# fake launcher for ${request.program.name}")
                request.graph.waves.forEachIndexed { i, wave ->
                    appendLine("wave $i: ${wave.joinToString(" ")}")
                }
            }
        val files: SortedMap<String, ByteArray> = TreeMap()
        files["run.fake"] = body.toByteArray()
        return EmitResult(files)
    }
}
