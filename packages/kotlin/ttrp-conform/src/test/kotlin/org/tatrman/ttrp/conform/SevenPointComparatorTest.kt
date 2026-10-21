// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.conform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class SevenPointComparatorTest :
    FunSpec({
        fun col(
            n: String,
            t: String,
            nullable: Boolean = true,
        ) = ConformColumn(n, t, nullable)

        fun table(
            cols: List<ConformColumn>,
            vararg rows: List<Any?>,
        ) = ConformTable(cols, rows.toList())

        val strInt = listOf(col("region", "utf8"), col("total", "int64"))

        fun point(
            report: ConformReport,
            p: Int,
        ) = report.results.first { it.point == p }

        test("Q9-1 schema fingerprint: equal schemas pass, differing schemas fail") {
            val a = table(strInt, listOf("x", 1L))
            val b = table(strInt, listOf("x", 1L))
            point(SevenPointComparator().compare(a, b), 1).pass shouldBe true
            val c = table(listOf(col("region", "utf8"), col("total", "float64")), listOf("x", 1.0))
            point(SevenPointComparator().compare(a, c), 1).pass shouldBe false
        }

        test("Q9-2 row multiset: order-insensitive by default; row diff fails") {
            val a = table(strInt, listOf("a", 1L), listOf("b", 2L))
            val permuted = table(strInt, listOf("b", 2L), listOf("a", 1L))
            point(SevenPointComparator().compare(a, permuted), 2).pass shouldBe true
            val diff = table(strInt, listOf("a", 1L), listOf("b", 3L))
            point(SevenPointComparator().compare(a, diff), 2).pass shouldBe false
        }

        test("Q9-2 terminal-Sort: order-sensitive on the sort prefix") {
            val cmp = SevenPointComparator(terminalSort = true, sortColumns = listOf("total"))
            val a = table(strInt, listOf("a", 1L), listOf("b", 2L))
            val sameOrder = table(strInt, listOf("a", 1L), listOf("b", 2L))
            point(cmp.compare(a, sameOrder), 2).pass shouldBe true
            val diffOrder = table(strInt, listOf("b", 2L), listOf("a", 1L))
            point(cmp.compare(a, diffOrder), 2).pass shouldBe false
        }

        test("Q9-3 NULLS LAST: nulls at the end pass; a null before a value fails") {
            val cmp = SevenPointComparator(terminalSort = true, sortColumns = listOf("total"))
            val ok = table(strInt, listOf("a", 1L), listOf("z", null))
            point(cmp.compare(ok, ok), 3).pass shouldBe true
            val bad = table(strInt, listOf("z", null), listOf("a", 1L))
            point(cmp.compare(bad, bad), 3).pass shouldBe false
        }

        test("a tolerance never lets decimal meet float64; only a declared engine numeric delta does (review-119 m9)") {
            val dec = table(listOf(col("share", "decimal(38,20)")), listOf(BigDecimal("0.68965517241379310345")))
            val dbl = table(listOf(col("share", "float64")), listOf(0.6896551724137931))
            SevenPointComparator(tolerances = mapOf("share" to 1e-12)).compare(dec, dbl).pass shouldBe false
            SevenPointComparator(numericDeltas = mapOf("share" to 1e-12)).compare(dec, dbl).pass shouldBe true
            // the delta bounds the difference: 0.6896 is 5.5e-5 away
            val far = table(listOf(col("share", "float64")), listOf(0.6896))
            SevenPointComparator(numericDeltas = mapOf("share" to 1e-12)).compare(dec, far).pass shouldBe false
            // and it names its column: another decimal ⇄ float64 column still fails Q9-1
            val other = table(listOf(col("ratio", "decimal(38,20)")), listOf(BigDecimal("0.5")))
            val otherDbl = table(listOf(col("ratio", "float64")), listOf(0.5))
            SevenPointComparator(numericDeltas = mapOf("share" to 1e-12)).compare(other, otherDbl).pass shouldBe false
        }

        test("Q9-4 numerics: decimals compare exactly; a scale-diff-but-equal decimal still matches") {
            val cols = listOf(col("amount", "decimal(19,2)"))
            val a = table(cols, listOf(BigDecimal("10.50")))
            val b = table(cols, listOf(BigDecimal("10.5")))
            point(SevenPointComparator().compare(a, b), 2).pass shouldBe true
            point(SevenPointComparator().compare(a, b), 4).pass shouldBe true
        }

        test("Q9-4 float64: no declared tolerance ⇒ exact (a delta fails point 2)") {
            val cols = listOf(col("x", "float64"))
            val a = table(cols, listOf(1.0))
            val b = table(cols, listOf(1.0001))
            point(SevenPointComparator().compare(a, b), 2).pass shouldBe false
            // With a declared tolerance the same delta passes.
            val tol = SevenPointComparator(tolerances = mapOf("x" to 0.001))
            point(tol.compare(a, b), 2).pass shouldBe true
        }

        test("Q9-5 datetime: µs/UTC passes; a non-UTC timestamp fails") {
            val ok = table(listOf(col("ts", "timestamp(microsecond,UTC)")), listOf(0L))
            point(SevenPointComparator().compare(ok, ok), 5).pass shouldBe true
            val bad = table(listOf(col("ts", "timestamp(millisecond,none)")), listOf(0L))
            point(SevenPointComparator().compare(bad, bad), 5).pass shouldBe false
        }

        test("Q9-6 & Q9-7: collation is codepoint and delivery is Arrow-only (structural)") {
            val a = table(strInt, listOf("a", 1L))
            val r = SevenPointComparator().compare(a, a)
            point(r, 6).pass shouldBe true
            point(r, 7).pass shouldBe true
        }

        // ---- RJ-P5 5.1.3: a rejects stream is a first-class compare target — the `_ttrp_*` columns
        //      go through the seven points exactly like any other schema. ----

        val rejectSchema =
            listOf(
                col("customer", "utf8"),
                col("region", "utf8"),
                col("amount", "float64"),
                col("_ttrp_reject_code", "utf8"),
                col("_ttrp_reject_expr", "utf8"),
            )

        test("rejects streams with identical rows pass the schema + multiset points") {
            val a = table(rejectSchema, listOf("abc", "N", 1.0, "TTRP-RJ-001", "returned_qty"))
            val b = table(rejectSchema, listOf("abc", "N", 1.0, "TTRP-RJ-001", "returned_qty"))
            val r = SevenPointComparator().compare(a, b)
            point(r, 1).pass shouldBe true // schema fingerprint incl. _ttrp_* columns
            point(r, 2).pass shouldBe true
        }

        test("a reject row differing only in _ttrp_reject_code fails the multiset point") {
            val a = table(rejectSchema, listOf("abc", "N", 1.0, "TTRP-RJ-001", "returned_qty"))
            val b = table(rejectSchema, listOf("abc", "N", 1.0, "TTRP-RJ-007", "returned_qty"))
            point(SevenPointComparator().compare(a, b), 2).pass shouldBe false
        }
    })
