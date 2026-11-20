// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.profile.CaptureFilter
import org.tatrman.ttr.profile.FileSnapshotSource
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileDefinition
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileResolver
import org.tatrman.ttr.profile.Sanitise
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** What a capture produced: the snapshot file's name and text, or the error that refused it. */
sealed interface Captured {
    class Written(
        val name: String,
        val text: String,
    ) : Captured

    class Batch(
        val files: Map<String, String>,
    ) : Captured

    /** A diagnostic (`ys/…` or `xm/snapshot-size`) at [file]:[line]:[column]. */
    class Refused(
        val file: String,
        val line: Int,
        val column: Int,
        val message: String,
    ) : Captured

    /** The command line names something that is not there (exit 2). */
    class Usage(
        val message: String,
    ) : Captured
}

/**
 * MW S3.6 T5 — `ttr snapshot --from file:<path> --profile <p> --out <dir> [--large]` (contracts §6, XM D-15): one
 * foreign tree fetched by its adapter, the profile's `ignore` applied, principal names replaced by role names, usage
 * counters dropped, written as YAML (`<name>.yaml`, deterministic: the export's key order, block style). Output over
 * [LIMIT] bytes is `xm/snapshot-size` unless [large]. The profile is a bundled identity, an identity of
 * `<root>/profiles/`, or a profile file inside [root] (the working directory).
 */
object SnapshotCapture {
    const val LIMIT: Long = 5L * 1024 * 1024

    /**
     * The line `<out>/.gitattributes` gains (✅R277-B): everything in the snapshot directory is generated and never
     * diffed. Git anchors the pattern to the directory of its `.gitattributes`, so it holds wherever `--out` is.
     */
    const val ATTRIBUTES = "* linguist-generated=true -diff"

    fun capture(
        from: String,
        profile: String,
        root: Path,
        large: Boolean,
    ): Captured {
        if (!from.startsWith("file:") || from == "file:") {
            return Captured.Usage("--from: expected file:<path> or openmetadata with --url and --token-env")
        }
        val source = FileSnapshotSource(Path.of(from.removePrefix("file:")).toAbsolutePath().normalize())
        // A missing export is a command-line mistake, as a missing --profile file is (review-277 F9).
        if (!(source.path.isRegularFile() && source.path.isReadable())) {
            return Captured.Usage("--from: no export file ${source.path}")
        }
        val top = root.toRealPath()
        val catalog = ProfileCatalog.read(top).entries
        return try {
            val reference =
                when {
                    YamlFileSource.isBundledProfile(profile) || profile in catalog -> profile
                    else -> {
                        val path = root.resolve(profile).toAbsolutePath().normalize()
                        if (!path.exists()) return Captured.Usage("--profile: no profile file $profile")
                        path.toString()
                    }
                }
            val chain =
                ProfileResolver.bundled(reference)
                    ?: ProfileResolver(top, catalog).resolve(reference, top.resolve("snapshot"))
            val tree = source.fetch()
            // Hygiene runs on the export as fetched, so record pointers hold; then the ignore prunes (review-277 F2).
            val plan = CaptureFilter.plan(tree, chain)
            val clean = Sanitise.counters(Sanitise.principals(tree.root.value, plan.records), plan.records)
            val text = yaml(CaptureFilter.prune(clean, plan.ignored))
            val name = "${source.name}.yaml"
            checkSize(name, text, large) ?: Captured.Written(name, text)
        } catch (e: ProfileException) {
            // The location is the Refused's own; the exception's message would print it a second time (F9).
            Captured.Refused(e.location.file, e.location.line, e.location.column, "${e.code}: ${e.detail}")
        }
    }

