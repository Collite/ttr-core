// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * The AU-core-P1 phase-exit review (review-115): one regression per finding, each the reviewer's own probe. Above
 * all: a set-level context must never come out per-candidate.
 */
class Review115Spec :
    StringSpec({
        val skeletonHead =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: order.order_number
                candidates placed(out orders) target erp { load(er.entity.order) -> orders }
            """.trimIndent()
        val skeletonTail =
            """
                decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                actions { v.go -> release { order: order_number } }
            }
            """.trimIndent()

        /** The class of context `c(in orders, out rows)` with the canonical [body]. */
        fun classOf(body: String): ContextInfo =
            RecipeFixtures
                .plan(
                    "$skeletonHead\ncontext c(in orders, out rows) target erp {\n$body\n}\n$skeletonTail",
                    "lineage.ttrp",
                ).recipe!!
                .contexts
                .single { it.name == "c" }

        /** The class of context `c(in orders, out rows)` with the TTR-SQL [sql]. */
        fun classOfSql(sql: String): ContextInfo =
            RecipeFixtures
                .plan(
                    "$skeletonHead\ncontext c(in orders, out rows) target erp \"\"\"sql\n$sql\n\"\"\"\n$skeletonTail",
                    "lineage.ttrp",
                ).recipe!!
                .contexts
                .single { it.name == "c" }

        fun findings(
            body: String,
            fileName: String = "probe.ttrp",
        ): List<TtrpDiagnostic> =
            RecipeFixtures.findings(
                RecipeFixtures.plan(
                    "uses world \"hartland.worlds.demo\"\nimport hartland.*\nimport hartland.automation.*\nrecipe {\n$body\n}\n",
                    fileName,
                ),
            )

        val set = ContextClass.SET_LEVEL
        val keyed = ContextClass.PER_CANDIDATE

        // ---- B1: a join whose unkeyed side reads the candidates ---------------------------------------------

        "B1: the candidates joined back to an aggregate of themselves are set-level — every join kind" {
            val perCustomer = "per_cust = orders -> aggregate { group_by: customer  n = count(order_number) }\n"
            for (kind in listOf("inner", "left")) {
                val c =
                    classOf(
                        "${perCustomer}orders -> join(right: per_cust, kind: $kind, on: left.customer = right.customer) -> rows",
                    )
                c.contextClass shouldBe set
                c.lostKeyAt!! shouldContain "rows built from the candidates"
            }
            classOf(
                "${perCustomer}busy = per_cust -> filter(n > 1)\n" +
                    "orders -> join(right: busy, kind: semi, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe set
            classOf(
                "big = orders -> filter(value > 1000) -> select(customer)\n" +
                    "orders -> join(right: big, kind: anti, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe set
        }

        "B1: in SQL — a CTE aggregate joined back, and an `IN (SELECT … GROUP BY …)`" {
            classOfSql(
                "WITH pc AS (SELECT customer, COUNT(*) AS n FROM orders GROUP BY customer)\n" +
                    "SELECT o.order_number, pc.n FROM orders o JOIN pc ON pc.customer = o.customer",
            ).contextClass shouldBe set
            classOfSql(
                "SELECT order_number, value FROM orders WHERE customer IN (SELECT customer FROM orders GROUP BY customer HAVING COUNT(*) > 1)",
            ).contextClass shouldBe set
        }

        "B1: inside the decision — an aggregate of the candidates joined back is REC-010" {
            val d =
                findings(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    decision v(in orders) {
                        per_cust = orders -> aggregate { group_by: customer  n = count(order_number) }
                        x = orders -> join(right: per_cust, kind: inner, on: left.customer = right.customer)
                        x -> switch { go: n > 3  because: "x" }
                    }
                    actions { v.go -> release { order: order_number } }
                    """,
                )
            d.map { it.id } shouldContain TtrpDiagnosticId.REC_010
            d.first { it.id == TtrpDiagnosticId.REC_010 }.message shouldContain "switch reads rows not keyed"
        }

        "B1: an opaque island fed by set-level rows is set-level" {
            val r =
                RecipeFixtures.plan(
                    "$skeletonHead\n" +
                        "context totals(in orders, out rows) target erp { orders -> aggregate { group_by: " +
                        "customer  n = count(order_number) } -> rows }\n" +
                        "context score(in totals, out rows) foreign python per candidate schema care_answer\n" +
                        skeletonTail,
                    "lineage.ttrp",
                )
            r.recipe!!
                .contexts
                .single { it.name == "score" }
                .contextClass shouldBe set
        }

        // ---- B2 … B6 -----------------------------------------------------------------------------------------

        "B2: `latest` keeps the key only when the key is among `per:`" {
            classOf("orders -> latest(by: placed_date, per: customer) -> rows").contextClass shouldBe set
            classOf("orders -> latest(by: placed_date, per: order_number) -> rows").contextClass shouldBe keyed
            classOf("orders -> within(placed_date, last: days(30)) -> rows").contextClass shouldBe keyed
        }

        "B3: a subquery the decomposition drops makes a candidates-reading island set-level, never silent" {
            classOfSql(
                "SELECT order_number, value FROM orders WHERE value > 0 AND customer IN (SELECT customer FROM orders GROUP BY customer HAVING COUNT(*) > 1)",
            ).let {
                it.contextClass shouldBe set
                it.lostKeyAt!! shouldContain "subquery"
            }
            classOfSql(
                "SELECT order_number, value FROM orders WHERE value > 0 AND EXISTS (SELECT customer FROM orders WHERE value > 1000)",
            ).contextClass shouldBe set
        }

        "B4: a column a join made ambiguous is not the key again after the next join" {
            classOfSql(
                "SELECT l.order_number, COUNT(*) AS n FROM orders o JOIN er.entity.invoice i ON i.customer = " +
                    "o.customer " +
                    "JOIN er.entity.sales_line l ON l.order_number = i.order_number GROUP BY l.order_number",
            ).contextClass shouldBe set
            classOf(
                "orders -> join(right: load(er.entity.invoice), kind: inner, on: left.customer = right.customer) " +
                    "-> join(right: load(er.entity.sales_line), kind: inner, on: left.order_number = " +
                    "right.order_number) " +
                    "-> aggregate { group_by: order_number  n = count(line_no) } -> rows",
            ).contextClass shouldBe set
        }

        "B5: a set operation lines columns up by position, not by name" {
            classOf(
                "a = orders -> select(order_number, customer)\nb = orders -> select(customer, order_number)\n" +
                    "union(a, b) -> aggregate { group_by: order_number  n = count(customer) } -> rows",
            ).contextClass shouldBe set
            classOf(
                "a = orders -> select(order_number, customer)\nb = orders -> select(order_number, customer)\n" +
                    "union(a, b) -> rows",
            ).contextClass shouldBe keyed
        }

        "B6: a second out port of the candidates is REC-000, and no seed" {
            val d =
                findings(
                    """
                    key: order.order_number
                    candidates placed(out orders, out per_cust) target erp {
                        orders = load(er.entity.order) -> filter(state = "placed")
                        per_cust = orders -> aggregate { group_by: customer  n = count(order_number) }
                    }
                    context c(in per_cust, out rows) target erp { per_cust -> rows }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions { v.go -> release { order: order_number } }
                    """,
                )
            d.first { it.id == TtrpDiagnosticId.REC_000 }.message shouldContain "second `out` port on the candidates"
            d.any { it.id == TtrpDiagnosticId.REC_010 && "context `c`" in it.message } shouldBe true
        }

        "B7 (D-133): a top-N over the candidates is set-level; `sort` alone and a `limit` over a lookup are not" {
            val top = classOf("orders -> sort(value) -> limit(1) -> rows")
            top.contextClass shouldBe set
            top.lostKeyAt!! shouldContain "`limit(1)` keeps the first rows of all the candidates taken together"
            classOfSql("SELECT o.order_number, o.value FROM orders o ORDER BY o.value DESC LIMIT 10")
                .contextClass shouldBe set
            classOf("orders -> sort(value) -> rows").contextClass shouldBe keyed
            classOf(
                "last_inv = load(er.entity.invoice) -> sort(due) -> limit(1)\n" +
                    "orders -> join(right: last_inv, kind: left, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe keyed
        }

        "B7 (D-133): the 10 largest of a run — batch only; an error once an event program runs it" {
            val body =
                """
                key: order.order_number
                candidates placed(out orders) target erp { load(er.entity.order) -> filter(state = "placed") -> orders }
                context big_ones(in orders, out rows) target erp {
                    orders -> sort(value) -> limit(10) -> select(order_number) -> calc(is_big = true) -> rows
                }
                decision verdict(in orders, in big_ones) {
                    v = orders -> join(right: big_ones, kind: left, on: left.order_number = right.order_number)
                               -> calc(is_big = coalesce(is_big, false))
                    v -> switch {
                        mode: exclusive
                        hold_sales: is_big  because: "one of the largest orders"
                        release:    true    because: "all checks passed"
                    }
                }
                actions {
                    verdict.hold_sales -> hold    { order: order_number, department: "Sales" }
                    verdict.release    -> release { order: order_number }
                }
                """.trimIndent()
            val batch = findings(body).single { it.id == TtrpDiagnosticId.REC_010 }
            batch.severity shouldBe Severity.WARNING
            batch.message shouldContain "context `big_ones` reads the candidates but `limit(10)`"
            batch.message shouldContain "batch only"
            // `probe_event_setlevel` runs `rec_010_event` per event: 4711 alone is always in its own top 10.
            val event = findings("recipe: rec_010_event\n$body").single { it.id == TtrpDiagnosticId.REC_010 }
            event.severity shouldBe Severity.ERROR
            event.message shouldContain "program `probe_event_setlevel` runs it per event"
        }

        "B7 (D-133): at most 50 releases per run — `port: key` is refused, not one slot of 50 per key" {
            val d =
                findings(
                    """
                    recipe: rec_012
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> filter(state = "placed") -> orders }
                    context slots(in orders, out rows) target erp {
                        orders -> sort(placed_date) -> limit(50) -> select(order_number) -> rows
                    }
                    decision verdict(in orders, in slots) {
                        v = orders -> join(right: slots, kind: semi, on: left.order_number = right.order_number)
                        v -> switch { release: true  because: "a release slot of the run" }
                    }
                    actions { verdict.release -> release { order: order_number } }
                    """.trimIndent(),
                )
            d.first { it.id == TtrpDiagnosticId.REC_010 }.message shouldContain "`limit(50)`"
            d.first { it.id == TtrpDiagnosticId.REC_012 }.message shouldContain "program `probe_key`"
        }

        // ---- majors ----------------------------------------------------------------------------------------

        "M1: the turn rule follows the answer through a context, a `when:` and an `on_due:`; a homonym is no answer" {
            val head =
                "key: order.order_number\ncandidates placed(out orders) target erp { load(er.entity.order) -> " +
                    "orders }\n" +
                    "context care(in orders, out rows) turn agents.care ask \"Release?\" answer care_answer\n"
            val relayed =
                findings(
                    head +
                        "context relay(in care, out rows) target erp { care -> calc(ok = approve) -> " +
                        "select(order_number, ok) -> rows }\n" +
                        "decision v(in orders, in relay) { x = orders -> join(right: relay, kind: left, on: " +
                        "left.order_number = right.order_number)\n" +
                        "  x -> switch { go: ok = true  because: \"x\"  else: none } }\n" +
                        "actions { v.go -> release { order: order_number } }",
                )
            relayed.map { it.id } shouldContain TtrpDiagnosticId.REC_040
            val viaWhen =
                findings(
                    head +
                        "decision v(in orders, in care) { x = orders -> join(right: care, kind: left, on: " +
                        "left.order_number = right.order_number)\n" +
                        "  x -> switch { go: value > 0  because: \"x\" } }\n" +
                        "actions { v.go -> release { order: order_number }  when: approve = true }",
                )
            viaWhen.map { it.id } shouldContain TtrpDiagnosticId.REC_040
            val viaOnDue =
                findings(
                    head +
                        "decision v(in orders, in care) { x = orders -> join(right: care, kind: left, on: " +
                        "left.order_number = right.order_number)\n" +
                        "  x -> switch { go: approve = true  because: \"x\"  else: none } }\n" +
                        "actions { v.go -> ask { who: role.sales_manager, about: order_number, action: hold { " +
                        "department: \"x\" },\n" +
                        "  due: 2 working_days, on_due: release { order: order_number } } }",
                )
            viaOnDue.single { it.id == TtrpDiagnosticId.REC_040 }.message shouldContain "on_due"
            val homonym =
                findings(
                    "key: order.order_number\ncandidates placed(out orders) target erp { load(er.entity.order) " +
                        "-> orders }\n" +
                        "context care(in orders, out rows) turn agents.care ask \"Release?\" answer care_answer\n" +
                        "decision v(in orders) { x = orders -> calc(approve = (value > 10))\n" +
                        "  x -> switch { go: approve = true  because: \"x\"  else: none } }\n" +
                        "actions { v.go -> release { order: order_number } }",
                )
            homonym.filter { it.id == TtrpDiagnosticId.REC_040 }.shouldBeEmpty()
        }

        "M2: a program in a package the recipe does not import still counts" {
            // `decide_on_request` (hartland.automation) runs decide_return per event; the probe imports only hartland.*
            val d =
                RecipeFixtures.findings(
                    RecipeFixtures.plan(
                        """
                        uses world "hartland.worlds.demo"
                        import hartland.*
                        recipe {
                            key: return_request_line.line_id
                            candidates received(out lines) target erp { load(er.entity.return_request_line) -> lines }
                            context per_req(in lines, out rows) target erp { lines -> aggregate { group_by: request_number  n = count(line_id) } -> rows }
                        }
                        """.trimIndent(),
                        "decide_return.ttrp",
                    ),
                )
            d.first { it.id == TtrpDiagnosticId.REC_010 }.severity shouldBe Severity.ERROR
        }

        "M3: a single-table SQL select with qualified columns keeps the key" {
            classOfSql("SELECT o.order_number, o.value FROM orders o WHERE o.value > 100").contextClass shouldBe keyed
            classOfSql(
                "SELECT orders.order_number, orders.value FROM orders WHERE orders.value > 100",
            ).contextClass shouldBe
                keyed
        }

        "M4: a symbol is checked against the attribute's vocabulary, and a near-miss of a column warns" {
            val bad =
                findings(
                    """
                    key: return_request_line.line_id
                    candidates received(out lines) target erp { load(er.entity.return_request_line) -> lines }
                    decision way(in lines) { lines -> switch { go: quantity > 0  because: "x" } }
                    actions { way.go -> set_state { line: line_id, to: settling, way: refund_no_retrun } }
                    """,
                )
            bad.single { it.id == TtrpDiagnosticId.REC_002 }.message shouldContain "does not list"
            val near =
                findings(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions {
                        v.go -> hold { order: order_number, department: chanel }
                        v.go -> ask  { who: role.sales_manager, about: ordr_number, action: release }
                    }
                    """,
                )
            // `department` writes `order.held_for`, whose value labels are its vocabulary (Sales, AR, both)
            near.single { it.severity == Severity.ERROR }.message shouldContain "`order.held_for` does not list"
            // `about` is free text: `ordr_number` is one edit from the column `order_number`
            near.single { it.severity == Severity.WARNING }.message shouldContain
                "did you mean the column `order_number`"
        }

        "M5: an attribute-typed column takes that attribute — or one an `on:` pair equates to it" {
            val d =
                findings(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions { v.go -> release { order: customer } }
                    """,
                )
            d.single().message shouldContain "takes `order.order_number`; `customer` is `order.customer`"
            // decide_return: notify_customer { customer: customer } — return_request.customer equates to customer.sk
            RecipeFixtures.findings(RecipeFixtures.sharedRecipe("decide_return.ttrp")).shouldBeEmpty()
            // through a shared key: sales_line.item → item.sk ← reorder_proposal.item fits; sales_line.warehouse does not
            val shared =
                findings(
                    """
                    key: sales_line.order_number
                    candidates lines(out orders) target erp { load(er.entity.sales_line) -> orders }
                    decision v(in orders) { orders -> switch { go: quantity > 0  because: "x" } }
                    actions {
                        v.go -> probe_item { item: item }
                        v.go -> probe_item { item: warehouse }
                    }
                    """,
                )
            shared.single().message shouldContain "`warehouse` is `sales_line.warehouse`"
        }

        "M6: a computed column keeps its type, so a time check downstream is not blind" {
            findings(
                """
                key: order.order_number
                candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                context c(in orders, out rows) target erp { orders -> calc(w = value * 2) -> within(w, last: days(3)) -> rows }
                decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                actions { v.go -> release { order: order_number } }
                """,
            ).map { it.id } shouldContain TtrpDiagnosticId.TYP_001
        }

        "M7: an int is a date only when the model leads it to the calendar; `within` bounds are dates" {
            fun ctx(body: String) =
                findings(
                    "key: order.order_number\ncandidates placed(out orders) target erp { load(er.entity.order) " +
                        "-> orders }\n" +
                        "context c(in orders, out rows) target erp { $body }\n" +
                        "decision v(in orders) { orders -> switch { go: value > 0  because: \"x\" } }\n" +
                        "actions { v.go -> release { order: order_number } }",
                ).filter { it.id == TtrpDiagnosticId.TYP_001 }
            ctx("orders -> calc(w = working_days(between: customer, and: :as_of)) -> rows").size shouldBe 1
            ctx("orders -> calc(a = age(since: 5)) -> rows").size shouldBe 1
            ctx(
                "orders -> join(right: load(er.entity.inventory), kind: inner, on: left.fulfilling_warehouse = " +
                    "right.warehouse) " +
                    "-> within(quantity_on_hand, last: weeks(8)) -> rows",
            ).size shouldBe 1
            ctx("orders -> within(placed_date, from: 5, to: \"x\") -> rows").size shouldBe 2
            // the snapshot sk reaches date_dim through the FK fk_inv_date
            ctx(
                "orders -> join(right: load(er.entity.inventory), kind: inner, on: left.fulfilling_warehouse = " +
                    "right.warehouse) " +
                    "-> within(as_of_date, last: weeks(8)) -> rows",
            ).shouldBeEmpty()
        }

        "M8: an island's schema must resolve" {
            val d =
                findings(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    context care(in orders, out rows) turn agents.care ask "Release?" answer no_such_schema
                    decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                    actions { v.go -> release { order: order_number } }
                    """,
                )
            d.first { it.id == TtrpDiagnosticId.RES_001 }.message shouldContain "no `def schema no_such_schema`"
        }

        // ---- minors ----------------------------------------------------------------------------------------

        "m3 / m4 / m6: unique roles, `detail:` names a context, `because:` fills resolve, the identity verdict wired" {
            val d =
                findings(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    context c(in orders, out rows) target erp { orders -> rows }
                    context c(in orders, out rows) target erp { orders -> rows }
                    decision v(in orders, in c) { orders -> switch { go: value > 0  because: "over {nonesuch}"  detail: nothere } }
                    actions { v.go -> release { order: order_number } }
                    """,
                )
            d.any { "a second role container named `c`" in it.message } shouldBe true
            d.any { "detail: nothere" in it.message } shouldBe true
            d.any { "{nonesuch}" in it.message } shouldBe true
            findings(
                """
                key: order.order_number
                candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                actions { }
                """,
            ).single().message shouldContain "identity verdict"
        }

        "m5: a fraction next to a date is an error; durations add in one unit" {
            fun ctx(body: String) =
                findings(
                    "key: order.order_number\ncandidates placed(out orders) target erp { load(er.entity.order) " +
                        "-> orders }\n" +
                        "context c(in orders, out rows) target erp { $body }\n" +
                        "decision v(in orders) { orders -> switch { go: value > 0  because: \"x\" } }\n" +
                        "actions { v.go -> release { order: order_number } }",
                ).filter { it.id == TtrpDiagnosticId.TYP_001 }
            ctx("orders -> filter(placed_date > :as_of - 1.5) -> rows").single().severity shouldBe Severity.ERROR
            ctx("orders -> filter(placed_date < 365 + placed_date) -> rows").single().severity shouldBe Severity.WARNING
            ctx("orders -> filter(placed_date > :as_of - (days(1) + minutes(5))) -> rows").single().severity shouldBe
                Severity.ERROR
        }
    })
