// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.tatrman.ttr.yaml.CanonicalYamlException
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

    /**
     * Read only when an orphan needs a suggestion: an empty document is no renames, a leading `---` is fine, and a
     * malformed ledger is reported at `renames.yaml`, at the parser's own line. YAML 1.2: an unquoted date or yes/no
     * is a string here, but `ttr-metadata export` (YAML 1.1) rejects it; quote them.
     */
    fun read(root: Path?): List<RenameEntry> {
        val file = root?.resolve(FILE)?.takeIf { Files.isRegularFile(it) } ?: return emptyList()
        val text =
            try {
                readProfileYaml(file)
            } catch (e: IOException) {
                invalid("", "Cannot read renames.yaml (${e::class.simpleName})")
            } catch (e: CanonicalYamlException) {
                // The reader names the absolute path and says "profile"; every ledger error names renames.yaml.
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(FILE, e.line, e.column, e.line, e.column, ""),
                    e.detail.replace(Regex("\\bprofile\\b", RegexOption.IGNORE_CASE), FILE),
                )
            }
        val value =
            try {
                Load(LoadSettings.builder().build()).loadFromString(text)
            } catch (e: YamlEngineException) {
                val mark = (e as? MarkedYamlEngineException)?.problemMark?.orElse(null)
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(
                        FILE,
                        (mark?.line ?: 0) + 1,
                        (mark?.column ?: 0) + 1,
                        (mark?.line ?: 0) + 1,
                        (mark?.column ?: 0) + 1,
                        "",
                    ),
                    "renames.yaml is not YAML: ${e.message}",
                )
            } ?: return emptyList()
        val list = value as? List<*> ?: invalid("", "renames.yaml must be a list")
        return list.mapIndexed { i, entry ->
            if (entry !is Map<*, *> || entry.keys != setOf("from", "to", "since")) {
                invalid("/$i", "rename requires from, to, since")
            }

            fun text(key: String) =
                (entry[key] as? String)?.takeIf { it.isNotBlank() }
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
     * A path overlay resolves relative to the declaring profile, whose label is absolute or relative to [root] (never
     * the cwd), and must stay inside [root] (or, without a root, inside the profile's directory); an inline overlay
     * keeps the profile's own positions.
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
        // The declaring label is absolute, or relative to the root, as ProfileResolver reads it; never the cwd.
        val declaring =
            try {
                Path.of(profile.file).let { if (it.isAbsolute) it else root?.resolve(it) }
            } catch (e: java.nio.file.InvalidPathException) {
                throw ProfileException("ys/profile-not-found", at, "Invalid profile path: ${profile.file}")
            }
                ?: throw ProfileException(
                    "ys/profile-not-found",
                    at,
                    "A path overlay needs a root or an absolute profile file",
                )
        val boundary =
            try {
                (root ?: declaring.parent).toRealPath()
            } catch (e: IOException) {
                throw ProfileException("ys/profile-not-found", at, "Overlay not found: $reference")
            }
        val file = confinedFile("Overlay", reference, at, boundary) { declaring.parent.resolve(reference) }
        val data =
            try {
                profileRead { YamlData.read(readProfileYaml(file), file.toString()) }
            } catch (e: IOException) {
                throw ProfileException("ys/profile-not-found", at, "Overlay not found: $reference")
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
                    if (candidate.record != null) {
                        listOf(candidate)
                    } else {
                        index.qname(candidate.qname).filter { it.record != null }
                    }
                }.distinctBy { it.qname }
        // ✅R251-A: under fan-out one name yields several records; keep those whose kind defines the property (none
        // left: all, and canonical validation reports the property), then prefer a record outside the binding model.
        val defining = named.filter { ProfileVocabulary.defines(it.record!!, entry.property) }.ifEmpty { named }
        val primary =
            defining
                .takeIf { it.size > 1 }
                ?.filter { ProfileVocabulary.model(it.kind) != "binding" }
                ?.ifEmpty { null } ?: defining
        return when (primary.size) {
            1 -> primary.single()
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
                "Overlay key ${entry.key} matches ${primary.joinToString {
                    it.qname
                }}; key the overlay by one of these qnames",
            )
        }
    }
}
