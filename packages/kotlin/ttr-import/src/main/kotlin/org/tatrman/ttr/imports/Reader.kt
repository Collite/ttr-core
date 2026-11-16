// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.export.GeneratedHeader
import org.tatrman.ttr.metadata.export.GeneratedInputs
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.profile.ApplyResult
import org.tatrman.ttr.profile.EmittedDocument
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.JsonPointer
import org.tatrman.ttr.profile.ProfileEngine
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
 * through [ProfileEngine.apply]; each emitted canonical document is rendered by [TtrRenderer] as a `.ttrm` file with the
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
        require(request.inputs.isNotEmpty()) { "a reader run needs at least one input" }
        val trees = frontEnd.parse(request.inputs)
        val result = ProfileEngine.apply(trees, profile.chain(request.overlay, request.packageName), request.root)
        val header =
            GeneratedInputs(
                snapshot(request.inputs),
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
            check(files.put(path, header.header(path).line(GeneratedHeader.Form.TTRM) + render(canonical)) == null) {
                "two documents render to $path"
            }
            packages += ImportReport.Package(pkg, model, path, canonical.ast.definitions.size)
        }
        val sources = sourceNames(result, trees, profile.options.sourceName)
        val sidecars = TreeMap<String, String>(Bytewise)
        sources.groupBy { it.pkg }.forEach { (pkg, entries) ->
            val name = "$pkg.sources.json"
            sidecars[name] = SourcesJsonWriter.write(header.header(name).text, entries)
        }
        return GeneratedPackage(files, sidecars, ImportReport.of(frontEnd.name, profile.identity, result, packages))
    }

    /** One input is the snapshot; several are their nearest common directory, hashed as a tree. */
    private fun snapshot(inputs: List<Path>): Path {
        val paths = inputs.map { it.toAbsolutePath().normalize() }
        if (paths.size == 1) return paths.single()
        var common = paths.first().parent
        while (paths.any { !it.startsWith(common) }) common = common.parent
        return common
    }

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

    /**
     * The run's sidecar entries, each record's `sourceName` read through [pointer] from its foreign node when the profile
     * declares one (an OM FQN); the engine's own entry (the record id) otherwise. A record is found by its package and
     * id chain ([SourceEntry.locator]) among the emitted documents, whose locations name its foreign node.
     */
    private fun sourceNames(
        result: ApplyResult,
        trees: List<ForeignTree>,
        pointer: String?,
    ): List<SourceEntry> {
        if (pointer == null) return result.sources
        val selector = JsonPointer(pointer)
        val byLabel = trees.associateBy { it.label }
        val nodes = mutableMapOf<Pair<String, String>, String>()
        result.documents.forEach { document -> foreignNames(document, byLabel, selector, nodes) }
        return result.sources.map { entry ->
            nodes[entry.pkg to entry.locator.orEmpty()]?.let { entry.copy(sourceName = it) } ?: entry
        }
    }

    private fun foreignNames(
        document: EmittedDocument,
        trees: Map<String, ForeignTree>,
        selector: JsonPointer,
        into: MutableMap<Pair<String, String>, String>,
    ) {
        val pkg = document.canonical.value.text("package")

        fun visit(
            value: JsonElement,
            path: String,
            chain: List<String>,
        ) {
            val obj = value as? JsonObject ?: return
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            val ids = if (id != null) chain + id else chain
            if (id != null) {
                val span = document.locations[path]
                val tree = span?.let { trees[it.file] }
                if (span != null && tree != null) {
                    val node = JsonPointer(span.path).resolve(tree.root)
                    val name = node?.let { selector.resolve(it) }?.value as? JsonPrimitive
                    if (name != null && name.isString) into.putIfAbsent(pkg to ids.joinToString("/"), name.content)
                }
            }
            listOf("properties", "fields").forEach { key ->
                (obj[key] as? JsonObject)?.forEach { (slot, items) ->
                    (items as? JsonArray)?.forEachIndexed { i, item ->
                        visit(item, "$path/$key/${slot.replace("~", "~0").replace("/", "~1")}/$i", ids)
                    }
                }
            }
        }
        (document.canonical.value["defs"] as? JsonArray)?.forEachIndexed {
            i,
            def,
            ->
            visit(def, "/defs/$i", emptyList())
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull.orEmpty()
}

/** Bytewise (UTF-8) string order, as the exporter and the sidecar writer sort qnames. */
internal object Bytewise : Comparator<String> {
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
