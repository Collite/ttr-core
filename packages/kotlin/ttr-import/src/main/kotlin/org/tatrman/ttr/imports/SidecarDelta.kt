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
 * generation's `<package>.sources.json` (where this run writes its own) against this run's entries. Per foreign id
 * (review-372 F3): when each side names it with exactly one qname and they differ, that is a rename `{from, to}`, even
 * across kinds (an EA stereotype change, `er.entity.X` → `md.dimension.X`); when either side names it with several
 * (one element fanned out to several kinds), its qnames are paired **within a kind** only, and a kind only one side
 * has (a cn skeleton turned on or off) is no rename. Ids only one side has, and entries without a foreign id, are no
 * rename. No previous sidecar, an unreadable one or one that is not the S3.6 shape means no renames (the run never
 * fails on it: the sidecar is regenerated whole).
 */
internal object SidecarDelta {
    /** One sidecar entry, as the diff sees it. */
    private data class Named(
        val kind: String,
        val qname: String,
    )

    fun renamed(
        previous: Path,
        current: List<SourceEntry>,
    ): List<ImportReport.Renamed> {
        val before = read(previous) ?: return emptyList()
        val now =
            current
                .filter { it.foreignId != null }
                .groupBy({ it.foreignId!! }, { Named(it.kind, it.qname) })
                .mapValues { it.value.toSet() }
        return before.flatMap { (foreignId, was) ->
            val after = now[foreignId].orEmpty()
            when {
                after.isEmpty() || after == was -> emptyList()
                was.size == 1 && after.size == 1 ->
                    listOf(ImportReport.Renamed(was.single().qname, after.single().qname, foreignId))
                        .filter { it.from != it.to }
                else ->
                    (was.map { it.kind } intersect after.map { it.kind }.toSet()).sorted().flatMap { kind ->
                        val from = was.filter { it.kind == kind }.map { it.qname }.toSet()
                        val to = after.filter { it.kind == kind }.map { it.qname }.toSet()
                        (from - to).sorted().zip((to - from).sorted()) { f, t -> ImportReport.Renamed(f, t, foreignId) }
                    }
            }
        }
    }

    /** foreignId → the (kind, qname) entries the sidecar at [path] names with it; null when there is none or it is not readable. */
    private fun read(path: Path): Map<String, Set<Named>>? {
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
                val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                foreignId to Named(kind, qname)
            }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }
}
