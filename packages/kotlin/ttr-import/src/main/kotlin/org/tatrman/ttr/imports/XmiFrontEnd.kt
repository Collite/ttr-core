// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.imports

import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.export.xmi.Uuid5
import org.tatrman.ttr.profile.ForeignTree
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import org.tatrman.ttr.yaml.MAX_YAML_CODE_POINTS
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * MW-P5 S5.3 T2 (contracts §1) — the `xmi` front-end: an XMI 2.1 file (UML 2.x, the Enterprise Architect flavour with
 * `xmi:Extension`) becomes the tree
 *
 * ```
 * {model: {"@id", name, …}, elements: {<xmi:id>: <element>, …}, "@diagrams": [{"@id", name}, …]}
 * ```
 *
 * - **elements**: every `packagedElement` that is not a `uml:Package`, at any package depth, keyed by its `xmi:id`, in
 *   document order. Packages are not elements; an element names its enclosing packages in `@xmi.package`.
 * - **An element** (and, recursively, each child element: `ownedAttribute`, `ownedEnd`, `generalization`, `lowerValue`,
 *   …) is an object: `@xmi` — `{id, uuid, type, package?}`: the `xmi:id`, a UUIDv5 of it in [NAMESPACE] (the sidecar's
 *   `foreignId`), the `xmi:type` verbatim (`uml:Class`) and, on an element, the `name`s of its packages below the model,
 *   `/`-joined (a package called `db.retail` stays one segment); then its other XML attributes verbatim by their
 *   qualified names, and its child elements grouped by tag name into lists, in document order. Text content is `@text`.
 * - **References**: an `xmi:idref` (on a child such as `memberEnd`, or `<type xmi:idref=…/>`) and any other attribute whose
 *   whole value is the `xmi:id` of an element anywhere in the document (`type`, `general`, `client`, `supplier`,
 *   `association`) is a `$ref` node `{"$ref": <id>, "@name": <the target's name>}` (`@name` absent when the target has
 *   none or is not in the document), so a profile reads a referenced name without a join.
 * - **Stereotypes and tags**: a stereotype application (a child of `xmi:XMI` with a `base_<metaclass>` attribute, such as
 *   `<ttr-mdg:concept base_Class=…/>`) is folded into the element it applies to: its local name joins `@stereotypes` (a
 *   list, document order) and its other attributes join `@tags`. EA's extension adds what it carries for the same
 *   `xmi:idref` (`elements/element`, its `attributes/attribute`, `connectors/connector`): `properties` → `@properties`
 *   (a non-empty `stereotype` also joins `@stereotypes`; a `<documentation value=…>` is `@properties.documentation`),
 *   `tags/tag` → `@tags` (`name` → `value`; a stereotype application's value wins), and a connector's `source`/`target`
 *   → `@source`/`@target`: the end's `$ref` with `role` (the role's `name`) and `multiplicity` (EA's `type`, or the
 *   role's). EA cannot tell an empty value from none, so an empty tag or property value is absent (as a CSV cell is).
 * - **Outgoing connectors**: each `uml:Association` and `uml:Dependency` element is also listed on its source element's
 *   `@outgoing`, grouped by the connector's first stereotype (else its `xmi:type`), as `{"$ref", "@name", "@tags",
 *   "@target"}`; the source is EA's connector `source`, else the first end's `type` (an association) or the `client`
 *   (a dependency). A profile reads a participant list with `each at: /@outgoing/<stereotype>`.
 * - **Not read**: diagrams (EA's `diagrams/diagram` → `@diagrams` stubs `{"@id", name}`, which a profile ignores so the
 *   report lists them), everything else in `xmi:Extension` (layout, styles, project data, EA's primitive-type package —
 *   whose types still name `$ref`s), and `xmi:Documentation`.
 *
 * Determinism: the tree follows document order, so the same bytes give the same tree. Positions: each element's object
 * starts on the tree line of its XML start tag, so a diagnostic on an element names the line the author sees (a later
 * sibling group that starts above an earlier one's last line keeps that line). The XML is read with StAX, no DTD and no
 * external entities, in the encoding its declaration names (EA writes `windows-1252`); the input is refused above 4 ×
 * the reader budget in bytes before parsing, and the tree is held to the budget again. Malformed XML, a root that is
 * not `xmi:XMI` or an `xmi:id` declared twice is `ys/profile-invalid` at its line and column.
 */
object XmiFrontEnd : FrontEnd {
    override val name = "xmi"

    override val records = "/elements"

    /**
     * The UUIDv5 namespace of `@uuid` (the sidecar's `foreignId`): UUIDv5 of `http://tatrman.org/ns/ttr-import/xmi-id`
     * in the RFC 4122 URL namespace, `@uuid` = UUIDv5(NAMESPACE, <xmi:id>) — a stable foreign id for any tool's ids,
     * EA's GUIDs included.
     */
    val NAMESPACE: UUID = Uuid5.of(Uuid5.URL, "http://tatrman.org/ns/ttr-import/xmi-id")

    override fun parse(files: List<Path>): List<ForeignTree> =
        labelled(files).map { (file, name) -> ForeignTree.read(tree(bytes(file), name), name, name) }

    /** The raw bytes, refused above 4 × the reader budget before anything is decoded. */
    internal fun bytes(file: Path): ByteArray {
        if (!Files.isRegularFile(file)) throw ReaderInputException("no input file $file")
        val limit = 4 * MAX_YAML_CODE_POINTS
        val bytes =
            try {
                Files.newInputStream(file).use { it.readNBytes(limit + 1) }
            } catch (e: IOException) {
                throw ReaderInputException("cannot read input $file (${e.javaClass.simpleName} ${e.message})")
            }
        if (bytes.size > limit) {
            throw ProfileException(
                "ys/profile-invalid",
                ProfileSpan(file.fileName.toString(), 1, 1, 1, 1, ""),
                "Input exceeds $limit bytes",
            )
        }
        return bytes
    }

    /** One parsed XML element: its qualified name, attributes in document order, children, text and position. */
    internal class XNode(
        val name: String,
        val attributes: List<Pair<String, String>>,
        val line: Int,
        val column: Int,
    ) {
        val children = mutableListOf<XNode>()
        val text = StringBuilder()

        fun attr(name: String): String? = attributes.firstOrNull { it.first == name }?.second

        val id: String? get() = attr("xmi:id")
    }

    /** The tree of [bytes] as JSON text. */
    internal fun tree(
        bytes: ByteArray,
        label: String,
    ): String = Builder(read(bytes, label), label).json()

    private fun read(
        bytes: ByteArray,
        label: String,
    ): XNode {
        val factory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
                setProperty(XMLInputFactory.IS_COALESCING, true)
            }
        var reader: XMLStreamReader? = null
        try {
            reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            val stack = ArrayList<XNode>()
            var root: XNode? = null
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val node =
                            XNode(
                                qualified(reader.prefix, reader.localName),
                                (0 until reader.attributeCount).map {
                                    qualified(reader.getAttributePrefix(it), reader.getAttributeLocalName(it)) to
                                        reader.getAttributeValue(it)
                                },
                                reader.location.lineNumber,
                                reader.location.columnNumber,
                            )
                        if (stack.isEmpty()) {
                            if (root != null) fail(label, node.line, node.column, "XMI has a second root element")
                            if (reader.localName != "XMI" || reader.namespaceURI?.contains("/XMI/") != true) {
                                fail(label, node.line, node.column, "The root element is ${node.name}, not xmi:XMI")
                            }
                            root = node
                        } else {
                            stack.last().children += node
                        }
                        stack += node
                    }
                    XMLStreamConstants.END_ELEMENT -> stack.removeAt(stack.lastIndex)
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        stack.lastOrNull()?.text?.append(reader.text)
                    else -> Unit
                }
            }
            return root ?: fail(label, 1, 1, "XMI has no root element")
        } catch (e: XMLStreamException) {
            val location = e.location
            fail(
                label,
                location?.lineNumber ?: 1,
                location?.columnNumber ?: 1,
                e.message?.substringAfter("Message: ")?.trim() ?: "XML syntax error",
            )
        } finally {
            reader?.close()
        }
    }

    private fun qualified(
        prefix: String?,
        local: String,
    ) = if (prefix.isNullOrEmpty()) local else "$prefix:$local"

    internal fun fail(
        label: String,
        line: Int,
        column: Int,
        message: String,
    ): Nothing {
        val l = line.coerceAtLeast(1)
        val c = column.coerceAtLeast(1)
        throw ProfileException("ys/profile-invalid", ProfileSpan(label, l, c, l, c, ""), message)
    }

    /** Folds the parsed document into the tree and writes it as JSON text, element objects on their XML lines. */
    private class Builder(
        private val root: XNode,
        private val label: String,
    ) {
        private val byId = LinkedHashMap<String, XNode>()
        private val stereotypes = HashMap<String, MutableList<String>>()
        private val tags = HashMap<String, LinkedHashMap<String, String>>()
        private val applied = HashMap<String, MutableSet<String>>()
        private val properties = HashMap<String, LinkedHashMap<String, String>>()
        private val ends = HashMap<String, LinkedHashMap<String, XNode>>()
        private val outgoing = LinkedHashMap<String, LinkedHashMap<String, MutableList<XNode>>>()
        private val diagrams = mutableListOf<XNode>()
        private val model: XNode?
        private val elements = mutableListOf<Pair<XNode, String>>()

        init {
            index(root)
            model = root.children.firstOrNull { it.name == "uml:Model" }
            model?.let { collect(it, emptyList()) }
            for (child in root.children) {
                when {
                    child.name == "xmi:Extension" -> extension(child)
                    child.name == "uml:Model" || child.name == "xmi:Documentation" -> Unit
                    else -> application(child)
                }
            }
            connectors()
        }

        private fun index(node: XNode) {
            node.id?.let { id ->
                if (byId.put(id, node) != null) fail(label, node.line, node.column, "xmi:id $id is declared twice")
            }
            node.children.forEach(::index)
        }

        /** Every non-package `packagedElement` below [node], with its package path. */
        private fun collect(
            node: XNode,
            packages: List<String>,
        ) {
            for (child in node.children.filter { it.name == "packagedElement" }) {
                if (child.attr("xmi:type") == "uml:Package") {
                    collect(child, packages + (child.attr("name") ?: ""))
                } else {
                    elements += child to packages.joinToString("/")
                }
            }
        }

        /** A stereotype application: `<prefix:Stereotype base_<Metaclass>="<id>" tag="value" …/>`. */
        private fun application(node: XNode) {
            val base = node.attributes.firstOrNull { it.first.startsWith("base_") }?.second ?: return
            if (base !in byId) return
            val name = node.name.substringAfter(':')
            stereotypes.getOrPut(base) { mutableListOf() }.let { if (name !in it) it += name }
            val target = tags.getOrPut(base) { LinkedHashMap() }
            val mine = applied.getOrPut(base) { mutableSetOf() }
            for ((key, value) in node.attributes) {
                if (key == "xmi:id" || key.startsWith("base_")) continue
                if (value.isNotEmpty()) target[key] = value
                mine += key
            }
        }

        /** EA's `xmi:Extension`: properties, tags and connector ends by `xmi:idref`; diagrams as stubs. */
        private fun extension(node: XNode) {
            for (group in node.children) {
                when (group.name) {
                    "elements", "connectors" ->
                        for (item in group.children) {
                            eaItem(item)
                            item.children.filter { it.name == "attributes" }.forEach { attributes ->
                                attributes.children.forEach(::eaItem)
                            }
                        }
                    "diagrams" -> diagrams += group.children.filter { it.name == "diagram" }
                    else -> Unit
                }
            }
        }

        private fun eaItem(item: XNode) {
            val id = item.attr("xmi:idref") ?: return
            if (id !in byId) return
            for (child in item.children) {
                when (child.name) {
                    "properties" -> {
                        val map = properties.getOrPut(id) { LinkedHashMap() }
                        child.attributes.forEach { (key, value) -> if (value.isNotEmpty()) map[key] = value }
                        child.attr("stereotype")?.takeIf { it.isNotEmpty() }?.let { st ->
                            stereotypes.getOrPut(id) { mutableListOf() }.let { if (st !in it) it += st }
                        }
                    }
                    "tags" -> {
                        val target = tags.getOrPut(id) { LinkedHashMap() }
                        val mine = applied[id].orEmpty()
                        for (tag in child.children.filter { it.name == "tag" }) {
                            val key = tag.attr("name") ?: continue
                            val value = tag.attr("value").orEmpty()
                            if (key !in mine && value.isNotEmpty()) target[key] = value
                        }
                    }
                    // EA writes an attribute's or a connector's notes as <documentation value=…/>.
                    "documentation" ->
                        child.attr("value")?.takeIf { it.isNotEmpty() }?.let {
                            properties.getOrPut(id) { LinkedHashMap() }.putIfAbsent("documentation", it)
                        }
                    "source", "target" -> ends.getOrPut(id) { LinkedHashMap() }[child.name] = child
                    else -> Unit
                }
            }
        }

        /** The source of each association/dependency element, for `@outgoing`. */
        private fun connectors() {
            for ((node, _) in elements) {
                val type = node.attr("xmi:type")
                if (type != "uml:Association" && type != "uml:Dependency") continue
                val id = node.id ?: continue
                val source =
                    ends[id]?.get("source")?.attr("xmi:idref")
                        ?: if (type == "uml:Dependency") {
                            node.attr("client")
                        } else {
                            node.children.firstOrNull { it.name == "ownedEnd" }?.attr("type")
                        }
                if (source == null || source !in byId) continue
                val group = stereotypes[id]?.firstOrNull() ?: type
                outgoing.getOrPut(source) { LinkedHashMap() }.getOrPut(group) { mutableListOf() } += node
            }
        }

        private fun target(connector: XNode): String? {
            val id = connector.id ?: return null
            return ends[id]?.get("target")?.attr("xmi:idref")
                ?: if (connector.attr("xmi:type") == "uml:Dependency") {
                    connector.attr("supplier")
                } else {
                    connector.children
                        .filter { it.name == "ownedEnd" }
                        .getOrNull(1)
                        ?.attr("type")
                }
        }

        // ---- JSON text, positioned ----

        private val out = StringBuilder()
        private var line = 1

        private fun at(target: Int) {
            while (line < target) {
                out.append('\n')
                line++
            }
        }

        private fun string(value: String) = out.append(JsonPrimitive(value).toString())

        private fun key(name: String) {
            string(name)
            out.append(": ")
        }

        fun json(): String {
            out.append("{")
            key("model")
            out.append("{")
            var first = true
            model?.let { m ->
                at(m.line)

                fun sep() {
                    if (!first) out.append(", ")
                    first = false
                }
                m.id?.let {
                    sep()
                    key("@id")
                    string(it)
                }
                for ((k, v) in m.attributes) {
                    if (k == "xmi:id" || k == "xmi:type") continue
                    sep()
                    key(k)
                    string(v)
                }
            }
            out.append("}, ")
            key("elements")
            out.append("{")
            elements.forEachIndexed { i, (node, pkg) ->
                if (i > 0) out.append(", ")
                at(node.line)
                key(requireNotNull(node.id) { "a packagedElement without xmi:id" })
                element(node, pkg)
            }
            out.append("}")
            if (diagrams.isNotEmpty()) {
                out.append(", ")
                at(diagrams.first().line)
                key("@diagrams")
                out.append("[")
                diagrams.forEachIndexed { i, d ->
                    if (i > 0) out.append(", ")
                    at(d.line)
                    out.append("{")
                    key("@id")
                    string(d.attr("xmi:id") ?: "")
                    d.children
                        .firstOrNull { it.name == "properties" }
                        ?.attr("name")
                        ?.let {
                            out.append(", ")
                            key("name")
                            string(it)
                        }
                    out.append("}")
                }
                out.append("]")
            }
            out.append("}\n")
            return out.toString()
        }

        private fun element(
            node: XNode,
            pkg: String?,
        ) {
            out.append("{")
            var first = true

            fun sep() {
                if (!first) out.append(", ")
                first = false
            }
            val id = node.id
            val xmi =
                buildList {
                    id?.let {
                        add("id" to it)
                        add("uuid" to Uuid5.of(NAMESPACE, it).toString())
                    }
                    node.attr("xmi:type")?.let { add("type" to it) }
                    pkg?.let { add("package" to it) }
                }
            if (xmi.isNotEmpty()) {
                sep()
                key("@xmi")
                map(xmi.toMap())
            }
            if (id != null) {
                stereotypes[id]?.let { list ->
                    sep()
                    key("@stereotypes")
                    out.append(list.joinToString(", ", "[", "]") { JsonPrimitive(it).toString() })
                }
                tags[id]?.takeIf { it.isNotEmpty() }?.let {
                    sep()
                    key("@tags")
                    map(it)
                }
                properties[id]?.takeIf { it.isNotEmpty() }?.let {
                    sep()
                    key("@properties")
                    map(it)
                }
                ends[id]?.let { e ->
                    for (end in listOf("source", "target")) {
                        val endNode = e[end] ?: continue
                        sep()
                        key("@$end")
                        end(endNode)
                    }
                }
                outgoing[id]?.let { groups ->
                    sep()
                    key("@outgoing")
                    out.append("{")
                    groups.entries.forEachIndexed { i, (group, list) ->
                        if (i > 0) out.append(", ")
                        key(group)
                        out.append("[")
                        list.forEachIndexed { j, c ->
                            if (j > 0) out.append(", ")
                            connector(c)
                        }
                        out.append("]")
                    }
                    out.append("}")
                }
            }
            for ((k, v) in node.attributes) {
                if (k == "xmi:id" || k == "xmi:type") continue
                sep()
                if (k == "xmi:idref") {
                    key("\$ref")
                    string(v)
                    byId[v]?.attr("name")?.let {
                        out.append(", ")
                        key("@name")
                        string(it)
                    }
                    continue
                }
                key(k)
                if (k != "name" && v in byId) ref(v) else string(v)
            }
            node.text
                .toString()
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let {
                    sep()
                    key("@text")
                    string(it)
                }
            val groups = LinkedHashMap<String, MutableList<XNode>>()
            node.children.forEach { groups.getOrPut(it.name) { mutableListOf() } += it }
            for ((name, children) in groups) {
                sep()
                key(name)
                out.append("[")
                children.forEachIndexed { i, child ->
                    if (i > 0) out.append(", ")
                    at(child.line)
                    element(child, null)
                }
                out.append("]")
            }
            out.append("}")
        }

        private fun ref(id: String) {
            out.append("{")
            key("\$ref")
            string(id)
            byId[id]?.attr("name")?.let {
                out.append(", ")
                key("@name")
                string(it)
            }
            out.append("}")
        }

        private fun end(node: XNode) {
            out.append("{")
            key("\$ref")
            string(node.attr("xmi:idref") ?: "")
            node.attr("xmi:idref")?.let { byId[it]?.attr("name") }?.let {
                out.append(", ")
                key("@name")
                string(it)
            }
            val role = node.children.firstOrNull { it.name == "role" }
            role?.attr("name")?.takeIf { it.isNotEmpty() }?.let {
                out.append(", ")
                key("role")
                string(it)
            }
            // EA writes the multiplicity on the end's <type>; the XM-P1 writer on its <role>.
            val multiplicity =
                node.children
                    .filter { it.name == "type" || it.name == "role" }
                    .firstNotNullOfOrNull { it.attr("multiplicity")?.takeIf { m -> m.isNotEmpty() } }
            multiplicity?.let {
                out.append(", ")
                key("multiplicity")
                string(it)
            }
            out.append("}")
        }

        private fun connector(node: XNode) {
            val id = requireNotNull(node.id)
            out.append("{")
            key("\$ref")
            string(id)
            node.attr("name")?.let {
                out.append(", ")
                key("@name")
                string(it)
            }
            tags[id]?.takeIf { it.isNotEmpty() }?.let {
                out.append(", ")
                key("@tags")
                map(it)
            }
            target(node)?.let { t ->
                out.append(", ")
                key("@target")
                ref(t)
            }
            out.append("}")
        }

        private fun map(values: Map<String, String>) {
            out.append(
                values.entries.joinToString(", ", "{", "}") { (k, v) ->
                    "${JsonPrimitive(k)}: ${JsonPrimitive(v)}"
                },
            )
        }
    }
}
