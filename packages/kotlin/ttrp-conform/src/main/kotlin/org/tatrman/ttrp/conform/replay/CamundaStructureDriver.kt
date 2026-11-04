// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import kotlinx.serialization.json.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** A structure proof cannot manufacture Observed verdict/action evidence. */
interface StructuralDriver : Driver {
    fun compareStructure(
        fixture: ReplayFixture,
        cell: String,
    ): List<ReplayDiff>

    override fun run(
        fixture: ReplayFixture,
        cell: String,
    ): Observed = error("structural driver has no executed verdict/action evidence")
}

/** Offline BPMN contract/graph diff. Does not connect to a Server, engine or connector runtime. */
class CamundaStructureDriver(
    private val exports: List<Path>,
) : StructuralDriver {
    init {
        require(exports.isNotEmpty()) { "Camunda structure replay requires --export directories" }
    }

    override fun compareStructure(
        fixture: ReplayFixture,
        cell: String,
    ): List<ReplayDiff> {
        val target = cell.substringBefore('.').replace('-', '_')
        val mode = cell.substringAfter('.').replace('-', '_')
        require(target in setOf("camunda_7", "camunda_8") && mode in setOf("on_event", "batch")) {
            "unsupported Camunda structure cell"
        }
        val artifacts =
            exports.mapNotNull { folder ->
                val manifest = Json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
                if (manifest
                        .getValue("target")
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                        .replace('-', '_') !=
                    target
                ) {
                    return@mapNotNull null
                }
                val paths =
                    Files.list(folder).use { stream ->
                        stream
                            .filter {
                                it.fileName.toString().endsWith(
                                    if (target ==
                                        "camunda_7"
                                    ) {
                                        ".c7.bpmn"
                                    } else {
                                        ".c8.bpmn"
                                    },
                                )
                            }.toList()
                    }
                require(paths.size == 1) { "expected one matching BPMN file per export" }
                Triple(manifest, parse(Files.readString(paths.single())), paths.single())
            }
        val selected =
            fixture.recipes
                .map { recipe ->
                    artifacts.singleOrNull { (_, doc, _) ->
                        val contract = contract(doc)
                        contract.getValue("recipes").jsonArray.any { it.jsonPrimitive.content == recipe } &&
                            contract.getValue("calls").jsonArray.any {
                                val c = it.jsonObject
                                c["recipe"] == JsonPrimitive(recipe) &&
                                    c["mode"]?.jsonPrimitive?.content?.replace('-', '_') == mode
                            }
                    } ?: error("$recipe.$mode: expected one matching Camunda export")
                }.distinctBy { it.third }
        return selected.flatMap { (manifest, doc, path) ->
            compare(doc, manifest, fixture) +
                compareArtifacts(path.parent)
        }
    }

    companion object {
        /** Checks the supplementary export against its declared associations, bindings and inventory. */
        fun compareArtifacts(folder: Path): List<ReplayDiff> =
            buildList {
                val manifest = Json.parseToJsonElement(Files.readString(folder.resolve("manifest.json"))).jsonObject
                val authoring = manifest["authoring"]?.jsonObject ?: return@buildList

                fun problem(message: String) {
                    add(ReplayDiff("authoring", listOf(message), emptyList()))
                }

                fun path(relative: String): Path {
                    val p = folder.resolve(relative).normalize()
                    require(
                        p.startsWith(folder.normalize()) &&
                            relative.split('/').none { it in setOf("", ".", "..") } &&
                            !relative.contains('\\'),
                    ) { "unsafe artifact path $relative" }
                    require(Files.isRegularFile(p) && p.toRealPath().startsWith(folder.toRealPath())) {
                        "missing or unsafe artifact $relative"
                    }
                    return p
                }
                manifest["files"]?.jsonObject?.forEach { (relative, expected) ->
                    try {
                        val actual =
                            "sha256:" +
                                java.security.MessageDigest
                                    .getInstance(
                                        "SHA-256",
                                    ).digest(Files.readAllBytes(path(relative)))
                                    .joinToString("") {
                                        "%02x".format(it)
                                    }
                        if (actual != expected.jsonPrimitive.content) problem("inventory hash differs: $relative")
                    } catch (e: IllegalArgumentException) {
                        problem(e.message ?: relative)
                    }
                }
                val dialect = authoring.getValue("dialect").jsonPrimitive.content
                require(dialect in setOf("c7", "c8")) { "invalid authoring dialect" }
                authoring.getValue("steps").jsonArray.forEach { entry ->
                    val step = entry.jsonObject
                    val name = step.getValue("name").jsonPrimitive.content
                    val relative = "templates/$dialect/$name.json"
                    try {
                        val template = Json.parseToJsonElement(Files.readString(path(relative))).jsonObject
                        val properties = template.getValue("properties").jsonArray.map { it.jsonObject }
                        val source = properties.single { it["type"] == JsonPrimitive("Dropdown") }
                        val choices = source.getValue("choices").jsonArray.map { it.jsonObject.getValue("value") }
                        if (choices !=
                            authoring.getValue("choices").jsonArray.toList()
                        ) {
                            problem("world choices differ: $relative")
                        }
                        val active =
                            properties.filter { property ->
                                property["condition"]
                                    ?.jsonObject
                                    ?.get("equals")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.let { it == name }
                                    ?: true
                            }
                        val input = if (dialect == "c7") "camunda:inputParameter" else "zeebe:input"
                        val output = if (dialect == "c7") "camunda:outputParameter" else "zeebe:output"
                        val params = step.getValue("params").jsonArray.map { it.jsonPrimitive.content } + "ttr_source"
                        params.forEach { param ->
                            if (active.count { p ->
                                    p.getValue("binding").jsonObject.let {
                                        it["type"] ==
                                            JsonPrimitive(input) &&
                                            it["name"] == JsonPrimitive(param)
                                    }
                                } !=
                                1
                            ) {
                                problem("input binding $param differs: $relative")
                            }
                        }
                        val columns = step.getValue("columns").jsonObject
                        columns.keys.forEach { column ->
                            val expectedSource = if (dialect == "c7") "\${$column}" else "=$column"
                            if (active.count { p ->
                                    p.getValue("binding").jsonObject.let {
                                        it["type"] ==
                                            JsonPrimitive(output) &&
                                            it["source"] == JsonPrimitive(expectedSource)
                                    }
                                } !=
                                1
                            ) {
                                problem("output binding $column differs: $relative")
                            }
                        }
                        step["dmn"]?.jsonPrimitive?.content?.let { dmnPath ->
                            val doc = parse(Files.readString(path(dmnPath)))
                            val ns = "https://www.omg.org/spec/DMN/20191111/MODEL/"
                            val decisions = elements(doc.documentElement, ns, "decision")
                            if (decisions.singleOrNull()?.getAttribute("id") !=
                                "${name}_decision"
                            ) {
                                problem("DMN decision association differs: $dmnPath")
                            }
                            val table = elements(doc.documentElement, ns, "decisionTable").single()
                            if (table.getAttribute("hitPolicy") != "FIRST" ||
                                elements(table, ns, "rule").isNotEmpty()
                            ) {
                                problem("DMN skeleton is not empty FIRST: $dmnPath")
                            }
                            val inputs = elements(table, ns, "inputExpression")
                            val names = inputs.map { elements(it, ns, "text").single().textContent }
                            if (names != columns.keys.toList()) problem("DMN typed inputs differ: $dmnPath")
                            inputs.forEach { expression ->
                                val column = elements(expression, ns, "text").single().textContent
                                val type = columns[column]?.jsonPrimitive?.content
                                val expectedType =
                                    when (type?.lowercase()) {
                                        "integer", "decimal", "int", "float", "number" -> "number"
                                        "bool", "boolean" -> "boolean"
                                        "text", "string" -> "string"
                                        "datetime" -> "dateTime"
                                        else -> type
                                    }
                                if (expression.getAttribute("typeRef") !=
                                    expectedType
                                ) {
                                    problem("DMN input type differs: $dmnPath/$column")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        problem("$relative: ${e.message}")
                    }
                }
            }

        private const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
        private const val TTR = "https://tatrman.org/schema/automation-export/1"
        private const val CAMUNDA = "http://camunda.org/schema/1.0/bpmn"
        private const val ZEEBE = "http://camunda.org/schema/zeebe/1.0"

        private fun parse(xml: String): Document =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }.newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))

        private fun elements(
            root: Element,
            ns: String,
            local: String,
        ): List<Element> =
            root.getElementsByTagNameNS(ns, local).let { nodes ->
                (0 until nodes.length).map { nodes.item(it) as Element }
            }

        private fun contract(doc: Document): JsonObject {
            val process = elements(doc.documentElement, BPMN, "process").single()
            val element = elements(process, TTR, "contract").single()
            require(element.parentNode.parentNode == process) { "BPMN contract must belong to the process" }
            return Json.parseToJsonElement(element.textContent).jsonObject
        }

        /** Host uses this to publish the same call inventory in manifest.json. No SDK dependency. */
        fun contract(xml: String): JsonObject = contract(parse(xml))

        private fun compare(
            doc: Document,
            manifest: JsonObject,
            fixture: ReplayFixture,
        ): List<ReplayDiff> =
            buildList {
                val declaration = contract(doc)

                fun missing(
                    collection: String,
                    item: String,
                ) {
                    add(ReplayDiff("structure.$collection", listOf(item), emptyList()))
                }

                fun extra(
                    collection: String,
                    item: String,
                ) {
                    add(ReplayDiff("structure.$collection", emptyList(), listOf(item)))
                }
                val root = doc.documentElement
                val all = elements(root, "*", "*")
                val ids = all.filter { it.hasAttribute("id") }.groupBy { it.getAttribute("id") }
                ids.filterValues { it.size != 1 }.keys.forEach { extra("ids", "duplicate $it") }
                val nodes = ids.filterValues { it.size == 1 }.mapValues { it.value.single() }
                val target =
                    manifest
                        .getValue("target")
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                if (declaration["target"] != JsonPrimitive(target) ||
                    declaration["deployment"] != manifest["deployment"]
                ) {
                    missing("identity", "target/deployment must match manifest")
                }
                val wanted = manifest.getValue("calls").jsonArray
                if (wanted != declaration["calls"]) missing("calls", "BPMN contract differs from manifest calls")
                val calls = wanted.map { it.jsonObject }
                val declaredIds = calls.map { it.getValue("task").jsonPrimitive.content }
                if (declaredIds.distinct().size != declaredIds.size) extra("calls", "duplicate task reference")
                val actualHttp =
                    elements(root, BPMN, "serviceTask")
                        .filter { task ->
                            elements(task, CAMUNDA, "connectorId").any { it.textContent == "http-connector" } ||
                                elements(task, ZEEBE, "taskDefinition").any {
                                    it.getAttribute("type") ==
                                        "io.camunda:http-json:1"
                                }
                        }.map { it.getAttribute("id") }
                (actualHttp - declaredIds.toSet()).forEach { extra("calls", "undeclared HTTP task $it") }
                for (call in calls) {
                    val id = call.getValue("task").jsonPrimitive.content
                    val task = nodes[id]
                    if (task == null ||
                        task.namespaceURI != BPMN ||
                        task.localName != "serviceTask"
                    ) {
                        missing("calls", "task $id (${call.getValue("name").jsonPrimitive.content})")
                        continue
                    }
                    if (id !in actualHttp) missing("calls", "HTTP connector for $id")
                    val c7 = target == "camunda_7"
                    val parameters =
                        if (c7) {
                            elements(
                                task,
                                CAMUNDA,
                                "inputParameter",
                            )
                        } else {
                            elements(task, ZEEBE, "input")
                        }

                    fun parameter(name: String): String? =
                        parameters
                            .singleOrNull {
                                it.getAttribute(if (c7) "name" else "target") ==
                                    name
                            }?.let { if (c7) it.textContent else it.getAttribute("source") }
                    if (parameter("method") !=
                        call.getValue("method").jsonPrimitive.content
                    ) {
                        missing("calls", "method for $id")
                    }
                    val url = parameter("url").orEmpty()
                    val path = call.getValue("path").jsonPrimitive.content
                    if (path
                            .split(
                                "{id}",
                            ).any { it.isNotEmpty() && !url.contains(it) }
                    ) {
                        missing("calls", "URL path $path for $id")
                    }
                }
                val flows = elements(root, BPMN, "sequenceFlow")
                flows.forEach { flow ->
                    for (end in listOf("sourceRef", "targetRef")) {
                        val node = nodes[flow.getAttribute(end)]
                        if (node == null ||
                            node.parentNode != flow.parentNode
                        ) {
                            missing("connections", "${flow.getAttribute("id")} $end")
                        }
                    }
                }
                val routeCalls = calls.filter { it["name"] == JsonPrimitive("route") }
                val branches = declaration.getValue("routes").jsonArray.map { it.jsonObject }
                for (call in routeCalls) {
                    val task = call.getValue("task").jsonPrimitive.content
                    val route =
                        call
                            .getValue("arguments")
                            .jsonObject
                            .getValue("route")
                            .jsonPrimitive.content
                    val branch =
                        branches.singleOrNull {
                            it["task"] == JsonPrimitive(task) &&
                                it["route"] == JsonPrimitive(route)
                        }
                    val flow =
                        branch
                            ?.get("flow")
                            ?.jsonPrimitive
                            ?.content
                            ?.let { nodes[it] }
                    val gateway =
                        branch
                            ?.get("gateway")
                            ?.jsonPrimitive
                            ?.content
                            ?.let { nodes[it] }
                    if (flow == null ||
                        gateway?.localName !in setOf("exclusiveGateway", "inclusiveGateway") ||
                        flow.getAttribute("sourceRef") != gateway?.getAttribute("id") ||
                        flow.getAttribute("targetRef") != (branch?.get("entry")?.jsonPrimitive?.content ?: task) ||
                        (
                            branch?.get("entry")?.jsonPrimitive?.content?.let { nodes[it] }?.let { entry ->
                                elements(entry, BPMN, "serviceTask").none { it.getAttribute("id") == task }
                            } == true
                        )
                    ) {
                        missing("routes", "$route branch to $task")
                    } else {
                        val condition =
                            elements(
                                flow,
                                BPMN,
                                "conditionExpression",
                            ).singleOrNull()?.textContent.orEmpty()
                        if (!condition.contains(route) ||
                            !condition.contains("rowCount")
                        ) {
                            missing("routes", "$route selection condition")
                        }
                    }
                }
                // The replay fixture is an independent lower bound on expected route names, not evidence of execution.
                for ((recipe, expected) in fixture.expected) {
                    for (verdict in expected.verdicts) {
                        val route = verdict["route"]?.jsonPrimitive?.content ?: continue
                        if (branches.none {
                                it["recipe"] == JsonPrimitive(recipe) && it["route"] == JsonPrimitive(route)
                            }
                        ) {
                            missing("routes", "$recipe.$route from fixture")
                        }
                    }
                }
                val reachable = mutableSetOf<String>()
                val queue = ArrayDeque<String>()
                val process = elements(root, BPMN, "process").single()
                elements(
                    process,
                    BPMN,
                    "startEvent",
                ).filter { it.parentNode == process }.forEach { queue.add(it.getAttribute("id")) }
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    if (!reachable.add(id)) continue
                    flows
                        .filter {
                            it.getAttribute(
                                "sourceRef",
                            ) == id
                        }.forEach { queue.add(it.getAttribute("targetRef")) }
                    nodes[id]?.takeIf { it.localName == "subProcess" }?.let { sub ->
                        elements(sub, BPMN, "startEvent")
                            .filter {
                                it.parentNode ==
                                    sub
                            }.forEach { queue.add(it.getAttribute("id")) }
                    }
                    elements(root, BPMN, "boundaryEvent")
                        .filter {
                            it.getAttribute("attachedToRef") == id
                        }.forEach { queue.add(it.getAttribute("id")) }
                }
                declaredIds.filter { it !in reachable }.forEach { missing("reachability", "HTTP task $it") }
            }
    }
}
