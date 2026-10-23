// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.concept.v1.ConceptRef
import java.time.Instant

/**
 * CN-P3 S3.4 — `definitionRender` branch by branch, beyond the goldens (which pin the S0.3 / H-1 shapes): the er
 * renderer's other aggregate forms, alias freshening, the window and DC-12 words the goldens do not reach, CONTEXT
 * without candidates, the refusals with their demands; the md dispatch (S3.3's renderer, its PL text and placeholders).
 * Worlds: `hartland` / `clinic` / `sales` plus a few definitions ([HARTLAND], [StatementLowerSpec.PLUS]); the er text
 * in its compact spelling ([SqlPrinter]) — the layout is the goldens'.
 */
class DefinitionRenderSpec :
    FunSpec({
        val hartland = StatementLowerSpec.world("hartland", HARTLAND)
        val sales = StatementLowerSpec.world("sales", StatementLowerSpec.PLUS)

        fun er(
            world: ResolvedCnModel,
            target: String,
            options: RenderOptions = RenderOptions(),
        ): Rendering = ErRenderer(world, compact = true).render(world.cn.byName(target)!!, options)

        fun md(
            world: ResolvedCnModel,
            target: String,
            options: RenderOptions = RenderOptions(),
        ): Rendering =
            CnConceptService(world).definitionRender(
                ConceptRef.newBuilder().setQname("cn.$target").build(),
                Layer.MD,
                "",
                options,
            )

        fun refusal(r: Rendering): String {
            r.text shouldBe ""
            return r.diagnostics.single().let { "${it.slug}: ${it.message}" }
        }

        // ---- er: aggregates ---------------------------------------------------------------------------------------

        test("a plain comparison over a stored bound reads through it where the bound decides (R125-4, A-CN-45)") {
            val clinic = FrameValidationSpec.world("clinic", STORED_BOUND)
            val svc = CnConceptService(clinic)

            fun er(name: String): Rendering {
                val ref = ConceptRef.newBuilder().setQname("cn.$name").build()
                return svc.definitionRender(ref, Layer.ER, "postgresql", RenderOptions())
            }

            val before = er("OnsetBefore")
            before.text shouldContain "\"condition\".onset_hi <= {as_of_hi}"
            before.diagnostics shouldBe emptyList()
            val strict = er("OnsetStrictlyBefore")
            strict.text shouldContain "\"condition\".onset_hi <= {as_of}"
            strict.diagnostics shouldBe emptyList()
            er("OnsetAfter").text shouldContain "\"condition\".onset >= {as_of}" // the stored start decides
            val window = er("OnsetWindow")
            window.text shouldContain "\"condition\".onset >= {window_lo} AND \"condition\".onset_hi <= {as_of_hi}"
            window.diagnostics shouldBe emptyList()
            // the lint and the renderer agree: the advisory stands only where the stored instant decides
            val mine = setOf("cn.OnsetBefore", "cn.OnsetStrictlyBefore", "cn.OnsetAfter", "cn.OnsetWindow")
            clinic
                .definitionDiagnostics()
                .filter { it.slug == CnDefinitionLints.IMPRECISE && it.subject in mine }
                .map { it.subject } shouldBe listOf("cn.OnsetAfter")
            er("OnsetAfter").diagnostics.map { it.slug } shouldBe listOf(CnDefinitionLints.IMPRECISE)
        }

        test("decidedByBound: the lesser side of < <= before at_or_before, or the greater of > >=; <= needs :as_of") {
            val d = CnDefinitionLints::decidedByBound
            d("<", true, false) shouldBe true // x < t: x_hi <= t, whatever t is
            d("<=", true, true) shouldBe true // x <= :as_of: x_hi <= {as_of_hi}
            d("<=", true, false) shouldBe false // x <= column: its upper end is not derivable (G-ER-1)
            d("at_or_before", true, true) shouldBe true
            d(">=", true, true) shouldBe false // x >= t: the stored start decides
            d(">=", false, true) shouldBe true // :as_of >= x — the mirror of x <= :as_of
            d(">", false, false) shouldBe true
        }

        test("a CONTEXT whose candidates phase does not parse is refused, never thrown (review-125 re-review)") {
            val unparsed = "model cn\ndef phase Broken { of: Order, definition: \"\"\"state in (\"\"\" }\n"
            val broken = StatementLowerSpec.world("hartland", HARTLAND + ("broken/cn.ttrm" to unparsed))
            val r =
                CnConceptService(broken).definitionRender(
                    ConceptRef.newBuilder().setQname("cn.ShortLine").build(),
                    Layer.ER,
                    "postgresql",
                    RenderOptions(
                        mode = RenderMode.CONTEXT,
                        candidates = ConceptRef.newBuilder().setQname("cn.Broken").build(),
                        columns = listOf("item"),
                    ),
                )
            r.text shouldBe ""
            r.diagnostics.single().message shouldContain "does not parse"
        }

        test("a target named in two packages is refused, never the first — definitionRender and materialize (R125-5)") {
            val two = CnConceptService(FrameValidationSpec.model(FrameValidationSpec.TWO_PACKAGES))
            val thing = ConceptRef.newBuilder().setQname("cn.Thing").build()
            for (layer in Layer.entries) {
                val r = two.definitionRender(thing, layer, "postgresql", RenderOptions())
                r.text shouldBe ""
                r.diagnostics.single().slug shouldBe "cn/definition-unreachable(${layer.name.lowercase()})"
                r.diagnostics.single().message shouldContain "2 packages (`shop.a`, `shop.b`)"
            }
            two
                .materialize(thing, "postgresql")
                .diagnostics
                .single()
                .message shouldContain
                "2 packages (`shop.a`, `shop.b`)"
            val bare =
                two.definitionRender(
                    ConceptRef.newBuilder().setQname("Thing").build(),
                    Layer.ER,
                    "pg",
                    RenderOptions(),
                )
            bare.diagnostics.single().message shouldContain "a target writes `cn.<Name>`"
        }

        test("a sum compared to a constant is JOIN + GROUP BY + HAVING (false on no rows); `value` is quoted") {
            er(hartland, "BigSpender").text shouldBe
                "SELECT customer.sk FROM customer JOIN \"order\" ON \"order\".customer = customer.sk " +
                "GROUP BY customer.sk HAVING SUM(\"order\".\"value\") > 1000"
        }

        test("a count that holds on no rows (= 0) is a grouped derived table read through COALESCE") {
            er(hartland, "NoOrders").text shouldBe
                "SELECT customer.sk FROM customer LEFT JOIN (SELECT \"order\".customer, COUNT(*) AS n FROM \"order\" " +
                "GROUP BY \"order\".customer) count_order ON count_order.customer = customer.sk " +
                "WHERE COALESCE(count_order.n, 0) = 0"
        }

        test("a count inside a disjunction is a grouped derived table; the existence beside it stays EXISTS") {
            er(hartland, "BusyOrDisputing").text shouldBe
                "SELECT customer.sk FROM customer LEFT JOIN (SELECT \"order\".customer, COUNT(*) AS n FROM \"order\" " +
                "GROUP BY \"order\".customer) count_order ON count_order.customer = customer.sk " +
                "WHERE COALESCE(count_order.n, 0) >= 3 OR EXISTS (SELECT 1 FROM invoice " +
                "WHERE invoice.customer = customer.sk AND invoice.status = 'disputed')"
        }

        test("a defined quality that counts: the derived table is named after it") {
            er(hartland, "lines_count").text shouldBe
                "SELECT \"order\".order_number, COALESCE(lines_count.n, 0) AS lines_count FROM \"order\" LEFT JOIN " +
                "(SELECT sales_line.order_number, COUNT(*) AS n FROM sales_line GROUP BY sales_line.order_number) " +
                "lines_count ON lines_count.order_number = \"order\".order_number"
        }

        test("an aggregate whose where reads outside its rows, off the top conjunction, is refused (G-ER-3)") {
            refusal(er(hartland, "Undercovered")) shouldContain "a correlated scalar sub-query (G-ER-3)"
        }

        test("latest(… by:) with an as-of is bounded by {as_of} (A-CN-17); without one it is unbounded") {
            val bounded = er(hartland, "on_hand", RenderOptions(asOf = Instant.parse("2026-03-15T00:00:00Z")))
            bounded.text shouldContain "FROM inventory WHERE inventory.as_of_date <= {as_of}) on_hand"
            bounded.parametersRead shouldBe listOf(ParameterRead("as_of"))
            er(hartland, "on_hand").text shouldContain "FROM inventory) on_hand"
        }

        // ---- er: names, windows, words --------------------------------------------------------------------------

        test("a ranged row on the bearer's own entity gets a fresh alias; two keys onto one entity elide it") {
            er(hartland, "SiblingHeld").text shouldBe
                "SELECT \"order\".order_number FROM \"order\" WHERE EXISTS (SELECT 1 FROM \"order\" order_2 " +
                "WHERE order_2.customer = \"order\".customer AND order_2.state = 'held')"
        }

        test("a value kind is a column of the event's own entity (AM-27)") {
            er(hartland, "WebSale").text shouldBe
                "SELECT sales.sale_id FROM sales WHERE sales.channel = 'web'"
        }

        test("within(x, from:, to:) over constants is an inclusive range") {
            er(hartland, "FirstQuarterOrder").text shouldBe
                "SELECT \"order\".order_number FROM \"order\" WHERE \"order\".placed_date >= '2026-01-01' " +
                "AND \"order\".placed_date <= '2026-03-31'"
        }

        test("overlaps: each start against the other's end, the open end's NULL arm (A-CN-19)") {
            er(hartland, "DuringOutage").text shouldBe
                "SELECT \"order\".order_number FROM \"order\" WHERE EXISTS (SELECT 1 FROM warehouse_outage w " +
                "WHERE w.warehouse = \"order\".fulfilling_warehouse AND (\"order\".delivered_date IS NULL OR " +
                "\"order\".delivered_date >= w.from_date) AND w.to_date >= \"order\".placed_date)"
        }

        test("not in descendants(…) is NOT IN the closure") {
            val clinic = StatementLowerSpec.world("clinic", CLINIC)
            er(clinic, "NonIschemicRecord").text shouldBe
                "SELECT \"condition\".condition_id FROM \"condition\" WHERE \"condition\".code NOT IN " +
                "(SELECT code_closure.descendant_code FROM code_closure WHERE code_closure.ancestor_code = 'X20-X25')"
        }

        test("CERTAINLY over a month-precision onset without a stored bound degrades to the day, advised (G-ER-1)") {
            val clinic = StatementLowerSpec.world("clinic", CLINIC)
            val r = er(clinic, "PostStemiCertainly")
            r.text shouldContain "\"condition\".onset <= {as_of}"
            r.diagnostics.single().slug shouldBe CnDefinitionLints.IMPRECISE
            r.diagnostics.single().severity shouldBe Severity.ADVISORY
        }

        // ---- er: CONTEXT ----------------------------------------------------------------------------------------

        test("CONTEXT without candidates: the bearer itself, the detail rows joined and keyed") {
            er(
                hartland,
                "DebtorCustomer",
                RenderOptions(mode = RenderMode.CONTEXT, columns = listOf("due")),
            ).text shouldBe
                "SELECT invoice.invoice_id, invoice.due FROM customer JOIN invoice ON invoice.customer = customer.sk " +
                "WHERE invoice.status = 'open' AND invoice.due < {as_of}"
        }

        test("CONTEXT: candidates that share no participant with the bearer are refused") {
            val r =
                er(
                    hartland,
                    "OutOfStockReading",
                    RenderOptions(
                        mode = RenderMode.CONTEXT,
                        candidates = ConceptRef.newBuilder().setQname("cn.PendingOrder").build(),
                    ),
                )
            refusal(r) shouldContain "share no participant"
        }

        // ---- er: refusals ---------------------------------------------------------------------------------------

        test("a datetime difference is refused (G-ER-2); an instant ± a duration (G-ER-1)") {
            refusal(er(hartland, "SlowDelivery")) shouldBe
                "cn/definition-unreachable(er): a datetime difference — datetime arithmetic is not on the plan.v1 wire (G-ER-2)"
            refusal(er(hartland, "DueThisWeek")) shouldBe
                "cn/definition-unreachable(er): an instant + a duration — interval arithmetic (G-ER-1)"
        }

        test("the derived revenue hop makes the return-share definition renderable on ER") {
            er(hartland, "AbusiveReturner").text shouldContain "SUM(sales.ext_sales_price)"
        }

        test("an unknown concept is refused on the layer asked") {
            val r =
                CnConceptService(
                    hartland,
                ).definitionRender(ConceptRef.newBuilder().setQname("cn.Nope").build(), Layer.ER, "", RenderOptions())
            r.diagnostics.single().slug shouldBe "cn/definition-unreachable(er)"
        }

        // ---- md -------------------------------------------------------------------------------------------------

        test("md: a phase of an event on its own cubelet — a parameter is a placeholder in the style asked") {
            md(sales, "WestSale").text shouldBe "customer.region = {home_region}"
            md(sales, "WestSale").parametersRead shouldBe listOf(ParameterRead("home_region"))
            md(sales, "WestSale", RenderOptions(placeholderStyle = PlaceholderStyle.COLON)).text shouldBe
                "customer.region = :home_region"
        }

        test("md: a window's bounds are placeholders, window_lo derived (A-CN-22); a set; phases inlined") {
            val recent = md(sales, "RecentSale")
            recent.text shouldBe "time.day >= {window_lo} and time.day <= {as_of}"
            recent.parametersRead shouldBe
                listOf(ParameterRead("window_lo", "as_of - weeks(2)"), ParameterRead("as_of"))
            md(sales, "NamedSale").text shouldBe "customer.name in ('Kaufland', 'Lidl')"
            val both = md(sales, "WestSpringSale")
            both.text shouldBe
                "customer.region = {home_region} and time.day >= '2025-03-01' and time.day <= '2025-05-31'"
            both.definitionsInlined shouldBe listOf("cn.WestSale", "cn.SpringSale")
        }

        test("md: a value kind's phase through the reverse hop") {
            md(GoldenWorlds["channel-reverse"].cn, "WebSale").text shouldBe "channel.code = 'web'"
        }

        test("md: what md cannot say is refused naming the construct; a bearer carried by two cubelets too") {
            refusal(md(sales, "NotKaufland")) shouldContain "md has no complement"
            refusal(md(sales, "ActiveCustomer")) shouldContain "carried by no one cubelet"
            refusal(md(hartland, "DebtorCustomer")) shouldContain "cn/definition-unreachable(md)"
            refusal(md(sales, "WestSale", RenderOptions(mode = RenderMode.CONTEXT))) shouldContain
                "CONTEXT mode is an er rendering"
        }
    }) {
    companion object {
        val HARTLAND: Map<String, String> =
            mapOf(
                "plus/cn.ttrm" to
                    """
                    model cn
                    def phase BigSpender        { of: Customer, definition: ""${'"'}sum(Order involving this).order_value > 1000""${'"'} }
                    def phase NoOrders          { of: Customer, definition: ""${'"'}count(Order involving this) = 0""${'"'} }
                    def phase BusyOrDisputing   {
                        of: Customer,
                        definition: ""${'"'}count(Order involving this) >= 3 or exists Invoicing involving this as customer where invoice_status = 'disputed'""${'"'}
                    }
                    def quality lines_count     { of: Order, definition: ""${'"'}count(OrderLine involving this)""${'"'} }
                    def phase Undercovered      {
                        of: OrderLine,
                        definition: ""${'"'}count(Inventory involving this.item where quantity_on_hand < this.line_quantity) > 0 or line_quantity > 10""${'"'}
                    }
                    def phase SiblingHeld       { of: Order, definition: ""${'"'}exists Order involving this.customer where state = 'held'""${'"'} }
                    def phase FirstQuarterOrder { of: Order, definition: ""${'"'}within(placed_date, from: '2026-01-01', to: '2026-03-31')""${'"'} }
                    def phase DuringOutage      { of: Order, definition: ""${'"'}exists WarehouseOutage w involving this.fulfilling where w.period overlaps this.period""${'"'} }
                    def phase OutOfStockReading { of: Inventory, definition: ""${'"'}quantity_on_hand = 0""${'"'} }
                    def phase SlowDelivery      { of: Order, definition: ""${'"'}period.end - period.start > days(3)""${'"'} }
                    def phase DueThisWeek       { of: Invoicing, definition: ""${'"'}within(period.end, from: :as_of, to: :as_of + days(7))""${'"'} }
                    """.trimIndent(),
            )

        val CLINIC: Map<String, String> =
            mapOf(
                "plus/cn.ttrm" to
                    """
                    model cn
                    def phase NonIschemicRecord { of: ConditionRecord, definition: ""${'"'}code not in descendants(Diagnosis, 'X20-X25')""${'"'} }
                    """.trimIndent(),
            )

        /** The clinic's `RecordedOnset` (month-granular, `at_hi:`) compared without the DC-12 words (review-125 R125-4). */
        val STORED_BOUND: Map<String, String> =
            mapOf(
                "bound/cn.ttrm" to
                    """
                    model cn
                    def phase OnsetBefore { of: Patient, imprecision: certainly, definition: ""${'"'}exists RecordedOnset involving this where period.at <= :as_of""${'"'} }
                    def phase OnsetStrictlyBefore { of: Patient, imprecision: certainly, definition: ""${'"'}exists RecordedOnset involving this where period.at < :as_of""${'"'} }
                    def phase OnsetAfter { of: Patient, imprecision: certainly, definition: ""${'"'}exists RecordedOnset involving this where period.at >= :as_of""${'"'} }
                    def phase OnsetWindow { of: Patient, imprecision: certainly, definition: ""${'"'}exists RecordedOnset involving this where within(period.at, last: days(30))""${'"'} }
                    """.trimIndent(),
            )
    }
}
