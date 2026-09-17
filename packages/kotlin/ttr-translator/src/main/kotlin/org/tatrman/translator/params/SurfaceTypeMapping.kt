// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.params

import org.apache.calcite.sql.type.SqlTypeFamily
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.translator.framework.SurfaceType

/**
 * Single source of truth for the surface parameter-type → Calcite [SqlTypeName] mapping, shared by
 * the two consumers that previously kept parallel `when` tables the comments admitted had to stay in
 * sync (N9): [ParameterBridge.calciteSqlType] (which renders a `CAST(... AS <keyword>)` string) and
 * [ParameterTyper.toRelDataType] (which builds a [org.apache.calcite.rel.type.RelDataType]).
 *
 * **Intentional numeric widening (M2).** `int`/`integer` both map to [SqlTypeName.BIGINT] and
 * `real` to [SqlTypeName.DOUBLE] — the narrower [SqlTypeName.INTEGER]/[SqlTypeName.REAL] are
 * deliberately *not* preserved. This is correct, not a shortcut, because the parameter **value**
 * never travels as a narrow type: the wire literal encoder collapses INTEGER+BIGINT into a single
 * `int_value` (long) and REAL+FLOAT+DOUBLE into a single `float_value` (double) — see
 * `org.tatrman.translator.wire.Expressions.encodeLiteral` — and the worker binds purely off that value
 * case (`Value.VCase.INT_VALUE -> setLong`, `FLOAT_VALUE -> setDouble`; see
 * `the MSSQL worker's ExecutePipeline.bindParameters`). The worker's surface→JDBC fallback (used only
 * for the NULL path) likewise knows only `"int"`/`"float"`, so emitting `"integer"`/`"real"` here
 * would make a NULL of those types bind as NVARCHAR. Widening keeps the typed-CAST fallback aligned
 * with the single wide value/bind path. Tradeoff: the CAST hint and any inferred result type are
 * 64-bit, so a value that would fit a 32-bit column is typed one notch wider than strictly needed —
 * no rounding or truncation occurs (the parameter is only ever a CAST target for validation, then
 * unwrapped by [ParameterTyper]).
 *
 * **The one deliberate divergence.** For an *unknown* surface type the two consumers cannot agree:
 * [ParameterTyper] needs a real [SqlTypeName] and uses [SqlTypeName.ANY] (Calcite's "infer it"
 * sentinel), but `ANY` is not a keyword the parser accepts inside `CAST(... AS ANY)`, so
 * [ParameterBridge] must fall back to a castable `VARCHAR`. This split is encoded as
 * [UNKNOWN_REL_TYPE] vs [UNKNOWN_SQL_KEYWORD] rather than a shared entry. Every *known* surface type
 * still resolves through the single [TABLE] below.
 */
object SurfaceTypeMapping {
    /** Canonical surface-type (lowercased) → [SqlTypeName]. See the widening note above. */
    val TABLE: Map<String, SqlTypeName> =
        mapOf(
            "text" to SqlTypeName.VARCHAR,
            "string" to SqlTypeName.VARCHAR,
            "varchar" to SqlTypeName.VARCHAR,
            "char" to SqlTypeName.VARCHAR,
            // int + integer widen to BIGINT; real widens to DOUBLE — intentional, see the M2 note.
            "int" to SqlTypeName.BIGINT,
            "integer" to SqlTypeName.BIGINT,
            "bigint" to SqlTypeName.BIGINT,
            "long" to SqlTypeName.BIGINT,
            "float" to SqlTypeName.DOUBLE,
            "double" to SqlTypeName.DOUBLE,
            "real" to SqlTypeName.DOUBLE,
            "decimal" to SqlTypeName.DECIMAL,
            "numeric" to SqlTypeName.DECIMAL,
            "bool" to SqlTypeName.BOOLEAN,
            "boolean" to SqlTypeName.BOOLEAN,
            "date" to SqlTypeName.DATE,
            "timestamp" to SqlTypeName.TIMESTAMP,
            "datetime" to SqlTypeName.TIMESTAMP,
        )

    /**
     * Fixed precision/scale for [SqlTypeName.DECIMAL] (M3). A bare DECIMAL defaults to scale 0,
     * which truncates the fractional part of a decimal parameter in the typed-CAST fallback;
     * `DECIMAL(38, 10)` keeps full precision with room for cents/rates.
     */
    const val DECIMAL_PRECISION: Int = 38

    /** Scale for [SqlTypeName.DECIMAL] — see [DECIMAL_PRECISION]. */
    const val DECIMAL_SCALE: Int = 10

    /** [ParameterTyper]'s fallback for an unknown surface type — Calcite infers it. */
    val UNKNOWN_REL_TYPE: SqlTypeName = SqlTypeName.ANY

    /**
     * [ParameterBridge]'s fallback keyword for an unknown surface type. `ANY` is not a castable
     * keyword, so the bridge uses `VARCHAR` (the safe default for the text-search contexts that
     * drive the typed-CAST path).
     */
    const val UNKNOWN_SQL_KEYWORD: String = "VARCHAR"

    /** Resolve [surfaceType] to a [SqlTypeName], or null when unknown (caller picks a fallback). */
    fun sqlTypeNameOrNull(surfaceType: String): SqlTypeName? = TABLE[surfaceType.lowercase()]

    /**
     * TF-P5 (contracts §3.1) — the operand family a model-declared function parameter of [surfaceType]
     * accepts: both numeric surfaces are [SqlTypeFamily.NUMERIC], so an `INT` parameter takes a decimal
     * column and a `FLOAT` one an integer literal, as SQL Server converts them implicitly.
     */
    fun familyOf(surfaceType: SurfaceType): SqlTypeFamily =
        when (surfaceType) {
            SurfaceType.INT, SurfaceType.FLOAT -> SqlTypeFamily.NUMERIC
            SurfaceType.TEXT -> SqlTypeFamily.CHARACTER
            SurfaceType.DATETIME -> SqlTypeFamily.DATETIME
            SurfaceType.BOOL -> SqlTypeFamily.BOOLEAN
        }
}
