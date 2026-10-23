// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One orchestration shared by an offline CLI and platform callers; no query engine or identity policy. */
class RenderService(
    private val model: ModelLookup,
    private val preferredLanguage: String = "en",
    private val clock: Clock = Clock.systemUTC(),
) {
    fun render(
        root: Path,
        id: String,
        params: Map<String, String>,
        runner: ProgramRunner,
        caller: Caller? = null,
        html: Boolean = false,
    ): ByteArray {
        if (!Regex("[a-z][a-z0-9_]*").matches(id)) throw ReportException("report-invalid", "Invalid report id '$id'")
        try {
            val dir = root.resolve("model/reports").resolve(id).toRealPath()
            if (!dir.startsWith(
                    root.toRealPath(),
                )
            ) {
                throw ReportException("report-invalid", "Report directory escapes the checkout")
            }
            val spec = ReportYaml.read(Files.readString(dir.resolve("report.yaml")), id, preferredLanguage)
            ReportValidator.validate(spec, model).firstOrNull()?.let { throw ReportException(it.code, it.message) }
            val bound = bind(spec, params)
            val variables =
                mapOf(
                    "title" to spec.title.getValue(preferredLanguage),
                    "run.date" to LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString(),
                ) +
                    bound.mapKeys { "param.${it.key}" }.mapValues { it.value?.toString().orEmpty() }
            val data = linkedMapOf<String, ReportData>()
            for (sheet in spec.sheets) {
                val tables = linkedMapOf<String, Table>()
                for ((anchor, declaration) in sheet.tables) {
                    val result = runner.forAnchor(anchor).run(spec.program, bound, caller)
                    val indices =
                        declaration.columns.map { column ->
                            result.columns.indexOf(column).also {
                                if (it <
                                    0
                                ) {
                                    throw ReportException("column-missing", "Program result has no column '$column'")
                                }
                            }
                        }
                    val table =
                        Table(
                            declaration.columns,
                            result.rows.take(declaration.limit ?: Int.MAX_VALUE).map { row ->
                                indices.map { row[it] }
                            },
                        )
                    if (html) return HtmlTable.render(table).toByteArray(Charsets.UTF_8)
                    tables[anchor] = table
                }
                val scalars =
                    sheet.scalars.mapValues { (_, expression) ->
                        variable.replace(expression) { match ->
                            val name = match.groupValues[1]
                            variables[name]
                                ?: if (name.startsWith("param.") && spec.params.any { "param.${it.name}" == name }) {
                                    ""
                                } else {
                                    throw ReportException("scalar-invalid", "Unknown scalar variable '$name'")
                                }
                        }
                    }
                data[sheet.sheet] = ReportData(scalars, tables)
            }
            val template = Files.readAllBytes(dir.resolve("template.xlsx"))
            return try {
                XlsxFill.fillSheets(template, data)
            } catch (e: ReportException) {
                throw e
            } catch (e: Exception) {
                throw ReportException("template-invalid", "Invalid template.xlsx: ${e.message}", e)
            }
        } catch (e: java.io.IOException) {
            throw ReportException("report-missing", "Cannot read report '$id': ${e.message}", e)
        }
    }

    private fun bind(
        spec: ReportSpec,
        input: Map<String, String>,
    ): Map<String, Any?> {
        input.keys.firstOrNull { key -> spec.params.none { it.name == key } }?.let { invalid(it, "is not declared") }
        return buildMap {
            for (param in spec.params) {
                val raw = input[param.name] ?: param.default
                if (raw == null) {
                    if (param.required) invalid(param.name, "is required")
                    continue
                }
                val text = raw.toString()
                val typed: Any =
                    when (param.kind) {
                        ParamKind.TEXT, ParamKind.ENTITY -> text
                        ParamKind.INT -> text.toLongOrNull() ?: invalid(param.name, "must be an integer")
                        ParamKind.FLOAT ->
                            text.toDoubleOrNull()?.takeIf { it.isFinite() }
                                ?: invalid(param.name, "must be a finite float")
                        ParamKind.BOOL -> text.toBooleanStrictOrNull() ?: invalid(param.name, "must be true or false")
                        ParamKind.DATETIME ->
                            runCatching { Instant.parse(text) }.getOrNull()
                                ?: invalid(param.name, "must be an ISO instant with an offset")
                    }
                put(param.name, typed)
            }
        }
    }

    private fun invalid(
        name: String,
        reason: String,
    ): Nothing = throw ReportException("parameter-invalid", "Parameter '$name' $reason")

    companion object {
        private val variable = Regex("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}")
    }
}
