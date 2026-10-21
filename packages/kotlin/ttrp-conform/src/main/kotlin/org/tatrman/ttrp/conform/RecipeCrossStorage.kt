// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import org.tatrman.ttrp.emit.recipe.RecipeStatements
import org.tatrman.ttrp.emit.sql.SqlTier
import org.tatrman.ttrp.graph.TtrpPipeline
import java.sql.Connection
import java.sql.Types
import java.time.LocalDateTime

/** The native runner's data path: storage door reads → typed DuckDB tables → combines. */
object RecipeCrossStorage {
    fun run(
        engines: Map<String, RecipeEngine>,
        plan: TtrpPipeline.PlanResult,
        asOf: LocalDateTime,
    ): RecipeConform.EngineRun {
        require(engines.isNotEmpty()) { "no storage engines" }
        val bindings = RecipeBindings.of(requireNotNull(plan.recipe), asOf)
        val dialects = engines.values.map { it.dialect }.distinct()
        require(dialects.size == 1) { "conform requires one source dialect per engine run" }
        val statements = RecipeStatements.plan(plan, SqlTier.DB, dialects.single(), splitStorage = true)
        val parts = statements.flatMap { it.parts }.distinctBy { it.name }
        val outputs = linkedMapOf<String, ConformTable>()
        val failures = linkedMapOf<String, String>()
        DuckDbRecipeEngine(emptyList()).use { combine ->
            for (p in parts) {
                try {
                    val engine = engines.getValue(p.storage)
                    copy(engine.connection, combine.connection, p.name, p.sql, bindings)
                } catch (e: Exception) {
                    failures[p.name] = "storage ${p.storage}: ${e.message}"
                }
            }
            for (s in statements) {
                val key = "${s.island}.${s.port}"
                try {
                    if (s.parts.any { it.name in failures }) {
                        failures[key] = "a required part failed"
                        continue
                    }
                    val connection =
                        if (s.parts.isNotEmpty()) {
                            combine.connection
                        } else {
                            engines.getValue(requireNotNull(s.storage)).connection
                        }
                    outputs[key] = JdbcStatements.query(connection, s.sql, bindings)
                } catch (e: Exception) {
                    failures[key] = "${e.message}\n${s.sql}"
                }
            }
        }
        return RecipeConform.EngineRun(engines.values.first().name + "-parts", statements, outputs, failures)
    }

    private fun quoted(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun copy(
        source: Connection,
        target: Connection,
        name: String,
        sql: String,
        bindings: Map<String, Any?>,
    ) {
        val (text, params) = JdbcStatements.positional(sql)
        source.prepareStatement(text).use { query ->
            params.forEachIndexed { i, p ->
                require(p in bindings)
                query.setObject(i + 1, bindings[p])
            }
            query.executeQuery().use { rows ->
                val md = rows.metaData
                val columns =
                    (1..md.columnCount).map { i ->
                        val type =
                            when (md.getColumnType(i)) {
                                Types.NUMERIC, Types.DECIMAL -> "DECIMAL(${md.getPrecision(
                                    i,
                                ).coerceIn(1, 38)},${md.getScale(i).coerceIn(0, 38)})"
                                Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "BIGINT"
                                Types.FLOAT, Types.REAL, Types.DOUBLE -> "DOUBLE"
                                Types.BOOLEAN, Types.BIT -> "BOOLEAN"
                                Types.DATE -> "DATE"
                                Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP"
                                else -> "VARCHAR"
                            }
                        quoted(md.getColumnLabel(i)) + " " + type
                    }
                target.createStatement().use { it.execute("CREATE TABLE ${quoted(name)} (${columns.joinToString()})") }
                val marks = List(md.columnCount) { "?" }.joinToString()
                target.prepareStatement("INSERT INTO ${quoted(name)} VALUES ($marks)").use { insert ->
                    while (rows.next()) {
                        for (i in 1..md.columnCount) {
                            val value =
                                when (md.getColumnType(i)) {
                                    Types.CHAR, Types.NCHAR -> rows.getString(i)?.trimEnd(' ')
                                    Types.DATE -> rows.getObject(i, java.time.LocalDate::class.java)
                                    Types.TIMESTAMP -> rows.getObject(i, LocalDateTime::class.java)
                                    else -> rows.getObject(i)
                                }
                            insert.setObject(i, value)
                        }
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
            }
        }
    }
}
