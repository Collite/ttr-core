// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag

/** Strict shape validation uses the published C-8 schema; contextual rules follow it. */
object ReportYaml {
    private val mapper = ObjectMapper()
    private val schema by lazy {
        val text =
            requireNotNull(
                javaClass.getResourceAsStream("/schemas/report.schema.json"),
            ).reader().use { it.readText() }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(text)
    }

    fun read(
        text: String,
        folderName: String,
        preferredLanguage: String = "en",
    ): ReportSpec {
        val root =
            try {
                val options =
                    LoaderOptions().apply {
                        isAllowDuplicateKeys = false
                        maxAliasesForCollections = 0
                    }
                val constructor =
                    object : SafeConstructor(options) {
                        init {
                            // Defaults are JSON scalars. Preserve ISO text instead of YAML 1.1's java.util.Date.
                            yamlConstructors[Tag.TIMESTAMP] = ConstructYamlStr()
                        }
                    }
                mapper.valueToTree<JsonNode>(Yaml(constructor).load<Any?>(text))
            } catch (e: Exception) {
                throw ReportException("report-invalid", "Invalid report YAML: ${e.message}", e)
            }
        val errors = schema.validate(root).map { it.message }.sorted()
        if (errors.isNotEmpty()) throw ReportException("report-invalid", errors.joinToString("; "))
        if (root["id"].asText() != folderName) {
            throw ReportException("id-mismatch", "Report id '${root["id"].asText()}' must equal folder '$folderName'")
        }
        if (!root["title"].has(preferredLanguage)) {
            throw ReportException("title-missing", "Report title must include preferred language '$preferredLanguage'")
        }
        val params =
            root["params"].map { p ->
                ParamSpec(
                    name = p["name"].asText(),
                    kind = ParamKind.entries.single { it.wire == p["kind"].asText() },
                    required = p["required"]?.asBoolean() ?: false,
                    default = p["default"]?.let { mapper.convertValue(it, Any::class.java) },
                    picker =
                        p["picker"]?.let {
                            PickerSpec(
                                it["program"].asText(),
                                it["label"]?.asText(),
                                it["value"]?.asText(),
                            )
                        },
                    label = strings(p["label"]),
                )
            }
        val sheets =
            root["sheets"].map { s ->
                SheetSpec(
                    sheet = s["sheet"].asText(),
                    scalars = strings(s["scalars"]),
                    tables =
                        s["tables"].fields().asSequence().associate { (name, t) ->
                            name to TableSpec(t["columns"].map { it.asText() }, t["limit"]?.intValue())
                        },
                )
            }
        if (params.map { it.name }.distinct().size != params.size ||
            sheets.map { it.sheet.lowercase(java.util.Locale.ROOT) }.distinct().size != sheets.size
        ) {
            throw ReportException("report-invalid", "Parameter names and workbook sheet names must be unique")
        }
        return ReportSpec(
            id = root["id"].asText(),
            title = strings(root["title"]),
            program = root["program"].asText(),
            params = params,
            refresh = root["refresh"]?.asText(),
            sheets = sheets,
            history = HistorySpec(root["history"]?.get("keep")?.asText() ?: "90d"),
            roles = root["roles"]?.map { it.asText() } ?: emptyList(),
        )
    }

    private fun strings(node: JsonNode?): Map<String, String> =
        node?.fields()?.asSequence()?.associate { (key, value) -> key to value.asText() } ?: emptyMap()
}

/** Only model-dependent checks live here: the same report core serves a snapshot or Veles. */
object ReportValidator {
    fun validate(
        spec: ReportSpec,
        model: ModelLookup,
    ): List<ReportIssue> =
        buildList {
            if (!model.programExists(spec.program)) {
                add(ReportIssue("program-missing", "Program '${spec.program}' does not exist"))
            } else {
                val available = model.outputColumns(spec.program).toSet()
                for (sheet in spec.sheets) {
                    for ((anchor, table) in sheet.tables) {
                        for (column in table.columns.filterNot { it in available }) {
                            add(
                                ReportIssue(
                                    "column-missing",
                                    "Sheet '${sheet.sheet}' table '$anchor' asks for '$column'; " +
                                        "'${spec.program}' does not output it",
                                ),
                            )
                        }
                    }
                }
            }
            for (param in spec.params) {
                param.picker?.let { picker ->
                    if (!model.programExists(picker.program)) {
                        add(
                            ReportIssue(
                                "program-missing",
                                "Picker for '${param.name}' names missing program '${picker.program}'",
                            ),
                        )
                    } else {
                        val available = model.outputColumns(picker.program).toSet()
                        for (column in listOfNotNull(
                            picker.label,
                            picker.value,
                        ).distinct().filterNot { it in available }) {
                            add(
                                ReportIssue(
                                    "column-missing",
                                    "Picker for '${param.name}' asks for '$column'; " +
                                        "'${picker.program}' does not output it",
                                ),
                            )
                        }
                    }
                }
            }
        }
}
