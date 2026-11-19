// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.profile.SourceEntry
import java.io.IOException
import java.io.UncheckedIOException
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
 * S5.3a T4b: the previous generation is every `<package>.sources.json` directly under `--out` (where a run writes them)
 * **whose `generated-from` names this run's input** (✅R378-A: the path before `@`, resolved against `--out`; the hash is
 * not compared, since the input changes between generations), each entry in the package its file names, so the pairing
 * spans packages: an element moved between UML packages is a rename whose [ImportReport.Renamed] names both packages.
 * Another input's sidecars under the same `--out` are not this run's generation; a renamed input starts a fresh one.
 * A sidecar without a parsable header is skipped like an unreadable one. A previous package this run emits nothing for
 * is [Delta.emptied] (named even when every entry moved, review-378 F3); each of its entries whose foreign id this run
 * does not name is [ImportReport.Removed]. Listing `--out` never fails the run: an [IOException] or
 * [UncheckedIOException] (raised while the listing is read) means no previous generation (review-378 F6).
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
        val emptied: List<String> = emptyList(),
        val emptiedFiles: Map<String, List<String>> = emptyMap(),
    )

    /** The delta of [current] (generated from [input]) against the previous generation of [input] under [out]. */
    fun of(
        out: Path,
        input: Path,
        current: List<SourceEntry>,
    ): Delta {
        val previous = previous(out, real(input))
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
        val emptied = previous.filterKeys { it !in emitted }
        val removed =
            emptied.values
                .flatten()
                .filter { it.foreignId == null || it.foreignId !in now }
                .map { ImportReport.Removed(it.qname, it.foreignId) }
        val emptiedFiles =
            emptied.mapValues { (pkg, entries) ->
                entries
                    .map { it.qname.removePrefix("$pkg.").substringBefore('.') }
                    .distinct()
                    .sorted()
                    .map { "${pkg.replace('.', '/')}/$it.ttrm" }
            }
        return Delta(renamed, removed, emptied.keys.toList(), emptiedFiles)
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

    /**
     * package → its sidecar's entries, for every readable `<package>.sources.json` directly under [out] whose header
     * names [input] (a real path).
     */
    private fun previous(
        out: Path,
        input: Path,
    ): Map<String, List<SourceEntry>> {
        if (!Files.isDirectory(out)) return emptyMap()
        val names =
            try {
                Files.list(out).use { paths -> paths.map { it.fileName.toString() }.toList() }
            } catch (e: IOException) {
                return emptyMap()
            } catch (e: UncheckedIOException) {
                return emptyMap()
            }
        val directory = real(out)
        return names
            .filter { it.endsWith(SUFFIX) && it.length > SUFFIX.length }
            .sorted()
            .mapNotNull { name ->
                val pkg = name.removeSuffix(SUFFIX)
                val (from, entries) = read(out.resolve(name)) ?: return@mapNotNull null
                val header = from?.let { GeneratedHeader.parse("# $it") } ?: return@mapNotNull null
                if (directory.resolve(header.snapshot.path).normalize() != input) return@mapNotNull null
                pkg to entries.map { it.copy(pkg = pkg) }
            }.toMap()
    }

    /**
     * [path] with its nearest existing ancestor made real and the rest appended, as `GeneratedInputs` writes the
     * header's paths (between real paths: macOS `/tmp`, a linked `--out`).
     */
    private fun real(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing: Path? = absolute
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        existing ?: return absolute
        return try {
            existing.toRealPath().resolve(existing.relativize(absolute))
        } catch (e: IOException) {
            absolute
        }
    }

    private const val SUFFIX = ".sources.json"

    /**
     * The sidecar at [path]: its `generated-from` text and its entries (qname, kind, foreign id); null when it is not
     * readable or not the S3.6 shape.
     */
    private fun read(path: Path): Pair<String?, List<SourceEntry>>? {
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
        val from = ((value as JsonObject)["generated-from"] as? JsonPrimitive)?.contentOrNull
        return from to
            entries.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val qname = (obj["qname"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val foreignId = (obj["foreignId"] as? JsonPrimitive)?.contentOrNull
                val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                SourceEntry(qname, kind, "", foreignId, null, null)
            }
    }
}
