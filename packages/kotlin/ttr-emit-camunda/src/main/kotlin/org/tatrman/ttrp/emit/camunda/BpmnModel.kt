// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import kotlinx.serialization.json.*
import java.security.MessageDigest

internal const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
internal const val TTR = "https://tatrman.org/schema/automation-export/1"

internal fun stableId(name: String): String =
    "n_" +
        MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8)).take(10).joinToString("") {
            "%02x".format(it)
        }

internal fun quote(value: String): String = JsonPrimitive(value).toString()

/** Small deterministic XML writer. No ambient clock, random ids or target SDK. */
internal data class Xml(
    val tag: String,
    val attrs: Map<String, String> = emptyMap(),
    val children: List<Xml> = emptyList(),
    val text: String? = null,
) {
    fun write(indent: String = ""): String =
        buildString {
            fun escape(s: String) =
                s
                    .replace(
                        "&",
                        "&amp;",
                    ).replace(
                        "<",
                        "&lt;",
                    ).replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("\r", "&#13;")
                    .replace("\n", "&#10;")
            append(indent).append('<').append(tag)
            attrs.toSortedMap().forEach { (k, v) ->
                append(' ')
                    .append(k)
                    .append("=\"")
                    .append(escape(v))
                    .append('"')
            }
            if (children.isEmpty() && text == null) {
                append(" />\n")
            } else {
                append('>')
                if (text != null) append(escape(text))
                if (children.isNotEmpty()) {
                    append('\n')
                    children.forEach { append(it.write("$indent  ")) }
                    append(indent)
                }
                append("</").append(tag).append(">\n")
            }
        }
}

