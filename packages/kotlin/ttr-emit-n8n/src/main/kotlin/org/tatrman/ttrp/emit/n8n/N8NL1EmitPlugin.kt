// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*

class N8NL1EmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "n8n_l1"
    override val supportsRecipes = true
    override val supportsProcesses = true

    override fun executorTypeManifest(): String =
        requireNotNull(javaClass.getResourceAsStream("/manifests/n8n_l1.ttrm")).bufferedReader().use {
            it.readText()
        }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("n8n_l1: recipe exporter required")

    override fun emitRecipe(request: RecipeEmitRequest): EmitResult {
        val renderer = N8nRenderer(request)
        return EmitResult(
            sortedMapOf(
                "workflow.json" to renderer.render().toByteArray(Charsets.UTF_8),
                "README.md" to renderer.readme().toByteArray(Charsets.UTF_8),
                "conformance.json" to request.conformanceJson.toByteArray(Charsets.UTF_8),
            ),
        )
    }
}
