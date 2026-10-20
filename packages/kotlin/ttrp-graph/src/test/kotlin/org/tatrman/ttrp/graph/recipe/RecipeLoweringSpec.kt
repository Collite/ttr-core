// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.ParamRef
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
import org.tatrman.ttrp.graph.model.Load
import org.tatrman.ttrp.graph.model.NodeExpressions
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.Switch
import org.tatrman.ttrp.graph.model.TimeSugar

/**
 * AU-core-P2 · S2.1 T2–T3, S2.2 T2 — a checked recipe's switch and time sugar lowered to the node set: the decision's
 * route and detail ports, the `because:` text, the derived `:as_of` values, `latest`, `within`, the calendar counts;
 * and the relation join a port side used to leave unconditioned. What the rows then hold is RecipeLivePgSpec's.
 */
class RecipeLoweringSpec :
    StringSpec({
        fun outPorts(r: TtrpPipeline.PlanResult): List<String> {
            val decision =
                r.graph!!
                    .containers.values
                    .single { c -> c.label == r.recipe!!.decision }
            return decision.declaredPorts.filter { it.direction == PortDirection.OUT }.map { it.name }
        }

        /** An order recipe whose context `c` runs [body] — planned, the lowering included. */
        fun context(
            body: String,
            parameters: String = "[]",
        ): TtrpPipeline.PlanResult =
            RecipeFixtures.inline(
                """
                key: order.order_number
                parameters: $parameters
                candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                context c(in orders, out rows) target erp {
                $body
                }
                decision v(in orders) { orders -> switch { go: true  because: "x" } }
                actions { v.go -> notify { who: role.ops, detail: c } }
                """,
            )

        // ---- the switch -------------------------------------------------------------------------------------------

        "every `17` decision lowers: its routes and `<route>.<context>` detail ports, no switch left" {
            val expected =
                mapOf(
                    "release_order.ttrp" to
                        listOf(
                            "hold_sales",
                            "hold_ar",
                            "release_confirmed",
                            "release",
                            "hold_sales.shortfall",
                            "hold_sales.price_differences",
                            "hold_ar.overdue",
                        ),
                    "decide_return.ttrp" to
                        listOf(
                            "reject_not_ours",
                            "reject_late",
                            "reject_abuse",
                            "to_customer_care",
                            "refund_no_return",
                            "replacement_no_return",
                            "exchange",
                            "refund_after_return",
                        ),
                    // `else: none` has no port (D-80)
                    "monitor_inbound.ttrp" to
                        listOf("complete", "damaged", "expired", "remind", "complete.receipts", "damaged.receipts"),
                    "settle.ttrp" to
                        listOf(
                            "credit_note_issued",
                            "money_received",
                            "goods_shipped",
                            "money_overdue",
                            "goods_overdue",
                            "closed",
                            "money_overdue.open_obligations",
                            "goods_overdue.open_obligations",
                        ),
                )
            for ((f, ports) in expected) {
                val r = RecipeFixtures.sharedRecipe(f)
                r.ok shouldBe true
                r.graph!!
                    .nodes.values
                    .filterIsInstance<Switch>()
                    .shouldBeEmpty()
                outPorts(r) shouldContainExactly ports
                // the authored graph keeps the switch (the canvas draws it)
                r.authoredGraph!!
                    .nodes.values
                    .filterIsInstance<Switch>()
                    .size shouldBe 1
            }
        }

        "`because:` — literal pieces and fills concatenated; a column as is when text, else cast; a parameter cast" {
            val at = SourceLocation.UNKNOWN
            SwitchLowering.reason(null, emptyMap(), at).shouldBeInstanceOf<Cast>()
            (SwitchLowering.reason("all checks passed", emptyMap(), at) as Literal).value shouldBe
                LiteralValue.Str("all checks passed")
            val row = mapOf("reason" to TtrpType.Str, "share" to TtrpType.Decimal())
            val e =
                SwitchLowering.reason(
                    "{reason}: {share} over {abuse_share}",
                    row,
                    at,
                    mapOf(
                        "abuse_share" to TtrpType.Decimal(),
                    ),
                )
            val leaves = NodeExpressions.walk(e)
            leaves.filterIsInstance<FunctionCall>().count { it.function == SwitchLowering.CONCAT } shouldBe 4
            // every fill: NULL reads as empty (a CASE, review-119 M2)
            leaves.filterIsInstance<CaseWhen>().size shouldBe 3
            // `reason` is text — read as is; `share` and the parameter `abuse_share` are decimals — at 4 places, then
            // text (the same digits on every engine, review-119 M3)
            val toText = leaves.filterIsInstance<Cast>().filter { it.target == TtrpType.Str }
            toText.map { (it.expr as Cast).target } shouldContainExactlyInAnyOrder
                listOf(TtrpType.Decimal(38, 4), TtrpType.Decimal(38, 4))
            leaves.filterIsInstance<ColumnRef>().map { it.column }.distinct() shouldContainExactlyInAnyOrder
                listOf("reason", "share")
            // a declared scale is kept; an integer or a date is cast as is
            (SwitchLowering.fillText(ColumnRef(null, "v", at), TtrpType.Decimal(12, 2), at) as Cast).expr shouldBe
                Cast(ColumnRef(null, "v", at), TtrpType.Decimal(38, 2), at)
            (SwitchLowering.fillText(ColumnRef(null, "n", at), TtrpType.Integer, at) as Cast).expr shouldBe
                ColumnRef(null, "n", at)
        }

        "a `route` column in the decision's rows is refused — the name is the verdict's" {
            val r =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> calc(route = "x") -> orders }
                    decision v(in orders) { orders -> switch { go: true  because: "x" } }
                    actions { v.go -> hold { order: order_number, department: "Sales" } }
                    """,
                )
            r.ok shouldBe false
            r.diagnostics.single { it.severity == Severity.ERROR }.message shouldContain "the name is the verdict's"
        }

        // ---- the time sugar ---------------------------------------------------------------------------------------

        "`:as_of - days(365)`, `:as_of - :return_window` become derived run-bound values (no date math at the door)" {
            val r = RecipeFixtures.sharedRecipe("decide_return.ttrp")
            r.recipe!!.derivedParams.map { Triple(it.name, it.amount, it.unit) } shouldContainExactlyInAnyOrder
                listOf(
                    Triple("as_of_minus_return_window_days", "return_window", "days"),
                    Triple("as_of_minus_365_days", "365", "days"),
                )
            // nothing subtracts a duration any more
            r.graph!!
                .nodes.values
                .flatMap { NodeExpressions.of(it) }
                .flatMap { NodeExpressions.walk(it) }
                .filterIsInstance<ParamRef>()
                .map { it.name }
                .toSet() shouldBe
                setOf(
                    "as_of_minus_return_window_days",
                    "as_of_minus_365_days",
                    "abuse_share",
                    "care_threshold",
                    "small_value",
                    "return_window",
                )
        }

        "`latest(by:, per:)` → max per group + a semi join of the rows back, a NULL `per:` its own group" {
            val r = context("load(er.entity.inventory) -> latest(by: as_of_date, per: item, warehouse) -> rows")
            r.ok shouldBe true
            val g = r.graph!!
            g.nodes.values
                .filterIsInstance<TimeSugar>()
                .shouldBeEmpty()
            val max =
                g.nodes.values
                    .filterIsInstance<Aggregate>()
                    .single { it.id.endsWith("~max") }
            // each `per:` column groups as a never-NULL text key (review-119 M6), computed once on the input rows
            max.groupBy.size shouldBe 2
            max.groupBy.forEach { it shouldStartWith "_ttrp_g_" }
            g.nodes.values
                .filterIsInstance<org.tatrman.ttrp.graph.model.Project>()
                .any { p -> max.groupBy.all { it in p.aliases } } shouldBe true
            g.nodes.values
                .filterIsInstance<Join>()
                .single { it.type == JoinType.SEMI }
                .on
                .shouldNotBeNull()
        }

        "`within(date, last: …)` filters by a derived value; on an sk it semi-joins the calendar's days" {
            val onDate = context("orders -> within(placed_date, last: weeks(8)) -> rows")
            onDate.ok shouldBe true
            onDate.recipe!!
                .derivedParams
                .single()
                .name shouldBe "as_of_minus_8_weeks"
            onDate.graph!!
                .nodes.values
                .filterIsInstance<Join>()
                .none { it.type == JoinType.SEMI } shouldBe true

            val onSk = context("load(er.entity.inventory) -> within(as_of_date, last: days(30)) -> rows")
            onSk.ok shouldBe true
            val semi =
                onSk.graph!!
                    .nodes.values
                    .filterIsInstance<Join>()
                    .single { it.type == JoinType.SEMI }
            NodeExpressions.walk(semi.on!!).filterIsInstance<ColumnRef>().map { it.column } shouldContainExactly
                listOf("as_of_date", "_ttrp_sk_" + semi.id.replace(Regex("[^A-Za-z0-9_]"), "_"))
            onSk.graph!!
                .nodes.values
                .filterIsInstance<Load>()
                .any { it.source.endsWith("date_dim") } shouldBe true
        }

        "`working_days` / `age` / a bound date minus a date column → a count of the calendar's days, no subtraction" {
            for (calc in listOf(
                "late = working_days(between: placed_date, and: :as_of)",
                "days_open = age(since: placed_date)",
                "days_open = :as_of - placed_date",
            )) {
                val r = context("orders -> calc($calc) -> select(order_number, ${calc.substringBefore(" =")}) -> rows")
                r.ok shouldBe true
                val g = r.graph!!
                g.nodes.values
                    .filterIsInstance<Load>()
                    .any { it.source.endsWith("date_dim") } shouldBe true
                g.nodes.values.filterIsInstance<Aggregate>().any { a ->
                    a.aggregations.any { it.name.startsWith("_ttrp_n_") }
                } shouldBe
                    true
                g.nodes.values
                    .flatMap { NodeExpressions.of(it) }
                    .flatMap { NodeExpressions.walk(it) }
                    .filterIsInstance<FunctionCall>()
                    .none { it.function.name in setOf("working_days", "age", "sub") } shouldBe true
            }
        }

        // ---- the relation join a port side left unconditioned ------------------------------------------------------

        "`port -> join(right: load(…), on: relation R)` is conditioned on R's pair — the port side is R's other end" {
            // stock_at_center: `lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)`
            val g = RecipeFixtures.sharedRecipe("decide_return.ttrp").authoredGraph!!
            val condition = g.logicalJoins.values.single()
            NodeExpressions
                .walk(condition)
                .filterIsInstance<ColumnRef>()
                .map { "${it.port}.${it.column}" } shouldContainExactlyInAnyOrder listOf("left.customer", "right.sk")
        }

        "a relation join neither side of which the relation names is refused, not left a Cartesian product" {
            // two role ports: the candidates' `orders` and the context `c` — no loaded entity on either side
            val r =
                RecipeFixtures.inline(
                    """
                    key: order.order_number
                    candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                    context c(in orders, out rows) target erp { orders -> rows }
                    decision v(in orders, in c) {
                        j = orders -> join(right: c, kind: left, on: relation rel_order_line)
                        j -> switch { go: true  because: "x" }
                    }
                    actions { v.go -> hold { order: order_number, department: "Sales" } }
                    """,
                )
            r.ok shouldBe false
            r.diagnostics.single { it.id == TtrpDiagnosticId.RES_004 }.message shouldContain "cannot be conditioned"
        }
    })
