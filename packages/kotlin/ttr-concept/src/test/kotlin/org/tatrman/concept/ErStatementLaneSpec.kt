// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
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
                        phases = listOf(concept(hartland, "OverdueInvoice") to false),
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
                    phases = listOf(concept(hartland, "MispricedLine") to false),
                    asOf = { day },
                )
            val bound = ErRenderer(hartland).statement(spec.copy(values = mapOf("tolerance" to "0.01")))
            errors(bound.diagnostics) shouldBe emptyList()
            bound.text shouldContain "> 0.01"
            val unbound = ErRenderer(hartland).statement(spec)
            unbound.text shouldBe ""
            unbound.diagnostics.single().message shouldContain "`:tolerance` has no value"
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
                        phases = listOf(concept(recent, "RecentWebSale") to false),
                        asOf = { day },
                    ),
                )
            errors(r.diagnostics) shouldBe emptyList()
            r.text shouldContain "DATE '2026-09-25'"
            r.text shouldContain "DATE '2026-10-25'"
            r.parametersRead.single { it.name.startsWith("window_lo") }.derivation shouldBe "as_of - days(30)"
        }

        test("a phase of another event refuses — the read is one event's rows") {
            val r =
                ErRenderer(hartland).statement(
                    ErStatement(
                        event = concept(hartland, "Sale"),
                        measures = listOf(ErMeasure(concept(hartland, "revenue"), "SUM")),
                        phases = listOf(concept(hartland, "OverdueInvoice") to false),
                        asOf = { day },
                    ),
                )
            r.text shouldBe ""
            r.diagnostics.single().message shouldContain "does not land on the event's `sales`"
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
    })
