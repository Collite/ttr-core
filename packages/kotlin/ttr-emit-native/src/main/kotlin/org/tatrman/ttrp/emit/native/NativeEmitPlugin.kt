// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.native

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult
import org.tatrman.ttrp.emit.spi.RecipeEmitRequest

class NativeEmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "native"

    override fun executorTypeManifest(): String =
        requireNotNull(javaClass.getResourceAsStream("/manifests/native.ttrm")).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("native: recipe exporter required")

    override val supportsRecipes = true

    override fun emitRecipe(request: RecipeEmitRequest): EmitResult =
        EmitResult(
            sortedMapOf(
                "README.md" to request.readme.toByteArray(Charsets.UTF_8),
                "conformance.json" to request.conformanceJson.toByteArray(Charsets.UTF_8),
            ),
        )
}
