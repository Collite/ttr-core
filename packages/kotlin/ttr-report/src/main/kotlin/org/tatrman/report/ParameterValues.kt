// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report

import java.time.Instant
import java.util.Locale

/** Source spelling classification shared by generation and the CLI's compiler adapter. */
object ReportTypes {
    private fun normalized(type: String): String = type.substringBefore('(').trim().lowercase(Locale.ROOT)

    fun isDate(type: String): Boolean = normalized(type) == "date"

    fun kind(type: String): ParamKind? =
        when (normalized(type)) {
            "text", "string", "varchar", "nvarchar", "char", "nchar", "clob", "uuid", "time" -> ParamKind.TEXT
            "int", "integer", "bigint", "smallint", "tinyint", "long" -> ParamKind.INT
            "float", "double", "real", "decimal", "numeric", "number", "money" -> ParamKind.FLOAT
            "bool", "boolean", "bit" -> ParamKind.BOOL
            "date", "datetime", "datetime2", "timestamp", "timestamptz", "smalldatetime" ->
                ParamKind.DATETIME
            else -> null
        }
}

internal object ParameterValues {
    fun parse(
        kind: ParamKind,
        text: String,
        name: String,
    ): Any =
        when (kind) {
            ParamKind.TEXT, ParamKind.ENTITY -> text
            ParamKind.INT -> text.toLongOrNull() ?: invalid(name, "must be an integer")
            ParamKind.FLOAT ->
                text.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: invalid(name, "must be a finite float")
            ParamKind.BOOL -> text.toBooleanStrictOrNull() ?: invalid(name, "must be true or false")
            ParamKind.DATETIME ->
                runCatching { Instant.parse(text) }.getOrNull()
                    ?: invalid(name, "must be an ISO instant with an offset")
        }

    fun validateDefault(param: ParamSpec) {
        val value = param.default ?: return
        val compatible =
            when (param.kind) {
                ParamKind.TEXT, ParamKind.ENTITY, ParamKind.DATETIME -> value is String
                ParamKind.INT ->
                    value is Number &&
                        value !is Float &&
                        value !is Double &&
                        value !is java.math.BigDecimal
                ParamKind.FLOAT -> value is Number
                ParamKind.BOOL -> value is Boolean
            }
        if (!compatible) invalid(param.name, "default must match kind '${param.kind.wire}'")
        parse(param.kind, value.toString(), param.name)
    }

    private fun invalid(
        name: String,
        reason: String,
    ): Nothing = throw ReportException("parameter-invalid", "Parameter '$name' $reason")
}
