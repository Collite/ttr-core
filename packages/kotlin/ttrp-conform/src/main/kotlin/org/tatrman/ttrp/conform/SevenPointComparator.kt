// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import java.math.BigDecimal
import java.security.MessageDigest

/** One Q9 point's verdict. */
data class PointResult(
    val point: Int,
    val name: String,
    val pass: Boolean,
    val detail: String,
)

/** The full seven-point verdict for one comparison. */
data class ConformReport(
    val results: List<PointResult>,
) {
    val pass: Boolean get() = results.all { it.pass }

    fun summary(): String =
        results.joinToString("\n") { "[${if (it.pass) "PASS" else "FAIL"}] Q9-${it.point} ${it.name}: ${it.detail}" }
}

/**
 * The Q9 seven-point comparator (E-e). Runs offline over two [ConformTable]s (the `out/` displays
 * or staged boundaries of two placement variants). Verdicts:
 *  1. schema fingerprint · 2. row multiset (canonical-sort; order-sensitive on the sort prefix
 *  under a terminal Sort) · 3. NULLS LAST · 4. numerics (decimal exact / declared float tolerance,
 *  no silent epsilon) · 5. UTC-µs datetimes · 6. binary UTF-8 collation · 7. Arrow-IPC delivery.
 *
 * @param terminalSort true when the island's terminal node is Sort (parameter, never sniffed).
 * @param sortColumns the sort-key columns (prefix that must match order-sensitively under Sort).
 * @param tolerances per-column float64 absolute tolerance; a column absent here compares exactly.
 * @param numericDeltas the declared engine numeric deltas (AU-core-P2 · S2.4; review-119 m9): a column here may be
 *   `decimal` on one side and `float64` on the other — DuckDB divides decimals in DOUBLE, Postgres in NUMERIC — its
 *   fingerprint reads `number` and its cells compare within the given tolerance; point 4 reports each. Separate from
 *   [tolerances], which never waive a type.
 */
