// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sqljob

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.EmitResult

class SqlJobEmitPlugin : TtrEmitPlugin {
    override val targetId = "sql_job"

    override fun executorTypeManifest(): String =
        requireNotNull(javaClass.getResourceAsStream("/manifests/sql_job.ttrm")).bufferedReader().use { it.readText() }

    override fun emit(request: EmitRequest): EmitResult =
        throw UnsupportedOperationException("sql_job: recipe exporter required")
}
