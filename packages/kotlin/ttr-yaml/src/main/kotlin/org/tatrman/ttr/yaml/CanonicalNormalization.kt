// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*

/** Normalize syntax data directly, so arbitrary string content never passes through a lexer. */
internal class CanonicalNormalization(
    private val model: String?,
) {
    fun document(value: JsonObject): JsonObject {
        val result = value.toMutableMap()
        for (key in listOf("imports", "defs", "security")) {
            if ((result[key] as? JsonArray)?.isEmpty() ==
                true
            ) {
                result.remove(key)
            }
        }
        result["defs"]?.let { defs -> result["defs"] = JsonArray(defs.jsonArray.map { definition(it.jsonObject) }) }
        result["graph"]?.let { graph ->
            val g = graph.jsonObject
            result["graph"] =
                obj(
                    "id" to g.getValue("id"),
                    "properties" to
                        canonicalMap(
                            g
                                .getValue("properties")
                                .jsonObject.entries
                                .map { it.key to data(it.value) },
                            listOf("model", "description", "tags", "objects", "layout"),
                        ),
                )
        }
        result["security"]?.let { blocks ->
            result["security"] =
                JsonArray(
                    blocks.jsonArray.map { block ->
                        JsonArray(
                            block.jsonArray.map { statement ->
                                val fields = statement.jsonObject
                                val order =
                                    when (fields.string("verb")) {
                                        "own" -> listOf("verb", "object", "owner")
                                        "classify" -> listOf("verb", "object", "classification")
                                        "grant" -> listOf("verb", "privilege", "object", "to")
                                        else -> listOf("verb", "object")
                                    }
                                canonicalMap(fields.entries.map { it.key to data(it.value) }, order)
                            },
                        )
                    },
                )
        }
        return JsonObject(
            listOf("ttr", "package", "imports", "model", "schema", "locale", "graph", "security", "defs")
                .mapNotNull { k ->
                    result[k]?.let {
                        k to
                            it
                    }
                }.toMap(),
        )
    }

    private fun definition(
        value: JsonObject,
        body: String = CanonicalGrammar.kinds.getValue(value.string("kind")),
    ): JsonObject =
        obj(
            "kind" to value["kind"],
            "id" to value["id"],
            "properties" to properties(body, value.getValue("properties")),
        )

    private fun properties(
        name: String,
        value: JsonElement,
    ): JsonElement {
        val shape = CanonicalGrammar.body(name)
        if ("fields" in shape) return orderedMap(value)
        val props = CanonicalGrammar.properties(name).associateBy { it.string("key") }
        val members = shape.getValue("members").jsonArray.map { it.jsonObject }
        val entries =
            CanonicalGrammar.entries(value).map { (key, v) ->
                val normalized =
                    when {
                        members.isNotEmpty() &&
                            key == (
                                if (name ==
                                    "storageDef"
                                ) {
                                    "schemas"
                                } else {
                                    "members"
                                }
                            ) ->
                            JsonArray(
                                v.jsonArray.map { member ->
                                    val m = member.jsonObject
                                    if (name == "storageDef") {
                                        obj("id" to m["id"], "fields" to orderedMap(m.getValue("fields")))
                                    } else {
                                        val b = members.single { it.string("kind") == m.string("kind") }.string("body")
                                        definition(m, b)
                                    }
                                },
                            )
                        props[key]?.string("rule") == "stepsProperty" &&
                            v is JsonArray &&
                            v.all { it is JsonObject && "id" in it } ->
                            JsonArray(
                                v.jsonArray.map { definition(it.jsonObject, "autoBody") },
                            )
                        key in props -> property(props.getValue(key).getValue("value"), v)
                        else -> data(v)
                    }
                key to normalized
            }
        return CanonicalGrammar.serialize(name, entries, model)
    }

    private fun property(
        expression: JsonElement,
        value: JsonElement,
    ): JsonElement {
        val symbol = expression.jsonObject["symbol"]?.jsonPrimitive?.content
        if (symbol in CanonicalGrammar.bodies &&
            (value is JsonObject || value is JsonArray)
        ) {
            return properties(symbol!!, value)
        }
        val nested =
            mapOf(
                "columnDefList" to "columnDef",
                "attributeDefList" to "attributeDef",
                "indexDefList" to "indexDef",
                "constraintDefList" to "constraintDef",
                "measureInlineList" to "measureDef",
            )
        if (symbol in nested &&
            value is JsonArray
        ) {
            return JsonArray(value.map { definition(it.jsonObject, nested.getValue(symbol!!)) })
        }
        if (symbol == "parameterDefList") return JsonArray(value.jsonArray.map { properties("parameterInline", it) })
        if (symbol == "measuresValue" &&
            value is JsonArray &&
            value.firstOrNull() is JsonObject
        ) {
            return JsonArray(value.map { definition(it.jsonObject, "measureDef") })
        }
        if (symbol == "bindingValue" && value is JsonObject) return properties("bindingBlock", value)
        if (symbol == "restrictBlock") {
            return JsonObject(
                value.jsonObject.mapValues { (_, v) ->
                    if (v is JsonObject &&
                        !isGenericRecord(v) &&
                        v.keys != setOf("range") &&
                        v.keys != setOf("object")
                    ) {
                        orderedMap(v)
                    } else {
                        data(v)
                    }
                },
            )
        }
        return data(value)
    }

    private fun orderedMap(value: JsonElement): JsonObject = JsonObject(value.jsonObject.mapValues { data(it.value) })

    private fun data(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> canonicalMap(value.entries.map { it.key to data(it.value) })
            is JsonArray -> JsonArray(value.map(::data))
            else -> value
        }
}
