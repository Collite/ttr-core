// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * One link candidate: a record of the run ([record] set) or an entry of an existing `<package>.sources.json` sidecar.
 * `by: sourceName` matches [sourceName], `by: iri` matches [iri], `by: path` matches [path].
 */
internal data class LinkEntry(
    val qname: String,
    val kind: String,
    val pkg: String,
    val sourceName: String?,
    val iri: String?,
    val path: String?,
    val record: Record? = null,
)

/** The qualified reference a resolved link emits: `<package>.<model>.<namespace>.<id>[.<nested id>…]`. */
internal object Qnames {
    fun of(
        record: Record,
        pkg: String,
    ): String {
        val chain = generateSequence(record) { it.parent }.toList().asReversed()
        val top = chain.first()
        val model = ProfileVocabulary.model(top.kind)
        // db references name the schema (canonical documents emit none, so the grammar's default `dbo`).
        val namespace = if (model == "db") "dbo" else top.kind
        return (listOfNotNull(pkg.ifEmpty { null }, model, namespace) + chain.map { it.id }).joinToString(".")
    }

    /** A run record's `path` is its id chain from the top-level record, `/`-separated. */
    fun path(record: Record): String =
        generateSequence(record) { it.parent }
            .toList()
            .asReversed()
            .joinToString("/") { it.id }
}

/** `<root>/**/<package>.sources.json` (S3.6 shape): `{generated-from, entries: [{qname, kind, foreignId, sourceName, locator}]}`. */
internal object SourcesJsonReader {
    private const val SUFFIX = ".sources.json"

    fun read(root: Path): List<LinkEntry> {
        val real = root.toRealPath()
        val files =
            Files.walk(real).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.name.endsWith(SUFFIX) && it.name.length > SUFFIX.length }
                    .map { real.relativize(it).toString().replace('\\', '/') }
                    .sorted()
                    .toList()
            }
        return files.flatMap { relative -> entries(relative, real.resolve(relative)) }
    }

    private fun entries(
        file: String,
        path: Path,
    ): List<LinkEntry> {
        val pkg = path.name.removeSuffix(SUFFIX)

        fun invalid(detail: String): Nothing =
            throw ProfileException("ys/profile-invalid", ProfileSpan(file, 1, 1, 1, 1, ""), detail)
        val value =
            try {
                Json.parseToJsonElement(Files.readString(path))
            } catch (e: IOException) {
                invalid("Cannot read sidecar: ${e.message}")
            } catch (e: kotlinx.serialization.SerializationException) {
                invalid("Sidecar is not JSON: ${e.message}")
            }
        val entries = (value as? JsonObject)?.get("entries") as? JsonArray ?: invalid("Sidecar needs an entries list")
        return entries.mapIndexed { i, element ->
            val entry = element as? JsonObject ?: invalid("Sidecar entry $i is not an object")

            fun text(key: String): String? =
                when (val v = entry[key]) {
                    null, JsonNull -> null
                    is JsonPrimitive -> if (v.isString) v.content else invalid("Sidecar entry $i $key is not a string")
                    else -> invalid("Sidecar entry $i $key is not a string")
                }
            LinkEntry(
                qname = text("qname") ?: invalid("Sidecar entry $i needs a qname"),
                kind = text("kind") ?: invalid("Sidecar entry $i needs a kind"),
                pkg = pkg,
                sourceName = text("sourceName"),
                iri = text("foreignId"),
                path = text("locator"),
            )
        }
    }
}

/** Every record of the run plus the root's sidecars; S3.6 writes the sidecar from the same entries. */
internal class LinkIndex(
    val entries: List<LinkEntry>,
) {
    private val byKey: Map<String, Map<String, List<LinkEntry>>> =
        mapOf(
            "sourceName" to entries.filter { it.sourceName != null }.groupBy { it.sourceName!! },
            "iri" to entries.filter { it.iri != null }.groupBy { it.iri!! },
            "path" to entries.filter { it.path != null }.groupBy { it.path!! },
        )
    private val byQname = entries.groupBy { it.qname }

    val records: Map<Record, LinkEntry> =
        entries.filter { it.record != null }.associateBy { it.record!! }

    /** Candidates with distinct qnames; [kind] null accepts every kind. */
    fun resolve(
        by: String,
        value: String,
        kind: String?,
    ): List<LinkEntry> =
        byKey
            .getValue(by)[value]
            .orEmpty()
            .filter { kind == null || it.kind == kind }
            .distinctBy { it.qname }

    fun qname(value: String): List<LinkEntry> = byQname[value].orEmpty()

    companion object {
        fun build(
            plans: List<RecordPlan>,
            packages: Map<RecordPlan, String>,
            root: Path?,
        ): LinkIndex {
            val run =
                plans.flatMap { plan ->
                    val pkg = packages.getValue(plan)
                    plan.records.map { record ->
                        LinkEntry(
                            Qnames.of(record, pkg),
                            record.kind,
                            pkg,
                            record.id,
                            null,
                            Qnames.path(record),
                            record,
                        )
                    }
                }
            return LinkIndex(run + (root?.let(SourcesJsonReader::read) ?: emptyList()))
        }
    }
}
