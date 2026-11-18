// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Path

/** [file] is the dialect file the document came from when the run grouped documents per file (MW S3.4). */
data class EmittedDocument(
    val canonical: CanonicalDocument,
    val locations: Map<String, ProfileSpan>,
    val file: String? = null,
) {
    // The execution envelope keeps imports explicit; the P2 text codec omits an empty list.
    val value: JsonObject =
        buildJsonObject {
            put("ttr", canonical.value.getValue("ttr"))
            put("package", canonical.value.getValue("package"))
            put("imports", canonical.value["imports"] ?: JsonArray(emptyList()))
            canonical.value.forEach { (key, value) -> put(key, value) }
        }

    fun write(): String = CanonicalYaml.write(canonical)
}

/**
 * One `ignored[]` entry. [record] is set when a whole record was skipped by a `kinds` mapping value `null`
 * (✅D-MW-81, S5.2a): the id the record would have had, or null when it has no string id.
 */
data class Ignored(
    val location: ProfileSpan,
    val reason: String?,
    val record: String? = null,
)

data class ApplyReport(
    val codes: Map<String, String>,
    val overridden: List<Overridden> = emptyList(),
    val warnings: List<ProfileWarning> = emptyList(),
)

/**
 * ✅D-MW-87: a report warning — the run went on, and [message] says what the engine did with input a generic profile did
 * not expect (`each key missing → en`, `each key repeated (en)`). [record] is the record's id; [location] the element.
 *
 * ✅D-MW-89 (S5.2c): under `unknown: ignore`, one warning per (file, [scope], [key]) for the keys no operation consumed —
 * [message] `not in this profile`, [location] the first occurrence, [scope] the record kind they were dropped from (null
 * for a file-scoped key), [key] their pointer relative to that record (relative to the file when file-scoped) with list
 * indices as `*`, and [count] the records they were dropped from (a file-scoped key's occurrences).
 */
data class ProfileWarning(
    val location: ProfileSpan,
    val message: String,
    val record: String? = null,
    val scope: String? = null,
    val key: String? = null,
    val count: Int? = null,
) {
    /**
     * The warning as one line, without its location: `<record>: <message>`, or for a dropped key (✅D-MW-89)
     * `not in this profile: <key> in <kind> (<n> records)` — `(<n> occurrences)` when file-scoped. The reader's report
     * and the loader's WARNINGs both say it this way (review-361 F1).
     */
    fun describe(): String =
        when {
            key == null -> "${record?.let { "$it: " } ?: ""}$message"
            scope == null -> "$message: $key (${plural(count, "occurrence")})"
            else -> "$message: $key in $scope (${plural(count, "record")})"
        }

    private fun plural(
        count: Int?,
        noun: String,
    ) = if (count == 1) "1 $noun" else "$count ${noun}s"
}

/**
 * One `<package>.sources.json` entry (contracts §6, XM D-9): a record of the run by its [qname], with the foreign names
 * the link index matches — [sourceName] (`by: sourceName`), [foreignId] (`by: iri`) and [locator] (`by: path`).
 */
data class SourceEntry(
    val qname: String,
    val kind: String,
    val pkg: String,
    val foreignId: String?,
    val sourceName: String?,
    val locator: String?,
)

/**
 * Where one record of the run came from (review-350 F6): its [qname], [kind] and [pkg] as emitted, and the input [file]
 * (the tree's label) and node [path] that made it — the record's identity, which a reader joins its own reads to.
 */
data class RecordOrigin(
    val qname: String,
    val kind: String,
    val pkg: String,
    val file: String,
    val path: String,
)

/** [sources] are the run's records as sidecar entries (MW S3.6 T4), in record order; [origins] the same records' inputs. */
data class ApplyResult(
    val documents: List<EmittedDocument>,
    val ignored: List<Ignored>,
    val report: ApplyReport,
    val sources: List<SourceEntry> = emptyList(),
    val origins: List<RecordOrigin> = emptyList(),
) {
    /** The PR-body report: `documents[]` (package, model, imports, defs), `ignored[]`, `overridden[]` and `warnings[]`. */
    fun reportJson(): JsonObject =
        buildJsonObject {
            putJsonArray("documents") {
                documents.forEach { document ->
                    addJsonObject {
                        put("package", document.value.getValue("package"))
                        put("model", document.value.getValue("model"))
                        put("imports", document.value.getValue("imports"))
                        put(
                            "defs",
                            document.value
                                .getValue("defs")
                                .jsonArray.size,
                        )
                    }
                }
            }
            putJsonArray("ignored") {
                ignored.forEach { item ->
                    addJsonObject {
                        put("file", item.location.file)
                        put("line", item.location.line)
                        put("column", item.location.column)
                        put("path", item.location.path)
                        item.record?.let { put("record", it) }
                        put("reason", item.reason)
                    }
                }
            }
            putJsonArray("overridden") {
                report.overridden.forEach { item ->
                    addJsonObject {
                        put("qname", item.qname)
                        put("property", item.property)
                        put("from", item.from)
                        put("to", item.to)
                    }
                }
            }
            // ✅D-MW-87: written only when there is one, so a run without warnings reports byte for byte as before.
            if (report.warnings.isNotEmpty()) {
                putJsonArray("warnings") {
                    report.warnings.forEach { item ->
                        addJsonObject {
                            put("file", item.location.file)
                            put("line", item.location.line)
                            put("column", item.location.column)
                            put("path", item.location.path)
                            item.record?.let { put("record", it) }
                            // ✅D-MW-89: a dropped key's aggregate; absent on other warnings, which keep their bytes.
                            item.scope?.let { put("in", it) }
                            item.key?.let { put("key", it) }
                            item.count?.let { put("count", it) }
                            put("message", item.message)
                        }
                    }
                }
            }
        }
}

/**
 * [profile] is the resolved ancestor-to-child chain returned by [ProfileResolver]. [root] is the model root: link
 * resolution reads its `<package>.sources.json` sidecars, an orphan's suggestion its `renames.yaml`, and a path overlay
 * must stay inside it. Overlays apply ancestor first, so a child's entry wins. [sourceName] (✅R330-A, an API argument,
 * not an operation) is a JSON Pointer relative to each record's foreign node: where it selects a string (an OM FQN),
 * that is the record's `sourceName` — in [ApplyResult.sources] and for this run's `by: sourceName` links and overlay
 * keys; elsewhere, and without it, the record id.
 */
object ProfileEngine {
    fun apply(
        tree: List<ForeignTree>,
        profile: List<ProfileDefinition>,
        root: Path? = null,
        sourceName: String? = null,
    ): ApplyResult = apply(tree, profile, root, perFile = false, sourceName = sourceName)

    /**
     * As above; with [perFile] each emitted document holds the records of one input file ([EmittedDocument.file]), so a
     * loader can keep the dialect file as the document's path (contracts §4). Links still resolve across the whole run.
     */
    fun apply(
        tree: List<ForeignTree>,
        profile: List<ProfileDefinition>,
        root: Path?,
        perFile: Boolean,
        sourceName: String? = null,
    ): ApplyResult =
        StructuralExecutor.apply(
            tree,
            profile.flatMap { it.operations },
            profile.flatMap { OverlayLoader.load(it, root) },
            root,
            perFile,
            sourceName?.let(::JsonPointer),
            UnknownKeys.of(profile),
        )
}
