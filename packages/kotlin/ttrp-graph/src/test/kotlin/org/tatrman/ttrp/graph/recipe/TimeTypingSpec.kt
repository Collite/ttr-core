// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId

/**
 * AU-core-P1 · S1.3 — time in a recipe: `:as_of`, runtime `:name`s, durations and date arithmetic, the time
 * functions and sugar ops, the sk-vs-date distinction, REC-050.
 */
class TimeTypingSpec :
    StringSpec({
        /** The findings of an order recipe whose context `c` reads [load] and runs [body] (`<input> -> … -> rows`). */
        fun findings(
            body: String,
            parameters: String = "[]",
            header: String = "",
            key: String = "order.order_number",
            candidates: String = "load(er.entity.order) -> orders",
        ): List<TtrpDiagnostic> {
            val r =
                RecipeFixtures.plan(
                    """
                    uses world "hartland.worlds.demo"
                    import hartland.*
                    import hartland.automation.*
                    recipe {
                        $header
                        key: $key
                        parameters: $parameters
                        candidates placed(out orders) target erp { $candidates }
                        context c(in orders, out rows) target erp {
                    $body
                        }
                        decision v(in orders) { orders -> switch { go: true  because: "x" } }
                        actions { v.go -> notify { who: role.ops, detail: c } }
                    }
                    """.trimIndent(),
                    "time.ttrp",
                )
            return RecipeFixtures.findings(r)
        }

        "`:as_of` is the run's instant; `date - days` is a date" {
            findings(
                "orders -> filter(placed_date >= :as_of - :return_window and placed_date < :as_of) -> rows",
                parameters = "[return_window]",
            ).shouldBeEmpty()
        }

        "`:as_of - 365` reads the integer as days — a warning that says `days(365)`" {
            val d = findings("orders -> filter(placed_date >= :as_of - 365) -> rows").single()
            d.id shouldBe TtrpDiagnosticId.TYP_001
            d.severity shouldBe Severity.WARNING
            d.message shouldContain "days(365)"
            findings("orders -> filter(placed_date >= :as_of - days(365)) -> rows").shouldBeEmpty()
        }

        "working days need the calendar: only `working_days(between:, and:)` — an int — takes them" {
            findings(
                "orders -> calc(late = working_days(between: placed_date, and: :as_of)) -> filter(late > 2) -> rows",
            ).shouldBeEmpty()
            val d =
                findings("orders -> filter(placed_date < :as_of - working_days(:settlement)) -> rows", "[settlement]")
            d.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.TYP_001)
            d.single().message shouldContain "working days need the calendar"
        }

        "`age(since:)` is an integer over a timestamp; `working_days` wants its names" {
            findings(
                "orders -> join(right: load(er.entity.obligation), kind: left, on: left.order_number = right.key) " +
                    "-> calc(days_open = age(since: created_at)) -> filter(days_open > 3) -> rows",
            ).filter { it.id == TtrpDiagnosticId.TYP_001 }.shouldBeEmpty()
            findings(
                "orders -> calc(late = working_days(placed_date, :as_of)) -> rows",
            ).map { it.id } shouldContainExactly
                listOf(TtrpDiagnosticId.FN_002)
        }

        "`days(:p)` takes a days / working-days / int parameter" {
            val d = findings("orders -> filter(placed_date >= :as_of - days(:tolerance)) -> rows", "[tolerance]")
            d.map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.TYP_001)
            d.single().message shouldContain "`tolerance` is decimal"
        }

        "a duration compares with a count of its unit, and a computed duration is a duration" {
            findings(
                "orders -> calc(age_days = :as_of - placed_date) -> filter(age_days > :return_window) -> rows",
                "[return_window]",
            ).shouldBeEmpty()
            findings("orders -> filter(placed_date < :as_of - days(2 * value)) -> rows").shouldBeEmpty()
        }

        "`latest(by:, per:)` on inventory keeps its row; its columns must be the input's" {
            findings(
                "load(er.entity.inventory) -> latest(by: as_of_date, per: item, warehouse) " +
                    "-> join(right: orders, kind: inner, on: left.warehouse = right.fulfilling_warehouse) -> rows",
            ).shouldBeEmpty()
            findings(
                "load(er.entity.inventory) -> latest(by: snapshot, per: item) " +
                    "-> join(right: orders, kind: inner, on: left.warehouse = right.fulfilling_warehouse) -> rows",
            ).map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.EXP_001)
        }

        "`within`: a date, or an sk the model leads to the calendar; anything else is not a date" {
            findings(
                "load(er.entity.sales_line) -> within(sold_date, last: weeks(8)) " +
                    "-> join(right: orders, kind: semi, on: left.order_number = right.order_number) -> rows",
            ).shouldBeEmpty()
            // inventory.as_of_date is an int sk; rel_inventory_date (FK-only) leads to date_dim, the calendar
            findings(
                "load(er.entity.inventory) -> within(as_of_date, from: :as_of - weeks(4), to: :as_of) " +
                    "-> join(right: orders, kind: inner, on: left.warehouse = right.fulfilling_warehouse) -> rows",
            ).shouldBeEmpty()
            val d =
                findings(
                    "load(er.entity.sales_line) -> within(quantity, last: weeks(8)) " +
                        "-> join(right: orders, kind: semi, on: left.order_number = right.order_number) -> rows",
                ).single()
            d.id shouldBe TtrpDiagnosticId.TYP_001
            d.message shouldContain "`within` reads a date"
            findings(
                "load(er.entity.sales_line) -> within(sold_date, 8) " +
                    "-> join(right: orders, kind: semi, on: left.order_number = right.order_number) -> rows",
            ).map { it.id } shouldContainExactly listOf(TtrpDiagnosticId.FN_002)
        }

        "REC-050: a sum of a snapshot measure across its dates warns; not after `latest`, nor by the date" {
            val sum =
                "-> aggregate { group_by: warehouse  stock = sum(quantity_on_hand) } " +
                    "-> join(right: orders, kind: inner, on: left.warehouse = right.fulfilling_warehouse) -> rows"
            val d = findings("load(er.entity.inventory) $sum").single()
            d.id shouldBe TtrpDiagnosticId.REC_050
            d.severity shouldBe Severity.WARNING
            d.message shouldContain "as_of_date"
            findings("load(er.entity.inventory) -> latest(by: as_of_date, per: item, warehouse) $sum").shouldBeEmpty()
            findings(
                "load(er.entity.inventory) " +
                    "-> aggregate { group_by: warehouse, as_of_date  stock = sum(quantity_on_hand) } " +
                    "-> join(right: orders, kind: inner, on: left.warehouse = right.fulfilling_warehouse) -> rows",
            ).shouldBeEmpty()
        }

        "a `:name` the program's event carries binds to it; `as_of` cannot be a parameter" {
            // decide_return runs under `decide_on_request { port: event, event: schema.line_received }` — `:line_id`
            findings(
                "orders -> filter(line_id = :line_id) -> rows",
                header = "recipe: decide_return",
                key = "return_request_line.line_id",
                candidates = "load(er.entity.return_request_line) -> orders",
            ).filter { it.id == TtrpDiagnosticId.REC_020 }.shouldBeEmpty()
            findings("orders -> filter(order_number = :line_id) -> rows").map { it.id } shouldContainExactly
                listOf(TtrpDiagnosticId.REC_020)
        }
    })
