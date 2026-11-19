// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.*
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/**
 * PowerDesigner 16 XML (CDM/LDM/PDM). XML attributes (`a:`) become scalars, collections (`c:`) become lists of objects,
 * and object references become `$ref` nodes. `@pd` carries the foreign class/model, local id, and package/code paths.
 * No TTR kinds or types are selected here: `pdm/16` owns that projection.
 *
 * One ForeignTree view per PD package, as with XMI. Model/Package Code segments are lower-cased (Locale.ROOT);
 * definition Codes stay verbatim. References retain local XML ids and qualified Code paths as provenance; links use
 * the resolved ObjectID (`by: sourceName`, control room §66), so repeated Codes in different packages are distinct.
 * Generation links are the object's History ORG ids, joined to objects of its model's GenerationOrigins among the
 * supplied files; missing models remain raw unresolved links, never trigger filesystem/network reads.
 */
object PdmFrontEnd : FrontEnd {
    override val name = "pdm"
    override val records = "/objects"
    private val extensions = setOf("cdm", "ldm", "pdm")
    private val containers = setOf("Model", "Package")
    private val nonDefinitions = containers + "RootObject"
    private val relationshipObjects = setOf("Relationship", "Reference")
    private val whitespace = Regex("\\s+")
    private val piAttributes = Regex("""([A-Za-z]+)="([^"]*)"""")
    private val version16 = Regex("16(?:\\.[0-9]+)+")
    private val historyOrigins = Regex("(?m)^ORG \\{([^}]+)}")
    private val sqlType =
        Regex(
            """([A-Z][A-Z0-9_ ]*?)(?:\(\s*([0-9]+|\*)(?:\s+(?:CHAR|BYTE))?(?:\s*,\s*([0-9]+))?\s*\))?(?:\s+(WITH(?:OUT)? TIME ZONE))?""",
        )

    // These spellings have a display width or fractional-second precision, not a character/decimal size.
    private val unsizedTypes =
        (
            "I INT INTEGER SMALLINT TINYINT LI BIGINT SI BT NO INT2 INT4 SERIAL INT8 BIGSERIAL " +
                "BL BOOLEAN BOOL BIT F FLOAT DOUBLE REAL SF LF BINARY_DOUBLE BINARY_FLOAT FLOAT4 FLOAT8 " +
                "D DATE T TIME DT DATETIME TIMESTAMP TS DATETIME2 SMALLDATETIME"
        ).split(' ').toSet() +
            setOf(
                "DOUBLE PRECISION",
                "TIMESTAMP WITH TIME ZONE",
                "TIMESTAMP WITHOUT TIME ZONE",
                "TIME WITH TIME ZONE",
                "TIME WITHOUT TIME ZONE",
            )

    // Only PD logical abbreviations take bare lengths: INT4 and FLOAT8 are whole SQL names.
    private val logicalType =
        Regex(
            "(LVA|VMBT|LBIN|MBT|BIN|PIC|TXT|VA|LA|SI|LI|BT|NO|DC|MN|NM|SF|LF|TS|DT|BL|A|I|N|F|D|T)([0-9]+)(?:,\\s*([0-9]+))?",
        )

    internal data class Parsed(
        val trees: List<ForeignTree>,
        val ignored: List<ImportReport.Ignored>,
        val warnings: List<String>,
    )

    override fun parse(files: List<Path>): List<ForeignTree> = parseReported(files).trees

    internal fun parseReported(
        files: List<Path>,
        xmlReader: (ByteArray, String) -> javax.xml.stream.XMLStreamReader = XmlInput::reader,
    ): Parsed {
        val expanded =
            try {
                files.flatMap { input ->
                    if (!Files.isDirectory(input)) {
                        listOf(input to input.fileName.toString())
                    } else {
                        Files.walk(input).use { paths ->
                            paths
                                .filter {
                                    Files.isRegularFile(it) &&
                                        it.fileName
                                            .toString()
                                            .substringAfterLast('.', "")
                                            .lowercase() in extensions
                                }.sorted()
                                .map { it to input.relativize(it).joinToString("/") }
                                .toList()
                        }
                    }
                }
            } catch (e: IOException) {
                throw ReaderInputException("cannot list PD input (${e.javaClass.simpleName}: ${e.message})")
            } catch (e: UncheckedIOException) {
                throw ReaderInputException("cannot list PD input (${e.javaClass.simpleName}: ${e.message})")
            }
        if (expanded.isEmpty()) throw ReaderInputException("no .cdm, .ldm or .pdm input files")
        expanded.groupBy { it.second }.values.firstOrNull { it.size > 1 }?.let { repeated ->
            throw ReaderInputException(
                "two inputs are labelled ${repeated.first().second}: " +
                    repeated.joinToString {
                        it.first
                            .toAbsolutePath()
                            .normalize()
                            .toString()
                    },
            )
        }
        val documents = expanded.map { (file, label) -> read(file, label, xmlReader) }
        // Case-folding is a collision only inside the same output model (er and db may share a package).
        val packageCodes = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        documents.forEach { d ->
            d.objects.values.filter { it.ns == "object" && it.tag in containers }.forEach { n ->
                val raw = d.rawPackagePath(n)
                val pkg = d.packagePath(n)
                val before = packageCodes.putIfAbsent(d.kind to pkg, raw to d.label)
                if (before != null && before.first != raw) {
                    throw ProfileException(
                        "ys/package-invalid",
                        XmlInput.span(d.label, n.line, n.column),
                        "PD packages '${before.first}' and '$raw' both map to package '$pkg' " +
                            "in ${before.second} and ${d.label} (${d.kind})",
                    )
                }
            }
        }
        val global =
            documents
                .flatMap { d ->
                    d.objects.values.mapNotNull { n ->
                        n.scalar("ObjectID")?.let {
                            uuid(it) to
                                (d to n)
                        }
                    }
                }.groupBy({ it.first }, { it.second })
        documents.forEach { it.global = global }
        // Determine eligibility before names: a skipped FK must not claim a Code or trigger a rename.
        documents.forEach { it.skip() }
        disambiguate(documents)
        // Project every document before any generation link: either end may have been skipped in another file.
        documents.forEach { it.project() }
        val trees =
            documents.flatMap { document ->
                val whole =
                    ForeignTree.read(
                        document.json(global),
                        document.label.substringBeforeLast('.'),
                        document.label,
                    )
                val value = whole.root.value.jsonObject
                val objects = value.getValue("objects").jsonObject
                val links = value.getValue("generationLinks").jsonObject
                val packages =
                    (objects.values + links.values)
                        .map {
                            it.jsonObject
                                .getValue("@pd")
                                .jsonObject
                                .getValue("package")
                                .jsonPrimitive.content
                        }.distinct()
                (packages.ifEmpty { listOf(document.packagePath(document.model)) }).map { pkg ->
                    fun own(items: JsonObject) =
                        JsonObject(
                            items.filterValues {
                                it.jsonObject
                                    .getValue("@pd")
                                    .jsonObject["package"]
                                    ?.jsonPrimitive
                                    ?.content ==
                                    pkg
                            },
                        )
                    whole.view(
                        buildJsonObject {
                            put(
                                "model",
                                JsonObject(
                                    value.getValue("model").jsonObject + ("package" to JsonPrimitive(pkg)),
                                ),
                            )
                            put("objects", own(objects))
                            put("generationLinks", own(links))
                            put("diagrams", own(value.getValue("diagrams").jsonObject))
                        },
                        "${whole.file}/$pkg",
                    )
                }
            }
        return Parsed(trees, documents.flatMap { it.ignored }, documents.flatMap { it.warnings })
    }

    /** Relationship Codes are names, not identities: ObjectID order makes the rename independent of XML order. */
    private fun disambiguate(documents: List<Document>) {
        val groups =
            documents
                .flatMap { d ->
                    d.objects.values
                        .filter { it.tag in relationshipObjects && it.scalar("Code") != null && !d.skipped(it) }
                        .map { d to it }
                }.groupBy { (d, n) -> Triple(d.kind, d.packagePath(n), n.tag) }
        groups.values.forEach { scope ->
            val used = scope.mapNotNull { it.second.scalar("Code") }.toMutableSet()
            scope.groupBy { it.second.scalar("Code")!! }.filterValues { it.size > 1 }.forEach { (code, duplicates) ->
                val ordered = duplicates.sortedBy { (_, n) -> n.scalar("ObjectID")?.let(::uuid).orEmpty() }
                val ids = ordered.map { (_, n) -> n.scalar("ObjectID")?.let(::uuid) }
                if (ids.any { it == null } || ids.distinct().size != ids.size) {
                    val (d, n) = ordered.last()
                    val files = ordered.joinToString { it.first.label }
                    fail(
                        d.label,
                        n,
                        "Cannot disambiguate PD relationship Code '$code': missing or repeated ObjectID " +
                            "in $files",
                    )
                }
                ordered.drop(1).forEach { (d, n) ->
                    val id = n.scalar("ObjectID")!!
                    val renamed = code + "__" + uuid(id).replace("-", "").take(8).lowercase(Locale.ROOT)
                    if (!used.add(
                            renamed,
                        )
                    ) {
                        fail(
                            d.label,
                            n,
                            "Cannot disambiguate PD relationship Code '$code': '$renamed' already exists",
                        )
                    }
                    d.codes[n] = renamed
                    d.warnings +=
                        "${d.label}:${n.line}:${n.column}: renamed PD ${n.tag} Code '$code' to '$renamed' (ObjectID $id)"
                }
            }
        }
    }

    private class Node(
        val ns: String,
        val tag: String,
        val attrs: Map<String, String>,
        val line: Int,
        val column: Int,
    ) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()
        var parent: Node? = null
        val id get() = attrs["Id"]

        fun scalar(key: String) =
            children
                .firstOrNull {
                    it.ns == "attribute" && it.tag == key
                }?.text
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        fun collection(key: String) =
            children.firstOrNull { it.ns == "collection" && it.tag == key }?.children.orEmpty()
    }

