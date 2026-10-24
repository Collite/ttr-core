// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform.replay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.math.BigDecimal

/** A missing collection was not asserted by the golden; an empty collection asserts zero rows. */
data class Expected(
    val verdicts: List<JsonObject>,
    val actions: List<JsonObject>?,
    val details: List<JsonObject>?,
    val detailCounts: JsonObject?,
)

data class ReplayFixture(
    val name: String,
    val recipes: List<String>,
    val world: String?,
    val asOf: String,
    val parameters: JsonObject,
    val keys: JsonArray?,
    val expected: Map<String, Expected>,
    val finalStates: JsonObject?,
    val pinnedFrom: JsonObject?,
) {
    val seed: Boolean get() = pinnedFrom == null
}

object FixtureLoader {
    fun load(path: Path): ReplayFixture = parse(Files.readString(path), path.fileName.toString())

    fun parse(
        text: String,
        name: String = "fixture",
    ): ReplayFixture {
        val options =
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                nestingDepthLimit =
                    40
                codePointLimit = 2_000_000
            }
        val yaml = Yaml(SafeConstructor(options))
        val docs = yaml.composeAll(StringReader(text)).toList()
        require(docs.size == 1) { "$name: expected one YAML document" }
        val root = node(docs.single(), name).jsonObject

        val allowedRoot =
            setOf("case", "recipe", "recipes", "world", "as_of", "parameters", "keys", "all", "expected", "pinned_from")
        require((root.keys - allowedRoot).isEmpty()) { "$name: unknown fields ${(root.keys - allowedRoot).sorted()}" }
        require(("recipe" in root) != ("recipes" in root)) { "$name: choose recipe or recipes" }

        fun required(key: String): JsonElement = requireNotNull(root[key]) { "$name.$key: required" }

        fun string(
            v: JsonElement,
            field: String,
        ): String = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$name.$field: expected string")
        val recipe = root["recipe"]?.let { string(it, "recipe") }
        val recipes =
            recipe?.let { listOf(it) } ?: (required("recipes") as? JsonArray)?.map { string(it, "recipes[]") }
                ?: error("$name.recipes: expected list")
        require(recipes.isNotEmpty() && recipes.distinct().size == recipes.size) {
            "$name.recipes: expected unique nonempty names"
        }
        val asOf = string(required("as_of"), "as_of")
        require(
            runCatching { java.time.LocalDate.parse(asOf) }.isSuccess ||
                runCatching { java.time.Instant.parse(asOf) }.isSuccess ||
                runCatching { java.time.LocalDateTime.parse(asOf) }.isSuccess,
        ) { "$name.as_of: expected ISO date or instant" }
        val keys = root["keys"]?.let { it as? JsonArray ?: error("$name.keys: expected list") }
        require(keys == null || keys.isNotEmpty()) { "$name.keys: empty keys do not mean all" }
        require(root["all"] == null || root["all"] == JsonPrimitive(true)) { "$name.all: expected true" }
        require(keys == null || root["all"] == null) { "$name: choose keys or all" }
        val params =
            root["parameters"]?.let { it as? JsonObject ?: error("$name.parameters: expected mapping") }
                ?: JsonObject(emptyMap())
        val expected = required("expected") as? JsonObject ?: error("$name.expected: expected mapping")

        val collections = setOf("verdicts", "actions", "details", "detail_counts", "final_states")
        require((expected.keys - collections).isEmpty()) {
            "$name.expected: unknown fields ${(expected.keys - collections).sorted()}"
        }
        if (recipe == null) {
            for (field in expected.keys - "final_states") {
                val perRecipe =
                    expected.getValue(field) as? JsonObject ?: error("$name.expected.$field: expected recipe mapping")
                require((perRecipe.keys - recipes.toSet()).isEmpty()) { "$name.expected.$field: unknown recipe" }
            }
        }
        val finalStates =
            expected["final_states"]?.let { value ->
                val state = value as? JsonObject ?: error("$name.expected.final_states: expected mapping")
                require(state["entity"] is JsonPrimitive && state["attr"] is JsonObject) {
                    "$name.expected.final_states: entity and attr required"
                }
                require(
                    state.getValue("attr").jsonObject.values.all {
                        it is JsonObject
                    },
                ) { "$name.expected.final_states.attr: expected attribute -> keys mapping" }
                state
            }

