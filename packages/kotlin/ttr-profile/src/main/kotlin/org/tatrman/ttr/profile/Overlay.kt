// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.YamlData
import org.tatrman.ttr.yaml.readProfileYaml
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One `{<qname|sourceName>: {<property>: <value>}}` pair of a profile's `overlay:`. */
data class OverlayEntry(
    val key: String,
    val property: String,
    val value: JsonElement,
    val keyLocation: ProfileSpan,
    val valueLocation: ProfileSpan,
)

/** The PR body's `overridden[]`: a property both the transform and the overlay supplied; the overlay won. */
data class Overridden(
    val qname: String,
    val property: String,
    val from: JsonElement,
    val to: JsonElement,
)

/** `renames.yaml` at the model root: `[{from, to, since}]` — the same list ttr-metadata's `ExportDelta` reads. */
internal data class RenameEntry(
    val from: String,
    val to: String,
    val since: String,
)

internal object Renames {
    private const val FILE = "renames.yaml"

    /** Read only when an orphan needs a suggestion. A malformed ledger is reported at the file, with its pointer. */
    fun read(root: Path?): List<RenameEntry> {
        val file = root?.resolve(FILE)?.takeIf { Files.isRegularFile(it) } ?: return emptyList()
        // YamlData reads mapping documents only; the ledger is a list, so it is read as the value of one key.
        val wrapped = "renames:\n" + readProfileYaml(file).lines().joinToString("\n") { "  $it" }
        val value = profileRead { YamlData.read(wrapped, FILE) }.value["renames"]
        val list = value as? JsonArray ?: invalid("", "renames.yaml must be a list")
        return list.mapIndexed { i, item ->
            val entry = item as? JsonObject
            if (entry == null ||
                entry.keys != setOf("from", "to", "since")
            ) {
                invalid("/$i", "rename requires from, to, since")
            }

            fun text(key: String) =
                (entry[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                    ?: invalid("/$i/$key", "rename $key must be a nonempty string")
            RenameEntry(text("from"), text("to"), text("since"))
        }
    }

    private fun invalid(
        path: String,
        detail: String,
    ): Nothing = throw ProfileException("ys/profile-invalid", ProfileSpan(FILE, 1, 1, 1, 1, path), detail)
}

object OverlayLoader {
    /**
     * A path overlay resolves relative to the declaring profile and must stay inside [root] (or, without a root, inside
     * the profile's directory); an inline overlay keeps the profile's own positions.
     */
    fun load(
        profile: ProfileDefinition,
        root: Path?,
    ): List<OverlayEntry> {
        val overlay = profile.overlay ?: return emptyList()
        if (overlay is JsonObject) {
            return entries(overlay, "/overlay") { path, key ->
                profile.locations[path] ?: profile.locations.getValue("/overlay")
            }
        }
        val at = profile.locations.getValue("/overlay")
        val reference = overlay.jsonPrimitive.content
        val data =
            try {
                val declaring = Path.of(profile.file).toAbsolutePath()
                val boundary = (root ?: declaring.parent).toRealPath()
                val file = declaring.parent.resolve(reference).toRealPath()
                if (!file.startsWith(boundary) || !Files.isRegularFile(file)) {
                    throw ProfileException(
                        "ys/profile-not-found",
                        at,
                        "Overlay is outside root or is not a file: $reference",
                    )
                }
                profileRead { YamlData.read(readProfileYaml(file), file.toString()) }
            } catch (e: IOException) {
                throw ProfileException("ys/profile-not-found", at, "Overlay not found: $reference")
            } catch (e: java.nio.file.InvalidPathException) {
                throw ProfileException("ys/profile-not-found", at, "Invalid overlay path: $reference")
            }
        val value =
            data.value as? JsonObject
                ?: throw ProfileException("ys/profile-invalid", data.span(""), "Overlay must be a mapping")
        return entries(value, "") { path, key -> data.span(path, key) }
    }

    private fun entries(
        overlay: JsonObject,
        prefix: String,
        span: (String, Boolean) -> ProfileSpan,
    ): List<OverlayEntry> =
        overlay.flatMap { (key, properties) ->
            val keyPath = "$prefix/${escapePointer(key)}"
            val map =
                properties as? JsonObject
                    ?: throw ProfileException(
                        "ys/profile-invalid",
                        span(keyPath, false),
                        "Overlay entry $key must be a mapping",
                    )
            map.map { (property, value) ->
                val path = "$keyPath/${escapePointer(property)}"
                OverlayEntry(key, property, value, span(keyPath, true), span(path, false))
            }
        }
}

internal object OverlayApplier {
    /** After every transform, before rendering: the overlay wins; a key naming no record is `xm/overlay-orphan`. */
    fun apply(
        entries: List<OverlayEntry>,
        index: LinkIndex,
        builder: CanonicalBuilder,
        root: Path?,
    ): List<Overridden> {
        if (entries.isEmpty()) return emptyList()
        val renames = lazy { Renames.read(root) }
        val overridden = mutableListOf<Overridden>()
        entries.forEach { entry ->
            val target = record(entry, index, renames)
            val previous = builder.overlay(target.record!!, entry.property, entry.value, entry.valueLocation)
            if (previous != null && previous != entry.value) {
                overridden += Overridden(target.qname, entry.property, previous, entry.value)
            }
        }
        return overridden
    }

    private fun record(
        entry: OverlayEntry,
        index: LinkIndex,
        renames: Lazy<List<RenameEntry>>,
    ): LinkEntry {
        val byQname = index.qname(entry.key).filter { it.record != null }
        if (byQname.isNotEmpty()) return byQname.first()
        // A sourceName names a run record directly, or a sidecar entry whose qname is one.
        val named =
            index
                .resolve("sourceName", entry.key, null)
                .flatMap { candidate ->
                    if (candidate.record !=
                        null
                    ) {
                        listOf(candidate)
                    } else {
                        index.qname(candidate.qname).filter { it.record != null }
                    }
                }.distinctBy { it.qname }
        return when (named.size) {
            1 -> named.single()
            0 -> {
                val rename = renames.value.firstOrNull { it.from == entry.key }
                throw ProfileException(
                    "xm/overlay-orphan",
                    entry.keyLocation,
                    "Overlay key ${entry.key} resolves to no record" + (rename?.let { "; renamed to ${it.to}" } ?: ""),
                    rename?.let {
                        buildJsonObject {
                            putJsonObject("delta") {
                                putJsonObject("renamed") {
                                    put("from", it.from)
                                    put("to", it.to)
                                    put("since", it.since)
                                }
                            }
                        }
                    },
                )
            }
            else -> throw ProfileException(
                "xm/link-ambiguous",
                entry.keyLocation,
                "Overlay key ${entry.key} matches ${named.joinToString { it.qname }}",
            )
        }
    }
}
