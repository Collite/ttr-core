// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

import kotlinx.serialization.json.*
import org.tatrman.ttrp.emit.spi.*
import java.security.MessageDigest

/** Authoring metadata: Modeler IO templates and empty DMN tables, never invented business rules. */
internal class AuthoringArtifacts(
    private val request: RecipeEmitRequest,
    private val dialect: Dialect,
    /** The estate's process engine: templates carry their HTTP connector implementation, addressed to the egress. */
    private val engine: Boolean = false,
) {
    private val json = Json { prettyPrint = true }
    private val world = request.world
    private val worldName =
        Json
            .parseToJsonElement(request.manifestJson)
            .jsonObject["world"]
            ?.jsonPrimitive
            ?.content
            ?: "world"
    private val choices = (world.programs.map { it.name } + world.queries.map { it.qname }).sorted()
    private val fingerprint =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                (
                    request.graph.recipe.fingerprints
                        .toSortedMap()
                        .toString() + world.toString()
                ).toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }
    private val version =
        request.templateVersion.also {
            require(it > 0) { "TTRP-EXPORT-VERSION: template version must be a positive integer" }
        }

    private fun identifier(value: String): String {
        require(
            value.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*")),
        ) { "TTRP-EXPORT-REF: unsafe artifact identifier $value" }
        return value
    }

    private fun property(
        name: String,
        value: String,
        choice: String,
        output: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("id", stableId("$choice/$name/$output"))
            put(
                "condition",
                buildJsonObject {
                    put("property", "ttr_source")
                    put("equals", choice)
                },
            )
            put("label", name)
            put("type", "String")
            put("value", value)
            put(
                "binding",
                buildJsonObject {
                    put(
                        "type",
                        if (dialect ==
                            Dialect.C7
                        ) {
                            if (output) "camunda:outputParameter" else "camunda:inputParameter"
                        } else {
                            if (output) "zeebe:output" else "zeebe:input"
                        },
                    )
                    if (dialect == Dialect.C7) {
                        put(if (output) "source" else "name", if (output) "\${$name}" else name)
                    } else {
                        put(if (output) "source" else "name", if (output) "=$name" else name)
                    }
                },
            )
        }

    /**
     * The engine's egress (D-158) for the templates' HTTP connector, validated like the BPMN's: a plain http(s) URL,
     * and nothing a Groovy single-quoted string would need escaped.
     */
    private val egress: String by lazy {
        val raw =
            requireNotNull(
                request.graph.deployment.egressUrl
                    ?.takeIf { it.isNotBlank() },
            ) {
                "TTRP-EXPORT-EGRESS: deployment ${request.graph.deployment.name}: executor " +
                    "${request.graph.deployment.target} declares no egress_url (D-158)"
            }
        require(raw.none { it == '\'' || it == '\\' || it == '$' || it.isWhitespace() }) {
            "TTRP-EXPORT-EGRESS: egress_url $raw cannot be written into a template script"
        }
        raw.trimEnd('/')
    }

    private fun hidden(
        value: String,
        binding: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("type", "Hidden")
            put("value", value)
            put("binding", binding)
        }

    private fun input(
        name: String,
        script: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("type", "camunda:inputParameter")
            put("name", name)
            if (script) put("scriptFormat", "groovy")
        }

    /**
     * The engine template's implementation (⚑R223-D, S1.5.2 T2b): Camunda Connect's `http-connector`, addressed to the
     * egress, which injects the bearer. The step's choice (`ttr_source`) picks the run-API call at run time: a world
     * program opens a run of `ttr_deployment` (`POST <egress>/recipes/<recipe>/runs`, the port from `ttrInput` as the
     * generated BPMN reads it); a world query is `POST <egress>/query` with its parameters bound by name (D-146).
     */
    private fun connectorScope(): JsonObject {
        val programs =
            world.programs
                .sortedBy { it.name }
                .joinToString(", ", "[", "]") { p ->
                    val port =
                        when (p.port) {
                            null, "none" -> "none"
                            "key", "keys" -> p.port
                            else -> error("TTRP-EXPORT-REF: unsupported template port ${p.port} for ${p.name}")
                        }
                    "'${identifier(p.name)}': [recipe: '${identifier(p.recipe)}', port: '$port']"
                }.let { if (it == "[]") "[:]" else it }
        val queries =
            world.queries
                .sortedBy { it.qname }
                .joinToString(", ", "[", "]") { q ->
                    "'${identifier(q.qname)}': " +
                        q.params.sortedBy { it.name }.joinToString(", ", "[", "]") { "'${identifier(it.name)}'" }
                }.let { if (it == "[]") "[:]" else it }
        val url =
            """
            def source = execution.getVariable('ttr_source')
            def programs = $programs
            programs.containsKey(source) ? '$egress/recipes/' + programs[source].recipe + '/runs' : '$egress/query'
            """.trimIndent()
        val payload =
            """
            def source = execution.getVariable('ttr_source')
            def programs = $programs
            def queries = $queries
            if (programs.containsKey(source)) {
                def kind = programs[source].port
                def port = kind == 'none' ? [none: true] : [(kind): execution.getVariable('ttrInput')[kind]]
                return org.camunda.spin.Spin.JSON([deployment: execution.getVariable('ttr_deployment'), port: port, origin: 'engine']).toString()
            }
            if (!queries.containsKey(source)) throw new IllegalArgumentException('unknown world program or query ' + source)
            return org.camunda.spin.Spin.JSON([query: source, parameters: queries[source].collectEntries { [(it): execution.getVariable(it)] }]).toString()
            """.trimIndent()
        val result =
            """
            def code = ((Number) statusCode).intValue()
            if (code < 200 || code >= 300) throw new IllegalStateException('run API HTTP ' + code)
            return org.camunda.spin.Spin.JSON(response)
            """.trimIndent()
        return buildJsonObject {
            put("type", "camunda:Connector")
            put(
                "properties",
                JsonArray(
                    listOf(
                        hidden(
                            "http-connector",
                            buildJsonObject {
                                put("type", "property")
                                put("name", "connectorId")
                            },
                        ),
                        hidden("POST", input("method")),
                        hidden(url, input("url", script = true)),
                        hidden("['Content-Type': 'application/json']", input("headers", script = true)),
                        hidden(payload, input("payload", script = true)),
                        hidden(
                            "ttr_response",
                            buildJsonObject {
                                put("type", "camunda:outputParameter")
                                put("source", result)
                                put("scriptFormat", "groovy")
                            },
                        ),
                    ),
                ),
            )
        }
    }

    private fun template(name: String): ByteArray {
        identifier(name)
        val properties =
            buildList {
                add(
                    buildJsonObject {
                        put("id", "ttr_source")
                        put("label", "World program or query")
                        put("type", "Dropdown")
                        put("value", name)
                        put(
                            "choices",
                            JsonArray(
                                choices.map { choice ->
                                    buildJsonObject {
                                        put("name", choice)
                                        put("value", choice)
                                    }
                                },
                            ),
                        )
                        put(
                            "binding",
                            buildJsonObject {
                                put("type", if (dialect == Dialect.C7) "camunda:inputParameter" else "zeebe:input")
                                put("name", "ttr_source")
                            },
                        )
                    },
                )
                val sources =
                    world.programs.map { Triple(it.name, it.params, emptyList<ColumnMeta>()) } +
                        world.queries.map {
                            Triple(
                                it.qname,
                                it.params,
                                it.columns + ColumnMeta("row_count", "integer"),
                            )
                        }
                if (engine) {
                    add(
                        buildJsonObject {
                            put("id", "ttr_deployment")
                            put("label", "World deployment (program steps: the run this task opens)")
                            put("type", "String")
                            put("value", "")
                            put(
                                "binding",
                                buildJsonObject {
                                    put("type", "camunda:inputParameter")
                                    put("name", "ttr_deployment")
                                },
                            )
                        },
                    )
                }
                sources.sortedBy { it.first }.forEach { (choice, inputs, outputs) ->
                    inputs.sortedBy { it.name }.forEach { param ->
                        identifier(param.name)
                        val family = dmnType(param.type)
                        val defaultValue =
                            param.value?.let { value ->
                                if (family == "string") {
                                    value
                                } else if (dialect == Dialect.C7) {
                                    "\${$value}"
                                } else {
                                    "=$value"
                                }
                            } ?: if (dialect == Dialect.C7) "\${${param.name}}" else "=${param.name}"
                        add(property(param.name, defaultValue, choice))
                    }
                    outputs.forEach { column ->
                        identifier(column.name)
                        dmnType(column.type)
                        add(property(column.name, column.name, choice, output = true))
                    }
                }
            }
        return (
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put(
                        "\$schema",
                        if (dialect ==
                            Dialect.C7
                        ) {
                            "https://unpkg.com/@camunda/element-templates-json-schema@0.12.1/resources/schema.json"
                        } else {
                            "https://unpkg.com/@camunda/zeebe-element-templates-json-schema@0.31.0/resources/schema.json"
                        },
                    )
                    put("name", "Tatrman $name")
                    put("id", "tatrman.$worldName.$name")
                    put("version", version)
                    put("appliesTo", JsonArray(listOf(JsonPrimitive("bpmn:ServiceTask"))))
                    put("properties", JsonArray(properties))
                    if (engine) put("scopes", JsonArray(listOf(connectorScope())))
                    put("metadata", buildJsonObject { put("fingerprint", fingerprint) })
                },
            ) + "\n"
        ).toByteArray(Charsets.UTF_8)
    }

    fun files(): Map<String, ByteArray> =
        buildMap {
            require(choices.distinct().size == choices.size) { "TTRP-EXPORT-REF: ambiguous world program/query choice" }
            world.programs.sortedBy { it.name }.forEach {
                put("templates/${dialect.suffix}/${identifier(it.name)}.json", template(it.name))
            }
            world.queries.sortedBy { it.qname }.forEach { query ->
                val name = identifier(query.qname)
                require(
                    query.columns.isNotEmpty() &&
                        query.columns.none { it.name == "row_count" } &&
                        query.columns
                            .map { it.name }
                            .distinct()
                            .size == query.columns.size,
                ) {
                    "TTRP-EXPORT-COLUMNS: $name needs unique columns, excluding row_count"
                }
                val inputs = query.columns + ColumnMeta("row_count", "integer")
                put("templates/${dialect.suffix}/$name.json", template(name))
                put("dmn/${name}_decision.dmn", dmn(name, inputs))
            }
        }

    private fun dmn(
        name: String,
        inputs: List<ColumnMeta>,
    ): ByteArray {
        val id = "${name}_decision"
        val table =
            Xml(
                "decisionTable",
                mapOf("id" to "${id}_table", "hitPolicy" to "FIRST"),
                inputs.mapIndexed { i, column ->
                    Xml(
                        "input",
                        mapOf("id" to "${id}_input_$i", "label" to column.name),
                        listOf(
                            Xml(
                                "inputExpression",
                                mapOf("id" to "${id}_expression_$i", "typeRef" to dmnType(column.type)),
                                listOf(Xml("text", text = identifier(column.name))),
                            ),
                        ),
                    )
                } + Xml("output", mapOf("id" to "${id}_output", "name" to "outcome", "typeRef" to "string")),
            )
        val definitions =
            Xml(
                "definitions",
                mapOf(
                    "xmlns" to "https://www.omg.org/spec/DMN/20191111/MODEL/",
                    "xmlns:camunda" to "http://camunda.org/schema/1.0/dmn",
                    "id" to "${id}_definitions",
                    "name" to name,
                    "namespace" to "https://tatrman.org/dmn/$worldName",
                ),
                listOf(
                    Xml(
                        "decision",
                        mapOf("id" to id, "name" to id, "camunda:historyTimeToLive" to "30"),
                        listOf(table),
                    ),
                ),
            )
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + definitions.write() + "\n").toByteArray(Charsets.UTF_8)
    }
}

internal fun dmnType(type: String): String =
    when (type.lowercase().substringBefore('(')) {
        "text", "string", "varchar", "char", "nvarchar", "nchar" -> "string"
        "number", "decimal", "numeric", "float", "double", "real", "int", "integer",
        "bigint", "smallint", "tinyint", "days", "working_days",
        -> "number"
        "bool", "boolean", "bit" -> "boolean"
        "date" -> "date"
        "time" -> "time"
        "datetime", "datetime2", "timestamp", "timestamp_with_local_time_zone" -> "dateTime"
        else -> error("TTRP-EXPORT-TYPE: unsupported template/DMN type $type")
    }
