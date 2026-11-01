// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.n8n

import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets
import java.util.UUID

internal fun json(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

internal fun str(value: String) = JsonPrimitive(value)

internal fun arr(values: Iterable<JsonElement>) = JsonArray(values.toList())

internal fun literal(value: String): String = str(value).toString()

internal fun expression(js: String): String = "={{ $js }}"

/** Node versions are deliberately independent of the installed n8n release. */
internal object NodeFactory {
    val versions =
        linkedMapOf(
            "webhook" to 2.0,
            "httpRequest" to 4.2,
            "switch" to 3.0,
            "wait" to 1.1,
            "emailSend" to 2.1,
            "scheduleTrigger" to 1.2,
            "code" to 2.0,
            "splitInBatches" to 3.0,
            "splitOut" to 1.0,
            "if" to 2.2,
        )

    fun create(
        name: String,
        type: String,
        parameters: JsonObject,
        index: Int,
    ): JsonObject {
        val node =
            linkedMapOf<String, JsonElement>(
                "id" to str(UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8)).toString()),
                "name" to str(name),
                "type" to str("n8n-nodes-base.$type"),
                "typeVersion" to JsonPrimitive(versions.getValue(type)),
                "position" to JsonArray(listOf(JsonPrimitive(index * 220), JsonPrimitive(0))),
                "parameters" to parameters,
            )
        if (type == "httpRequest") {
            node["credentials"] = json("httpBearerAuth" to json("name" to str("tatrman-automation")))
            node["retryOnFail"] = JsonPrimitive(true)
            node["maxTries"] = JsonPrimitive(3)
        }
        if (type == "emailSend") node["credentials"] = json("smtp" to json("name" to str("tatrman-mail")))
        return JsonObject(node)
    }
}

internal class Workflow(
    private val name: String,
) {
    val nodes = mutableListOf<JsonObject>()
    val calls = mutableListOf<JsonObject>()
    val mail = mutableListOf<JsonObject>()
    private val edges = linkedMapOf<String, MutableList<MutableList<JsonObject>>>()

    fun node(
        name: String,
        type: String,
        parameters: JsonObject,
    ): String {
        require(nodes.none { it["name"] == str(name) }) { "duplicate workflow node $name" }
        nodes += NodeFactory.create(name, type, parameters, nodes.size)
        return name
    }

    fun link(
        from: String,
        to: String,
        output: Int = 0,
    ) {
        val outputs = edges.getOrPut(from) { mutableListOf() }
        while (outputs.size <= output) outputs += mutableListOf<JsonObject>()
        outputs[output] += json("node" to str(to), "type" to str("main"), "index" to JsonPrimitive(0))
    }

    fun code(
        name: String,
        js: String,
    ) = node(
        name,
        "code",
        json(
            "jsCode" to str(js),
            "mode" to str("runOnceForAllItems"),
        ),
    )

    fun condition(
        name: String,
        js: String,
    ): String = node(name, "if", json("conditions" to conditions(js)))

    fun render(
        meta: JsonObject,
        timezone: String = "UTC",
    ): String =
        Json { prettyPrint = true }.encodeToString(
            JsonObject.serializer(),
            json(
                "name" to str(name),
                "nodes" to arr(nodes),
                "connections" to
                    JsonObject(edges.mapValues { (_, outputs) -> json("main" to arr(outputs.map { arr(it) })) }),
                "settings" to json("executionOrder" to str("v1"), "timezone" to str(timezone)),
                "active" to JsonPrimitive(false),
                "pinData" to json(),
                "meta" to json("templateCredsSetupCompleted" to JsonPrimitive(false), "tatrman" to meta),
            ),
        ) + "\n"
}

internal fun conditions(js: String): JsonObject =
    json(
        "options" to
            json(
                "caseSensitive" to JsonPrimitive(true),
                "leftValue" to str(""),
                "typeValidation" to str("strict"),
                "version" to JsonPrimitive(2),
            ),
        "conditions" to
            arr(
                listOf(
                    json(
                        "leftValue" to str(expression(js)),
                        "rightValue" to JsonPrimitive(true),
                        "operator" to
                            json(
                                "type" to str("boolean"),
                                "operation" to str("true"),
                                "singleValue" to JsonPrimitive(true),
                            ),
                    ),
                ),
            ),
        "combinator" to str("and"),
    )
