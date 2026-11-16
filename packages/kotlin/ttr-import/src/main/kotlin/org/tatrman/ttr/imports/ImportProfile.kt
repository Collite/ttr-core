// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.tatrman.ttr.profile.LocatedOp
import org.tatrman.ttr.profile.ProfileDefinition
import org.tatrman.ttr.profile.ProfileReader
import org.tatrman.ttr.profile.ProfileResolver
import org.tatrman.ttr.profile.ProfileSpan
import org.tatrman.ttr.yaml.YamlData
import java.nio.file.Files
import java.nio.file.Path

/**
 * The reader's emission data a profile declares at its top level (contracts §1, D-14) — flags, not operations, read by
 * ttr-import only; the engine ignores them. [skeleton]: every generated `cn` definition is `abstract: true`.
 * [sourceName]: a JSON Pointer relative to each record whose string is the record's sidecar `sourceName` (an OM FQN,
 * an IRI's label); without it, or where it selects no string, the sidecar keeps the record id. A child wins.
 */
data class ReaderOptions(
    val skeleton: Boolean = false,
    val sourceName: String? = null,
)

/**
 * A resolved profile for a reader run: [identity] (`<name>/<major>`, the header's), the ancestor→child [chain] the engine
 * applies, [options], and the file each profile was read from ([sources]; empty for a shipped profile — the header's
 * overlay slot reads a path overlay relative to it).
 */
class ImportProfile internal constructor(
    val chain: List<ProfileDefinition>,
    val options: ReaderOptions,
    internal val sources: Map<ProfileDefinition, String>,
) {
    val identity: String get() = chain.last().let { "${it.name}/${it.major}" }

    /**
     * The chain the engine applies: with [packageName], every `package` operation names that package ([withPackage]);
     * with [overlay] (a YAML file `{<qname|sourceName>: {<property>: <value>}}`) applied after every
     * profile's own overlay: a child profile that declares only the overlay, so the engine applies it as it applies any
     * overlay and reports `overridden[]` and `xm/overlay-orphan` at the file's own positions.
     */
    internal fun chain(
        overlay: Path?,
        packageName: String?,
    ): List<ProfileDefinition> {
        val chain = packageName?.let { withPackage(it) } ?: chain
        if (overlay == null) return chain
        val file = overlay.toAbsolutePath().normalize()
        if (!Files.isRegularFile(file)) throw ReaderInputException("no overlay file $overlay")
        val leaf = chain.last()
        val declaring = file.resolveSibling("--overlay").toString()

        fun span(path: String) = ProfileSpan(declaring, 1, 1, 1, 1, path)
        return chain +
            ProfileDefinition(
                leaf.name,
                leaf.major,
                leaf.grammarRange,
                null,
                JsonPrimitive(file.fileName.toString()),
                false,
                emptyList(),
                mapOf("" to span(""), "/profile" to span("/profile"), "/overlay" to span("/overlay")),
            )
    }

    /**
     * `--package`: every `package` operation of the chain becomes `{op: package, mapping: {}, default: <name>}` at its own
     * position, so every input lands in [name]; a chain without one gets it on its leaf.
     */
    private fun withPackage(name: String): List<ProfileDefinition> {
        val op =
            buildJsonObject {
                put("op", "package")
                put("mapping", JsonObject(emptyMap()))
                put("default", name)
            }

        fun isPackage(it: LocatedOp) = (it.value["op"] as? JsonPrimitive)?.contentOrNull == "package"
        if (chain.none { profile -> profile.operations.any(::isPackage) }) {
            val leaf = chain.last()
            return chain.dropLast(1) +
                leaf.copy(operations = leaf.operations + LocatedOp(op, leaf.locations.getValue(""), emptyMap()))
        }
        return chain.map { profile ->
            profile.copy(operations = profile.operations.map { if (isPackage(it)) it.copy(value = op) else it })
        }
    }

    /** The overlay a header names: [cli] when given, else the nearest file profile's path overlay or inline overlay. */
    internal fun overlayInput(cli: Path?): Path? {
        cli?.let { return it.toAbsolutePath().normalize() }
        val profile = chain.lastOrNull { it.overlay != null && sources[it] != null } ?: return null
        val file = Path.of(requireNotNull(sources[profile]))
        return when (val value = profile.overlay) {
            is JsonPrimitive -> file.resolveSibling(value.content).normalize()
            else -> file
        }
    }

    companion object {
        /** The resource directory of the profiles ttr-core ships (`<repo>/profiles/<name>/<major>.yaml`). */
        private const val SHIPPED = "/profiles"

        /**
         * [reference] is a shipped identity (`om-tables/1`) or a profile file, relative to the working directory
         * (✅R269-B); a file profile's `extends` and path overlay resolve inside its own directory.
         */
        fun resolve(reference: String): ImportProfile {
            shipped(reference)?.let { return it }
            val file = Path.of(reference).toAbsolutePath().normalize()
            if (!Files.isRegularFile(file)) {
                throw ReaderInputException("--profile $reference is neither a shipped profile nor a file")
            }
            val directory = file.parent
            val chain = ProfileResolver(directory, emptyMap()).resolve("./${file.fileName}", file)
            // ProfileResolver labels each profile with its real path; read the reader keys from those files.
            val sources = chain.associateWith { it.file }
            return ImportProfile(chain, options(chain) { Files.readString(Path.of(sources.getValue(it))) }, sources)
        }

        /** A profile ttr-core ships, by identity; null when it ships none of that name. */
        fun shipped(identity: String): ImportProfile? {
            if (!Regex("^[a-z0-9][a-z0-9-]*/[0-9]+$").matches(identity)) return null
            val resource = "$SHIPPED/$identity.yaml"
            val text =
                ImportProfile::class.java
                    .getResourceAsStream(resource)
                    ?.bufferedReader()
                    ?.use { it.readText() } ?: return null
            val profile = ProfileReader.read(text, "<shipped>$resource")
            check(
                "${profile.name}/${profile.major}" == identity,
            ) { "$resource declares ${profile.name}/${profile.major}" }
            check(profile.extends == null) { "a shipped profile extends nothing: $resource" }
            return ImportProfile(listOf(profile), options(listOf(profile)) { text }, emptyMap())
        }

        private fun options(
            chain: List<ProfileDefinition>,
            text: (ProfileDefinition) -> String,
        ): ReaderOptions =
            chain.fold(ReaderOptions()) { options, profile ->
                val value = YamlData.read(text(profile), profile.file).value
                ReaderOptions(
                    (value["skeleton"] as? JsonPrimitive)?.booleanOrNull ?: options.skeleton,
                    (value["sourceName"] as? JsonPrimitive)?.contentOrNull ?: options.sourceName,
                )
            }
    }
}
