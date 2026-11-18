// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.metadata.export.GeneratedInputs
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.holding
import org.tatrman.ttr.profile.JsonPointer
import org.tatrman.ttr.profile.ProfileEngine
import org.tatrman.ttr.profile.ProfileWarning
import org.tatrman.ttr.profile.RecordOrigin
import org.tatrman.ttr.profile.SourceEntry
import org.tatrman.ttr.profile.SourcesJsonWriter
import org.tatrman.ttr.writer.TtrRenderer
import org.tatrman.ttr.yaml.CanonicalDocument
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Path
import java.util.SortedMap
import java.util.TreeMap

/**
 * One reader run's request: the [inputs] the front-end parses, the [out] directory the package is written under (the
 * header's paths run from each generated file's directory below it), the [generator] (ttr-core version) the header
 * names, the model [root] whose `<package>.sources.json` sidecars and `renames.yaml` links and overlays read, and an
 * authored [overlay] applied after the profile's own; [packageName] replaces the profile's `package` operation (every
 * input lands in that package).
 */
data class ReaderRequest(
    val inputs: List<Path>,
    val out: Path,
    val generator: String,
    val root: Path? = null,
    val overlay: Path? = null,
    val packageName: String? = null,
)

/**
 * What a run generates (contracts §1), each by its path under `--out`: [files] the `.ttrm` documents
 * (`<package as directories>/<model>.ttrm`, each with the generated header), [sidecars] one `<package>.sources.json` per
 * package, and the [report]. Every map is in bytewise path order; nothing in it depends on time or the machine but the
 * header's relative paths.
 */
class GeneratedPackage(
    val files: SortedMap<String, String>,
    val sidecars: SortedMap<String, String>,
    val report: ImportReport,
) {
    /** Everything a run writes, `report.json` included. */
    fun outputs(): SortedMap<String, String> =
        TreeMap<String, String>(Bytewise).also {
            it.putAll(files)
            it.putAll(sidecars)
            it[REPORT] = report.json()
        }

    companion object {
        const val REPORT = "report.json"
    }
}

/**
 * MW-P5 S5.1 — one reader = one front-end + one profile + the P3 pipeline (contracts §1): the front-end's trees go
 * through [ProfileEngine.apply] (with the profile's `sourceName` pointer, so the run's records carry it); each emitted canonical document is rendered by [TtrRenderer] as a `.ttrm` file with the
 * generated header; the run's records become the `<package>.sources.json` sidecar. Readers never emit cn sugar or
 * `grounds:`; a `skeleton: true` profile's cn definitions are `abstract: true` (D-14), never its db/er ones. Engine
 * errors propagate as the engine's located `ProfileException`s.
 */
object Reader {
    fun run(
        frontEnd: FrontEnd,
        profile: ImportProfile,
        request: ReaderRequest,
    ): GeneratedPackage {
        val input = snapshot(request.inputs)
        val trees = frontEnd.parse(request.inputs)
        val result =
            ProfileEngine.apply(
                trees,
                profile.chain(request.overlay, request.packageName),
                request.root,
                sourceName = profile.options.sourceName,
            )
        val header =
            GeneratedInputs(
                input,
                profile.overlayInput(request.overlay),
                profile.identity,
                request.generator,
                request.out.toAbsolutePath().normalize(),
            )
        val files = TreeMap<String, String>(Bytewise)
        val packages = mutableListOf<ImportReport.Package>()
        for (document in result.documents) {
            val canonical = if (profile.options.skeleton) skeleton(document.canonical) else document.canonical
            val pkg = canonical.value.text("package")
            val model = canonical.value.text("model")
            val path = listOf(pkg.replace('.', '/'), "$model.ttrm").filter { it.isNotEmpty() }.joinToString("/")
            if (files.put(path, header.header(path).line(GeneratedHeader.Form.TTRM) + render(canonical)) != null) {
                throw ReaderInputException("two generated documents render to $path; the profile's packages collide")
            }
            packages += ImportReport.Package(pkg, model, path, canonical.ast.definitions.size)
        }
        // ✅D-MW-87: the engine's warnings (an `each` key missing or repeated) first, then the reader's own.
        val warnings =
            result.report.warnings
                .map(::warning)
                .toMutableList()
        // T4: synonyms → one `lexicon` document per package and locale, beside the engine's documents.
        val grammar =
            result.documents
                .firstOrNull()
                ?.canonical
                ?.value
                ?.text("ttr")
        if (grammar != null && profile.options.lexicon.isNotEmpty()) {
            val terms = Lexicon.terms(trees, result.origins, profile.options.lexicon, warnings)
            Lexicon.documents(terms, grammar).forEach { (path, document) ->
                if (files.put(path, header.header(path).line(GeneratedHeader.Form.TTRM) + render(document)) != null) {
                    throw ReaderInputException(
                        "two generated documents render to $path; the profile's packages collide",
                    )
                }
                packages +=
                    ImportReport.Package(document.value.text("package"), "lexicon", path, document.ast.definitions.size)
            }
        }
        val sidecars = TreeMap<String, String>(Bytewise)
        val renamed = mutableListOf<ImportReport.Renamed>()
        val sources = foreignIds(result.sources, result.origins, trees, profile.options.foreignId)
        sources.groupBy { it.pkg }.forEach { (pkg, entries) ->
            val name = "$pkg.sources.json"
            sidecars[name] = SourcesJsonWriter.write(header.header(name).text, entries)
            renamed += SidecarDelta.renamed(request.out.resolve(name), entries)
        }
        return GeneratedPackage(
            files,
            sidecars,
            ImportReport.of(frontEnd.name, profile.identity, result, packages, warnings, frontEnd.records, renamed),
        )
    }

