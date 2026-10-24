// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttrp.emit.recipe.RecipePolarsEmitter
import org.tatrman.ttrp.graph.TtrpPipeline
import java.math.BigDecimal
import java.nio.file.Files
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Executes the Polars twin. JDBC is used only to extract source relations; every transform, route and calendar
 * count runs in Polars. TTRP_PYTHON selects the interpreter; its environment must contain polars and pyarrow.
 */
object RecipePolars {
    /** SQL-only local builds need no Python; CI opts in and treats runtime absence as a failure. */
    val enabled: Boolean get() = System.getenv("TTRP_CONFORM_POLARS") == "1" || System.getenv("TTRP_PYTHON") != null

    fun run(
        source: Connection,
        plan: TtrpPipeline.PlanResult,
        asOf: LocalDateTime,
    ): RecipeConform.EngineRun {
        val emitter = RecipePolarsEmitter(plan)
        val script = emitter.emit()
        val dir = Files.createTempDirectory("ttrp-recipe-polars-")
        try {
            Files.createDirectories(dir.resolve("staging"))
            Files.createDirectories(dir.resolve("out"))
            val setup =
                buildString {
                    append(
                        "import polars as pl\nfrom datetime import date, datetime, timezone\nfrom decimal import Decimal\n",
                    )
                    val bindings = RecipeBindings.of(requireNotNull(plan.recipe), asOf)
                    append(
                        "bindings = {",
                    ).append(bindings.entries.joinToString(", ") { (n, v) -> "${q(n)}: ${literal(v)}" }).append("}\n")
                    emitter.sources.forEach { (id, src) ->
                        fun ident(n: String) = "\"${n.replace("\"", "\"\"")}\""
                        val relation = src.sql?.let { "($it) AS src" } ?: ident(src.name)
                        val projection = src.columns.joinToString(", ") { "${ident(it.physical)} AS ${ident(it.name)}" }
                        val (table, decimalTypes) =
                            JdbcStatements.withRows(source, "SELECT $projection FROM $relation", emptyMap()) { rs ->
                                val md = rs.metaData
                                val types =
                                    (1..md.columnCount).associate { i ->
                                        md.getColumnLabel(i) to
                                            "pl.Decimal(${md.getPrecision(
                                                i,
                                            ).coerceIn(1, 38)}, ${md.getScale(i).coerceIn(0, 38)})"
                                    }
                                JdbcStatements.table(rs) to types
                            }
                        val schema =
                            table.columns.joinToString(", ") { c ->
                                "${q(
                                    c.name,
                                )}: ${if (c.type == "decimal") decimalTypes.getValue(c.name) else dtype(c.type)}"
                            }
                        val rows =
                            table.rows.joinToString(
                                ",\n",
                            ) { row -> "[${row.joinToString(", ", transform = ::literal)}]" }
                        append(
                            "pl.DataFrame([$rows], schema={$schema}, orient=\"row\").write_ipc(${q(
                                "staging/$id.arrow",
                            )}, compat_level=pl.CompatLevel.oldest())\n",
                        )
                    }
                }
            Files.writeString(dir.resolve("run.py"), setup + script)
            val log = dir.resolve("process.log")
            val process =
                ProcessBuilder(System.getenv("TTRP_PYTHON") ?: "python3", "run.py")
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Polars recipe execution exceeded 120 seconds")
            }
            if (process.exitValue() != 0) {
                return RecipeConform.EngineRun(
                    "polars",
                    emptyList(),
                    emptyMap(),
                    mapOf(
                        "execution" to Files.readString(log),
                    ),
                )
            }
            val outputs =
                Files.list(dir.resolve("out")).use { paths ->
                    paths.sorted().toList().associate { path ->
                        val table = ArrowIo.readTable(path)
                        path.fileName.toString().removeSuffix(".arrow") to
                            table.copy(
                                columns =
                                    table.columns.map { c ->
                                        c.copy(
                                            type =
                                                when {
                                                    c.type.startsWith("decimal(") -> "decimal"
                                                    c.type.startsWith("int") -> "int64"
                                                    else -> c.type
                                                },
                                            nullable = true,
                                        )
                                    },
                            )
                    }
                }
            return RecipeConform.EngineRun("polars", emptyList(), outputs, emptyMap())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun q(s: String) = JsonPrimitive(s).toString()

    private fun literal(v: Any?): String =
        when (v) {
            null -> "None"
            is Boolean -> if (v) "True" else "False"
            is BigDecimal -> "Decimal(${q(v.toPlainString())})"
            is Number -> v.toString()
            is LocalDateTime -> "datetime.fromisoformat(${q(v.toString())}).replace(tzinfo=timezone.utc)"
            is LocalDate -> "date.fromisoformat(${q(v.toString())})"
            else -> q(v.toString())
        }

    private fun dtype(type: String): String =
        when (type) {
            "int64" -> "pl.Int64"
            "float64" -> "pl.Float64"
            "bool" -> "pl.Boolean"
            "date32" -> "pl.Date"
            "timestamp(microsecond,UTC)" -> "pl.Datetime(\"us\", \"UTC\")"
            "decimal" -> "pl.Decimal(38, 12)"
            else -> "pl.String"
        }
}
