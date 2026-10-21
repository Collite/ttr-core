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
 * on success; `{ "diagnostics": [ … ] }` when the build refuses — every diagnostic of the compile, an emit failure as
 * one with its TTRP-EMT id (review-119 M8). A diagnostic is `{ id, severity, message, file, line, column }`.
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

    fun failure(diagnostics: List<TtrpDiagnostic>): String =
        pretty.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("diagnostics", JsonArray(diagnostics.map { diagnostic(it) })) },
        )

    /** An emit failure (no checker id): its TTRP-EMT code, at its location when it has one. */
    fun failure(e: org.tatrman.ttrp.emit.TtrpEmitException): String =
        pretty.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "diagnostics",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", e.id.code)
                                put("severity", "ERROR")
                                put("message", e.message ?: e.detail)
                                put("file", JsonPrimitive(e.location?.file))
                                put("line", JsonPrimitive(e.location?.line))
                                put("column", JsonPrimitive(e.location?.column))
                            },
                        ),
                    ),
                )
            },
        )

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
