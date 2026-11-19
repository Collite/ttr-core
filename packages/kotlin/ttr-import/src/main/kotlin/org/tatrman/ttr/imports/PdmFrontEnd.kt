// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.*
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/**
 * PowerDesigner 16 XML (CDM/LDM/PDM). XML attributes (`a:`) become scalars, collections (`c:`) become lists of objects,
 * and object references become `$ref` nodes. `@pd` carries the foreign class/model, local id, and package/code paths.
 * No TTR kinds or types are selected here: `pdm/16` owns that projection.
 *
 * One ForeignTree view per PD package, as with XMI. The model's Code and enclosing package Codes form its verbatim
 * package path; a Code is never sanitised. A reference keeps its XML id and the target's Code ancestry (`@path`), so
 * profile `link by: path` resolves the record that `kinds id: /Code` discovers. Duplicate paths fail in the engine.
 * Generation links are the object's History ORG ids, joined to objects of its model's GenerationOrigins among the
 * supplied files; missing models remain raw unresolved links, never trigger filesystem/network reads.
 */
object PdmFrontEnd : FrontEnd {
    override val name = "pdm"
    override val records = "/objects"
    private val extensions = setOf("cdm", "ldm", "pdm")

    override fun parse(files: List<Path>): List<ForeignTree> {
        val expanded =
            try {
                files.flatMap { input ->
                    if (!Files.isDirectory(input)) {
                        listOf(input)
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
        val documents = labelled(expanded).map { (file, label) -> read(file, label) }
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
        return documents.flatMap { document ->
            val whole = ForeignTree.read(document.json(global), document.label.substringBeforeLast('.'), document.label)
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
                        put("model", JsonObject(value.getValue("model").jsonObject + ("package" to JsonPrimitive(pkg))))
                        put("objects", own(objects))
                        put("generationLinks", own(links))
                        put("diagrams", own(value.getValue("diagrams").jsonObject))
                    },
                    "${whole.file}/$pkg",
                )
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
            ProfileSpan(label, node.line, node.column, node.line, node.column, ""),
            message,
        )

    private fun read(
        file: Path,
        label: String,
    ): Document {
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_COALESCING, true)
                setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            }
        val reader =
            try {
                factory.createXMLStreamReader(ByteArrayInputStream(XmiFrontEnd.bytes(file)))
            } catch (e: XMLStreamException) {
                throw ProfileException(
                    "ys/profile-invalid",
                    ProfileSpan(label, 1, 1, 1, 1, ""),
                    e.message ?: "Invalid PD XML",
                )
            }
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
                                Regex("([A-Za-z]+)=\"([^\"]*)\"").findAll(reader.piData).associate {
                                    it.groupValues[1] to
                                        it.groupValues[2]
                                }
                            signature = attrs["signature"]
                            version = attrs["version"]
                            if (version?.matches(Regex("16(?:\\.[0-9]+)+")) != true) {
                                val l = reader.location
                                throw ProfileException(
                                    "xm/pdm-version",
                                    ProfileSpan(label, l.lineNumber, l.columnNumber, l.lineNumber, l.columnNumber, ""),
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
                                reader.location.lineNumber,
                                reader.location.columnNumber,
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
            val l = e.location
            throw ProfileException(
                "ys/profile-invalid",
                ProfileSpan(
                    label,
                    l?.lineNumber ?: 1,
                    l?.columnNumber ?: 1,
                    l?.lineNumber ?: 1,
                    l?.columnNumber ?: 1,
                    "",
                ),
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

        fun packagePath(node: Node): String =
            generateSequence(node) {
                it.parent
            }.filter {
                it.ns == "object" &&
                    it.tag in
                    setOf(
                        "Model",
                        "Package",
                    )
            }.toList()
                .asReversed()
                .joinToString("/") {
                    val code = it.scalar("Code") ?: fail(label, it, "PD ${it.tag} has no Code for its package path")
                    if ('/' in code) fail(label, it, "PD package Code contains '/'")
                    code
                }

        private fun path(node: Node) =
            generateSequence(node) { it.parent }
                .filter {
                    it.ns == "object" &&
                        it.tag !in setOf("Model", "Package", "RootObject")
                }.toList()
                .asReversed()
                .mapNotNull { it.scalar("Code") }
                .joinToString("/")

        private fun ref(node: Node): JsonObject {
            val id = node.attrs["Ref"]!!
            val target = objects[id]
            return buildJsonObject {
                put("\$ref", id)
                put("@path", target?.let(::path) ?: id)
                if (target != null) {
                    put("@type", "$kind.${target.tag}")
                }
            }
        }

        private fun value(
            node: Node,
            at: String,
        ): JsonObject {
            positions[at] = node
            return buildJsonObject {
                if (node.attrs["Ref"] != null) {
                    ref(node).forEach { (k, v) -> put(k, v) }
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
                    node.scalar(child.tag)?.let { put(child.tag, it) }
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
                                value(child, "$at/${escape(group.tag)}/$i")
                            },
                        ),
                    )
                }
                node.id?.let { parents[it] }?.let {
                    put(
                        "@parents",
                        JsonArray(
                            it.map { n ->
                                ref(n).getValue("@path")
                            },
                        ),
                    )
                }
                if (node.tag == "Reference") {
                    val joins = node.collection("Joins")
                    for ((key, end) in listOf("@joinFrom" to "Object2", "@joinTo" to "Object1")) {
                        put(
                            key,
                            JsonArray(
                                joins.mapNotNull { join ->
                                    join.collection(end).firstOrNull()?.let { ref(it)["@path"] }
                                },
                            ),
                        )
                    }
                }
                node.scalar("DataType")?.let { raw ->
                    val match = Regex("([A-Za-z ]+)(?:\\(?([0-9]+)(?:,\\s*([0-9]+))?\\)?)?").matchEntire(raw.trim())
                    if (match != null) {
                        put(
                            "@dataType",
                            buildJsonObject {
                                put("name", match.groupValues[1].trim().uppercase())
                                match.groupValues[2].toIntOrNull()?.let { put("length", it) }
                                match.groupValues[3].toIntOrNull()?.let { put("precision", it) }
                            },
                        )
                    }
                }
            }
        }

        private fun top(node: Node) =
            node.tag != "Package" &&
                node.parent?.ns == "collection" &&
                node.parent?.parent?.let { it.tag in setOf("Model", "Package") && it.ns == "object" } == true

        fun json(global: Map<String, List<Pair<Document, Node>>>): String {
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
                    Regex("(?m)^ORG \\{([^}]+)}")
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
                    if (candidates.size > 1) fail(label, target, "Generation origin $origin is ambiguous")
                    val found = candidates.singleOrNull()
                    val sourceDoc = found?.first
                    val source = found?.second
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
                            put("source", if (sourceDoc != null && source != null) sourceDoc.path(source) else origin)
                            put("target", path(target))
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
                        JsonObject(
                            objects
                                .filterValues {
                                    top(it) && !it.tag.endsWith("Diagram")
                                }.mapValues { value(it.value, "/objects/${escape(it.key)}") },
                        ),
                    )
                    put(
                        "diagrams",
                        JsonObject(
                            objects
                                .filterValues {
                                    top(it) && it.tag.endsWith("Diagram")
                                }.mapValues { value(it.value, "/diagrams/${escape(it.key)}") },
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
