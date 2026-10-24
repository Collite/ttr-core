// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.LargeVarCharVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.types.pojo.ArrowType
import java.io.FileInputStream
import java.math.BigDecimal
import java.nio.file.Path

/** One column's type descriptor — name + a canonical Arrow type string + nullability. */
data class ConformColumn(
    val name: String,
    val type: String,
    val nullable: Boolean,
)

/**
 * A materialized Arrow table sufficient for conformance comparison: column descriptors + row-major
 * cells. Cell values are normalized to comparable JVM types (Long, Double, [BigDecimal], String,
 * Boolean, epoch-µs Long for `timestamp[us,UTC]`, null).
 */
data class ConformTable(
    val columns: List<ConformColumn>,
    val rows: List<List<Any?>>,
)

/** Reads Arrow IPC *file* format (what `polars.write_ipc` / pyarrow `new_file` produce). */
object ArrowIo {
    fun readTable(path: Path): ConformTable {
        RootAllocator().use { allocator ->
            FileInputStream(path.toFile()).use { fis ->
                ArrowFileReader(fis.channel, allocator).use { reader ->
                    val schema = reader.vectorSchemaRoot.schema
                    val columns =
                        schema.fields.map { f ->
                            ConformColumn(f.name, canonicalType(f.type), f.isNullable)
                        }
                    val rows = mutableListOf<List<Any?>>()
                    for (block in reader.recordBlocks) {
                        reader.loadRecordBatch(block)
                        val root: VectorSchemaRoot = reader.vectorSchemaRoot
                        for (r in 0 until root.rowCount) {
                            rows += root.fieldVectors.map { cell(it, r) }
                        }
                    }
                    return ConformTable(columns, rows)
                }
            }
        }
    }

    private fun cell(
        vector: org.apache.arrow.vector.FieldVector,
        row: Int,
    ): Any? {
        if (vector.isNull(row)) return null
        return when (vector) {
            is BigIntVector -> vector.get(row)
            is IntVector -> vector.get(row).toLong()
            is Float8Vector -> vector.get(row)
            is DecimalVector -> vector.getObject(row) as BigDecimal
            is BitVector -> vector.get(row) != 0
            is org.apache.arrow.vector.DateDayVector -> vector.get(row).toLong()
            is org.apache.arrow.vector.TimeStampMicroVector -> vector.get(row)
            is TimeStampMicroTZVector -> vector.get(row) // epoch micros
            is VarCharVector -> String(vector.get(row), Charsets.UTF_8)
            is LargeVarCharVector -> String(vector.get(row), Charsets.UTF_8)
            else -> vector.getObject(row)?.toString()
        }
    }

    private fun canonicalType(t: ArrowType): String =
        when (t) {
            is ArrowType.Int -> if (t.bitWidth >= 64) "int64" else "int${t.bitWidth}"
            is ArrowType.FloatingPoint -> "float64"
            // All UTF-8 string encodings are one logical type for conformance (Q9): Postgres/pyarrow
            // emit `utf8`, Polars emits `large_string` (oldest compat) / `string_view` (default).
            is ArrowType.Utf8 -> "utf8"
            is ArrowType.LargeUtf8 -> "utf8"
            is ArrowType.Bool -> "bool"
            is ArrowType.Decimal -> "decimal(${t.precision},${t.scale})"
            is ArrowType.Date -> "date32"
            is ArrowType.Timestamp -> "timestamp(${t.unit.name.lowercase()},${t.timezone ?: "none"})"
            else -> t.toString()
        }
}
