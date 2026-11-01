// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import kotlinx.serialization.json.*

/**
 * Test adapter for the invented C-7 snapshot. Values come exclusively from serialized defs.json,
 * never from the original AST. C-7's documented projection aliases/defaults differ from syntax.
 * This is deliberately not a production snapshot importer or a new public interchange contract.
 */
internal fun canonicalExportRecord(
    id: String,
    record: JsonObject,
    records: JsonObject,
): JsonObject =
    buildJsonObject {
        put(
            "kind",
            if (record.getValue("kind").jsonPrimitive.content ==
                "foreign_key"
            ) {
                "fk"
            } else {
                record.getValue("kind").jsonPrimitive.content
            },
        )
        put("id", id.substringAfterLast('.'))
        put("properties", canonicalExportProperties(record.getValue("properties").jsonObject, records))
    }

private fun canonicalExportProperties(
    properties: JsonObject,
    records: JsonObject,
): JsonObject =
    buildJsonObject {
        val aliases =
            mapOf(
                "areaPackages" to "packages",
                "areaEntities" to "entities",
                "domainRef" to "domain",
                "dimensionRef" to "dimension",
                "cubeletRef" to "cubelet",
                "mapRef" to "map",
                "attributesMap" to "attributes",
                "measuresMap" to "measures",
                "columnsMap" to "columns",
            )
        for ((key, value) in properties) {
            if (emptyExportValue(value)) continue
            if (key == "publish" && value == JsonPrimitive(false)) continue
            val normalized =
                when (key) {
                    "publish" -> JsonPrimitive("members")
                    "columns", "attributes", "indices", "constraints", "resultColumns" ->
                        if (value is JsonArray) {
                            JsonArray(
                                value.map { nested ->
                                    val obj = nested.jsonObject
                                    val nestedId = obj.getValue("id").jsonPrimitive.content
                                    // Flat and nested C-7 records must agree; neither may bypass the YAML parse.
                                    check(JsonObject(obj - "id") == records.getValue(nestedId))
                                    JsonObject(canonicalExportRecord(nestedId, obj, records) - "kind")
                                },
                            )
                        } else {
                            canonicalExportValue(value)
                        }
                    "involves" ->
                        JsonArray(
                            value.jsonArray.map { participant ->
                                JsonObject(
                                    participant.jsonObject
                                        .map { (k, v) ->
                                            (
                                                if (k ==
                                                    "label"
                                                ) {
                                                    "as"
                                                } else {
                                                    k
                                                }
                                            ) to v
                                        }.toMap(),
                                )
                            },
                        )
                    else -> canonicalExportValue(value)
                }
            put(aliases[key] ?: key, normalized)
        }
    }

private fun emptyExportValue(value: JsonElement): Boolean =
    when (value) {
        JsonNull -> true
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty() || value.values.all { emptyExportValue(it) || it == JsonPrimitive(false) }
        else -> false
    }

private fun canonicalExportValue(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(value.filterValues { it != JsonNull }.mapValues { canonicalExportValue(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalExportValue))
        else -> value
    }
