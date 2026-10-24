// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult

class N8NL1EmitPlugin : TtrEmitPlugin {
    override val targetId = "n8n_l1"

    override fun executorTypeManifest(): String =
        requireNotNull(javaClass.getResourceAsStream("/manifests/n8n_l1.ttrm")).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("n8n_l1: recipe exporter required")
}
