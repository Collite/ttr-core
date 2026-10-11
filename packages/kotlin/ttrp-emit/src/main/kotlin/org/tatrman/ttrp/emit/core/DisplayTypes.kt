// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.core

import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.resolve.ColumnType
import org.tatrman.ttrp.resolve.ColumnType.Kind
import org.tatrman.ttrp.resolve.DisplaySchemaColumn

/**
 * The one type each engine writes an action-display column in (grammar 0.14 `def schema`). Every source of one
 * display — several OUT ports, several islands, any engine — casts every projected column, carried or NULL-filled,
 * to the type derived from the schema column, so the host can concatenate the per-source files. (A carried column
 * used to keep its source type and a NULL-filled one took a per-engine default: Polars filled a decimal as
 * `Float64` while a carried one stayed `Decimal`, and the files could not be concatenated.)
 *
 * | schema column | sql-text cast | Postgres | Polars | Arrow file (Polars and Postgres) |
 * |---|---|---|---|---|
 * | text (any length) | `varchar:max` | `TEXT` | `pl.String` | `large_string` |
 * | int / bigint / smallint / tinyint | `int` / `bigint` / `smallint` / `tinyint` | `BIGINT` | `pl.Int64` | `int64` |
 * | decimal(p,s) (unsized: 19,2) | `decimal:p,s` | `NUMERIC(p,s)` | `pl.Decimal(p, s)` | `decimal128(p, s)` |
 * | float / real | `float` / `real` | `DOUBLE PRECISION` | `pl.Float64` | `double` |
 * | bool | `bit` | `BOOLEAN` | `pl.Boolean` | `bool` |
 * | date | `date` | `DATE` | `pl.Date` | `date32[day]` |
 * | time | `time` | `TIME` | `pl.Time` | `time64[ns]` |
 * | datetime / timestamp | `datetime` / `datetime2` | `TIMESTAMP` | `pl.Datetime("us", "UTC")` | `timestamp[us, tz=UTC]` |
 *
 * A text length is the schema's (it is reported in the manifest) but not cast to: a length-bounded cast truncates
 * silently in SQL and has no Polars counterpart. Postgres returns `NUMERIC` as an opaque Arrow extension and text
 * as `string`, so the Postgres island casts the fetched Arrow table to [arrow] before writing it.
 */
object DisplayTypes {
    /** The column's type, or an emit error for one no engine can produce (the checker reports TTRP-DSP-005). */
    fun of(column: DisplaySchemaColumn): ColumnType =
        column.columnType
            ?: throw TtrpEmitException(
                EmitDiagnosticId.UNSUPPORTED_NODE,
                detail =
                    "action display column `${column.name}` is `${column.spelling}`, not a scalar column type " +
                        "(TTRP-DSP-005)",
            )

    /** The `sql-text` cast code (the translator's physical type code). */
    fun sqlText(t: ColumnType): String =
        when (t.kind) {
            Kind.TEXT -> "varchar:max"
            Kind.INT -> "int"
            Kind.BIGINT -> "bigint"
            Kind.SMALLINT -> "smallint"
            Kind.TINYINT -> "tinyint"
            Kind.DECIMAL -> "decimal:${t.precision},${t.scale}"
            Kind.FLOAT -> "float"
            Kind.REAL -> "real"
            Kind.BOOL -> "bit"
            Kind.DATE -> "date"
            Kind.TIME -> "time"
            Kind.DATETIME -> "datetime"
            Kind.TIMESTAMP -> "datetime2"
        }

    /** The Postgres SQL type. */
    fun postgres(t: ColumnType): String =
        when (t.kind) {
            Kind.TEXT -> "TEXT"
            Kind.INT, Kind.BIGINT, Kind.SMALLINT, Kind.TINYINT -> "BIGINT"
            Kind.DECIMAL -> "NUMERIC(${t.precision}, ${t.scale})"
            Kind.FLOAT, Kind.REAL -> "DOUBLE PRECISION"
            Kind.BOOL -> "BOOLEAN"
            Kind.DATE -> "DATE"
            Kind.TIME -> "TIME"
            Kind.DATETIME, Kind.TIMESTAMP -> "TIMESTAMP"
        }

    /** The Polars dtype. */
    fun polars(t: ColumnType): String =
        when (t.kind) {
            Kind.TEXT -> "pl.String"
            Kind.INT, Kind.BIGINT, Kind.SMALLINT, Kind.TINYINT -> "pl.Int64"
            Kind.DECIMAL -> "pl.Decimal(${t.precision}, ${t.scale})"
            Kind.FLOAT, Kind.REAL -> "pl.Float64"
            Kind.BOOL -> "pl.Boolean"
            Kind.DATE -> "pl.Date"
            Kind.TIME -> "pl.Time"
            Kind.DATETIME, Kind.TIMESTAMP -> "pl.Datetime(\"us\", \"UTC\")"
        }

    /** The pyarrow type the Arrow file carries — what Polars writes (`CompatLevel.oldest()`) for [polars]. */
    fun arrow(t: ColumnType): String =
        when (t.kind) {
            Kind.TEXT -> "_pa.large_string()"
            Kind.INT, Kind.BIGINT, Kind.SMALLINT, Kind.TINYINT -> "_pa.int64()"
            Kind.DECIMAL -> "_pa.decimal128(${t.precision}, ${t.scale})"
            Kind.FLOAT, Kind.REAL -> "_pa.float64()"
            Kind.BOOL -> "_pa.bool_()"
            Kind.DATE -> "_pa.date32()"
            Kind.TIME -> "_pa.time64(\"ns\")"
            Kind.DATETIME, Kind.TIMESTAMP -> "_pa.timestamp(\"us\", tz=\"UTC\")"
        }
}
