// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.assertions.fail
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.params.SqlParam
import org.tatrman.ttr.metadata.model.Model

/**
 * CN-P3 S3.4 T7 — an er rendering on its way to an engine: translated over the world's [ErModelHandle] (the production
 * handle — er tier, db tier, `er2db` mappings) to the db tier, then unparsed to a dialect with its placeholders bound —
 * what the PG / MSSQL spec and the live suite run.
 */
internal object ErSql {
    /** What the live suite binds (any value of the right type for a translation; these for the seeds' rows). */
    val values: Map<String, Pair<String, Any>> =
        mapOf(
            "as_of" to ("datetime" to "2026-03-15T00:00:00"),
            "window_lo" to ("datetime" to "2025-03-15T00:00:00"),
            "as_of_hi" to ("datetime" to "2026-03-16T00:00:00"),
            "tolerance" to ("float" to 0.01),
        )

    private val CURLY = Regex("\\{(\\w+)}")
    private val COLON = Regex("(?<![\\w:]):(\\w+)")

    /** The placeholders [sql] reads (a COLON one read as its CURLY twin). */
    private fun curly(sql: String): String = sql.trim().replace(COLON) { "{${it.groupValues[1]}}" }

    private fun names(sql: String): List<String> =
        CURLY
            .findAll(curly(sql))
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** [sql] (over entities) translated to the db tier of [model]. */
    fun toDb(
        model: Model,
        sql: String,
    ): PlanNode {
        val text = curly(sql)
        val params =
            names(sql).map { n ->
                val (type, v) = values[n] ?: fail("the live suite binds no value for `{$n}`")
                SqlParam(n, type, if (type == "datetime") (v as String).replace('T', ' ') else v)
            }
        val result =
            Translator(ErModelHandle(model)).parseToRelNode(
                text,
                Language.SQL,
                targetSchema = SchemaCode.DB,
                sourceSchema = SchemaCode.ER,
                parameters = params,
                strictLane = params.isEmpty(),
            )
        if (result is ParseResult.Failure) {
            fail(
                "does not translate to the db tier (${result.code}): ${result.message}\n$text",
            )
        }
        return (result as ParseResult.Success).plan
    }

    /** [sql] translated and unparsed to [dialect]: the text and its `?` values in order. */
    fun unparse(
        model: Model,
        sql: String,
        dialect: SqlDialect,
    ): Pair<String, List<Any?>> {
        val bindings =
            names(sql).map { n ->
                val (type, v) = values.getValue(n)
                val value =
                    when (type) {
                        "datetime" -> Value.newBuilder().setDatetimeValue(v as String)
                        "float" -> Value.newBuilder().setFloatValue(v as Double)
                        else -> Value.newBuilder().setStringValue(v.toString())
                    }
                ParameterBinding
                    .newBuilder()
                    .setName(n)
                    .setType(type)
                    .setValue(value)
                    .build()
            }
        val out =
            Translator(
                ErModelHandle(model),
            ).unparseFromRelNode(toDb(model, sql), Language.SQL, dialect, parameters = bindings)
        if (out is UnparseResult.Failure) fail("does not unparse to $dialect (${out.code}): ${out.message}\n$sql")
        out as UnparseResult.Success
        return out.output to out.parameters.map(::jdbc)
    }

    private fun jdbc(b: ParameterBinding): Any? {
        val v = b.value
        return when {
            v.isNull -> null
            v.hasDatetimeValue() -> java.time.LocalDateTime.parse(v.datetimeValue.replace(' ', 'T'))
            v.hasFloatValue() -> java.math.BigDecimal.valueOf(v.floatValue)
            v.hasIntValue() -> v.intValue
            v.hasBoolValue() -> v.boolValue
            else -> v.stringValue
        }
    }
}
