// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import org.tatrman.ttrp.expr.TtrpType

/**
 * A scalar column type normalized from a TTR-M / TTR-P type spelling — the one vocabulary every engine maps
 * to its own type when it must *produce* a declared column: an action display's row-schema columns (grammar
 * 0.14 `def schema`, cast to on every emit path so all sources of one display share one schema) and a Polars
 * CSV load's declared decimal precision.
 *
 * Accepted spellings (case-insensitive): the TTR-M type keywords — `text string char varchar int integer
 * float double number decimal bool boolean date datetime timestamp` — and the SQL spellings a host-generated
 * model carries as a custom type id — `nchar nvarchar ntext bigint long smallint tinyint numeric money
 * smallmoney real bit time datetime2 smalldatetime`. `object`, `list` and any other id are not scalar column
 * types ([of] returns null; an action display reports `TTRP-DSP-005`).
 *
 * Length / precision come in three written forms, all understood by [parse]: the TTR-M structured type
 * `{ type: decimal, length: 19, precision: 2 }` (for a decimal `length` is the precision and `precision` the
 * scale — `DECIMAL(19,2)`; for text `length` is the length), the TTR-P spelling `decimal(19,2)` /
 * `varchar(40)`, or a bare name. A decimal without a declared precision is
 * `DECIMAL(`[DEFAULT_DECIMAL_PRECISION]`, `[DEFAULT_DECIMAL_SCALE]`)` — the TTR-M manual's own example and the
 * type the Postgres CSV ingest has always read a bare `decimal` as.
 */
