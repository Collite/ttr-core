// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.ss.SpreadsheetVersion
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.util.Locale

/** A named parameter's declared source type, without a dependency on metadata or Veles wire classes. */
data class QueryParameter(
    val name: String,
    val type: String,
)

/** Authoring adapters supply both output shape and declarations from the same snapshot. */
interface QueryModelLookup : ModelLookup {
    fun declaredParameters(qname: String): List<QueryParameter>
}

data class GeneratedTemplate(
    val yaml: String,
    val xlsx: ByteArray,
    val warnings: List<ReportIssue> = emptyList(),
)

object TemplateGenerator {
    private val mapper = ObjectMapper()
    private val plain = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
    private val reserved = setOf("yes", "no", "on", "off", "true", "false", "null")

    /**
     * Pure shared authoring path for the CLI and Platform BFF. Columns retain query order. DB parameter types
     * map to required report inputs; unknown types become text with a returned warning. ER parameters refuse
     * (LQ-5). Roles are host-supplied, empty by default. Output contains no clock, path or service identity.
     * The host owns persisting the returned pair; this method never writes files or executes a query.
     */
    fun generate(
        program: String,
        columns: List<String>,
        params: List<QueryParameter> = emptyList(),
        id: String,
        title: Map<String, String>,
        roles: List<String> = emptyList(),
    ): GeneratedTemplate {
        checkErParameters(program, params)
        if (columns.isEmpty() ||
            columns.distinct().size != columns.size ||
            columns.size > SpreadsheetVersion.EXCEL2007.maxColumns ||
            columns.joinToString(",").length + "{{table:ROWS|}}".length > SpreadsheetVersion.EXCEL2007.maxTextLength ||
            columns.any { it.isBlank() || it != it.trim() || it.any { ch -> ch in ",{}|\r\n" } }
        ) {
            throw ReportException("generator-invalid", "Columns must be unique and representable in a table marker")
        }
        val warnings = mutableListOf<ReportIssue>()
        val kinds =
            params.map { param ->
                val type =
                    param.type
                        .trim()
                        .substringBefore('(')
                        .lowercase(Locale.ROOT)
                when (type) {
                    "text", "string", "varchar", "nvarchar", "char", "nchar", "clob", "uuid" -> "text"
                    "int", "integer", "bigint", "smallint", "tinyint", "long" -> "int"
                    "float", "double", "real", "decimal", "numeric", "money" -> "float"
                    "bool", "boolean", "bit" -> "bool"
                    "date", "datetime", "datetime2", "timestamp", "time" -> "datetime"
                    else -> {
                        warnings +=
                            ReportIssue(
                                "parameter-type-unknown",
                                "Parameter '${param.name}' has unknown type '${param.type}'; using text",
                            )
                        "text"
                    }
                }
            }
        val yaml =
            buildString {
                append("id: ").append(atom(id)).append('\n')
                val languages =
                    title.keys.sortedWith(
                        compareBy<String> {
                            if (it ==
                                "en"
                            ) {
                                0
                            } else if (it == "cs") {
                                1
                            } else {
                                2
                            }
                        }.thenBy { it },
                    )
                append(
                    "title: { ",
                ).append(languages.joinToString(", ") { "${atom(it)}: ${quote(title.getValue(it))}" }).append(" }\n")
                append("version: 1\nprogram: ").append(atom(program)).append('\n')
                if (params.isEmpty()) {
                    append("params: []\n")
                } else {
                    append("params:\n")
                    params.forEachIndexed { i, p ->
                        append("  - { name: ")
                            .append(atom(p.name))
                            .append(", kind: ")
                            .append(kinds[i])
                            .append(", required: true }\n")
                    }
                }
                append("refresh: daily\nsheets:\n  - sheet: Summary\n")
                append("    scalars: { title: \"{{title}}\", as_of: \"{{run.date}}\" }\n")
                append(
                    "    tables:  { ROWS: { columns: [",
                ).append(columns.joinToString(", ") { atom(it) }).append("] } }\n")
                append("history: { keep: 90d }\nroles: [").append(roles.joinToString(", ") { atom(it) }).append("]\n")
            }
        // Apply the public schema/context checks before allocating a workbook. No second set of shape rules.
        ReportYaml.read(yaml, id, if ("en" in title) "en" else title.keys.sorted().firstOrNull() ?: "en")
        val bytes =
            XSSFWorkbook().use { book ->
                book.getFontAt(0).apply {
                    fontName = "Calibri"
                    fontHeightInPoints = 11
                }
                val heading =
                    book.createFont().apply {
                        fontName = "Calibri"
                        fontHeightInPoints = 11
                        bold = true
                    }
                val titleFont =
                    book.createFont().apply {
                        fontName = "Calibri"
                        fontHeightInPoints = 14
                        bold = true
                    }
                val headerStyle = book.createCellStyle().apply { setFont(heading) }
                val titleStyle = book.createCellStyle().apply { setFont(titleFont) }
                val sheet = book.createSheet("Summary")
                sheet.createRow(0).createCell(0).apply {
                    setCellValue("{{title}}")
                    cellStyle = titleStyle
                }
                sheet.createRow(1).createCell(0).setCellValue("{{as_of}}")
                val headers = sheet.createRow(2)
                columns.forEachIndexed { i, column ->
                    headers.createCell(i).apply {
                        setCellValue(column)
                        cellStyle = headerStyle
                    }
                    sheet.setColumnWidth(i, (column.length + 2).coerceIn(10, 80) * 256)
                }
                sheet.createRow(3).createCell(0).setCellValue("{{table:ROWS|${columns.joinToString(",")}}}")
                XlsxFill.writeDeterministic(book)
            }
        return GeneratedTemplate(yaml, bytes, warnings)
    }

    /**
     * Resolve declarations and output columns from one host-provided snapshot, then call [generate].
     * SnapshotModelLookup (CLI) and a Veles-backed QueryModelLookup (Platform) use this exact entry point.
     * ER parameter refusal precedes compilation; absent programs have the same program-missing code as render.
     */
    fun fromQuery(
        model: QueryModelLookup,
        program: String,
        id: String,
        title: Map<String, String>,
        roles: List<String> = emptyList(),
    ): GeneratedTemplate {
        if (!model.programExists(program)) throw ReportException("program-missing", "Program '$program' does not exist")
        val params = model.declaredParameters(program)
        checkErParameters(program, params)
        return generate(program, model.outputColumns(program), params, id, title, roles)
    }

    private fun checkErParameters(
        program: String,
        params: List<QueryParameter>,
    ) {
        if (program.startsWith("er.query.") && params.isNotEmpty()) {
            throw ReportException("query-has-parameters", "ER report queries must be parameterless (LQ-5)")
        }
    }

    private fun quote(value: String): String =
        mapper
            .writeValueAsString(value)
            .replace("\u0085", "\\u0085")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

    private fun atom(value: String): String =
        if (plain.matches(value) &&
            value.lowercase(Locale.ROOT) !in reserved
        ) {
            value
        } else {
            quote(value)
        }
}