    fun captureOpenMetadata(
        url: URI,
        token: String,
        chain: List<ProfileDefinition>,
        large: Boolean,
    ): Captured {
        val tables = chain.firstOrNull { "${it.name}/${it.major}" == "om-tables/1" }
        val glossary = chain.any { "${it.name}/${it.major}" == "om-glossary/1" }
        val documents =
            OpenMetadataSnapshotSource(url, token).use { source ->
                when {
                    tables != null -> {
                        val mappedTypes =
                            tables.operations.firstNotNullOfOrNull { operation ->
                                (operation.value["mapping"] as? JsonObject)
                                    ?.takeIf { (operation.value["at"] as? JsonPrimitive)?.content == "/dataType" }
                            } ?: return Captured.Usage("--profile: om-tables/1 must map column data types")
                        source.tables(mappedTypes.keys)
                    }
                    glossary -> source.glossary()
                    else -> return Captured.Usage("--profile: openmetadata needs om-tables/1 or om-glossary/1")
                }
            }
        val files = linkedMapOf<String, String>()
        val captureChain =
            chain.map { profile ->
                val annotations =
                    when ("${profile.name}/${profile.major}") {
                        "om-tables/1" -> TABLE_READER_ANNOTATIONS
                        "om-glossary/1" -> GLOSSARY_READER_ANNOTATIONS
                        else -> emptySet()
                    }
                profile.copy(
                    operations =
                        profile.operations.filterNot { operation ->
                            val at = (operation.value["at"] as? JsonPrimitive)?.content
                            val scope = (operation.value["in"] as? JsonPrimitive)?.content
                            (operation.value["op"] as? JsonPrimitive)?.content == "ignore" &&
                                (scope to at) in annotations
                        },
                )
            }
        for ((name, document) in documents) {
            val catalog = OpenMetadataSnapshotSource.catalogContent(document)
            val tree = ForeignTree.read(catalog.toString(), name)
            val plan = CaptureFilter.plan(tree, captureChain)
            val clean = Sanitise.counters(catalog, plan.records)
            val text =
                SNAPSHOT_JSON.encodeToString(JsonElement.serializer(), CaptureFilter.prune(clean, plan.ignored)) + "\n"
            checkSize(name, text, large)?.let { return it }
            files[name] = text
        }
        return Captured.Batch(files)
    }

    private val SNAPSHOT_JSON = Json { prettyPrint = true }

    // These base-profile ignores acknowledge fields the reader uses for routing, provenance, or reporting.
    private val TABLE_READER_ANNOTATIONS =
        setOf(
            null to "/package",
            "table" to "/fullyQualifiedName",
            "column" to "/fullyQualifiedName",
            "column" to "/typeFallback",
        )
    private val GLOSSARY_READER_ANNOTATIONS = setOf("kind" to "/fullyQualifiedName", "kind" to "/synonyms")

    private fun checkSize(
        name: String,
        text: String,
        large: Boolean,
    ): Captured.Refused? {
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (large || size <= LIMIT) return null
        return Captured.Refused(
            name,
            1,
            1,
            "xm/snapshot-size: the snapshot is $size bytes, over the $LIMIT-byte limit; pass --large to write it",
        )
    }

    /**
     * `.gitattributes` in [directory] with [ATTRIBUTES] once: appended when missing, the file left as it is otherwise.
     * True when the file changed.
     */
    fun attributes(directory: Path): Boolean {
        val file = directory.resolve(".gitattributes")
        val text = if (file.exists()) file.readText() else ""
        if (text.lines().any { it.trim() == ATTRIBUTES }) return false
        val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        Files.writeString(file, "$text$separator$ATTRIBUTES\n")
        return true
    }

    private fun yaml(element: JsonElement): String {
        val options =
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 2
                width = Int.MAX_VALUE
                isAllowUnicode = true
                lineBreak = DumperOptions.LineBreak.UNIX
            }
        return Yaml(options).dump(plain(element))
    }

    private fun plain(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonObject -> LinkedHashMap<String, Any?>().apply { element.forEach { (k, v) -> put(k, plain(v)) } }
            is JsonArray -> element.map(::plain)
            is JsonPrimitive ->
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    else -> element.content.toLongOrNull() ?: BigDecimal(element.content)
                }
        }
}
