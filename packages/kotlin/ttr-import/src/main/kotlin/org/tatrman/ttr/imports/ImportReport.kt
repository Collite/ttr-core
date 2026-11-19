// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ApplyResult

/**
 * A reader run's report (contracts §1, XM 06 §2.6): `{ignored[], overridden[], packages[], warnings[]}` — what the source
 * carried that the profile ignored (with the profile's reason), what an overlay set (replaced, added, re-kinded), what was generated — written as
 * `report.json` beside the package and rendered by [markdown] as the PR-body section. Entries are sorted; no timestamps.
 */
data class ImportReport(
    val frontEnd: String,
    val profile: String,
    val ignored: List<Ignored>,
    val overridden: List<Overridden>,
    val packages: List<Package>,
    val warnings: List<String> = emptyList(),
    /** The front-end's record collection ([FrontEnd.records]); only [markdown]'s folding reads it. */
    val records: String? = null,
    /**
     * MW-P5 S5.3 (XM D-9, ⚑XM-31): the sidecar diff against the previous generation — records whose `foreignId` the
     * previous `<package>.sources.json` under `--out` names with another qname. Written as `delta.renamed` only when
     * there is one, so a run without renames keeps its bytes.
     */
    val renamed: List<Renamed> = emptyList(),
    /**
     * S5.3a T4b: the entries of a previous `<package>.sources.json` under `--out` that this run emits nothing for, whose
     * foreign id this run does not name. Written as `delta.removed` only when there is one.
     */
    val removed: List<Removed> = emptyList(),
    /**
     * review-378 F3: the previous generation's packages this run emits nothing for, named even when every entry moved.
     * Written as `delta.emptied` only when there is one.
     */
    val emptied: List<String> = emptyList(),
    /** Each emptied package's own model files, derived from its previous sidecar (S5.4 T0, review-378 N1). */
    val emptiedFiles: Map<String, List<String>> = emptyMap(),
) {
    /**
     * One rename: the record with this foreign id was [from] in the previous generation and is [to] now; [moved] names
     * both packages when it moved between them (S5.3a T4b).
     */
    data class Renamed(
        val from: String,
        val to: String,
        val foreignId: String,
        val moved: Moved? = null,
    )

    /** The package a renamed record was in ([from]) and is in now ([to]). */
    data class Moved(
        val from: String,
        val to: String,
    )

    /** One entry of an emptied previous package: its [qname], and its [foreignId] when it had one. */
    data class Removed(
        val qname: String,
        val foreignId: String?,
    )

    /** One ignored key; [record] names the record a `kinds` null mapping skipped whole (S5.2a). */
    data class Ignored(
        val file: String,
        val line: Int,
        val column: Int,
        val path: String,
        val reason: String?,
        val record: String? = null,
    )

    data class Overridden(
        val qname: String,
        val property: String,
        val from: JsonElement,
        val to: JsonElement,
    )

    data class Package(
        val pkg: String,
        val model: String,
        val file: String,
        val definitions: Int,
    )

    fun json(): String =
        pretty.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("frontEnd", frontEnd)
                put("profile", profile)
                putJsonArray("packages") {
                    packages.forEach {
                        addJsonObject {
                            put("package", it.pkg)
                            put("model", it.model)
                            put("file", it.file)
                            put("defs", it.definitions)
                        }
                    }
                }
                putJsonArray("ignored") {
                    ignored.forEach {
                        addJsonObject {
                            put("file", it.file)
                            put("line", it.line)
                            put("column", it.column)
                            put("path", it.path)
                            it.record?.let { record -> put("record", record) }
                            put("reason", it.reason)
                        }
                    }
                }
                putJsonArray("overridden") {
                    overridden.forEach {
                        addJsonObject {
                            put("qname", it.qname)
                            put("property", it.property)
                            put("from", it.from)
                            put("to", it.to)
                        }
                    }
                }
                putJsonArray("warnings") { warnings.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                if (renamed.isNotEmpty() || removed.isNotEmpty() || emptied.isNotEmpty()) {
                    put(
                        "delta",
                        buildJsonObject {
                            if (renamed.isNotEmpty()) {
                                putJsonArray("renamed") {
                                    renamed.forEach {
                                        addJsonObject {
                                            put("from", it.from)
                                            put("to", it.to)
                                            put("foreignId", it.foreignId)
                                            it.moved?.let { moved ->
                                                put(
                                                    "package",
                                                    buildJsonObject {
                                                        put("from", moved.from)
                                                        put("to", moved.to)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (emptied.isNotEmpty()) {
                                putJsonArray("emptied") {
                                    emptied.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                }
                            }
                            if (removed.isNotEmpty()) {
                                putJsonArray("removed") {
                                    removed.forEach {
                                        addJsonObject {
                                            put("qname", it.qname)
                                            it.foreignId?.let { id -> put("foreignId", id) }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            },
        ) + "\n"

    /**
     * The PR-body section (XM 06 §2.6). Ignored keys are grouped by reason and by the key's path with record indices
     * folded to a star (`/data/12/x` reads `/data/<star>/x`), with a count and the first location, so a 500-table export stays readable;
     * every occurrence is in `report.json`.
     */
    fun markdown(): String =
        buildString {
            appendLine("### Import report — `$profile` ($frontEnd front-end)")
            appendLine()
            appendLine("| generated | model | definitions |")
            appendLine("|---|---|---|")
            packages.forEach { appendLine("| `${it.file}` | ${it.model} | ${it.definitions} |") }
            appendLine()
            appendLine("#### Ignored (${ignored.size})")
            appendLine()
            if (ignored.isEmpty()) {
                appendLine("Nothing the source carried was ignored.")
            } else {
                appendLine("| key | reason | count | first at |")
                appendLine("|---|---|---|---|")
                ignored
                    .groupBy { folded(it.path, records) to it.reason.orEmpty() }
                    .toSortedMap(
                        compareBy<Pair<String, String>, String>(Bytewise) { it.first }.thenBy(Bytewise) { it.second },
                    ).forEach { (key, items) ->
                        val first = items.first()
                        appendLine(
                            "| `${key.first.ifEmpty { "(file)" }}` | ${cell(
                                key.second.ifEmpty { "—" },
                            )} | ${items.size} | " +
                                "`${first.file}:${first.line}:${first.column}` |",
                        )
                    }
            }
            appendLine()
            appendLine("#### Overridden (${overridden.size})")
            appendLine()
            if (overridden.isEmpty()) {
                appendLine("No overlay entry set a value.")
            } else {
                appendLine("| qname | property | from | to |")
                appendLine("|---|---|---|---|")
                overridden.forEach {
                    appendLine(
                        "| `${it.qname}` | ${it.property} | `${cell(
                            it.from.toString(),
                        )}` | `${cell(it.to.toString())}` |",
                    )
                }
            }
            if (renamed.isNotEmpty()) {
                appendLine()
                appendLine("#### Renamed since the last generation (${renamed.size})")
                appendLine()
                appendLine(
                    "Add these to `renames.yaml` (with a `since`) so overlays and bindings keyed by the old name follow.",
                )
                appendLine()
                appendLine("| from | to | foreign id |")
                appendLine("|---|---|---|")
                renamed.forEach { appendLine("| `${it.from}` | `${it.to}` | `${it.foreignId}` |") }
            }
            if (emptied.isNotEmpty()) {
                appendLine()
                appendLine("#### Emptied since the last generation (${emptied.size})")
                appendLine()
                appendLine(
                    "These packages are no longer generated; delete their files under `--out` (the run does not):",
                )
                appendLine()
                emptied.forEach { pkg ->
                    val paths = listOf("$pkg.sources.json") + emptiedFiles[pkg].orEmpty()
                    appendLine("- `$pkg`: ${paths.joinToString(" and ") { "`$it`" }}")
                }
            }
            if (removed.isNotEmpty()) {
                appendLine()
                appendLine("#### Removed since the last generation (${removed.size})")
                appendLine()
                appendLine("Entries of the emptied packages that this run does not generate anywhere else.")
                appendLine()
                removed.forEach { appendLine("- `${it.qname}`" + (it.foreignId?.let { id -> " (`$id`)" } ?: "")) }
            }
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("#### Warnings (${warnings.size})")
                appendLine()
                warnings.forEach { appendLine("- ${cell(it)}") }
            }
        }

    companion object {
        private val pretty = Json { prettyPrint = true }

        /**
         * Record indices folded to a star: `/data/12/columns/3/profile` → `/data/<star>/columns/<star>/profile`; so is the
         * key beneath the front-end's record collection (`/subjects/<iri>/skos:altLabel` → `/subjects/<star>/skos:altLabel`).
         */
        private fun folded(
            path: String,
            records: String?,
        ): String {
            val keyed =
                records?.takeIf { path.startsWith("$it/") }?.let { prefix ->
                    "$prefix/*" +
                        path.removePrefix("$prefix/").let { rest ->
                            rest.indexOf('/').let {
                                if (it <
                                    0
                                ) {
                                    ""
                                } else {
                                    rest.substring(it)
                                }
                            }
                        }
                } ?: path
            return keyed.split('/').joinToString("/") { if (it.matches(Regex("[0-9]+"))) "*" else it }
        }

        private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")

        internal fun of(
            frontEnd: String,
            profile: String,
            result: ApplyResult,
            packages: List<Package>,
            warnings: List<String> = emptyList(),
            records: String? = null,
            renamed: List<Renamed> = emptyList(),
            removed: List<Removed> = emptyList(),
            emptied: List<String> = emptyList(),
            emptiedFiles: Map<String, List<String>> = emptyMap(),
            frontEndIgnored: List<Ignored> = emptyList(),
        ): ImportReport =
            ImportReport(
                frontEnd,
                profile,
                result.ignored
                    .map {
                        Ignored(
                            it.location.file,
                            it.location.line,
                            it.location.column,
                            it.location.path,
                            it.reason,
                            it.record,
                        )
                    }.plus(frontEndIgnored)
                    .distinct()
                    .sortedWith(
                        compareBy<Ignored, String>(Bytewise) { it.file }
                            .thenBy { it.line }
                            .thenBy { it.column }
                            .thenBy(Bytewise) { it.path },
                    ),
                result.report.overridden
                    .map { Overridden(it.qname, it.property, it.from, it.to) }
                    .sortedWith(compareBy<Overridden, String>(Bytewise) { it.qname }.thenBy(Bytewise) { it.property }),
                packages.sortedWith(compareBy(Bytewise) { it.file }),
                warnings,
                records,
                renamed.sortedWith(compareBy<Renamed, String>(Bytewise) { it.from }.thenBy(Bytewise) { it.to }),
                removed.sortedWith(compareBy(Bytewise) { it.qname }),
                emptied.sortedWith(Bytewise),
                emptiedFiles,
            )
    }
}
