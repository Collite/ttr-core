// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.sql.SqlBasicTypeNameSpec
import org.apache.calcite.sql.SqlTypeNameSpec
import org.apache.calcite.sql.SqlUtil
import org.apache.calcite.sql.SqlWriter
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.util.Static.RESOURCE
import java.util.Locale

/**
 * TF-P3.S1 (G C9; contracts §4.1) — the T-SQL type names the stock Calcite parser does not know, as
 * the `TsqlDataType()` production (parserImpls.ftl, `dataTypeParserMethods` hook) hands them over.
 *
 * Each name maps to the Calcite type the validator works with; the T-SQL spelling survives only as
 * the type spec's unparse text (until validation), so after `SqlToRel` a `CAST(… AS nvarchar(30))` is
 * a `VARCHAR(30)` cast and the MSSQL dialect re-spells it from that type (contracts §3.3, ⚑TF-6).
 *
 * `BIT`, `DATETIME` and `NCHAR` are Calcite keyword tokens; the other names are plain identifiers
 * matched by image, so none of them becomes a reserved word (`SELECT x AS money` still parses).
 */
object TsqlDataTypes {
    /** The identifier-token names; the keyword-token ones are matched by the grammar directly. */
    private val IDENTIFIER_NAMES =
        setOf("NVARCHAR", "NTEXT", "TEXT", "MONEY", "SMALLMONEY", "DATETIME2", "SMALLDATETIME", "UNIQUEIDENTIFIER")

    /** `varchar(max)` — the type system's VARCHAR ceiling, which the wire spells `varchar:max`. */
    private val VARCHAR_MAX: Int = RelDataTypeSystem.DEFAULT.getMaxPrecision(SqlTypeName.VARCHAR)

    /** T-SQL `nvarchar` / `varchar` without a length in `CAST`/`CONVERT` is 30 characters. */
    private const val CAST_DEFAULT_LENGTH = 30

    /** T-SQL `datetime2` without a precision is `datetime2(7)`. */
    private const val DATETIME2_DEFAULT_PRECISION = 7

    @JvmStatic
    fun isIdentifierTypeName(image: String): Boolean = image.uppercase(Locale.ROOT) in IDENTIFIER_NAMES

    /**
     * The type spec for [name] (upper-cased) with the optional `(precision [, scale])` or `(MAX)` the
     * parser read (`-1` = absent). Arguments the type does not take are a parse error.
     */
    @JvmStatic
    fun spec(
        name: String,
        precision: Int,
        scale: Int,
        max: Boolean,
        pos: SqlParserPos,
    ): SqlTypeNameSpec {
        val hasArgs = precision >= 0 || max
        val written =
            when {
                max -> "$name(MAX)"
                scale >= 0 -> "$name($precision, $scale)"
                precision >= 0 -> "$name($precision)"
                else -> name
            }

        fun reject(): Nothing = throw SqlUtil.newContextException(pos, RESOURCE.unknownDatatypeName(written))

        fun plain(
            type: SqlTypeName,
            p: Int = RelDataType.PRECISION_NOT_SPECIFIED,
            s: Int = RelDataType.SCALE_NOT_SPECIFIED,
        ): SqlTypeNameSpec {
            if (hasArgs) reject()
            return TsqlTypeNameSpec(written, type, p, s, pos)
        }

        fun sized(
            type: SqlTypeName,
            default: Int,
        ): SqlTypeNameSpec {
            val p = if (precision >= 0) precision else default
            return TsqlTypeNameSpec(written, type, p, RelDataType.SCALE_NOT_SPECIFIED, pos)
        }
        if (scale >= 0) reject()
        return when (name) {
            "VARCHAR", "NVARCHAR" -> sized(SqlTypeName.VARCHAR, if (max) VARCHAR_MAX else CAST_DEFAULT_LENGTH)
            "NCHAR" -> if (max) reject() else sized(SqlTypeName.CHAR, 1)
            "DATETIME2" -> if (max) reject() else sized(SqlTypeName.TIMESTAMP, DATETIME2_DEFAULT_PRECISION)
            "TEXT", "NTEXT" -> plain(SqlTypeName.VARCHAR, VARCHAR_MAX)
            "MONEY" -> plain(SqlTypeName.DECIMAL, 19, 4)
            "SMALLMONEY" -> plain(SqlTypeName.DECIMAL, 10, 4)
            "DATETIME" -> plain(SqlTypeName.TIMESTAMP, 3)
            "SMALLDATETIME" -> plain(SqlTypeName.TIMESTAMP, 0)
            "BIT" -> plain(SqlTypeName.BOOLEAN)
            "UNIQUEIDENTIFIER" -> plain(SqlTypeName.CHAR, 36)
            else -> reject()
        }
    }
}

/**
 * A basic Calcite type that unparses as the T-SQL spelling it was written with (like Calcite's
 * `SqlAlienSystemTypeNameSpec`, which has no scale). Only the parsed SqlNode carries the spelling —
 * `deriveType` yields the plain Calcite type.
 */
internal class TsqlTypeNameSpec(
    private val written: String,
    typeName: SqlTypeName,
    precision: Int,
    scale: Int,
    pos: SqlParserPos,
) : SqlBasicTypeNameSpec(typeName, precision, scale, pos) {
    override fun unparse(
        writer: SqlWriter,
        leftPrec: Int,
        rightPrec: Int,
    ) {
        writer.keyword(written)
    }
}
