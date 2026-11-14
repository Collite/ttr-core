// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * MW S3.6 T5 — the capture seam of `ttr snapshot` (contracts §6, XM D-15): an adapter fetches one foreign tree. P3 has
 * the `file` adapter only (re-capture of an existing export); P5 adds the system adapters (`openmetadata`, …) behind
 * the same interface. [name] is the snapshot's file stem.
 */
interface SnapshotSource {
    val name: String

    fun fetch(): ForeignTree
}

/** `file:<path>` — one YAML or JSON export on disk. */
class FileSnapshotSource(
    val path: Path,
) : SnapshotSource {
    override val name: String = path.name.substringBeforeLast('.')

    override fun fetch(): ForeignTree {
        if (!path.isRegularFile()) {
            throw ProfileException("ys/profile-not-found", ProfileSpan("$path", 1, 1, 1, 1, ""), "No export file $path")
        }
        val text =
            try {
                Files.readString(path)
            } catch (e: IOException) {
                throw ProfileException(
                    "ys/profile-not-found",
                    ProfileSpan("$path", 1, 1, 1, 1, ""),
                    "Cannot read $path: ${e.message}",
                )
            }
        return ForeignTree.read(text, path.name, "$path")
    }
}

/**
 * The profile's `ignore` at capture time: the JSON pointers (as [ForeignNode.path] spells them) the profile ignores in
 * [tree], or `""` when it ignores the whole file. Only record discovery (`kinds`) and `ignore` run: a capture never
 * fails on a key the profile does not know yet — `apply` reports that. An ignore that would cover a record is left in
 * place, as `apply` refuses it (`ys/profile-conflict`).
 */
object CaptureFilter {
    fun ignored(
        tree: ForeignTree,
        profile: List<ProfileDefinition>,
    ): List<String> {
        val ops = profile.flatMap { it.operations }.map(::PlannedOp).filter { it.matches(tree) }
        val ignores = ops.filter { it.name == "ignore" }
        if (ignores.any { it.scope == null && "at" !in it.value }) return listOf("")
        if (ignores.isEmpty()) return emptyList()
        val plan = RecordPlan(tree, ops)
        return ignores
            .flatMap { op ->
                val contexts =
                    if (op.scope ==
                        null
                    ) {
                        listOf(tree.root)
                    } else {
                        plan.records.filter { it.kind == op.scope }.map { it.source }
                    }
                contexts.mapNotNull { op.at.resolve(it, includeNull = true) }
            }.filter { node ->
                plan.records.none { it.source.path == node.path || it.source.path.startsWith("${node.path}/") }
            }.map { it.path }
            .distinct()
    }

    /**
     * The JSON pointers of every record the profile discovers in [tree] (`kinds` only), as [ForeignNode.path] spells
     * them: the capture never renames or drops a record's own key ([Sanitise], review-277 F2).
     */
    fun records(
        tree: ForeignTree,
        profile: List<ProfileDefinition>,
    ): Set<String> {
        val ops = profile.flatMap { it.operations }.map(::PlannedOp).filter { it.matches(tree) }
        return RecordPlan(tree, ops).records.map { it.source.path }.toSet()
    }

    /** [element] without the values at [paths] (pointers rooted at [element], as [ignored] returns them). */
    fun prune(
        element: JsonElement,
        paths: Collection<String>,
    ): JsonElement = if ("" in paths) JsonObject(emptyMap()) else prune(element, "", paths.toSet())

    private fun prune(
        element: JsonElement,
        path: String,
        paths: Set<String>,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.entries
                        .filter { (key, _) -> "$path/${escapePointer(key)}" !in paths }
                        .associate { (key, value) -> key to prune(value, "$path/${escapePointer(key)}", paths) },
                )
            is JsonArray ->
                JsonArray(
                    element.withIndex().filter { "$path/${it.index}" !in paths }.map {
                        prune(it.value, "$path/${it.index}", paths)
                    },
                )
            else -> element
        }
}

