// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import kotlinx.serialization.json.*
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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

    /** Directories never walked below the root (✅R251-C): VCS/tool state, build output and vendored packages. */
    private fun skipped(name: String) = name.startsWith(".") || name == "build" || name == "node_modules"

    fun read(root: Path): List<LinkEntry> {
        val top =
            try {
                root.toRealPath()
            } catch (e: IOException) {
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(".", 1, 1, 1, 1, ""),
                    "Cannot walk for sidecars: the root is unreadable (${e::class.simpleName})",
                )
            }
        val visitor = SidecarVisitor(top)
        try {
            Files.walkFileTree(top, visitor)
        } catch (e: IOException) {
            visitor.unreadable(top, e)
        } catch (e: UncheckedIOException) {
            visitor.unreadable(top, e.cause ?: IOException(e))
        }
        return visitor.files.sorted().flatMap { relative -> entries(relative, top.resolve(relative)) }
    }

    /**
     * Collects `*.sources.json` below [top], never entering a [skipped] directory, not even one it cannot open
     * (review-251 R1). Any other failure is `ys/profile-invalid` at the path relative to the root (R2).
     */
    internal class SidecarVisitor(
        private val top: Path,
    ) : SimpleFileVisitor<Path>() {
        val files = mutableListOf<String>()

        private fun relative(path: Path) =
            top
                .relativize(path)
                .toString()
                .replace('\\', '/')
                .ifEmpty { "." }

        private fun skip(path: Path) = path != top && skipped(path.name)

        fun unreadable(
            at: Path,
            e: IOException,
        ): Nothing {
            val file = relative(at)
            throw ProfileException(
                "ys/profile-invalid",
                ProfileSpan(file, 1, 1, 1, 1, ""),
                "Cannot walk for sidecars: $file is unreadable (${e::class.simpleName})",
            )
        }

        override fun preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult = if (skip(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult {
            if (attrs.isRegularFile && file.name.endsWith(SUFFIX) && file.name.length > SUFFIX.length) {
                files += relative(file)
            }
            return FileVisitResult.CONTINUE
        }

        // The JDK opens a directory before preVisitDirectory; when that fails it calls this instead.
        override fun visitFileFailed(
            file: Path,
            exc: IOException,
        ): FileVisitResult = if (skip(file)) FileVisitResult.SKIP_SUBTREE else unreadable(file, exc)

        override fun postVisitDirectory(
            dir: Path,
            exc: IOException?,
        ): FileVisitResult = if (exc != null) unreadable(dir, exc) else FileVisitResult.CONTINUE
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

/**
 * MW S3.6 T4 — writes `<package>.sources.json` (contracts §6): `{"generated-from": <header text>, "entries": [{qname,
 * kind, foreignId, sourceName, locator}]}`, entries in bytewise qname order (then kind), no timestamps; an absent
 * value is left out. [SourcesJsonReader] reads back exactly the entries written. A bare-DB import names its
 * tables and columns by `sourceName` only ([sourceNameOnly]).
 */
object SourcesJsonWriter {
    fun write(
        generatedFrom: String?,
        entries: List<SourceEntry>,
        sourceNameOnly: Boolean = false,
    ): String {
        val sorted =
            entries
                .distinct()
                .sortedWith(compareBy<SourceEntry, String>(Bytewise) { it.qname }.thenBy(Bytewise) { it.kind })
        val lines =
            sorted.map { entry ->
                buildJsonObject {
                    put("qname", entry.qname)
                    put("kind", entry.kind)
                    if (!sourceNameOnly) entry.foreignId?.let { put("foreignId", it) }
                    entry.sourceName?.let { put("sourceName", it) }
                    if (!sourceNameOnly) entry.locator?.let { put("locator", it) }
                }.toString()
            }
        val head = generatedFrom?.let { "  \"generated-from\": ${JsonPrimitive(it)},\n" }.orEmpty()
        val body = if (lines.isEmpty()) "[]" else lines.joinToString(",\n", "[\n", "\n  ]") { "    $it" }
        return "{\n$head  \"entries\": $body\n}\n"
    }

    /** Bytewise (UTF-8) order, as the exporter sorts qnames. */
    private object Bytewise : Comparator<String> {
        override fun compare(
            a: String,
            b: String,
        ): Int {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) {
                val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (c != 0) return c
            }
            return x.size - y.size
        }
    }
}

/**
 * Every record of the run plus the root's sidecars; S3.6 writes the sidecar from the same entries. The sidecar part is
 * read on the first [resolve] only (✅R251-C): a run with no `link` and no sourceName overlay key never walks the root.
 */
internal class LinkIndex(
    private val run: List<LinkEntry>,
    sidecars: () -> List<LinkEntry> = { emptyList() },
) {
    private val byKey: Map<String, Map<String, List<LinkEntry>>> by lazy {
        val entries = run + sidecars()
        mapOf(
            "sourceName" to entries.filter { it.sourceName != null }.groupBy { it.sourceName!! },
            "iri" to entries.filter { it.iri != null }.groupBy { it.iri!! },
            "path" to entries.filter { it.path != null }.groupBy { it.path!! },
        )
    }
    private val byQname = run.groupBy { it.qname }

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

    /** The run's records as sidecar entries; never reads the sidecars (MW S3.6 T4). */
    fun sources(): List<SourceEntry> =
        run.map { SourceEntry(it.qname, it.kind, it.pkg, it.iri, it.sourceName, it.path) }

    /** The run records with this qname; never reads the sidecars. */
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
            return LinkIndex(run) { root?.let(SourcesJsonReader::read) ?: emptyList() }
        }
    }
}