        fun rows(
            v: JsonElement?,
            path: String,
        ): List<JsonObject>? =
            v?.let { a ->
                (a as? JsonArray)?.mapIndexed { i, row ->
                    row as? JsonObject
                        ?: error("$name.$path[$i]: expected mapping")
                }
                    ?: error("$name.$path: expected list")
            }
        val byRecipe =
            recipes.associateWith { r ->
                // A golden is one recipe; a replay stores each collection under its recipe name.
                fun collection(field: String): JsonElement? =
                    if (recipe !=
                        null
                    ) {
                        expected[field]
                    } else {
                        (expected[field] as? JsonObject)?.get(r)
                    }
                val verdicts =
                    rows(collection("verdicts"), "expected.verdicts.$r")
                        ?: error("$name.expected.verdicts.$r: required")
                verdicts.forEachIndexed {
                    i,
                    v,
                    ->
                    require(
                        v["key"] != null &&
                            v["key"] != JsonNull &&
                            v["route"] is JsonPrimitive &&
                            (v.keys - setOf("key", "route", "verdict_reason")).isEmpty(),
                    ) { "$name.expected.verdicts.$r[$i]: key and route required" }
                }
                val actions = rows(collection("actions"), "expected.actions.$r")
                actions?.forEachIndexed { i, v ->
                    require(
                        v["key"] != null &&
                            v["action"] is JsonPrimitive &&
                            v["columns"] is JsonObject &&
                            (v.keys - setOf("key", "action", "columns")).isEmpty(),
                    ) { "$name.expected.actions.$r[$i]: key, action and columns required" }
                }
                val details = rows(collection("details"), "expected.details.$r")
                details?.forEachIndexed { i, v ->
                    require(
                        v["key"] != null &&
                            v["route"] is JsonPrimitive &&
                            v["context"] is JsonPrimitive &&
                            v["rows"] is JsonArray,
                    ) { "$name.expected.details.$r[$i]: key, route, context and rows required" }
                }
                val counts =
                    collection("detail_counts")?.let {
                        it as? JsonObject
                            ?: error("$name.expected.detail_counts.$r: expected mapping")
                    }
                Expected(verdicts, actions, details, counts)
            }
        val pinned = root["pinned_from"]?.let { it as? JsonObject ?: error("$name.pinned_from: expected mapping") }
        pinned?.let {
            require(
                listOf("run", "at", "by").all { key ->
                    (it[key] as? JsonPrimitive)?.content?.isNotBlank() ==
                        true
                },
            ) { "$name.pinned_from: run, at, by required" }
        }
        return ReplayFixture(
            (root["case"] as? JsonPrimitive)?.content ?: name,
            recipes,
            root["world"]?.let {
                string(it, "world")
            },
            asOf,
            params,
            keys,
            byRecipe,
            finalStates,
            pinned,
        )
    }

    /** Compose nodes to retain decimal lexical precision (SafeConstructor would turn decimals into Double). */
    private fun node(
        n: Node,
        path: String,
    ): JsonElement =
        when (n) {
            is MappingNode -> {
                val entries =
                    n.value.map { tuple ->
                        val k = (tuple.keyNode as? ScalarNode)?.value ?: error("$path: scalar mapping key required")
                        k to node(tuple.valueNode, "$path.$k")
                    }
                require(entries.map { it.first }.distinct().size == entries.size) { "$path: duplicate mapping key" }
                JsonObject(entries.toMap())
            }
            is SequenceNode -> JsonArray(n.value.mapIndexed { i, v -> node(v, "$path[$i]") })
            is ScalarNode ->
                when (n.tag) {
                    Tag.NULL -> JsonNull
                    Tag.BOOL -> {
                        require(n.value.lowercase() in setOf("true", "false")) { "$path: use true/false" }
                        JsonPrimitive(n.value.toBoolean())
                    }
                    Tag.INT, Tag.FLOAT -> {
                        require(n.value.matches(Regex("[-+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?"))) {
                            "$path: expected finite decimal number"
                        }
                        JsonPrimitive(BigDecimal(n.value))
                    }
                    Tag.STR, Tag.TIMESTAMP -> JsonPrimitive(n.value)
                    else -> error("$path: unsupported YAML tag ${n.tag}")
                }
            else -> error("$path: unsupported YAML node")
        }
}
