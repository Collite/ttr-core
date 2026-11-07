// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin

/**
 * The estate's process engine (D-145): the C7 dialect, bound to the executor's egress (D-158), start groups on the
 * process (⚑R226-A) and element templates with their HTTP connector implementation. `ttrp emit --deploy` posts the
 * rendered BPMN and DMN to the engine (process-engine `contracts.md` §1a). It replaces the retired `native` type.
 */
class ProcessEngineEmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "process_engine"
    override val supportsRecipes = true

    override fun executorTypeManifest(): String =
        requireNotNull(
            javaClass.getResourceAsStream("/manifests/process_engine.ttrm"),
        ).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("process_engine: recipe exporter required")

    override fun emitRecipe(request: RecipeEmitRequest): EmitResult = emitCamunda(request, Dialect.C7, engine = true)
}