internal class BpmnModel(
    val processId: String,
    val dialect: Dialect,
) {
    class Node(
        val id: String,
        val tag: String,
        val name: String,
        val attrs: MutableMap<String, String> = linkedMapOf(),
        val extensions: MutableList<Xml> = mutableListOf(),
        val tail: MutableList<Xml> = mutableListOf(),
        val child: Scope? = null,
    ) {
        var x = 0
        var y = 0
        var width =
            if (tag.endsWith("Gateway")) {
                50
            } else if (tag.endsWith("Event")) {
                36
            } else {
                160
            }
        var height =
            if (tag.endsWith("Gateway")) {
                50
            } else if (tag.endsWith("Event")) {
                36
            } else {
                90
            }
    }

    data class Flow(
        val id: String,
        val from: String,
        val to: String,
        val name: String?,
        val condition: String?,
    )

    class Scope(
        val id: String,
    ) {
        val nodes = mutableListOf<Node>()
        val flows = mutableListOf<Flow>()

        fun node(
            kind: String,
            name: String,
            key: String = name,
            child: Scope? = null,
        ): Node =
            Node(stableId("$id/node/$key"), kind, name, child = child).also {
                require(nodes.none { n -> n.id == it.id }) { "duplicate BPMN node $key" }
                nodes += it
            }

        fun link(
            from: Node,
            to: Node,
            name: String? = null,
            condition: String? = null,
        ): Flow =
            Flow(stableId("$id/flow/${from.id}/${to.id}/${flows.size}"), from.id, to.id, name, condition).also {
                flows +=
                    it
            }

        fun default(
            from: Node,
            to: Node,
        ): Flow = link(from, to).also { from.attrs["default"] = it.id }
    }

    val root = Scope(processId)
    val messages = linkedMapOf<String, Xml>()
    val calls = mutableListOf<JsonObject>()
    val routes = mutableListOf<JsonObject>()

    private fun element(
        n: Node,
        scope: Scope,
    ): Xml {
        val children =
            buildList {
                if (n.extensions.isNotEmpty()) add(Xml("bpmn:extensionElements", children = n.extensions))
                scope.flows.filter { it.to == n.id }.forEach { add(Xml("bpmn:incoming", text = it.id)) }
                scope.flows.filter { it.from == n.id }.forEach { add(Xml("bpmn:outgoing", text = it.id)) }
                addAll(n.tail)
                n.child?.let { addAll(elements(it)) }
            }
        return Xml("bpmn:${n.tag}", mapOf("id" to n.id, "name" to n.name) + n.attrs, children)
    }

    private fun elements(scope: Scope): List<Xml> =
        scope.nodes.map { element(it, scope) } +
            scope.flows.map { f ->
                Xml(
                    "bpmn:sequenceFlow",
                    mapOf("id" to f.id, "sourceRef" to f.from, "targetRef" to f.to) +
                        (f.name?.let { mapOf("name" to it) } ?: emptyMap()),
                    f.condition?.let {
                        listOf(
                            Xml(
                                "bpmn:conditionExpression",
                                mapOf("xsi:type" to "bpmn:tFormalExpression"),
                                text = dialect.condition(it),
                            ),
                        )
                    }
                        ?: emptyList(),
                )
            }

    private fun layout(
        scope: Scope,
        left: Int,
        top: Int,
    ): Pair<Int, Int> {
        val ordinary = scope.nodes.filter { it.tag != "boundaryEvent" }
        ordinary.forEach { n ->
            n.child?.let {
                val size = layout(it, 0, 0)
                n.width = size.first + 80
                n.height = size.second + 100
            }
        }
        val edges =
            scope.flows.map { it.from to it.to } +
                scope.nodes.filter { it.tag == "boundaryEvent" }.map { it.attrs.getValue("attachedToRef") to it.id }
        val state = mutableMapOf<String, Int>()
        val back = mutableSetOf<Pair<String, String>>()
        val order = mutableListOf<String>()

        fun visit(id: String) {
            state[id] = 1
            edges.filter { it.first == id }.forEach { edge ->
                when (state[edge.second]) {
                    1 -> back += edge
                    null -> visit(edge.second)
                }
            }
            state[id] = 2
            order += id
        }
        scope.nodes.forEach { if (state[it.id] == null) visit(it.id) }
        val x = scope.nodes.associate { it.id to left }.toMutableMap()
        order.asReversed().forEach { id ->
            val n = scope.nodes.single { it.id == id }
            edges.filter { it.first == id && it !in back }.forEach { edge ->
                x[edge.second] = maxOf(x.getValue(edge.second), x.getValue(id) + n.width + 100)
            }
        }
        val placed = mutableListOf<Node>()
        ordinary.forEach { n ->
            n.x = x.getValue(n.id)
            n.y = top + 60
            while (true) {
                val overlap =
                    placed.firstOrNull { p ->
                        n.x < p.x + p.width + 40 &&
                            n.x + n.width + 40 > p.x &&
                            n.y < p.y + p.height + 60 &&
                            n.y + n.height + 60 > p.y
                    }
                        ?: break
                n.y = overlap.y + overlap.height + 60
            }

            fun translate(child: Scope) {
                child.nodes.forEach { c ->
                    c.x += n.x + 40
                    c.y += n.y + 40
                    c.child?.let(::translate)
                }
            }
            n.child?.let(::translate)
            placed += n
        }
        scope.nodes.filter { it.tag == "boundaryEvent" }.forEach { n ->
            val attached = scope.nodes.single { it.id == n.attrs.getValue("attachedToRef") }
            n.x = attached.x + attached.width - 50
            n.y = attached.y + attached.height - 18
        }
        return (scope.nodes.maxOf { it.x + it.width } - left + 40) to
            (scope.nodes.maxOf { it.y + it.height } - top + 60)
    }

    private fun diagram(scope: Scope): List<Xml> =
        buildList {
            scope.nodes.forEach { n ->
                add(
                    Xml(
                        "bpmndi:BPMNShape",
                        mapOf("id" to "${n.id}_di", "bpmnElement" to n.id) +
                            if (n.child != null) mapOf("isExpanded" to "true") else emptyMap(),
                        listOf(
                            Xml(
                                "dc:Bounds",
                                mapOf(
                                    "x" to "${n.x}",
                                    "y" to "${n.y}",
                                    "width" to "${n.width}",
                                    "height" to "${n.height}",
                                ),
                            ),
                        ),
                    ),
                )
                n.child?.let { addAll(diagram(it)) }
            }
            scope.flows.forEach { f ->
                val a = scope.nodes.single { it.id == f.from }
                val b = scope.nodes.single { it.id == f.to }
                val points =
                    if (b.x > a.x) {
                        listOf((a.x + a.width) to (a.y + a.height / 2), b.x to (b.y + b.height / 2))
                    } else {
                        listOf(
                            (a.x + a.width / 2) to a.y,
                            (a.x + a.width / 2) to (a.y - 30),
                            (b.x + b.width / 2) to (b.y - 30),
                            (b.x + b.width / 2) to b.y,
                        )
                    }
                add(
                    Xml(
                        "bpmndi:BPMNEdge",
                        mapOf("id" to "${f.id}_di", "bpmnElement" to f.id),
                        points.map { (x, y) ->
                            Xml(
                                "di:waypoint",
                                mapOf(
                                    "x" to "$x",
                                    "y" to "$y",
                                ),
                            )
                        },
                    ),
                )
            }
        }

    fun render(contract: JsonObject): String {
        layout(root, 60, 60)
        val ids = mutableSetOf<String>()

        fun verify(scope: Scope) {
            scope.nodes.forEach {
                require(ids.add(it.id)) { "duplicate BPMN id" }
                it.child?.let(::verify)
            }
            scope.flows.forEach { require(ids.add(it.id)) { "duplicate BPMN flow id" } }
        }
        verify(root)
        val process =
            Xml(
                "bpmn:process",
                mapOf("id" to processId, "name" to processId, "isExecutable" to "true") +
                    if (dialect == Dialect.C7) mapOf("camunda:historyTimeToLive" to "30") else emptyMap(),
                listOf(
                    Xml("bpmn:extensionElements", children = listOf(Xml("ttr:contract", text = contract.toString()))),
                ) +
                    elements(root),
            )
        val definitions =
            Xml(
                "bpmn:definitions",
                mapOf(
                    "xmlns:bpmn" to BPMN,
                    "xmlns:bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
                    "xmlns:dc" to "http://www.omg.org/spec/DD/20100524/DC",
                    "xmlns:di" to "http://www.omg.org/spec/DD/20100524/DI",
                    "xmlns:xsi" to "http://www.w3.org/2001/XMLSchema-instance",
                    (if (dialect == Dialect.C7) "xmlns:camunda" else "xmlns:zeebe") to
                        (
                            if (dialect ==
                                Dialect.C7
                            ) {
                                "http://camunda.org/schema/1.0/bpmn"
                            } else {
                                "http://camunda.org/schema/zeebe/1.0"
                            }
                        ),
                    "xmlns:modeler" to "http://camunda.org/schema/modeler/1.0",
                    "modeler:executionPlatform" to dialect.choose("Camunda Platform", "Camunda Cloud"),
                    "modeler:executionPlatformVersion" to dialect.choose("7.24.0", "8.6.0"),
                    "xmlns:ttr" to TTR,
                    "id" to stableId("$processId/definitions"),
                    "targetNamespace" to TTR,
                    "exporter" to "ttr-emit-camunda",
                ),
                listOf(
                    Xml("bpmn:error", mapOf("id" to "stale_export", "errorCode" to "TTR_STALE")),
                    Xml(
                        "bpmn:error",
                        mapOf(
                            "id" to "run_refused",
                            "errorCode" to "TTR_REFUSED",
                        ),
                    ),
                ) +
                    messages.values +
                    process +
                    Xml(
                        "bpmndi:BPMNDiagram",
                        mapOf("id" to "diagram"),
                        listOf(
                            Xml(
                                "bpmndi:BPMNPlane",
                                mapOf(
                                    "id" to "plane",
                                    "bpmnElement" to processId,
                                ),
                                diagram(root),
                            ),
                        ),
                    ),
            )
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + definitions.write()
    }
}
