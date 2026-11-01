// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.airflow3

import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import java.util.SortedMap
import java.util.TreeMap

/**
 * PL-P5.S4 — the Airflow 3 emit plugin (`targetId = "airflow3"`, contracts §8, EQ-3). Emits a single Python DAG
 * (`dag.py`) whose SHAPE is world-driven (E-3-γ), read from the executor **instance** manifest
 * ([EmitRequest.executorInstance], populated by the core from the world's executor entry):
 *  - **native** (standalone default) → [NativeDagRenderer]: islands run directly, no platform on the path.
 *  - **door-calling** (platform world) → [DoorCallingDagRenderer]: a whole-program task calls the program door.
 *
 * A CODE-defined target: `emit` is deterministic string codegen (no timestamps — a constant `start_date`; task ids
 * derived stably from island names), the heavier H-6 burden of a code-defined target discharged by construction.
 */
class Airflow3EmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId: String = "airflow3"

    override val spiVersion: Int = TtrEmitPlugin.SPI_VERSION

    override fun executorTypeManifest(): String = EXECUTOR_MANIFEST

    override fun emit(request: EmitRequest): EmitResult {
        val dagId = sanitizeDagId(request.program.name.removeSuffix(".ttrp"))
        val dag =
            when (val binding = InstanceBinding.parse(request.executorInstance.text)) {
                is InstanceBinding.Native -> NativeDagRenderer.render(dagId, request.graph)
                is InstanceBinding.DoorCalling ->
                    // The envelope defaults to the program id; the deploy binds the concrete envelope ref (@version).
                    DoorCallingDagRenderer.render(dagId, dagId, binding.doorConnection)
            }
        val files: SortedMap<String, ByteArray> = TreeMap()
        files["dag.py"] = dag.toByteArray()
        return EmitResult(files)
    }

    /** Airflow dag ids are `[A-Za-z0-9._-]+`; TTR program names already fit, but sanitize defensively. */
    private fun sanitizeDagId(raw: String): String {
        val cleaned =
            raw.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
        return cleaned.ifBlank { "dag" }
    }

    companion object {
        private val EXECUTOR_MANIFEST: String =
            Airflow3EmitPlugin::class.java
                .getResourceAsStream("/org/tatrman/ttrp/emit/airflow3/airflow3-executor.ttrm")
                ?.use { it.readBytes().decodeToString() }
                ?: error("ttr-emit-airflow3: bundled airflow3-executor.ttrm resource is missing")
    }
}
