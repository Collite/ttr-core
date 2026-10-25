// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.statement.v1.Statement
import org.tatrman.ttr.metadata.model.Concept
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * CN-P6 ⚑S62-1 (A-CN-101) — the er lane beyond the goldens (`goldens/er-lane`, `goldens/channel`): md first, E-R only when
 * md carries no single read; every placeholder a phase reads bound (`:as_of`, a `:name`, a derived window bound); what the
 * lane refuses; and `statementValidate` predicting it.
 */
class ErStatementLaneSpec :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC)
        val sales = GoldenWorlds["sales"].cn
        val hartland = GoldenWorlds["hartland"].cn
        val day = LocalDate.of(2026, 10, 25)

        fun statement(vararg parts: String): Statement =
            Statement.newBuilder().also { JsonFormat.parser().merge(parts.joinToString(", ", "{ ", " }"), it) }.build()

        fun lower(
            model: ResolvedCnModel,
            vararg parts: String,
        ): LowerResult = CnConceptService(model, clock).statementLower(statement(*parts))

        fun errors(ds: List<Diagnostic>): List<String> =
            ds
                .filter {
                    it.severity == Severity.ERROR
                }.map { "${it.slug} ${it.subject}" }

        fun concept(
            model: ResolvedCnModel,
            name: String,
        ): Concept =
            model.cn.concepts.values
                .single { it.qname.name == name }

        val ask = "\"ask\": \"ASK_VALUE\""
        val revenue = "\"measures\": [{ \"qname\": \"cn.revenue\" }]"
        val jan = "\"time\": { \"periodCode\": \"2026-01\" }"
        val web = "\"filters\": [{ \"phase\": { \"phase\": { \"qname\": \"cn.WebSale\" } } }]"

        test("a statement md carries stays on md — the er lane is the fallback, never the first") {
            val r = lower(sales, ask, revenue, "\"time\": { \"periodCode\": \"2026-01\" }")
            errors(r.diagnostics) shouldBe emptyList()
            r.erSql shouldBe ""
            (r.dotPath.isNotEmpty() && r.plan != null) shouldBe true
            r.diagnostics.none { it.slug == FrameSlugs.LOWERED_ON_ER } shouldBe true
        }

        test("md's carrier ambiguity falls to E-R, and the advisory says why md did not read it") {
            val r = lower(hartland, ask, revenue, jan)
            errors(r.diagnostics) shouldBe emptyList()
            r.plan shouldBe null
            r.erSql shouldContain "FROM sales"
            val why = r.diagnostics.single { it.slug == FrameSlugs.LOWERED_ON_ER }
            why.severity shouldBe Severity.ADVISORY
            why.message shouldContain "`storeSales`"
            why.message shouldContain "over `er.entity.sales`"
            r.provenance.bindingPaths
                .single()
                .element shouldBe "er.entity.sales.ext_sales_price"
        }

        test("a phase reading `:as_of` binds it to the evaluation day; the provenance records the day") {
            val r =
                ErRenderer(hartland).statement(
                    ErStatement(
                        event = concept(hartland, "Invoicing"),
                        measures = listOf(ErMeasure(concept(hartland, "amount"), "SUM")),
                        phases = listOf(ErPhase(concept(hartland, "OverdueInvoice"))),
                        asOf = { day },
                    ),
                )
            errors(r.diagnostics) shouldBe emptyList()
            r.text shouldContain "invoice.due < DATE '2026-10-25'"
            r.text shouldNotContain "{as_of}"
            r.parametersRead.map { it.name } shouldBe listOf("as_of")
        }

        test("a `:name` a phase reads binds from the world's `def parameter`; one with no value refuses") {
            val line = concept(hartland, "OrderLine")
            val spec =
                ErStatement(
                    event = line,
                    measures = listOf(ErMeasure(concept(hartland, "line_quantity"), "SUM")),
                    phases = listOf(ErPhase(concept(hartland, "MispricedLine"))),
                    asOf = { day },
                )
            val bound = ErRenderer(hartland).statement(spec.copy(values = mapOf("tolerance" to "0.01")))
            errors(bound.diagnostics) shouldBe emptyList()
            bound.text shouldContain "> 0.01"
            val unbound = ErRenderer(hartland).statement(spec)
            unbound.text shouldBe ""
            // the phase's slot, as md's pair: the read refused, and the phase's definition does not render (E8)
            unbound.diagnostics.map { "${it.slug} ${it.subject}" } shouldBe
                listOf(
                    "cn/not-analytically-reachable filters[0].phase.phase",
                    "cn/definition-unreachable(er) filters[0].phase.phase",
                )
            unbound.diagnostics.first().message shouldContain "`:tolerance` has no value"
        }

        test("a derived window bound (`within(…, last: days(n))`) is computed from the evaluation day") {
            val recent =
                StatementLowerSpec.world(
                    "calendar-key",
                    mapOf(
                        "recent.ttrm" to
                            """
                            model cn
                            def quality channel_name { of: Sale, grounds: er.entity.sales.channel }
                            def phase RecentWebSale { of: Sale, definition: ${"\"\"\""}channel_name = 'web' and within(period.at, last: days(30))${"\"\"\""} }
                            """.trimIndent(),
                    ),
                )
            val r =
                ErRenderer(recent).statement(
                    ErStatement(
                        event = concept(recent, "Sale"),
                        measures = listOf(ErMeasure(concept(recent, "revenue"), "SUM")),
                        phases = listOf(ErPhase(concept(recent, "RecentWebSale"))),
                        asOf = { day },
                    ),
                )
            errors(r.diagnostics) shouldBe emptyList()
            // review-154 E6: the window reads the calendar's date, never the int key against a DATE
            r.text shouldContain "date_dim.cal_date >= DATE '2026-09-25'"
            r.text shouldContain "date_dim.cal_date <= DATE '2026-10-25'"
            r.text shouldNotContain "sales.sold_date >= DATE"
            r.parametersRead.single { it.name.startsWith("window_lo") }.derivation shouldBe "as_of - days(30)"
        }

        test("a phase of another event refuses — the read is one event's rows") {
            val r =
                ErRenderer(hartland).statement(
                    ErStatement(
                        event = concept(hartland, "Sale"),
                        measures = listOf(ErMeasure(concept(hartland, "revenue"), "SUM")),
                        phases = listOf(ErPhase(concept(hartland, "OverdueInvoice"))),
                        asOf = { day },
                    ),
                )
            r.text shouldBe ""
            r.diagnostics.single().message shouldContain "does not land on the event's `sales`"
            r.diagnostics.single().subject shouldBe "filters[0].phase.phase"
        }

        test("an ANY group, a compare, or an E-R-only measure is refused by both lanes, md's findings first") {
            val any =
                lower(
                    hartland,
                    ask,
                    revenue,
                    jan,
                    "\"filters\": [{ \"group\": { \"op\": \"ANY\", \"filters\": [" +
                        "{ \"phase\": { \"phase\": { \"qname\": \"cn.WebSale\" } } }, " +
                        "{ \"phase\": { \"phase\": { \"qname\": \"cn.StoreSale\" } } }] } }]",
                )
            any.erSql shouldBe ""
            errors(any.diagnostics).last() shouldBe "cn/not-analytically-reachable filters[0].group"
            val orderValue =
                lower(
                    hartland,
                    ask,
                    "\"measures\": [{ \"qname\": \"cn.order_value\" }]",
                    "\"time\": { \"allTime\": true }",
                )
            orderValue.erSql shouldBe ""
            // one finding for both lanes — md's, with E-R's reason folded in
            errors(orderValue.diagnostics) shouldBe listOf("cn/not-analytically-reachable measures[0]")
            orderValue.diagnostics.single().message shouldContain "— and on E-R: `cn.order_value` grounds no md measure"
        }

        test("statementValidate predicts the er lane — md's reach findings give way to its advisory, and only then") {
            val svc = CnConceptService(hartland, clock)
            svc.statementValidate(statement(ask, revenue, jan, web)).map { it.slug } shouldBe
                listOf(FrameSlugs.LOWERED_ON_ER)
            errors(
                svc.statementValidate(
                    statement(
                        ask,
                        "\"measures\": [{ \"qname\": \"cn.order_value\" }]",
                        "\"time\": { \"allTime\": true }",
                    ),
                ),
            ) shouldBe
                listOf("cn/not-analytically-reachable measures[0]")
        }

        // ---- review-154: what the er lane refuses, and how it reads ----------------------------------------------------

        val parity = GoldenWorlds["er-parity"].cn
        val y2026 = "\"time\": { \"periodCode\": \"2026\" }"

        fun measure(q: String) = "\"measures\": [{ \"qname\": \"$q\" }]"

        fun phase(
            q: String,
            negated: Boolean = false,
        ) =
            "\"filters\": [{ \"phase\": { \"phase\": { \"qname\": \"$q\" }${if (negated) ", \"negated\": true" else ""} } }]"

        fun quality(
            q: String,
            op: String,
            value: String,
        ) = "\"filters\": [{ \"quality\": { \"quality\": { \"qname\": \"$q\" }, \"op\": \"$op\", \"value\": $value } }]"

        fun member(
            q: String,
            v: String,
        ) =
            "\"filters\": [{ \"member\": { \"concept\": { \"qname\": \"$q\" }, \"member\": { \"label\": \"$v\", \"resolvedValue\": \"$v\" } } }]"

        fun instant(
            v: String,
            g: String,
        ) = "{ \"instantValue\": { \"value\": \"$v\", \"granularity\": \"$g\" } }"

        test(
            "E1: a non-additive measure with no aggregation and a semi-additive one are refused; a declared `max` reads",
        ) {
            val unit = lower(parity, ask, measure("cn.unit_price"), y2026, phase("cn.WebSale"))
            unit.erSql shouldBe ""
            unit.diagnostics.single { it.subject == "measures[0]" }.message shouldContain
                "on E-R: `cn.unit_price` is non-additive and declares no aggregation"
            val stock = lower(parity, ask, measure("cn.stock"), y2026, phase("cn.WebSale"))
            stock.erSql shouldBe ""
            stock.diagnostics.single { it.subject == "measures[0]" }.message shouldContain
                "aggregates per dimension (`Time: latestValid`)"
            val top = lower(parity, ask, measure("cn.top_price"), y2026, phase("cn.WebSale"))
            errors(top.diagnostics) shouldBe emptyList()
            top.erSql shouldContain "MAX(sale.top_price) AS top_price"
        }

        test("E2: a statement caps every `latest(…, by:)` at its evaluation day — the md lane's `asOfDay` parity") {
            val r =
                ErRenderer(hartland).statement(
                    ErStatement(
                        event = concept(hartland, "OrderLine"),
                        measures = listOf(ErMeasure(concept(hartland, "line_quantity"), "SUM")),
                        phases = listOf(ErPhase(concept(hartland, "ShortLine"))),
                        asOf = { LocalDate.of(2026, 1, 31) },
                    ),
                )
            errors(r.diagnostics) shouldBe emptyList()
            r.text shouldContain "inventory.as_of_date <= DATE '2026-01-31'"
            r.parametersRead.map { it.name } shouldContain "as_of"
        }

        test("E4: a join-free negated phase is two-valued; a negated phase that joins is refused on its slot") {
            val web = lower(parity, ask, measure("cn.revenue"), y2026, phase("cn.WebSale", negated = true))
            errors(web.diagnostics) shouldBe emptyList()
            web.erSql shouldContain "WHERE NOT COALESCE(sale.channel = 'web', FALSE)"
            val west = lower(parity, ask, measure("cn.revenue"), y2026, phase("cn.WestSale", negated = true))
            west.erSql shouldBe ""
            errors(west.diagnostics) shouldBe
                listOf(
                    "cn/definition-unreachable(md) filters[0].phase.phase",
                    "cn/not-analytically-reachable filters[0].phase.phase",
                )
            west.diagnostics.last().message shouldContain
                "— and on E-R: a negated phase (`not cn.WestSale`) that joins `customer`"
        }

        test(
            "E4: revenue where WebSale plus revenue where not WebSale is the total — a NULL channel counts as not web (live)",
        ).config(enabled = LivePg.connection() != null) {
            val db = LivePg.connection()!!
            val model = parity.model
            val seed =
                LivePg.ddl(model) + "\n" +
                    GoldenWorlds["er-parity"]
                        .dir
                        .resolve("live-seed.sql")
                        .toFile()
                        .readText()

            fun sum(vararg parts: String): java.math.BigDecimal {
                val r = lower(parity, ask, measure("cn.revenue"), y2026, *parts)
                errors(r.diagnostics) shouldBe emptyList()
                val sql =
                    if (r.erSql.isNotEmpty()) {
                        r.erSql
                    } else {
                        // md carries the plain read; its er twin, for one database
                        "SELECT SUM(sale.ext_price) AS revenue FROM sale WHERE sale.sold_on >= DATE '2026-01-01' " +
                            "AND sale.sold_on < DATE '2027-01-01'"
                    }
                val (unparsed, params) = ErSql.unparse(model, sql, org.tatrman.translate.v1.SqlDialect.POSTGRESQL)
                return java.math.BigDecimal(db.run(seed, unparsed, params).single().single()!!)
            }
            val total = sum()
            val web = sum(phase("cn.WebSale"))
            val notWeb = sum(phase("cn.WebSale", negated = true))
            total.compareTo(java.math.BigDecimal("525.00")) shouldBe 0
            (web + notWeb).compareTo(total) shouldBe 0
            notWeb.compareTo(java.math.BigDecimal("385.00")) shouldBe 0
        }

        test("E5: a phase joining a relation from its many side is refused; a forward hop still reads") {
            val dhl = lower(parity, ask, measure("cn.revenue"), y2026, phase("cn.DhlSale"))
            dhl.erSql shouldBe ""
            dhl.diagnostics.last().message shouldContain "joins `rel_shipment_sale` from its many side"
            val west = lower(parity, ask, measure("cn.revenue"), y2026, phase("cn.WestSale"))
            errors(west.diagnostics) shouldBe emptyList()
            west.erSql shouldContain "JOIN customer ON customer.sk = sale.customer"
        }

        test("E6: a window phase and the statement's time on a calendar key — one calendar join, the day both times") {
            val recent =
                StatementLowerSpec.world(
                    "calendar-key",
                    mapOf(
                        "recent.ttrm" to
                            """
                            model cn
                            def phase RecentSale { of: Sale, definition: ${"\"\"\""}within(period.at, last: days(30))${"\"\"\""} }
                            """.trimIndent(),
                    ),
                )
            val r =
                lower(
                    recent,
                    ask,
                    revenue,
                    jan,
                    "\"asOf\": { \"value\": \"2026-01-31\", \"granularity\": \"DAY\" }",
                    phase("cn.RecentSale"),
                )
            errors(r.diagnostics) shouldBe emptyList()
            Regex("JOIN date_dim").findAll(r.erSql).count() shouldBe 1
            r.erSql shouldContain "date_dim.cal_date >= DATE '2026-01-01' AND date_dim.cal_date <= DATE '2026-01-31'"
            r.erSql shouldNotContain "sales.sold_date >= DATE"
        }

        test("E6: an event whose period is declared on the concept alone — the statement's time reads it") {
            val cnOnly =
                StatementLowerSpec.world(
                    "calendar-key",
                    mapOf(
                        "cn-only.ttrm" to
                            """
                            model cn
                            def event Booking { period: { at: booked_on }, qualities: [booked_revenue, booked_on] }
                            def quality booked_revenue { of: Booking }
                            def quality booked_on { of: Booking }
                            """.trimIndent(),
                        "cn-only-bindings.ttrm" to
                            """
                            model binding
                            def cn2er_event booking_g { event: cn.Booking, target: er.entity.sales }
                            def cn2md_quality booked_revenue_g { quality: cn.booked_revenue, target: md.revenue }
                            def cn2er_quality booked_on_g { quality: cn.booked_on, target: er.entity.sales.sold_date }
                            """.trimIndent(),
                    ),
                )
            val r = lower(cnOnly, ask, "\"measures\": [{ \"qname\": \"cn.booked_revenue\" }]", jan)
            errors(r.diagnostics) shouldBe emptyList()
            r.erSql shouldContain "JOIN date_dim ON date_dim.sk = sales.sold_date"
            r.erSql shouldContain "date_dim.cal_date >= DATE '2026-01-01'"
        }

        test("E7: a narrower sub-concept is refused as a group-by and as a member; the participant itself reads") {
            val by =
                lower(parity, ask, measure("cn.revenue"), y2026, "\"groupBy\": [{ \"qname\": \"cn.VipCustomer\" }]")
            by.erSql shouldBe ""
            errors(by.diagnostics) shouldBe listOf("cn/not-analytically-reachable groupBy[0]")
            by.diagnostics.single().message shouldContain "— and on E-R: `cn.VipCustomer` narrows `cn.Customer`"
            val vip = lower(parity, ask, measure("cn.revenue"), y2026, member("cn.VipCustomer", "1"))
            vip.erSql shouldBe ""
            vip.diagnostics.map { it.message }.joinToString() shouldContain "narrows `cn.Customer`"
            val customer =
                lower(
                    parity,
                    ask,
                    measure("cn.revenue"),
                    y2026,
                    "\"groupBy\": [{ \"qname\": \"cn.Customer\" }]",
                    phase("cn.WebSale"),
                )
            errors(customer.diagnostics) shouldBe emptyList()
            customer.erSql shouldContain "GROUP BY sale.customer"
        }

        test(
            "E8: every er-lane refusal names its frame slot — a group-by, a member, the time, a phase; never a concept",
        ) {
            val slots =
                listOf(
                    lower(
                        parity,
                        ask,
                        measure("cn.revenue"),
                        y2026,
                        "\"groupBy\": [{ \"qname\": \"cn.VipCustomer\" }]",
                    ),
                    lower(parity, ask, measure("cn.revenue"), y2026, member("cn.VipCustomer", "1")),
                    lower(GoldenWorlds["channel-reverse"].cn, ask, revenue, y2026),
                    lower(parity, ask, measure("cn.revenue"), y2026, phase("cn.DhlSale")),
                ).map { r -> errors(r.diagnostics).map { it.substringAfter(' ') } }
            slots shouldBe
                listOf(
                    listOf("groupBy[0]"),
                    listOf("filters[0].member.concept"),
                    listOf("time"),
                    listOf("filters[0].phase.phase", "filters[0].phase.phase"),
                )
            slots.flatten().none { it.startsWith("cn.") } shouldBe true
        }

        test("E9: an instant in a quality filter is its interval, compared by its bounds — never a short TIMESTAMP") {
            fun sql(vararg parts: String): String {
                val r = lower(parity, ask, measure("cn.revenue"), y2026, *parts)
                errors(r.diagnostics) shouldBe emptyList()
                return r.erSql
            }
            sql(quality("cn.due_on", "EQ", instant("2026-01", "MONTH"))) shouldContain
                "sale.due_on >= DATE '2026-01-01' AND sale.due_on < DATE '2026-02-01'"
            sql(quality("cn.due_on", "LT", instant("2026", "YEAR"))) shouldContain "sale.due_on < DATE '2026-01-01'"
            sql(quality("cn.due_on", "NEQ", instant("2026-01", "MONTH"))) shouldContain
                "(sale.due_on < DATE '2026-01-01' OR sale.due_on >= DATE '2026-02-01')"
            sql(quality("cn.created_at", "EQ", instant("2026-01-15", "DAY"))) shouldContain
                "sale.created_at >= TIMESTAMP '2026-01-15 00:00:00' AND sale.created_at < TIMESTAMP '2026-01-16 00:00:00'"
            val hour = sql(quality("cn.created_at", "GTE", instant("2026-01-15T10", "HOUR")))
            hour shouldContain "sale.created_at >= TIMESTAMP '2026-01-15 10:00:00'"
            Regex("TIMESTAMP '[^']{1,18}'").find(hour) shouldBe null
        }

        test(
            "E10: a member is the key the event holds — digits for an int key, a value it cannot take a slot mismatch",
        ) {
            val sku = lower(hartland, ask, revenue, jan, member("cn.Item", "SKU-1"))
            sku.erSql shouldBe ""
            errors(sku.diagnostics) shouldContain "cn/slot-mismatch filters[0].member.member"
            val item = lower(hartland, ask, revenue, jan, member("cn.Item", "1017"))
            errors(item.diagnostics) shouldBe emptyList()
            item.erSql shouldContain "sales.item = 1017"
        }
    })
