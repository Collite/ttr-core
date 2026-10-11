// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Action displays (grammar 0.14 `def schema`): when a row schema named exactly `<name>` is in scope
 * (declared in a TTR-M package the program imports), `x -> display(<name>)` holds `x`'s columns to it —
 * a missing non-optional column is TTRP-DSP-001, a non-assignable type TTRP-DSP-002 (same type;
 * int → decimal/float; any scalar → text), an extra column TTRP-DSP-003 (warning: dropped from the
 * display). Several sources may feed one action display; a duplicated name that is NOT a declared
 * schema is TTRP-DSP-004. A display whose name is not a declared schema is unchanged.
 */
class DisplaySchemaCheckSpec :
    StringSpec({
        val root: Path = locate("display")
        val manifest =
            TtrpManifest(world = "shop.worlds.host", extraModelRoots = listOf("generated"), manifestDir = root)

        fun check(source: String) = TtrpChecker(manifest).check(source, "actions.ttrp")

        fun dsp(source: String): List<TtrpDiagnostic> =
            check(source).diagnostics.filter { it.id.id.startsWith("TTRP-DSP-") || it.id.id == "TTRP-RES-002" }

        val header =
            """
            uses world "shop.worlds.host"
            import shop.orders.*
            import shop.actions.*

            """.trimIndent() + "\n"

        // Both OUT ports carry the `notify` shape: recipient/subject/order_id (+ optional amount).
        fun review(
            late: String = "select(recipient, subject, order_id, amount)",
            large: String = "select(recipient, subject, order_id)",
        ) = header +
            """
            container review(out late, out large) target erp {
                o  = load(orders)
                c  = load(customer)
                oc = join(left: o, right: c, type: inner, on: relation order_customer)
                n  = oc -> calc { recipient = email  subject = full_name }
                late  = n -> filter(status = 1) -> $late
                large = n -> filter(amount > 1000) -> $large
            }
            """.trimIndent() + "\n"

        "rows matching the schema check clean — an absent optional column is fine" {
            val r = check(review() + "review.late -> display(notify)\n")
            r.errors.shouldBeEmpty()
            r.diagnostics.filter { it.id.id.startsWith("TTRP-DSP-") }.shouldBeEmpty()
        }

        "a missing non-optional column is TTRP-DSP-001 (error), naming the column and the schema" {
            val d = dsp(review(late = "select(recipient, order_id)") + "review.late -> display(notify)\n")
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-001")
            d.single().severity shouldBe Severity.ERROR
            d.single().message shouldContain "`subject`"
            d.single().message shouldContain "shop.actions.notify"
        }

        "every missing column is its own TTRP-DSP-001" {
            dsp(review(late = "select(order_id)") + "review.late -> display(notify)\n").map { it.id.id } shouldBe
                listOf("TTRP-DSP-001", "TTRP-DSP-001")
        }

        "a non-assignable column type is TTRP-DSP-002 (error)" {
            // `order_id` re-typed to a date: date → int is not assignable.
            val src =
                review(late = "calc { order_id = due_date } -> select(recipient, subject, order_id)") +
                    "review.late -> display(notify)\n"
            val d = dsp(src)
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-002")
            d.single().severity shouldBe Severity.ERROR
            d.single().message shouldContain "`order_id`"
        }

        "assignable: int → decimal, and any scalar (int, date, decimal) → text" {
            // amount ← order_id (int → decimal); recipient ← customer_id (int → text);
            // subject ← due_date (date → text); note ← amount (decimal → text).
            val src =
                review(
                    late =
                        "calc { note = amount } -> " +
                            "calc { amount = order_id  recipient = customer_id  subject = due_date }" +
                            " -> select(recipient, subject, order_id, amount, note)",
                ) + "review.late -> display(notify)\n"
            dsp(src).shouldBeEmpty()
        }

        "decimal → int is not assignable (TTRP-DSP-002)" {
            val src =
                review(late = "calc { order_id = amount } -> select(recipient, subject, order_id)") +
                    "review.late -> display(notify)\n"
            dsp(src).map { it.id.id } shouldContainExactly listOf("TTRP-DSP-002")
        }

        "a column the schema does not name is TTRP-DSP-003 (warning) — dropped, not an error" {
            val r =
                check(
                    review(late = "select(recipient, subject, order_id, status)") + "review.late -> display(notify)\n",
                )
            r.errors.shouldBeEmpty()
            val w = r.diagnostics.filter { it.id.id == "TTRP-DSP-003" }
            w shouldHaveSize 1
            w.single().severity shouldBe Severity.WARNING
            w.single().message shouldContain "`status`"
        }

        "several sources may feed one action display — each is checked on its own" {
            val src =
                review(large = "select(recipient, order_id)") +
                    "review.late  -> display(notify)\n" +
                    "review.large -> display(notify)\n"
            val d = dsp(src)
            // only the second source (large) misses `subject`; no duplicate-name error for a declared schema.
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-001")
            d.single().location.line shouldBe src.lines().indexOfFirst { it.startsWith("review.large") } + 1
        }

        "the report carries the resolved schema for every action display site, in wiring order" {
            val src = review() + "review.late  -> display(notify)\nreview.large -> display(notify)\n"
            val r = check(src)
            val sites = r.displaySchemas.entries.sortedBy { it.key.line }
            sites shouldHaveSize 2
            sites.forEach {
                it.value.qualifiedName shouldBe "shop.actions.notify"
                it.value.columns.map { c -> c.name } shouldContainExactly
                    listOf("recipient", "subject", "order_id", "amount", "note")
                it.value.columns.map { c -> c.optional } shouldContainExactly listOf(false, false, false, true, true)
            }
        }

        "a duplicated display name that is not a declared schema is TTRP-DSP-004 (error)" {
            val src = review() + "review.late  -> display(evidence)\nreview.large -> display(evidence)\n"
            val d = dsp(src)
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-004")
            d.single().severity shouldBe Severity.ERROR
            d.single().message shouldContain "`evidence`"
        }

        "a display whose name is not a declared schema is unchanged (any shape, no DSP)" {
            dsp(review(late = "select(order_id)") + "review.late -> display(evidence)\n").shouldBeEmpty()
        }

        "a schema is in scope only through an import of its package" {
            val noImport = review(late = "select(order_id)").replace("import shop.actions.*\n", "")
            dsp(noImport + "review.late -> display(notify)\n").shouldBeEmpty()
            // ...so without the import a duplicated `notify` is an ordinary (ambiguous) display name.
            dsp(
                noImport + "review.late -> display(notify)\nreview.large -> display(notify)\n",
            ).map { it.id.id } shouldBe
                listOf("TTRP-DSP-004")
        }

        "two imported packages declaring the same schema name is TTRP-RES-002 (ambiguous)" {
            val both = review().replace("import shop.actions.*\n", "import shop.actions.*\nimport shop.alerts.*\n")
            val d = dsp(both + "review.late -> display(notify)\n")
            d.map { it.id.id } shouldContainExactly listOf("TTRP-RES-002")
            d.single().message shouldContain "shop.actions.notify"
            d.single().message shouldContain "shop.alerts.notify"
        }

        "rows that cross a wired container boundary are checked against the schema" {
            val src =
                review() +
                    """
                    container pick(in rows, out picked) target erp {
                        picked = rows -> select(recipient, order_id)
                    }
                    review.late -> pick.rows
                    pick.picked -> display(notify)
                    """.trimIndent() + "\n"
            dsp(src).map { it.id.id } shouldContainExactly listOf("TTRP-DSP-001")
        }

        "a chain that ends in an OUT port gives the port its row type — DSP-001/002/003 are reported" {
            // `… -> late` is `late = …`: the port used to stay untyped, so the check was silently skipped.
            fun chain(body: String) =
                header +
                    """
                    container review(out late) target erp {
                        o = load(orders) -> calc { recipient = cast(order_id as string) }
                        $body
                    }
                    review.late -> display(notify)
                    """.trimIndent() + "\n"
            val d = dsp(chain("o -> filter(status = 1) -> select(recipient, order_id, status) -> late"))
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-001", "TTRP-DSP-003")
            d[0].message shouldContain "`subject`"
            d[1].message shouldContain "`status`"
            dsp(
                chain(
                    "o -> calc { subject = cast(status as string)  order_id = due_date } -> select(recipient, subject, order_id) -> late",
                ),
            ).map { it.id.id } shouldContainExactly listOf("TTRP-DSP-002")
            dsp(
                chain("o -> calc { subject = cast(status as string) } -> select(recipient, subject, order_id) -> late"),
            ).shouldBeEmpty()
        }

        "chain-to-port on a Polars container in the bash world is checked the same way" {
            val src =
                """
                uses world "shop.worlds.local"
                import shop.actions.*

                container review(out late) target pl {
                    o = load(files.orders, schema: orders_csv) -> calc { recipient = email }
                    o -> filter(status = 1) -> select(recipient, order_id) -> late
                }
                review.late -> display(notify)
                """.trimIndent() + "\n"
            val d = dsp(src)
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-001")
            d.single().message shouldContain "`subject`"
        }

        "SQL type spellings in a schema (bigint, numeric, money, time) are judged, not waved through" {
            // `batch_no` is a bigint: an int is assignable, a date is not (it used to pass as an unknown `Named` type).
            fun audit(calc: String) =
                review(late = "calc { $calc } -> select(order_id, batch_no)") + "review.late -> display(audit)\n"
            dsp(audit("batch_no = order_id")).shouldBeEmpty()
            val d = dsp(audit("batch_no = due_date"))
            d.map { it.id.id } shouldContainExactly listOf("TTRP-DSP-002")
            d.single().message shouldContain "`bigint`"
            // an omitted optional bigint / numeric / money / time column checks clean
            dsp(review(late = "select(order_id)") + "review.late -> display(audit)\n").shouldBeEmpty()
        }

        "a schema column no engine can produce (an object) is TTRP-DSP-005, once per display" {
            val src =
                review(late = "select(order_id)", large = "select(order_id)") +
                    "review.late  -> display(raw_payload)\n" +
                    "review.large -> display(raw_payload)\n"
            val d = dsp(src).filter { it.id.id == "TTRP-DSP-005" }
            d shouldHaveSize 1
            d.single().severity shouldBe Severity.ERROR
            d.single().message shouldContain "`payload`"
            d.single().message shouldContain "`object`"
        }

        "a second schema checks its own shape (flag_order: order_id int, reason text, due date?)" {
            val src =
                review(late = "calc { reason = status  due = due_date } -> select(order_id, reason, due)") +
                    "review.late -> display(flag_order)\n"
            dsp(src).shouldBeEmpty()
        }
    }) {
    companion object {
        /** The `src/test/resources/<name>` fixture dir, walked up from the working dir. */
        fun locate(name: String): Path {
            val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/$name")
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(rel)
                if (Files.isDirectory(candidate)) return candidate
                val local = dir.resolve("src/test/resources/$name")
                if (Files.isDirectory(local)) return local
                dir = dir.parent
            }
            error("could not locate the $name fixture from ${Path.of("").toAbsolutePath()}")
        }
    }
}