class SevenPointComparator(
    private val terminalSort: Boolean = false,
    private val sortColumns: List<String> = emptyList(),
    private val tolerances: Map<String, Double> = emptyMap(),
    private val numericDeltas: Map<String, Double> = emptyMap(),
) {
    fun compare(
        left: ConformTable,
        right: ConformTable,
        stagedFingerprint: String? = null,
    ): ConformReport =
        ConformReport(
            listOf(
                schemaFingerprint(left, right, stagedFingerprint),
                rowMultiset(left, right),
                nullsLast(left, right),
                numerics(left, right),
                datetime(left, right),
                collation(),
                delivery(),
            ),
        )

    private fun schemaFingerprint(
        l: ConformTable,
        r: ConformTable,
        staged: String?,
    ): PointResult {
        val lf = fingerprint(l)
        val rf = fingerprint(r)
        val eq = lf == rf && (staged == null || staged == lf)
        return PointResult(1, "schema-fingerprint", eq, if (eq) lf else "left=$lf right=$rf staged=$staged")
    }

    private fun rowMultiset(
        l: ConformTable,
        r: ConformTable,
    ): PointResult {
        if (l.columns.map { it.name } != r.columns.map { it.name }) {
            return PointResult(2, "row-multiset", false, "column names differ")
        }
        if (l.rows.size != r.rows.size) {
            return PointResult(2, "row-multiset", false, "row counts differ (${l.rows.size} vs ${r.rows.size})")
        }
        val ls = l.rows.sortedWith(rowComparator(l))
        val rs = r.rows.sortedWith(rowComparator(r))
        val cols = l.columns.map { it.name }
        for (i in ls.indices) {
            for (c in cols.indices) {
                if (!cellEquals(cols[c], ls[i][c], rs[i][c])) {
                    return PointResult(2, "row-multiset", false, "row $i col ${cols[c]}: ${ls[i][c]} != ${rs[i][c]}")
                }
            }
        }
        // Order-sensitive prefix under a terminal Sort: the engine-native ordering must agree.
        if (terminalSort && sortColumns.isNotEmpty()) {
            val idx = sortColumns.map { cols.indexOf(it) }.filter { it >= 0 }
            for (i in l.rows.indices) {
                if (idx.any { !cellEquals(cols[it], l.rows[i][it], r.rows[i][it]) }) {
                    return PointResult(2, "row-multiset", false, "terminal-Sort order differs at row $i")
                }
            }
        }
        return PointResult(2, "row-multiset", true, "${l.rows.size} rows match")
    }

    private fun nullsLast(
        l: ConformTable,
        r: ConformTable,
    ): PointResult {
        if (!terminalSort || sortColumns.isEmpty()) {
            return PointResult(3, "nulls-last", true, "n/a (no terminal Sort)")
        }
        for ((side, t) in listOf("left" to l, "right" to r)) {
            val col = t.columns.indexOfFirst { it.name == sortColumns.first() }
            if (col < 0) continue
            var seenNull = false
            for (row in t.rows) {
                if (row[col] == null) {
                    seenNull = true
                } else if (seenNull) {
                    return PointResult(3, "nulls-last", false, "$side: non-null after null in sort column")
                }
            }
        }
        return PointResult(3, "nulls-last", true, "nulls sort last on both sides")
    }

    private fun numerics(
        l: ConformTable,
        r: ConformTable,
    ): PointResult {
        val floatCols = l.columns.filter { it.type == "float64" }.map { it.name }
        val untoleranced = floatCols.filter { it !in tolerances }
        // Decimal columns are always exact (checked in row compare via BigDecimal.compareTo); this
        // point additionally records the no-silent-epsilon rule for float64.
        val detail =
            (
                if (untoleranced.isEmpty()) {
                    "decimals exact; float64 tolerances: $tolerances"
                } else {
                    "float64 exact (no declared tolerance): $untoleranced"
                }
            ) + if (numericDeltas.isEmpty()) "" else "; declared engine deltas (decimal ⇄ float64): $numericDeltas"
        return PointResult(4, "numerics", true, detail)
    }

    private fun datetime(
        l: ConformTable,
        r: ConformTable,
    ): PointResult {
        val offenders =
            (l.columns + r.columns).filter {
                it.type.startsWith(
                    "timestamp",
                ) &&
                    it.type != "timestamp(microsecond,UTC)"
            }
        val ok = offenders.isEmpty()
        return PointResult(
            5,
            "datetime-utc-us",
            ok,
            if (ok) "all timestamps are µs/UTC" else "non-µs/UTC: ${offenders.map { it.name }}",
        )
    }

    private fun collation() =
        PointResult(6, "collation", true, "binary UTF-8 codepoint (String.compareTo; no Collator)")

    private fun delivery() =
        PointResult(7, "delivery", true, "both sides read as Arrow IPC (ConformTable is Arrow-only)")

    // --- helpers ---

    private fun fingerprint(t: ConformTable): String {
        val canon = t.columns.joinToString(";") { "${it.name}:${fingerprintType(it)}:${it.nullable}" }
        val d = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8))
        return "sha256:" + d.joinToString("") { "%02x".format(it) }
    }

    private fun fingerprintType(c: ConformColumn): String =
        if (c.name in numericDeltas && (c.type == "float64" || c.type.startsWith("decimal"))) "number" else c.type

    private fun rowComparator(t: ConformTable): Comparator<List<Any?>> =
        Comparator { a, b ->
            for (c in t.columns.indices) {
                val cmp = compareCells(a[c], b[c])
                if (cmp != 0) return@Comparator cmp
            }
            0
        }

    /** Codepoint/natural order with nulls last (Q9-6 binary collation). */
    private fun compareCells(
        a: Any?,
        b: Any?,
    ): Int =
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            a is BigDecimal && b is BigDecimal -> a.compareTo(b)
            a is Long && b is Long -> a.compareTo(b)
            a is Double && b is Double -> a.compareTo(b)
            a is Boolean && b is Boolean -> a.compareTo(b)
            else -> a.toString().compareTo(b.toString())
        }

    private fun cellEquals(
        column: String,
        a: Any?,
        b: Any?,
    ): Boolean =
        when {
            a == null || b == null -> a == b
            a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
            a is Double && b is Double -> {
                val tol = tolerances[column]
                if (tol != null) kotlin.math.abs(a - b) <= tol else a == b
            }
            // a declared engine delta spans decimal ⇄ float64 (the engine's own division), never a silent one
            a is Number && b is Number && column in numericDeltas ->
                kotlin.math.abs(a.toDouble() - b.toDouble()) <= numericDeltas.getValue(column)
            else -> a == b
        }
}
