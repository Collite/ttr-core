// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.tatrman.ttr.profile.SourceEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * MW-P5 S5.3 (XM D-9 "readers' sidecar diffs by foreign id", ⚑XM-31) — the rename half of a reader's delta: the previous
 * generation's `<package>.sources.json` (where this run writes its own) against this run's entries. A foreign id the
 * previous sidecar names with one qname and this run with another is a rename `{from, to}`; ids only one side has, and
 * entries without a foreign id, are no rename. No previous sidecar, an unreadable one or one that is not the S3.6 shape
 * means no renames (the run never fails on it: the sidecar is regenerated whole).
 */
internal object SidecarDelta {
    fun renamed(
        previous: Path,
        current: List<SourceEntry>,
    ): List<ImportReport.Renamed> {
        val before = read(previous) ?: return emptyList()
        val now = current.filter { it.foreignId != null }.groupBy { it.foreignId!! }
        return before.flatMap { (foreignId, qnames) ->
            val after = now[foreignId]?.map { it.qname }?.toSet().orEmpty()
            if (after.isEmpty() || after == qnames) {
                emptyList()
            } else {
                // One foreign id is one record; a fan-out (one element, several kinds) renames each of its qnames.
                (qnames - after).sorted().zip((after - qnames).sorted()) { from, to ->
                    ImportReport.Renamed(from, to, foreignId)
                }
            }
        }
    }

    /** foreignId → the qnames the sidecar at [path] names with it; null when there is none or it is not readable. */
    private fun read(path: Path): Map<String, Set<String>>? {
        if (!Files.isRegularFile(path)) return null
        val value =
            try {
                Json.parseToJsonElement(Files.readString(path))
            } catch (e: IOException) {
                return null
            } catch (e: SerializationException) {
                return null
            }
        val entries = ((value as? JsonObject)?.get("entries") as? JsonArray) ?: return null
        return entries
            .mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val qname = (obj["qname"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val foreignId = (obj["foreignId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                foreignId to qname
            }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }
}