data class ColumnType(
    val kind: Kind,
    /** Text length (`varchar(40)`); null = unbounded. */
    val length: Int? = null,
    /** Decimal precision (total digits); set for every [Kind.DECIMAL]. */
    val precision: Int? = null,
    /** Decimal scale; set for every [Kind.DECIMAL]. */
    val scale: Int? = null,
) {
    enum class Kind {
        TEXT,
        INT,
        BIGINT,
        SMALLINT,
        TINYINT,
        DECIMAL,
        FLOAT,
        REAL,
        BOOL,
        DATE,
        TIME,
        DATETIME,
        TIMESTAMP,
    }

    /** True for the integer kinds (`int`, `bigint`, `smallint`, `tinyint`). */
    val isInteger: Boolean get() =
        kind == Kind.INT ||
            kind == Kind.BIGINT ||
            kind == Kind.SMALLINT ||
            kind == Kind.TINYINT

    /** True for the timestamp kinds (`datetime`, `timestamp`). */
    val isTimestamp: Boolean get() = kind == Kind.DATETIME || kind == Kind.TIMESTAMP

    /**
     * May a value of [source] be written into a column of this type? Text takes any scalar (the host renders
     * action fields as text); an integer kind takes any integer; a decimal takes an integer or a decimal (its
     * precision is the target's); a float takes an integer or a float; a timestamp takes either timestamp kind;
     * every other kind takes only itself.
     */
    fun accepts(source: ColumnType): Boolean =
        when {
            kind == Kind.TEXT -> true
            isInteger -> source.isInteger
            kind == Kind.DECIMAL -> source.isInteger || source.kind == Kind.DECIMAL
            kind == Kind.FLOAT || kind == Kind.REAL ->
                source.isInteger || source.kind == Kind.FLOAT || source.kind == Kind.REAL
            isTimestamp -> source.isTimestamp
            else -> source.kind == kind
        }

    companion object {
        /** Precision of a decimal written without one (`decimal`, `numeric`, `number`). */
        const val DEFAULT_DECIMAL_PRECISION = 19

        /** Scale of a decimal written without one. */
        const val DEFAULT_DECIMAL_SCALE = 2

        /** The widest decimal every engine holds (Polars / Arrow `decimal128`, T-SQL). */
        const val MAX_DECIMAL_PRECISION = 38

        /**
         * The type a TTR-M column declares: its type [name] plus the structured form's [length] / [precision]
         * (for a decimal: precision and scale). Null when [name] is not a scalar column type, or the decimal
         * is not a valid `DECIMAL(p,s)` (`1 ≤ p ≤ 38`, `0 ≤ s ≤ p`).
         */
        fun of(
            name: String,
            length: Int? = null,
            precision: Int? = null,
        ): ColumnType? =
            when (name.trim().lowercase()) {
                "text", "string", "char", "varchar", "nchar", "nvarchar", "ntext" ->
                    ColumnType(
                        Kind.TEXT,
                        length = length,
                    )
                "int", "integer" -> ColumnType(Kind.INT)
                "bigint", "long" -> ColumnType(Kind.BIGINT)
                "smallint" -> ColumnType(Kind.SMALLINT)
                "tinyint" -> ColumnType(Kind.TINYINT)
                "decimal", "numeric", "number" -> decimal(length, precision)
                "money" -> decimal(19, 4)
                "smallmoney" -> decimal(10, 4)
                "float", "double" -> ColumnType(Kind.FLOAT)
                "real" -> ColumnType(Kind.REAL)
                "bool", "boolean", "bit" -> ColumnType(Kind.BOOL)
                "date" -> ColumnType(Kind.DATE)
                "time" -> ColumnType(Kind.TIME)
                "datetime", "smalldatetime" -> ColumnType(Kind.DATETIME)
                "timestamp", "datetime2" -> ColumnType(Kind.TIMESTAMP)
                else -> null
            }

        /**
         * Parses a written type: a bare name (`decimal`), the TTR-P form (`decimal(19,2)`, `varchar(40)`), or the
         * TTR-M structured form's source text (`{ type: decimal, length: 19, precision: 2 }`, as a world schema
         * field carries it — whitespace optional, `:` or `=`). Null when it is not a scalar column type.
         */
        fun parse(spelling: String): ColumnType? {
            val s = spelling.trim()
            if (s.startsWith("{") && s.endsWith("}")) {
                val props =
                    s
                        .removePrefix("{")
                        .removeSuffix("}")
                        .split(',')
                        .mapNotNull { p ->
                            val kv = p.split(':', '=', limit = 2)
                            if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
                        }.toMap()
                val name = props["type"] ?: return null
                return of(name, props["length"]?.toIntOrNull(), props["precision"]?.toIntOrNull())
            }
            val name = s.substringBefore('(')
            val args =
                if ('(' in s) {
                    s
                        .substringAfter('(')
                        .substringBefore(')')
                        .split(',')
                        .map { it.trim().toIntOrNull() }
                } else {
                    emptyList()
                }
            return of(name, args.getOrNull(0), args.getOrNull(1))
        }

        /**
         * The column type of a TTR-P expression type: null for a non-scalar (`object`, `list`) or an unknown /
         * custom type the spelling vocabulary does not cover (a `Named` that does not [parse]).
         */
        fun of(t: TtrpType): ColumnType? =
            when (t) {
                TtrpType.Integer -> ColumnType(Kind.INT)
                TtrpType.Float, TtrpType.Double -> ColumnType(Kind.FLOAT)
                TtrpType.Number -> decimal(null, null)
                is TtrpType.Decimal -> decimal(t.precision, t.scale)
                TtrpType.Bool -> ColumnType(Kind.BOOL)
                TtrpType.Str -> ColumnType(Kind.TEXT)
                TtrpType.Date -> ColumnType(Kind.DATE)
                TtrpType.Datetime -> ColumnType(Kind.DATETIME)
                TtrpType.Timestamp -> ColumnType(Kind.TIMESTAMP)
                TtrpType.Obj, TtrpType.Lst -> null
                is TtrpType.Named -> parse(t.name)
            }

        private fun decimal(
            precision: Int?,
            scale: Int?,
        ): ColumnType? {
            val p = precision ?: DEFAULT_DECIMAL_PRECISION
            val s = scale ?: if (precision == null) DEFAULT_DECIMAL_SCALE else 0
            if (p < 1 || p > MAX_DECIMAL_PRECISION || s < 0 || s > p) return null
            return ColumnType(Kind.DECIMAL, precision = p, scale = s)
        }
    }
}
