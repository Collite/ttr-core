// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.tatrman.ttr.profile.LocatedOp
import org.tatrman.ttr.profile.ProfileDefinition
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileReader
import org.tatrman.ttr.profile.ProfileResolver
import org.tatrman.ttr.profile.ProfileSpan
import org.tatrman.ttr.yaml.CanonicalYamlException
import org.tatrman.ttr.yaml.YamlData
import org.tatrman.ttr.yaml.readBoundedYaml
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The reader's emission data a profile declares at its top level (contracts §1, D-14) — flags, not operations, read by
 * ttr-import only; the engine ignores them. [skeleton]: every generated `cn` definition is `abstract: true`.
 * [sourceName]: a JSON Pointer relative to each record whose string is the record's sidecar `sourceName` (an OM FQN,
 * an IRI's label); without it, or where it selects no string, the sidecar keeps the record id. [lexicon]: the synonym
 * rules [Lexicon] emits `lexicon` documents by (MW-P5 S5.2 T4). A child wins, key by key.
 */
data class ReaderOptions(
    val skeleton: Boolean = false,
    val sourceName: String? = null,
    val lexicon: List<LexiconRule> = emptyList(),
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
     * The chain the engine applies: with [packageName], one `package` operation naming that package ([withPackage]);
     * with [overlay] (a YAML file `{<qname|sourceName>: {<property>: <value>}}`), a child profile that declares only that
     * overlay, inline, so the engine applies it as any overlay and reports `overridden[]` and `xm/overlay-orphan` at the
     * file's own positions. The user named the file, so no model root confines it (review-330 R3); a chain that declares
     * an overlay of its own is refused ([refuseOverlay]).
     */
    internal fun chain(
        overlay: Path?,
        packageName: String?,
    ): List<ProfileDefinition> {
        val chain = packageName?.let { withPackage(it) } ?: chain
        if (overlay == null) return chain
        refuseOverlay(overlay)
        val leaf = chain.last()
        val label = overlay.toString()
        val data =
            try {
                if (!Files.isRegularFile(overlay)) throw ReaderInputException("no overlay file $overlay")
                YamlData.read(readBoundedYaml(overlay, "Overlay"), label)
            } catch (e: IOException) {
                throw ReaderInputException("cannot read overlay $overlay (${e.javaClass.simpleName} ${e.message})")
            } catch (e: CanonicalYamlException) {
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(label, e.line, e.column, e.line, e.column, e.keyPath),
                    e.detail,
                )
            }

        fun span(
            path: String,
            key: Boolean = false,
        ) = data
            .source(
                path,
                key,
            ).let { ProfileSpan(it.file, it.line, it.column + 1, it.endLine, it.endColumn + 1, path) }
        val locations = mutableMapOf("" to span(""), "/profile" to span(""), "/overlay" to span(""))
        val keyLocations = mutableMapOf<String, ProfileSpan>()
        data.value.forEach { (key, properties) ->
            val keyPath = "/${pointer(key)}"
            locations["/overlay$keyPath"] = span(keyPath)
            keyLocations["/overlay$keyPath"] = span(keyPath, key = true)
            (properties as? JsonObject)?.keys?.forEach { property ->
                val path = "$keyPath/${pointer(property)}"
                locations["/overlay$path"] = span(path)
            }
        }
        return chain +
            ProfileDefinition(
                leaf.name,
                leaf.major,
                leaf.grammarRange,
                null,
                data.value,
                false,
                emptyList(),
                locations,
                keyLocations,
            )
    }

    /**
     * ✅R330-E: the generated header names one overlay, so `--overlay` and an overlay the chain declares (a path or an
     * inline one) are not combined: a [ReaderInputException] naming both, checked before anything is written.
     */
    fun refuseOverlay(cli: Path?) {
        if (cli == null) return
        val declaring = chain.lastOrNull { it.overlay != null } ?: return
        val declared =
            when (val value = declaring.overlay) {
                is JsonPrimitive ->
                    sources[declaring]?.let {
                        Path
                            .of(it)
                            .resolveSibling(value.content)
                            .normalize()
                            .toString()
                    }
                        ?: "${value.content} (in ${declaring.file})"
                else -> "inline in ${declaring.file}"
            }
        throw ReaderInputException(
            "--overlay $cli cannot be combined with the overlay $declared the profile declares " +
                "(the generated header names one overlay)",
        )
    }

    /**
     * `--package`: every `package` operation of the chain is dropped, and the leaf gets one `{op: package, mapping: {},
     * default: <name>}` at its own position, so every input lands in [name] whatever file scopes the profile's own
     * operations had (review-330 R1).
     */
    private fun withPackage(name: String): List<ProfileDefinition> {
        val op =
            buildJsonObject {
                put("op", "package")
                put("mapping", JsonObject(emptyMap()))
                put("default", name)
            }

        fun isPackage(it: LocatedOp) = (it.value["op"] as? JsonPrimitive)?.contentOrNull == "package"
        val stripped = chain.map { profile -> profile.copy(operations = profile.operations.filterNot(::isPackage)) }
        val leaf = stripped.last()
        return stripped.dropLast(1) +
            leaf.copy(operations = leaf.operations + LocatedOp(op, leaf.locations.getValue(""), emptyMap()))
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
            // ✅R330-B: a file profile may extend a shipped one (after any catalog entry of that name).
            val chain = ProfileResolver(directory, emptyMap(), ::shippedText).resolve("./${file.fileName}", file)
            // ProfileResolver labels each file profile with its real path, a shipped one `<shipped>/profiles/<id>.yaml`.
            val sources =
                chain
                    .filterNot {
                        it.file.startsWith(
                            ProfileResolver.SHIPPED_LABEL,
                        )
                    }.associateWith { it.file }
            return ImportProfile(
                chain,
                options(chain) { profile ->
                    sources[profile]?.let { Files.readString(Path.of(it)) }
                        ?: requireNotNull(shippedText("${profile.name}/${profile.major}"))
                },
                sources,
            )
        }

        /** A profile ttr-core ships, by identity; null when it ships none of that name. */
        fun shipped(identity: String): ImportProfile? {
            val text = shippedText(identity) ?: return null
            val resource = "$SHIPPED/$identity.yaml"
            val profile = ProfileReader.read(text, ProfileResolver.shippedLabel(identity))
            check(
                "${profile.name}/${profile.major}" == identity,
            ) { "$resource declares ${profile.name}/${profile.major}" }
            check(profile.extends == null) { "a shipped profile extends nothing: $resource" }
            return ImportProfile(listOf(profile), options(listOf(profile)) { text }, emptyMap())
        }

        /** The text of a profile ttr-core ships, by identity; null when it ships none of that name. */
        private fun shippedText(identity: String): String? {
            if (!Regex("^[a-z0-9][a-z0-9-]*/[0-9]+$").matches(identity)) return null
            return ImportProfile::class.java
                .getResourceAsStream("$SHIPPED/$identity.yaml")
                ?.bufferedReader()
                ?.use { it.readText() }
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
                    (value["lexicon"] as? JsonArray)?.map { lexiconRule(it as JsonObject) } ?: options.lexicon,
                )
            }
    }
}

/** One schema-checked `lexicon` entry (the profile schema requires target/at/id/terms and one of language/locale). */
private fun lexiconRule(value: JsonObject): LexiconRule {
    fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
    return LexiconRule(
        requireNotNull(text("target")),
        requireNotNull(text("at")),
        requireNotNull(text("id")),
        requireNotNull(text("terms")),
        text("form"),
        text("language"),
        text("locale"),
        text("split"),
    )
}

private fun pointer(key: String) = key.replace("~", "~0").replace("/", "~1")
