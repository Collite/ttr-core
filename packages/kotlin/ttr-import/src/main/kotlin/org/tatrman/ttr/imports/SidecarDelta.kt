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
 * MW-P5 S5.3 (XM D-9 "readers' sidecar diffs by foreign id", ⚑XM-31) — a reader's delta: the previous generation's
 * sidecars against this run's entries. Per foreign id (review-372 F3): when each side names it with exactly one qname
 * and they differ, that is a rename `{from, to}`, even across kinds (an EA stereotype change, `er.entity.X` →
 * `md.dimension.X`); when either side names it with several (one element fanned out to several kinds), its qnames are
 * paired **within a kind** only, and a kind only one side has (a cn skeleton turned on or off) is no rename. Ids only
 * one side has, and entries without a foreign id, are no rename. An unreadable sidecar, or one that is not the S3.6
 * shape, is skipped (the run never fails on it: the sidecar is regenerated whole).
 *
 * S5.3a T4b: the previous generation is **every** `<package>.sources.json` directly under `--out` (where a run writes
 * them), each entry in the package its file names, so the pairing spans packages: an element moved between UML
 * packages is a rename whose [ImportReport.Renamed] names both packages. A previous package this run emits nothing
 * for is emptied: each of its entries whose foreign id this run does not name is [ImportReport.Removed].
 */
internal object SidecarDelta {
    /** One sidecar entry, as the diff sees it. */
    private data class Named(
        val kind: String,
        val qname: String,
        val pkg: String,
    )

    /** What a run reports against the previous generation under [out]. */
    data class Delta(
        val renamed: List<ImportReport.Renamed>,
        val removed: List<ImportReport.Removed>,
    )

    fun of(
        out: Path,
        current: List<SourceEntry>,
    ): Delta {
        val previous = previous(out)
        val before =
            previous.values
                .flatten()
                .filter { it.foreignId != null }
                .groupBy({ it.foreignId!! }, { Named(it.kind, it.qname, it.pkg) })
                .mapValues { it.value.toSet() }
        val now =
            current
                .filter { it.foreignId != null }
                .groupBy({ it.foreignId!! }, { Named(it.kind, it.qname, it.pkg) })
                .mapValues { it.value.toSet() }
        val renamed =
            before.flatMap { (foreignId, was) ->
                val after = now[foreignId].orEmpty()
                when {
                    after.isEmpty() || after == was -> emptyList()
                    was.size == 1 && after.size == 1 ->
                        listOfNotNull(rename(was.single(), after.single(), foreignId))
                    else ->
                        (was.map { it.kind } intersect after.map { it.kind }.toSet()).sorted().flatMap { kind ->
                            val from = was.filter { it.kind == kind }.associateBy { it.qname }
                            val to = after.filter { it.kind == kind }.associateBy { it.qname }
                            (from.keys - to.keys).sorted().zip((to.keys - from.keys).sorted()) { f, t ->
                                rename(from.getValue(f), to.getValue(t), foreignId)!!
                            }
                        }
                }
            }
        val emitted = current.map { it.pkg }.toSet()
        val removed =
            previous
                .filterKeys { it !in emitted }
                .values
                .flatten()
                .filter { it.foreignId == null || it.foreignId !in now }
                .map { ImportReport.Removed(it.qname, it.foreignId) }
        return Delta(renamed, removed)
    }

    private fun rename(
        was: Named,
        now: Named,
        foreignId: String,
    ): ImportReport.Renamed? =
        when {
            was.qname == now.qname -> null
            was.pkg == now.pkg -> ImportReport.Renamed(was.qname, now.qname, foreignId)
            else -> ImportReport.Renamed(was.qname, now.qname, foreignId, ImportReport.Moved(was.pkg, now.pkg))
        }

    /** package → its sidecar's entries, for every readable `<package>.sources.json` directly under [out]. */
    private fun previous(out: Path): Map<String, List<SourceEntry>> {
        if (!Files.isDirectory(out)) return emptyMap()
        val names =
            try {
                Files.list(out).use { paths -> paths.map { it.fileName.toString() }.toList() }
            } catch (e: IOException) {
                return emptyMap()
            }
        return names
            .filter { it.endsWith(SUFFIX) && it.length > SUFFIX.length }
            .sorted()
            .mapNotNull { name ->
                val pkg = name.removeSuffix(SUFFIX)
                read(out.resolve(name))?.let { pkg to it.map { e -> e.copy(pkg = pkg) } }
            }.toMap()
    }

    private const val SUFFIX = ".sources.json"

    /** The entries of the sidecar at [path] (qname, kind, foreign id); null when it is not readable or not the S3.6 shape. */
    private fun read(path: Path): List<SourceEntry>? {
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
        return entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val qname = (obj["qname"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val foreignId = (obj["foreignId"] as? JsonPrimitive)?.contentOrNull
            val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SourceEntry(qname, kind, "", foreignId, null, null)
        }
    }
}
