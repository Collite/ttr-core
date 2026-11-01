// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest

class Camunda7EmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "camunda_7"
    override val supportsRecipes = true
    override val supportsProcesses = true

    override fun executorTypeManifest(): String =
        requireNotNull(
            javaClass.getResourceAsStream("/manifests/camunda_7.ttrm"),
        ).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("camunda_7: recipe exporter required")

    override fun emitRecipe(request: RecipeEmitRequest): EmitResult = emitCamunda(request, Dialect.C7)
}

internal fun emitCamunda(
    request: RecipeEmitRequest,
    dialect: Dialect,
): EmitResult {
    val renderer = CamundaRenderer(request, dialect)
    val xml = renderer.render()
    return EmitResult(
        sortedMapOf(
            "${request.graph.recipe.name}.${dialect.suffix}.bpmn" to xml.toByteArray(Charsets.UTF_8),
            "README.md" to renderer.readme().toByteArray(Charsets.UTF_8),
            "conformance.json" to request.conformanceJson.toByteArray(Charsets.UTF_8),
        ),
    )
}
