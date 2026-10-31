// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.python

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult

class PythonClientEmitPlugin : TtrEmitPlugin {
    override val spiRevision = TtrEmitPlugin.SPI_REVISION
    override val targetId = "python_client"

    override fun executorTypeManifest(): String =
        requireNotNull(
            javaClass.getResourceAsStream("/manifests/python_client.ttrm"),
        ).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("python_client: recipe exporter required")
}
