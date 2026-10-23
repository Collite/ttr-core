// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.CoreSchema

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
                val settings =
                    LoadSettings
                        .builder()
                        .setAllowDuplicateKeys(false)
                        .setMaxAliasesForCollections(0)
                        .setSchema(CoreSchema())
                        .build()
                mapper.valueToTree<JsonNode>(Load(settings).loadFromString(text))
            } catch (e: Exception) {
                throw ReportException("report-invalid", "Invalid report YAML: ${e.message}", e)
            }
        if (root?.get("sheets")?.any { sheet ->
                sheet
                    .get("tables")
                    ?.elements()
                    ?.asSequence()
                    ?.any { it.has("program") } == true
            } == true
        ) {
            throw ReportException("anchor-program-unsupported", "Per-anchor programs require report format v2")
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
        params.forEach(ParameterValues::validateDefault)
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
                if (spec.program.startsWith("db.query.")) {
                    val declared = model.declaredParameters(spec.program).map { it.name }.toSet()
                    val supplied = spec.params.map { it.name }.toSet()
                    for (name in supplied - declared) {
                        add(
                            ReportIssue(
                                "parameter-invalid",
                                "Report parameter '$name' is not declared by '${spec.program}'",
                            ),
                        )
                    }
                    for (name in declared - supplied) {
                        add(
                            ReportIssue("parameter-missing", "Report does not declare query parameter '$name'"),
                        )
                    }
                }
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