    private fun escape(value: String) = value.replace("~", "~0").replace("/", "~1")

    private fun uuid(value: String) = value.trim().trim('{', '}').uppercase()

    private fun fail(
        label: String,
        node: Node,
        message: String,
    ): Nothing =
        throw ProfileException(
            "ys/profile-invalid",
            XmlInput.span(label, node.line, node.column),
            message,
        )

    private fun read(
        file: Path,
        label: String,
        xmlReader: (ByteArray, String) -> javax.xml.stream.XMLStreamReader,
    ): Document {
        val reader = xmlReader(XmiFrontEnd.bytes(file, label), label)
        try {
            val stack = mutableListOf<Node>()
            var root: Node? = null
            var signature: String? = null
            var version: String? = null
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.PROCESSING_INSTRUCTION ->
                        if (reader.piTarget == "PowerDesigner") {
                            val attrs =
                                piAttributes.findAll(reader.piData).associate {
                                    it.groupValues[1] to
                                        it.groupValues[2]
                                }
                            signature = attrs["signature"]
                            version = attrs["version"]
                            if (version?.matches(version16) != true) {
                                val l = reader.location
                                throw ProfileException(
                                    "xm/pdm-version",
                                    XmlInput.span(label, l.lineNumber, l.columnNumber),
                                    "PowerDesigner version ${version ?: "(missing)"}; expected the 16 line",
                                )
                            }
                        }
                    XMLStreamConstants.DTD -> throw ProfileException(
                        "ys/profile-invalid",
                        ProfileSpan(label, 1, 1, 1, 1, ""),
                        "DTD is not allowed",
                    )
                    XMLStreamConstants.START_ELEMENT -> {
                        val node =
                            Node(
                                reader.namespaceURI.orEmpty(),
                                reader.localName,
                                (0 until reader.attributeCount).associate {
                                    reader.getAttributeLocalName(it) to
                                        reader.getAttributeValue(it)
                                },
                                reader.location.lineNumber.coerceAtLeast(1),
                                reader.location.columnNumber.coerceAtLeast(1),
                            )
                        node.parent = stack.lastOrNull()
                        node.parent?.children?.add(node) ?: run { root = node }
                        stack += node
                        if (stack.size > 128) fail(label, node, "PD XML exceeds 128 levels")
                    }
                    XMLStreamConstants.END_ELEMENT -> stack.removeAt(stack.lastIndex)
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        stack.lastOrNull()?.text?.append(
                            reader.text,
                        )
                }
            }
            val top = root ?: throw ReaderInputException("$label has no XML root")
            if (top.tag != "Model" || top.ns.isNotEmpty()) fail(label, top, "The root must be Model")
            if (version ==
                null
            ) {
                throw ProfileException(
                    "xm/pdm-version",
                    ProfileSpan(label, 1, 1, 1, 1, ""),
                    "PowerDesigner version is missing; expected the 16 line",
                )
            }
            val kind =
                when (signature) {
                    "CDM_DATA_MODEL_XML" -> "CDM"
                    "LDM_DATA_MODEL_XML" -> "LDM"
                    "PDM_DATA_MODEL_XML" -> "PDM"
                    else -> fail(label, top, "Unsupported PowerDesigner model signature $signature")
                }
            return Document(top, label, kind)
        } catch (e: XMLStreamException) {
            XmlInput.fail(
                label,
                e.location?.lineNumber ?: 1,
                e.location?.columnNumber ?: 1,
                e.message ?: "Invalid PD XML",
            )
        } finally {
            reader.close()
        }
    }

    private class Document(
        root: Node,
        val label: String,
        val kind: String,
    ) {
        val objects = linkedMapOf<String, Node>()
        val model: Node
        private val parents = mutableMapOf<String, MutableList<Node>>()
        private val positions = mutableMapOf<String, Node>()
        private val packagePaths = mutableMapOf<Node, String>()
        private val paths = mutableMapOf<Node, String>()
        private val skippedRecords = mutableSetOf<Node>()
        private val references = mutableMapOf<Node, JsonObject>()
        private val reportedEnds = mutableSetOf<Node>()
        private val joins = mutableMapOf<Node, List<Pair<JsonElement, JsonElement>>>()
        private lateinit var projected: Map<String, JsonObject>
        val codes = mutableMapOf<Node, String>()
        val ignored = mutableListOf<ImportReport.Ignored>()
        val warnings = mutableListOf<String>()
        lateinit var global: Map<String, List<Pair<Document, Node>>>

        init {
            fun index(node: Node) {
                if (node.ns == "object" && node.id == null && node.attrs["Ref"] == null) {
                    fail(label, node, "PD ${node.tag} has neither Id nor Ref")
                }
                node.children
                    .groupBy { it.ns to it.tag }
                    .entries
                    .firstOrNull {
                        it.value.size > 1 && it.key.first in setOf("attribute", "collection")
                    }?.let { fail(label, it.value[1], "PD ${it.key.second} is declared twice") }
                node.id?.let { if (objects.put(it, node) != null) fail(label, node, "PD id $it is declared twice") }
                node.children.forEach(::index)
            }
            index(root)
            model =
                objects.values.firstOrNull { it.tag == "Model" && it.ns == "object" } ?: fail(label, root, "No o:Model")
            objects.values.filter { it.tag == "InheritanceLink" }.forEach { link ->
                val inheritance =
                    link
                        .collection("Object1")
                        .firstOrNull()
                        ?.attrs
                        ?.get("Ref")
                        ?.let(objects::get)
                val parent = inheritance?.collection("ParentEntity")?.firstOrNull()
                val child =
                    link
                        .collection("Object2")
                        .firstOrNull()
                        ?.attrs
                        ?.get("Ref")
                if (parent != null && child != null) parents.getOrPut(child) { mutableListOf() } += parent
            }
        }

        fun rawPackagePath(node: Node): String =
            generateSequence(node) { it.parent }
                .filter { it.ns == "object" && it.tag in containers }
                .toList()
                .asReversed()
                .joinToString("/") {
                    val code = it.scalar("Code") ?: fail(label, it, "PD ${it.tag} has no Code for its package path")
                    if ('/' in code) fail(label, it, "PD package Code contains '/'")
                    code
                }

        fun packagePath(node: Node): String =
            packagePaths.getOrPut(node) {
                if (node.ns == "object" && node.tag in containers) {
                    rawPackagePath(node).lowercase(Locale.ROOT)
                } else {
                    node.parent?.let(::packagePath).orEmpty()
                }
            }

        private fun path(node: Node): String =
            paths.getOrPut(node) {
                (
                    listOf(packagePath(node)) +
                        generateSequence(node) { it.parent }
                            .filter { it.ns == "object" && it.tag !in nonDefinitions }
                            .toList()
                            .asReversed()
                            .mapNotNull { it.scalar("Code") }
                ).filter { it.isNotEmpty() }.joinToString("/")
            }

        private fun resolved(
            node: Node,
            seen: Set<Node> = emptySet(),
        ): Pair<Document, Node>? {
            if (node.tag != "Shortcut") return this to node
            val id = node.scalar("TargetID") ?: return null
            if (node in seen) fail(label, node, "PD shortcut cycle at $id")
            val candidates = global[uuid(id)].orEmpty()
            if (candidates.size > 1) {
                fail(
                    label,
                    node,
                    "Shortcut target $id is ambiguous: ${candidates.joinToString { it.first.label }}",
                )
            }
            val (doc, target) = candidates.singleOrNull() ?: return null
            return doc.resolved(target, seen + node)
        }

        private fun ref(node: Node): JsonObject =
            references.getOrPut(node) {
                val id = node.attrs["Ref"]!!
                val target = objects[id]
                val found = target?.let(::resolved)
                buildJsonObject {
                    put("\$ref", id)
                    put("@path", found?.let { (d, n) -> d.path(n) } ?: id)
                    if (found != null) {
                        val (doc, n) = found
                        n.scalar("ObjectID")?.let { put("@objectId", it) }
                        put("@type", "${doc.kind}.${n.tag}")
                    } else if (target?.tag == "Shortcut") {
                        val reason = "shortcut to ${target.scalar("TargetID")} outside the supplied models"
                        put("@skip", reason)
                    }
                }
            }

        private fun ignore(
            node: Node,
            at: String,
            reason: String,
        ) {
            ignored += ImportReport.Ignored(label, node.line, node.column, at, reason, node.scalar("Code") ?: node.id)
        }

        private fun pairs(
            node: Node,
            at: String,
        ): List<Pair<JsonElement, JsonElement>> =
            joins.getOrPut(node) {
                node.collection("Joins").mapIndexedNotNull { i, join ->
                    val ends = listOf("Object1", "Object2").map { key -> join.collection(key).firstOrNull() }
                    val refs = ends.map { it?.let(::ref) }
                    val parent = refs[0]?.get("@objectId")
                    val child = refs[1]?.get("@objectId")
                    if (parent != null && child != null) {
                        child to parent
                    } else {
                        val reason =
                            when {
                                ends.any { it == null } -> "missing Object1 or Object2"
                                refs.any {
                                    it?.containsKey(
                                        "@skip",
                                    ) == true
                                } -> refs.firstNotNullOf { it?.get("@skip")?.jsonPrimitive?.content }
                                else -> "Object1 or Object2 target has no ObjectID"
                            }
                        reportedEnds += ends.filterNotNull()
                        ignore(join, "$at/Joins/$i", "incomplete ReferenceJoin: $reason; whole pair skipped")
                        null
                    }
                }
            }

        fun skipped(node: Node) = node in skippedRecords

        fun skip() {
            // R396-A: an incomplete LDM relation must not become a loader-synthesized self-relation.
            if (kind == "LDM") {
                objects.values.filter { top(it) && it.tag == "Relationship" }.forEach { n ->
                    val missing =
                        listOf("Object1", "Object2").mapNotNull { key ->
                            n
                                .collection(key)
                                .firstOrNull()
                                ?.let(::ref)
                                ?.get("@skip")
                                ?.jsonPrimitive
                                ?.content
                                ?.let { key to it }
                        }
                    if (missing.isNotEmpty()) {
                        skippedRecords += n
                        val ends = missing.joinToString("; ") { (key, reason) -> "$key is a $reason" }
                        val reason = "LDM Relationship ${n.scalar("Code")}: $ends; relation skipped"
                        ignore(n, "/objects/${escape(n.id!!)}", reason)
                        warnings += "$label:${n.line}:${n.column}: $reason"
                    }
                }
            }
            objects.values.filter { top(it) && it.tag == "Reference" }.forEach { n ->
                val at = "/objects/${escape(n.id!!)}"
                if (pairs(n, at).isEmpty()) {
                    skippedRecords += n
                    for (key in listOf("ParentTable", "ChildTable")) {
                        n.collection(key).forEachIndexed { i, end ->
                            ref(end)["@skip"]?.jsonPrimitive?.content?.let { ignore(end, "$at/$key/$i", it) }
                        }
                    }
                    ignore(n, at, "Reference has no complete column pairs; foreign key skipped")
                }
            }
        }

        fun project() {
            projected =
                objects
                    .filterValues { top(it) && !it.tag.endsWith("Diagram") && it !in skippedRecords }
                    .mapValues { value(it.value, "/objects/${escape(it.key)}") }
        }

        private fun value(
            node: Node,
            at: String,
            report: Boolean = true,
        ): JsonObject {
            positions[at] = node
            return buildJsonObject {
                if (node.attrs["Ref"] != null) {
                    val reference = ref(node)
                    if (report && node !in reportedEnds) {
                        reference["@skip"]?.jsonPrimitive?.content?.let { reason -> ignore(node, at, reason) }
                    }
                    reference.forEach { (k, v) -> put(k, v) }
                    return@buildJsonObject
                }
                put(
                    "@pd",
                    buildJsonObject {
                        node.id?.let { put("id", it) }
                        put("type", "$kind.${node.tag}")
                        put("package", packagePath(node))
                    },
                )
                node.children.filter { it.ns == "attribute" }.forEach { child ->
                    positions["$at/${escape(child.tag)}"] = child
                    (
                        if (child.tag ==
                            "Code"
                        ) {
                            codes[node] ?: node.scalar(child.tag)
                        } else {
                            node.scalar(child.tag)
                        }
                    )?.let { put(child.tag, it) }
                }
                node.children.filter { it.ns == "collection" }.forEach { group ->
                    positions["$at/${escape(group.tag)}"] = group
                    put(
                        group.tag,
                        JsonArray(
                            group.children.mapIndexed {
                                i,
                                child,
                                ->
                                value(child, "$at/${escape(group.tag)}/$i", report)
                            },
                        ),
                    )
                }
                node.id?.let { parents[it] }?.let {
                    put(
                        "@parents",
                        JsonArray(
                            it.mapNotNull { n -> ref(n)["@objectId"] },
                        ),
                    )
                }
                if (node.tag == "Reference") {
                    val pairs = pairs(node, at)
                    put("@joinFrom", JsonArray(pairs.map { it.first }))
                    put("@joinTo", JsonArray(pairs.map { it.second }))
                }
                node.scalar("DataType")?.let { raw ->
                    val text = raw.trim().uppercase(Locale.ROOT)
                    val match = logicalType.matchEntire(text) ?: sqlType.matchEntire(text)
                    if (match != null) {
                        put(
                            "@dataType",
                            buildJsonObject {
                                val name =
                                    (
                                        match.groupValues[1].trim() +
                                            match.groupValues
                                                .getOrNull(4)
                                                .orEmpty()
                                                .let { if (it.isEmpty()) "" else " $it" }
                                    ).replace(whitespace, " ")
                                put("name", name)
                                if (name !in unsizedTypes) {
                                    match.groupValues[2].toIntOrNull()?.let { put("length", it) }
                                    match.groupValues[3].toIntOrNull()?.let { put("precision", it) }
                                }
                            },
                        )
                    }
                }
            }
        }

        private fun top(node: Node) =
            node.tag != "Package" &&
                node.parent?.ns == "collection" &&
                node.parent?.parent?.let { it.tag in containers && it.ns == "object" } == true

        fun json(global: Map<String, List<Pair<Document, Node>>>): String {
            this.global = global
            val links = linkedMapOf<String, JsonElement>()
            val originIds =
                model
                    .collection(
                        "GenerationOrigins",
                    ).mapNotNull { it.scalar("TargetID") }
                    .map(::uuid)
                    .toSet()
            objects.values.filter { it.scalar("History") != null }.forEach { target ->
                val origins =
                    historyOrigins
                        .findAll(target.scalar("History")!!)
                        .map {
                            uuid(it.groupValues[1])
                        }.distinct()
                for (origin in origins) {
                    val candidates =
                        global[origin].orEmpty().filter { (d, _) ->
                            d.model.scalar("ObjectID")?.let(::uuid) in
                                originIds
                        }
                    if (candidates.size >
                        1
                    ) {
                        fail(
                            label,
                            target,
                            "Generation origin $origin is ambiguous: ${candidates.joinToString { it.first.label }}",
                        )
                    }
                    val found = candidates.singleOrNull()
                    val sourceDoc = found?.first
                    val source = found?.second
                    if (target in skippedRecords || (source != null && source in sourceDoc!!.skippedRecords)) {
                        ignore(
                            target,
                            "/objects/${escape(target.id!!)}",
                            "generation link $origin skipped: source or target record was skipped",
                        )
                        continue
                    }
                    val id = "g_${uuid(
                        target.scalar("ObjectID") ?: target.id!!,
                    ).replace('-', '_')}_${origin.replace('-', '_')}"
                    links[id] =
                        buildJsonObject {
                            put("Code", id)
                            target.scalar("Name")?.let { put("Name", it) }
                            put("ObjectID", id)
                            put(
                                "@pd",
                                buildJsonObject {
                                    put(
                                        "type",
                                        "${sourceDoc?.kind ?: "unknown"}.${source?.tag ?: "Object"}" +
                                            "->$kind.${target.tag}",
                                    )
                                    put("package", packagePath(target))
                                },
                            )
                            put("source", source?.scalar("ObjectID") ?: origin)
                            put("target", target.scalar("ObjectID") ?: target.id!!)
                        }
                }
            }
            val raw =
                buildJsonObject {
                    put(
                        "model",
                        buildJsonObject {
                            put("type", kind)
                            put("package", packagePath(model))
                        },
                    )
                    put(
                        "objects",
                        JsonObject(projected),
                    )
                    put(
                        "diagrams",
                        JsonObject(
                            objects
                                .filterValues {
                                    top(it) && it.tag.endsWith("Diagram")
                                }.mapValues { value(it.value, "/diagrams/${escape(it.key)}", report = false) },
                        ),
                    )
                    put("generationLinks", JsonObject(links))
                }
            // Objects, including nested columns/attributes, retain their XML positions. Synthetic generation
            // records have no XML element of their own and follow the objects, as a separate collection.
            val out = StringBuilder()
            var line = 1

            fun emit(
                value: JsonElement,
                at: String,
            ) {
                while (line < (positions[at]?.line ?: line)) {
                    out.append('\n')
                    line++
                }
                when (value) {
                    is JsonObject -> {
                        out.append('{')
                        value.entries.forEachIndexed { i, (key, item) ->
                            if (i > 0) out.append(',')
                            val child = "$at/${escape(key)}"
                            while (line < (positions[child]?.line ?: line)) {
                                out.append('\n')
                                line++
                            }
                            out.append(JsonPrimitive(key)).append(':')
                            emit(item, child)
                        }
                        out.append('}')
                    }
                    is JsonArray -> {
                        out.append('[')
                        value.forEachIndexed { i, item ->
                            if (i > 0) out.append(',')
                            emit(item, "$at/$i")
                        }
                        out.append(']')
                    }
                    else -> out.append(value)
                }
            }
            emit(raw, "")
            return out.toString()
        }
    }
}
