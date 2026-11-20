// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.profile.Bytewise
import org.tatrman.ttr.profile.ProfileException
import org.tatrman.ttr.profile.ProfileSpan
import org.tatrman.ttr.profile.Sanitise
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException

/** Fetches the two catalog export shapes consumed by the bundled reader profiles. */
internal class OpenMetadataSnapshotSource(
    base: URI,
    private val token: String,
) : AutoCloseable {
    private val api =
        base.toString().trimEnd('/').let {
            when {
                it.endsWith("/api/v1") -> it
                it.endsWith("/api") -> "$it/v1"
                else -> "$it/api/v1"
            }
        }
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(
                Duration.ofSeconds(15),
            ).followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun close() = client.close()

    fun tables(mappedTypes: Set<String>): Map<String, JsonObject> {
        val tables = records("tables", "tableConstraints,tags")
        val schemas =
            tables.groupBy { table ->
                val schema = (table["databaseSchema"] as? JsonObject)?.text("fullyQualifiedName")
                schema ?: fail("tables", "a table has no databaseSchema.fullyQualifiedName")
            }
        val names = mutableSetOf<String>()
        return schemas
            .toSortedMap(Bytewise)
            .map { (schema, records) ->
                val name = packageSegments(schema)
                if (!names.add(name.lowercase())) fail("tables", "database schema names collide after normalization")
                val sorted = records.sortedWith(compareBy(Bytewise) { it.requiredText("fullyQualifiedName", "tables") })
                val foreignKeys = sorted.flatMap(::foreignKeys)
                val document =
                    JsonObject(
                        mapOf(
                            "package" to JsonPrimitive("om.$name"),
                            "data" to JsonArray(sorted.map { table(it, mappedTypes) }),
                            "foreignKeys" to JsonArray(foreignKeys),
                        ),
                    )
                "$name.json" to document
            }.toMap()
    }

    fun glossary(): Map<String, JsonObject> {
        val endpoint = "glossaryTerms"
        val terms = records(endpoint, "parent,relatedTerms")
        val namesById =
            terms
                .mapNotNull { term ->
                    term.text("id")?.let { it to term.requiredText("fullyQualifiedName", endpoint) }
                }.toMap()

        fun referenceId(node: JsonElement?): String? {
            val ref = node as? JsonObject ?: return null
            val name = ref.text("fullyQualifiedName") ?: namesById[ref.text("id")] ?: return null
            return termId(name)
        }

        val rows =
            terms
                .sortedWith(compareBy(Bytewise) { it.requiredText("fullyQualifiedName", endpoint) })
                .map { term ->
                    val row = mutableMapOf<String, JsonElement>()
                    val qualified = term.requiredText("fullyQualifiedName", endpoint)
                    row["name*"] = JsonPrimitive(termId(qualified))
                    row["fullyQualifiedName"] = JsonPrimitive(qualified)
                    val status = term.text("entityStatus") ?: term.text("status")
                    status?.let { row["glossaryStatus"] = JsonPrimitive(it) }
                    if (status == "Approved") {
                        row["displayName"] =
                            JsonPrimitive(term.text("displayName") ?: term.requiredText("name", endpoint))
                        for (key in listOf("description", "synonyms", "tags", "references")) {
                            term[key]?.takeUnless { it == JsonNull }?.let { row[key] = it }
                        }
                        referenceId(term["parent"])?.let { row["parentName"] = JsonPrimitive(it) }
                        val related =
                            (term["relatedTerms"] as? JsonArray)
                                ?.mapNotNull(::referenceId)
                                .orEmpty()
                                .distinct()
                                .sortedWith(Bytewise)
                        if (related.isNotEmpty()) row["relatedNames"] = JsonArray(related.map(::JsonPrimitive))
                    }
                    JsonObject(row)
                }
        return mapOf("glossary.json" to JsonObject(mapOf("rows" to JsonArray(rows))))
    }

    /** Encode the complete FQN as a TTR identifier, including literal underscores and quoted path segments. */
    private fun termId(qualified: String): String =
        buildString {
            append("om_")
            for (byte in qualified.toByteArray(Charsets.UTF_8)) {
                val char = byte.toInt().toChar()
                when (char) {
                    in 'A'..'Z', in 'a'..'z', in '0'..'9' -> append(char)
                    '.' -> append("__")
                    else -> append('_').append(byte.toHexString())
                }
            }
        }

    private fun records(
        endpoint: String,
        fields: String,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val query = "limit=100&fields=${encode(fields)}" + (cursor?.let { "&after=${encode(it)}" } ?: "")
            val page = page(endpoint, query)
            val data = page["data"] as? JsonArray ?: fail(endpoint, "response has no data array")
            rows += data.map { it as? JsonObject ?: fail(endpoint, "a data record is not an object") }
            val paging = page["paging"] as? JsonObject ?: fail(endpoint, "response has no paging object")
            val after = paging["after"]
            cursor =
                when {
                    after == null || after == JsonNull -> null
                    after is JsonPrimitive && after.isString -> after.content.ifEmpty { null }
                    else -> fail(endpoint, "invalid paging cursor")
                }
            if (cursor != null && !seen.add(cursor)) fail(endpoint, "the server repeated a pagination cursor")
        } while (cursor != null)
        return rows
    }

    private fun page(
        endpoint: String,
        query: String,
    ): JsonObject {
        val request =
            HttpRequest
                .newBuilder(URI.create("$api/$endpoint?$query"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .GET()
                .build()
        val result =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            } catch (_: SSLHandshakeException) {
                fail(endpoint, "could not establish a trusted TLS connection")
            } catch (_: HttpTimeoutException) {
                fail(endpoint, "request timed out")
            } catch (_: IOException) {
                fail(endpoint, "could not read the response")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(endpoint, "request interrupted")
            }
        if (result.statusCode() != 200) fail(endpoint, "HTTP ${result.statusCode()}")
        return try {
            Json.parseToJsonElement(result.body()) as? JsonObject ?: fail(endpoint, "response is not a JSON object")
        } catch (_: SerializationException) {
            fail(endpoint, "response is not JSON")
        }
    }

    private fun table(
        table: JsonObject,
        mappedTypes: Set<String>,
    ): JsonObject =
        JsonObject(
            table.toMutableMap().apply {
                val constraints = (table["tableConstraints"] as? JsonArray).orEmpty()
                val otherConstraints =
                    constraints.filterNot {
                        (it as? JsonObject)?.text("constraintType") ==
                            "FOREIGN_KEY"
                    }
                if (otherConstraints.isEmpty()) {
                    remove(
                        "tableConstraints",
                    )
                } else {
                    put("tableConstraints", JsonArray(otherConstraints))
                }
                (table["columns"] as? JsonArray)?.let { columns ->
                    put(
                        "columns",
                        JsonArray(
                            columns.map { column ->
                                val objectColumn = column as? JsonObject ?: fail("tables", "a column is not an object")
                                JsonObject(
                                    objectColumn.toMutableMap().apply {
                                        if (objectColumn["precision"]?.let { it != JsonNull } ==
                                            true
                                        ) {
                                            remove("dataLength")
                                        }
                                        val type = objectColumn.text("dataType")
                                        if (type != null &&
                                            type !in mappedTypes
                                        ) {
                                            put("typeFallback", JsonPrimitive(type))
                                        }
                                    },
                                )
                            },
                        ),
                    )
                }
            },
        )

    private fun foreignKeys(table: JsonObject): List<JsonObject> {
        val constraints = (table["tableConstraints"] as? JsonArray).orEmpty()
        val all =
            constraints.mapNotNull { it as? JsonObject }.filter { it.text("constraintType") == "FOREIGN_KEY" }
        if (all.isEmpty()) return emptyList()
        val name = table.requiredText("name", "tables")
        val columns = (table["columns"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val byName = columns.associateBy { it.requiredText("name", "tables") }
        val byFqn = columns.mapNotNull { column -> column.text("fullyQualifiedName")?.let { it to column } }.toMap()
        val joins =
            all
                .map { constraint ->
                    val from =
                        constraint.strings("columns").map { column ->
                            (byName[column] ?: byFqn[column])?.text("fullyQualifiedName")
                                ?: fail("tables", "a foreign key local column has no matching FQN")
                        }
                    val to = constraint.strings("referredColumns")
                    if (from.size != to.size ||
                        from.isEmpty()
                    ) {
                        fail("tables", "a foreign key has unmatched column pairs")
                    }
                    from to to
                }.sortedWith(
                    compareBy(Bytewise) { (from, to) -> JsonArray((from + to).map(::JsonPrimitive)).toString() },
                )
        return joins.mapIndexed { index, (from, to) ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("fk_${name}_${index + 1}"),
                    "columns" to JsonArray(from.map(::JsonPrimitive)),
                    "referredColumns" to JsonArray(to.map(::JsonPrimitive)),
                ),
            )
        }
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.map {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    ?: fail("tables", "expected strings in $key")
            }
            ?: fail("tables", "a foreign key has no $key array")

    private fun packageSegments(schema: String): String =
        schema.split('.').joinToString(".") { segment ->
            val simple = segment.trim('"').lowercase().replace(Regex("[^a-z0-9_]"), "_")
            if (simple.firstOrNull() in 'a'..'z') simple else "schema_$simple"
        }

    private fun JsonObject.requiredText(
        key: String,
        endpoint: String,
    ): String = text(key) ?: fail(endpoint, "a record has no $key")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun fail(
        endpoint: String,
        detail: String,
    ): Nothing =
        throw ProfileException("ys/profile-invalid", ProfileSpan("openmetadata/$endpoint", 1, 1, 1, 1, ""), detail)

    companion object {
        private val CATALOG_STATE =
            setOf(
                "id",
                "href",
                "version",
                "createdat",
                "updatedat",
                "deletedat",
                "changedescription",
                "incrementalchangedescription",
            )
        private val CREDENTIALS =
            setOf(
                "authorization",
                "credentials",
                "password",
                "token",
                "accesstoken",
                "refreshtoken",
                "apitoken",
                "apikey",
                "clientsecret",
            )

        /**
         * Remove OM instance and principal bookkeeping before the normal snapshot filter runs.
         */
        fun catalogContent(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject ->
                    JsonObject(
                        element
                            .filterKeys { key ->
                                val normalized = Sanitise.key(key)
                                normalized !in CATALOG_STATE &&
                                    normalized !in Sanitise.roles &&
                                    normalized !in CREDENTIALS
                            }.toSortedMap(Bytewise)
                            .mapValues { catalogContent(it.value) },
                    )
                is JsonArray -> JsonArray(element.map(::catalogContent))
                else -> element
            }
    }
}
