// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.ttrp.bundle.BundleAssembler
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic

/**
 * `ttrp build --json` (AU-core-P2, contracts §9): `{ "bundle": <dir>, "manifest": <manifest.json>, "diagnostics": [ … ] }`
 * on success; `{ "error": <message> }` when the build refuses (its errors are in the message, as on stderr).
 */
object BuildJson {
    private val pretty = Json { prettyPrint = true }

    fun success(r: BundleAssembler.BundleResult): String =
        pretty.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("bundle", r.dir.toString())
                put("manifest", Json.parseToJsonElement(r.manifest.toJson()))
                put("diagnostics", JsonArray(r.diagnostics.map { diagnostic(it) }))
            },
        )

    fun failure(message: String): String =
        pretty.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", message) })

    private fun diagnostic(d: TtrpDiagnostic) =
        buildJsonObject {
            put("id", d.id.id)
            put("severity", d.severity.name)
            put("message", d.message)
            put("file", JsonPrimitive(d.location.file))
            put("line", d.location.line)
            put("column", d.location.column)
        }
}
