// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.python

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest

class PythonClientEmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "python_client"
    override val supportsRecipes = true

    override fun executorTypeManifest(): String =
        requireNotNull(
            javaClass.getResourceAsStream("/manifests/python_client.ttrm"),
        ).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("python_client: recipe exporter required")

    override fun emitRecipe(request: RecipeEmitRequest): EmitResult = PythonClientRenderer(request).render()
}
