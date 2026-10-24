// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import kotlinx.serialization.json.*

/** Explicit insertion order is the wire contract. Data-class serialization is deliberately not used. */
object GraphJsonWriter {
    fun write(
        graph: DefinitionGraph,
        header: ExportHeader,
        delta: ExportDelta? = null,
    ): ByteArray =
        jsonBytes(
            buildJsonObject {
                put("graph", "v1")
                putHeader(header)
                put(
                    "scope",
                    header.scope.header?.let { scope ->
                        buildJsonObject { scope.forEach { (key, values) -> put(key, jsonValue(values)) } }
                    }
                        ?: JsonNull,
                )
                put(
                    "lossiness",
                    JsonArray(
                        LossinessLedger.entriesFor("graph.json").map {
                            buildJsonObject {
                                put("id", it.id)
                                put("drops", it.drops)
                            }
                        },
                    ),
                )
                delta?.let { d ->
                    put(
                        "delta",
                        buildJsonObject {
                            put("since", d.since)
                            put(
                                "renamed",
                                JsonArray(
                                    d.renamed.map {
                                        buildJsonObject {
                                            put("from", it.from)
                                            put("to", it.to)
                                        }
                                    },
                                ),
                            )
                            put("removed", jsonValue(d.removed))
                        },
                    )
                }
                put(
                    "nodes",
                    JsonArray(
                        graph.nodes
                            .sortedWith(
                                compareBy<Node, String>(bytewise) { it.id }.thenBy(bytewise) { it.kind },
                            ).map { node ->
                                buildJsonObject {
                                    put("id", node.id)
                                    if (node.external) {
                                        put("kind", node.kind)
                                        put("external", true)
                                    } else {
                                        put("iri", jsonValue(node.iri))
                                        put("model", jsonValue(node.model))
                                        put("kind", node.kind)
                                        put("ossieTerm", jsonValue(node.ossieTerm))
                                        put("label", jsonValue(node.label))
                                        put("schema", jsonValue(node.schema))
                                        put("pkg", jsonValue(node.pkg))
                                        put("displayLabel", jsonValue(node.displayLabel.orEmpty()))
                                        put("labelPlural", jsonValue(node.labelPlural))
                                        put("description", jsonValue(node.description.orEmpty()))
                                        put("synonyms", jsonValue(node.synonyms.orEmpty()))
                                        put("hasDefinition", node.hasDefinition ?: false)
                                        put("definitionRef", jsonValue(node.definitionRef))
                                        put("sourceName", JsonNull)
                                        put("foreignId", JsonNull)
                                        put("external", false)
                                        put("props", jsonValue(node.props))
                                    }
                                }
                            },
                    ),
                )
                put(
                    "edges",
                    JsonArray(
                        graph.edges.sortedWith(edgeOrder).map { edge ->
                            buildJsonObject {
                                put("id", jsonValue(edge.id))
                                put("from", edge.from)
                                put("to", edge.to)
                                put("kind", edge.kind)
                                put("label", jsonValue(edge.label))
                                put("ossieTerm", jsonValue(edge.ossieTerm))
                                put("props", jsonValue(edge.props))
                                if (edge.hasDefinition) {
                                    put("hasDefinition", true)
                                    put("definitionRef", jsonValue(edge.definitionRef))
                                }
                            }
                        },
                    ),
                )
            },
        )
}

internal fun JsonObjectBuilder.putHeader(header: ExportHeader) {
    put("grammar", header.grammar)
    put("snapshot", header.snapshot)
    put("world", header.world)
}

internal fun jsonBytes(value: JsonElement): ByteArray = (value.toString() + "\n").toByteArray(Charsets.UTF_8)

internal fun jsonValue(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> ->
            buildJsonObject {
                value.entries.sortedWith(compareBy(bytewise) { it.key.toString() }).forEach { (k, v) ->
                    put(k.toString(), jsonValue(v))
                }
            }
        is Iterable<*> -> JsonArray(value.map { jsonValue(it) })
        else -> error("Unsupported export value: ${value::class.simpleName}")
    }