/**
 * Snapshot hygiene (XM D-15) as pure functions over a foreign tree: principal names give way to role names, usage
 * counters are dropped. Keys match case-insensitively with `_` and `-` ignored (`created_by` = `createdBy`).
 *
 * A key whose pointer is a record's source path, or an ancestor of one ([CaptureFilter.records]), is never renamed or
 * dropped: a column named `owner` or `votes` is content (review-277 F2); its own properties are still sanitised. A key
 * the profile does not discover as a record is matched by name. Profile-driven lists are P5's.
 */
object Sanitise {
    /** A principal-valued key → the role name that replaces every principal it holds. */
    val roles: Map<String, String> =
        mapOf(
            "owner" to "owner",
            "owners" to "owner",
            "steward" to "steward",
            "stewards" to "steward",
            "datasteward" to "steward",
            "datastewards" to "steward",
            "expert" to "expert",
            "experts" to "expert",
            "domainexpert" to "expert",
            "domainexperts" to "expert",
            "reviewer" to "reviewer",
            "reviewers" to "reviewer",
            "author" to "author",
            "authors" to "author",
            "creator" to "author",
            "createdby" to "author",
            "updatedby" to "editor",
            "modifiedby" to "editor",
            "changedby" to "editor",
            "lastmodifiedby" to "editor",
            "lastupdatedby" to "editor",
            "assignee" to "assignee",
            "assignees" to "assignee",
        )

    /** Usage counters and access statistics: volatile, never model content. */
    val counters: Set<String> =
        setOf(
            "usagecount",
            "usagesummary",
            "querycount",
            "viewcount",
            "accesscount",
            "readcount",
            "writecount",
            "popularity",
            "frequentlyjoinedwith",
            "frequentlyjoinedtables",
            "lastaccessed",
            "lastaccessedat",
            "lastaccesstime",
            "lastqueried",
            "lastqueriedat",
            "followers",
            "followercount",
            "votes",
        )

    fun key(name: String) = name.lowercase().replace("_", "").replace("-", "")

    /** Every principal under a principal-valued key becomes its role name; a list of principals becomes one role. */
    fun principals(
        element: JsonElement,
        records: Set<String> = emptySet(),
    ): JsonElement = principals(element, "", records)

    private fun principals(
        element: JsonElement,
        path: String,
        records: Set<String>,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.mapValues { (name, value) ->
                        val at = "$path/${escapePointer(name)}"
                        roles[key(name)]?.takeUnless { holdsRecord(at, records) }?.let { role -> asRole(value, role) }
                            ?: principals(value, at, records)
                    },
                )
            is JsonArray -> JsonArray(element.mapIndexed { i, value -> principals(value, "$path/$i", records) })
            else -> element
        }

    /** True when [path] is a record's source path or lies above one. */
    private fun holdsRecord(
        path: String,
        records: Set<String>,
    ) = records.any { it == path || it.startsWith("$path/") }

    private fun asRole(
        value: JsonElement,
        role: String,
    ): JsonElement =
        when (value) {
            JsonNull -> value
            is JsonArray -> if (value.isEmpty()) value else JsonArray(listOf(JsonPrimitive(role)))
            else -> JsonPrimitive(role)
        }

    /** The tree without usage counters, at any depth. */
    fun counters(
        element: JsonElement,
        records: Set<String> = emptySet(),
    ): JsonElement = counters(element, "", records)

    private fun counters(
        element: JsonElement,
        path: String,
        records: Set<String>,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.entries
                        .filter { (name, _) ->
                            key(name) !in counters || holdsRecord("$path/${escapePointer(name)}", records)
                        }.associate { (name, value) ->
                            name to counters(value, "$path/${escapePointer(name)}", records)
                        },
                )
            is JsonArray -> JsonArray(element.mapIndexed { i, value -> counters(value, "$path/$i", records) })
            else -> element
        }
}