    /**
     * MW-P5 S5.3: with a `foreignId` pointer, each record's sidecar entry takes the string the pointer selects in the
     * record's own node (its input tree and path, [RecordOrigin]; the engine's sources and origins are both in record
     * order); a record where it selects no string keeps none.
     */
    private fun foreignIds(
        sources: List<SourceEntry>,
        origins: List<RecordOrigin>,
        trees: List<ForeignTree>,
        pointer: String?,
    ): List<SourceEntry> {
        if (pointer == null) return sources
        check(sources.size == origins.size) { "the engine's sources and origins differ in length" }
        return sources.zip(origins).map { (entry, origin) ->
            val tree = trees.holding(origin.file, origin.path) ?: return@map entry
            val node = JsonPointer(origin.path + pointer).resolve(tree.root)?.value as? JsonPrimitive
            node?.takeIf { it.isString }?.let { entry.copy(foreignId = it.content) } ?: entry
        }
    }

    /**
     * The header's snapshot: the one input file. Several inputs are refused until a stage specifies their snapshot
     * (review-330 R9): the header names one path, and hashing a common directory would take in unrelated files.
     */
    private fun snapshot(inputs: List<Path>): Path =
        when (inputs.size) {
            0 -> throw ReaderInputException("a reader run needs one input")
            1 -> inputs.single().toAbsolutePath().normalize()
            else -> throw ReaderInputException(
                "a reader run takes one input file; ${inputs.size} given (a multi-input snapshot is not specified yet)",
            )
        }

    /**
     * One report line for an engine warning: `<record>: <message> — <file> <path>`; a key `unknown: ignore` dropped
     * (✅D-MW-89) reads `not in this profile: <key> in <kind> (<n> records) — <file> <first occurrence>`.
     */
    private fun warning(it: ProfileWarning): String = "${it.describe()} — ${it.location.file} ${it.location.path}"

    /**
     * D-14: every definition of a generated `cn` document is `abstract: true` (a skeleton the authored core package
     * completes); a definition that states `abstract` itself keeps it. db/er/binding documents are never touched.
     */
    internal fun skeleton(document: CanonicalDocument): CanonicalDocument {
        if (document.value.text("model") != "cn") return document
        val defs =
            (document.value["defs"] as? JsonArray ?: return document).map { def ->
                val obj = def as JsonObject
                // A cn body is an ordered list of one-key entries; a map body is read the same way.
                val properties =
                    when (val value = obj["properties"]) {
                        is JsonArray -> value
                        is JsonObject -> JsonArray(value.map { (key, item) -> JsonObject(mapOf(key to item)) })
                        else -> JsonArray(emptyList())
                    }
                if (properties.any { (it as? JsonObject)?.containsKey("abstract") == true }) {
                    obj
                } else {
                    val entry = JsonObject(mapOf("abstract" to JsonPrimitive(true)))
                    JsonObject(obj + ("properties" to JsonArray(properties + entry)))
                }
            }
        val value = JsonObject(document.value + ("defs" to JsonArray(defs)))
        // JSON is YAML 1.2: the codec reads the amended value back and gives the typed AST the renderer needs.
        return CanonicalYaml.read(Json.encodeToString(JsonObject.serializer(), value), document.ast.sourceFile)
    }

    /**
     * The document as `.ttrm`: [TtrRenderer]'s `package`/`import`/`model` lines, then each top-level definition rendered
     * by [TtrRenderer.renderDef] in bytewise qname order (contracts §1, XM GI-2). Within one document every definition
     * shares the package, model and namespace, so qname order is name order.
     */
    internal fun render(document: CanonicalDocument): String {
        val ast = document.ast
        val directive = ast.modelDirective
        val head =
            TtrRenderer.renderFile(
                directive?.modelCode,
                directive?.schema,
                emptyList(),
                ast.packageName,
                ast.imports.map { if (it.wildcard) "${it.target}.*" else it.target },
                directive?.locale,
            )
        return buildString {
            append(head)
            ast.definitions
                .sortedWith(compareBy<Definition, String>(Bytewise) { it.name })
                .forEach { appendLine(TtrRenderer.renderDef(it)) }
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull.orEmpty()
}
