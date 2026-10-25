// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.plan.v1.PlanNode
import org.tatrman.statement.v1.Statement
import org.tatrman.ttr.semantics.md.MD_CATALOG_VERSION
import org.tatrman.ttrp.resolve.MdRepo
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * CN-P3 S3.3 — `statementLower` branch by branch (T1–T5), beyond the goldens: each slot kind and how it reaches md, the
 * carrier choice, the time forms, the HAVING and order/limit compositions, the md renderer's coordinates and every
 * construct it refuses, and the statement's shape (only the group-by and a semi-join's key are group keys). Over the
 * `sales` world plus [PLUS] (a hop attribute, a second carrier's event, parameters, phases) and `hartland`.
 */
class StatementLowerSpec :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC)
        val sales = GoldenWorlds["sales"].cn
        val hartland = GoldenWorlds["hartland"].cn
        val reverse = GoldenWorlds["channel-reverse"].cn
        val plus = world("sales", PLUS)

        fun lower(
            model: ResolvedCnModel,
            vararg parts: String,
        ): LowerResult {
            val s = Statement.newBuilder().also { JsonFormat.parser().merge(parts.joinToString(", ", "{ ", " }"), it) }
            return CnConceptService(model, clock).statementLower(s.build())
        }

        fun refused(r: LowerResult): List<String> =
            r.diagnostics.filter { it.severity == Severity.ERROR }.map { "${it.slug} ${it.subject}" }

        fun lowered(r: LowerResult): LowerResult {
            refused(r) shouldBe emptyList()
            return r
        }

        val ask = "\"ask\": \"ASK_VALUE\""
        val revenue = "\"measures\": [{ \"qname\": \"cn.revenue\" }]"
        val y2026 = "\"time\": { \"periodCode\": \"2026\" }"
        val always = "\"time\": { \"allTime\": true }"

        fun groupBy(vararg q: String) = "\"groupBy\": [${q.joinToString(", ") { "{ \"qname\": \"$it\" }" }}]"

        fun filters(vararg f: String) = "\"filters\": [${f.joinToString(", ")}]"

        fun phase(
            q: String,
            negated: Boolean = false,
        ) = "{ \"phase\": { \"phase\": { \"qname\": \"$q\" }${if (negated) ", \"negated\": true" else ""} } }"

        fun member(
            q: String,
            v: String,
        ) =
            "{ \"member\": { \"concept\": { \"qname\": \"$q\" }, \"member\": { \"label\": \"$v\", \"resolvedValue\": \"$v\" } } }"

        fun quality(
            q: String,
            op: String,
            value: String,
        ) = "{ \"quality\": { \"quality\": { \"qname\": \"$q\" }, \"op\": \"$op\", \"value\": $value } }"

        fun asOf(day: String) = "\"asOf\": { \"value\": \"$day\", \"granularity\": \"DAY\" }"

        // ---- T1 — slots → md --------------------------------------------------------------------------------------

        test("T1: a role group-by frees its dimension's grain attribute (cn2md_role → md.Customer → Customer.name)") {
            val r = lowered(lower(sales, ask, revenue, y2026, groupBy("cn.Customer")))
            r.dotPath shouldBe "sales[customer.name: *, time.year: 2026].net @ sum"
            r.provenance.bindingPaths shouldContainExactly
                listOf(
                    BindingPath("measures[0]", "cn.revenue", "revenue_g", "md.net", Via.DIRECT),
                    BindingPath("groupBy[0]", "cn.Customer", "customer_g", "md.Customer", Via.DIRECT),
                )
            r.plan!!
                .aggregate.groupKeysList
                .map { it.name } shouldBe listOf("customer_name")
        }

        test("T1: a quality grounded on an md attribute frees that attribute (cn2md_quality → md.Customer.region)") {
            val r = lowered(lower(plus, ask, revenue, y2026, groupBy("cn.region")))
            r.dotPath shouldBe "sales[customer.region: *, time.year: 2026].net @ sum"
            r.provenance.bindingPaths[1] shouldBe
                BindingPath("groupBy[0]", "cn.region", "region_g", "md.Customer.region", Via.DIRECT)
        }

        test("T1: a value kind reaches md through the reverse hop — via MD_HOP, the er grounding's def (⚑CN-P8)") {
            val r = lowered(lower(reverse, ask, revenue, always, groupBy("cn.Channel")))
            r.provenance.bindingPaths shouldContainExactly
                listOf(
                    BindingPath("measures[0]", "cn.revenue", "revenue_g", "md.revenue", Via.DIRECT),
                    BindingPath("groupBy[0]", "cn.Channel", "channel_g", "md.Channel.code", Via.MD_HOP),
                )
        }

        test("T1: a measure as a group-by, an md attribute as a measure, a second measure — refused") {
            refused(lower(sales, ask, revenue, y2026, groupBy("cn.gross_revenue"))) shouldBe
                listOf("cn/not-analytically-reachable groupBy[0]")
            refused(lower(sales, ask, "\"measures\": [{ \"qname\": \"cn.sold_on\" }]", y2026)) shouldBe
                listOf("cn/not-analytically-reachable measures[0]")
            refused(
                lower(
                    sales,
                    ask,
                    "\"measures\": [{ \"qname\": \"cn.revenue\" }, { \"qname\": \"cn.gross_revenue\" }]",
                    y2026,
                ),
            ) shouldBe listOf("cn/not-analytically-reachable measures[1]")
        }

        // ---- T2 — the carrier -------------------------------------------------------------------------------------

        test("T2: `net` is carried by `plan` and `sales` — the grain event's cubelet (Sale → sales) wins") {
            lowered(lower(sales, ask, revenue, y2026)).dotPath shouldBe "sales[time.year: 2026].net @ sum"
        }

        test("T2: no grain event and two carriers — ambiguous, refused with both, never the first") {
            val r = lower(plus, ask, "\"measures\": [{ \"qname\": \"cn.anything_net\" }]", always)
            refused(r) shouldBe listOf("cn/ambiguous-statement measures[0]")
            r.diagnostics.single().message shouldContain "`plan`, `sales`"
        }

        test("T2: no cubelet carries the measure with the slots' dimensions — not analytically reachable") {
            refused(lower(plus, ask, revenue, y2026, filters(member("cn.Product", "P1")))) shouldBe
                listOf("cn/not-analytically-reachable measures[0]")
        }

        // ---- T3 — members, quality filters, time ------------------------------------------------------------------

        test("T3: the statement's shape — an unmentioned grain dimension is aggregated over, not grouped") {
            val r = lowered(lower(sales, ask, revenue, y2026))
            r.plan!!.aggregate.groupKeysList shouldBe emptyList()
        }

        test("T3: a quality filter `EQ` on an md attribute pins it; any other operator is refused") {
            lowered(
                lower(plus, ask, revenue, y2026, filters(quality("cn.region", "EQ", "{ \"stringValue\": \"West\" }"))),
            ).dotPath shouldBe "sales[customer.region: \"West\", time.year: 2026].net @ sum"
            refused(
                lower(plus, ask, revenue, y2026, filters(quality("cn.region", "NEQ", "{ \"stringValue\": \"West\" }"))),
            ) shouldBe
                listOf("cn/not-analytically-reachable filters[0].quality.op")
        }

        test("T3: a quality filter on the measure is a HAVING over the read; on another measure it is refused") {
            val r =
                lowered(
                    lower(
                        sales,
                        ask,
                        revenue,
                        y2026,
                        groupBy("cn.Customer"),
                        filters(quality("cn.revenue", "GT", "{ \"intValue\": \"100\" }")),
                    ),
                )
            r.dotPath shouldBe "sales[customer.name: *, time.year: 2026].net @ sum having net > 100"
            r.plan!!
                .filter.condition.function.operation shouldBe "gt"
            r.plan!!
                .filter.input
                .hasAggregate() shouldBe true
            r.provenance.bindingPaths.map { it.slot } shouldBe listOf("measures[0]", "filters[0]", "groupBy[0]")
            refused(
                lower(
                    sales,
                    ask,
                    revenue,
                    y2026,
                    filters(quality("cn.gross_revenue", "GT", "{ \"intValue\": \"1\" }")),
                ),
            ) shouldBe
                listOf("cn/not-analytically-reachable filters[0].quality.quality")
        }

        test(
            "T3: a quarter the model maps only through the month (a chained calc md does not lower) is its day range",
        ) {
            lowered(lower(sales, ask, revenue, "\"time\": { \"periodCode\": \"2026Q1\" }")).dotPath shouldBe
                "sales[time.day: \"2026-01-01\"..\"2026-03-31\"].net @ sum"
        }

        test("T3: a range is half-open — its md range ends the day before `to`") {
            val range =
                "\"time\": { \"range\": { \"from\": { \"value\": \"2026-01-01\", \"granularity\": \"DAY\" }, " +
                    "\"to\": { \"value\": \"2026-02-01\", \"granularity\": \"DAY\" } } }"
            lowered(lower(sales, ask, revenue, range)).dotPath shouldBe
                "sales[time.day: \"2026-01-01\"..\"2026-01-31\"].net @ sum"
        }

        test("T3: `last(n,unit)` counts back from `as_of` — else from the clock's day, recorded either way") {
            val last30 = "\"time\": { \"relative\": \"last(30,days)\" }"
            lowered(lower(sales, ask, revenue, last30, asOf("2026-01-31"))).let {
                it.dotPath shouldBe "sales[time.day: \"2026-01-01\"..\"2026-01-31\"].net @ sum"
                it.provenance.asOf shouldBe "2026-01-31"
            }
            lowered(lower(sales, ask, revenue, last30)).let {
                it.dotPath shouldBe "sales[time.day: \"2026-02-13\"..\"2026-03-15\"].net @ sum"
                it.provenance.asOf shouldBe "2026-03-15"
            }
            lowered(lower(sales, ask, revenue, y2026)).provenance.asOf shouldBe ""
        }

        test("T3: a working-day or a sub-day window, an hour-granular range, a dateless carrier — refused") {
            refused(lower(sales, ask, revenue, "\"time\": { \"relative\": \"last(5,working_days)\" }")) shouldBe
                listOf("cn/not-analytically-reachable time.relative")
            refused(lower(sales, ask, revenue, "\"time\": { \"relative\": \"last(5,hours)\" }")) shouldBe
                listOf("cn/not-analytically-reachable time.relative")
            val hours =
                "\"time\": { \"range\": { \"from\": { \"value\": \"2026-01-01T08\", \"granularity\": \"HOUR\" }, " +
                    "\"to\": { \"value\": \"2026-01-02T08\", \"granularity\": \"HOUR\" } } }"
            refused(lower(sales, ask, revenue, hours)) shouldBe listOf("cn/not-analytically-reachable time.range.from")
            // E-R reads `channel_sales` too (CN-P6 ⚑S62-1), but its `Sale` has no period there either — both lanes refuse
            refused(lower(reverse, ask, revenue, y2026)) shouldBe
                listOf("cn/not-analytically-reachable time", "cn/definition-unreachable(er) cn.Sale")
        }

        // ---- T4 — resolve, lower, compose, provenance ---------------------------------------------------------------

        test("T4: order_limit sorts and limits on the measure") {
            val ordered =
                "\"orderLimit\": { \"by\": [{ \"by\": { \"qname\": \"cn.revenue\" }, \"descending\": true }], " +
                    "\"limit\": 5 }"
            val r = lowered(lower(sales, ask, revenue, y2026, groupBy("cn.Customer"), ordered))
            r.dotPath shouldBe "sales[customer.name: *, time.year: 2026].net @ sum order by net desc limit 5"
            val plan = r.plan!!
            plan.limitOffset.limit shouldBe 5
            plan.limitOffset.input.sort.sortKeysList
                .single()
                .let {
                    it.column.name shouldBe "net"
                    it.descending shouldBe true
                }
            plan.limitOffset.input.sort.input
                .hasAggregate() shouldBe true
        }

        test("T4: a compare, an ANY / NOT group, an expression filter — refused; an ALL group conjoins") {
            refused(
                lower(sales, ask, revenue, y2026, "\"compare\": { \"periodCode\": \"2025\", \"mode\": \"DELTA\" }"),
            ) shouldBe
                listOf("cn/not-analytically-reachable compare")
            val any = "{ \"group\": { \"op\": \"ANY\", \"filters\": [${member("cn.Customer", "Lidl")}] } }"
            refused(lower(sales, ask, revenue, y2026, filters(any))) shouldBe
                listOf("cn/not-analytically-reachable filters[0].group")
            refused(
                lower(sales, ask, revenue, y2026, filters("{ \"expr\": { \"plExpr\": \"revenue > 1\" } }")),
            ) shouldBe
                listOf("cn/not-analytically-reachable filters[0].expr")
            val all = "{ \"group\": { \"op\": \"ALL\", \"filters\": [${member("cn.Customer", "Lidl")}] } }"
            lowered(lower(sales, ask, revenue, y2026, filters(all))).let {
                it.dotPath shouldBe "sales[customer.name: \"Lidl\", time.year: 2026].net @ sum"
                it.provenance.bindingPaths[1].slot shouldBe "filters[0].group.filters[0]"
            }
        }

        test("T4: an md attribute whose name another dimension shares cannot be constrained by name — refused") {
            refused(
                lower(
                    plus,
                    ask,
                    revenue,
                    y2026,
                    filters(quality("cn.customer_code", "EQ", "{ \"stringValue\": \"K1\" }")),
                ),
            ) shouldBe
                listOf("cn/not-analytically-reachable measures[0]")
        }

        test("T4: the provenance carries the snapshot, the grammar version and the md catalog version") {
            val p = lowered(lower(sales, ask, revenue, y2026)).provenance
            p.snapshotHash shouldBe "golden:sales"
            val g4 = DefinitionFixtures.repo.resolve("packages/grammar/src/TTR.g4").readText()
            p.grammarVersion shouldBe Regex("@grammar-version:\\s*(\\S+)").find(g4)!!.groupValues[1]
            p.catalogVersion shouldBe MD_CATALOG_VERSION
            p.imprecisionMode shouldBe ImprecisionMode.CERTAINLY
        }

        test("T4: a model with no md tier refuses; a frame the validate refuses lowers to no plan") {
            refused(lower(sales.copy(md = null), ask, revenue, y2026)) shouldBe
                listOf("cn/not-analytically-reachable measures[0]")
            lower(sales, ask, revenue).let {
                it.plan shouldBe null
                refused(it) shouldBe listOf("cn/missing-time time")
            }
        }

        // ---- T5 — the md renderer -----------------------------------------------------------------------------------

        test("T5: `region = :home_region` pins the attribute to the world's value, recorded") {
            val r = lowered(lower(plus, ask, revenue, y2026, filters(phase("cn.WestSale"))))
            r.dotPath shouldBe "sales[customer.region: \"West\", time.year: 2026].net @ sum"
            r.provenance.parametersBound shouldBe mapOf("home_region" to "West")
            r.provenance.inlinedDefinitions shouldBe listOf("cn.WestSale")
            r.provenance.bindingPaths[1] shouldBe
                BindingPath("filters[0]", "cn.region", "region_g", "md.Customer.region", Via.DIRECT)
        }

        test("T5: `x >= a and x <= b` and `within(x, from:, to:)` are one inclusive range") {
            for (p in listOf("cn.SpringSale", "cn.WindowSale")) {
                lowered(lower(plus, ask, revenue, always, filters(phase(p)))).dotPath shouldBe
                    "sales[time.day: \"2025-03-01\"..\"2025-05-31\"].net @ sum"
            }
        }

        test("T5: `within(period.at, last: weeks(2))` counts back from `as_of`") {
            val r = lowered(lower(plus, ask, revenue, always, asOf("2026-01-31"), filters(phase("cn.RecentSale"))))
            r.dotPath shouldBe "sales[time.day: \"2026-01-17\"..\"2026-01-31\"].net @ sum"
            r.provenance.asOf shouldBe "2026-01-31"
        }

        test("a phase filter and the time on one attribute: cn names the clash, not the md resolver (R125-8)") {
            val range =
                "\"time\": { \"range\": { \"from\": { \"value\": \"2026-01-01\", \"granularity\": \"DAY\" }, " +
                    "\"to\": { \"value\": \"2026-02-01\", \"granularity\": \"DAY\" } } }"
            val r = lower(plus, ask, revenue, range, asOf("2026-01-31"), filters(phase("cn.RecentSale")))
            refused(r) shouldBe listOf("cn/not-analytically-reachable filters[0].phase.phase")
            r.diagnostics.single().message shouldContain "`cn.RecentSale` constrains `time.day`"
            r.diagnostics.single().message shouldNotContain "MD-006"
        }

        test("T5: `customer in (…)` is a member set on the participant's grain attribute") {
            val r = lowered(lower(plus, ask, revenue, y2026, filters(phase("cn.NamedSale"))))
            r.dotPath shouldBe "sales[customer.name: {\"Kaufland\", \"Lidl\"}, time.year: 2026].net @ sum"
            r.provenance.bindingPaths[1] shouldBe
                BindingPath("filters[0]", "cn.Customer", "customer_g", "md.Customer", Via.DIRECT)
        }

        test("T5: a phase naming phases inlines them, transitively, in order") {
            val r = lowered(lower(plus, ask, revenue, always, filters(phase("cn.WestSpringSale"))))
            r.provenance.inlinedDefinitions shouldBe listOf("cn.WestSpringSale", "cn.WestSale", "cn.SpringSale")
            r.dotPath shouldBe "sales[customer.region: \"West\", time.day: \"2025-03-01\"..\"2025-05-31\"].net @ sum"
        }

        for ((ph, says) in mapOf(
            "cn.NotKaufland" to "`<>`",
            "cn.OnlyAfter" to "one-sided",
            "cn.Unvalued" to "`:unvalued` has no value",
            "cn.Planned" to "(G-MD-1)",
            "cn.Lapsed" to "`not exists`",
            "cn.Frequent" to "(G-MD-7)",
            "cn.LargeSale" to "the measure `cn.revenue`",
        )) {
            test("T5: `$ph` cannot render on md — refused with the cause, naming $says") {
                val r = lower(plus, ask, revenue, y2026, filters(phase(ph)))
                refused(r) shouldBe
                    listOf(
                        "cn/not-analytically-reachable filters[0].phase.phase",
                        "cn/definition-unreachable(md) filters[0].phase.phase",
                    )
                r.diagnostics.first().message shouldContain says
            }
        }

        test("T5: a negated phase needs a complement — refused") {
            refused(lower(plus, ask, revenue, y2026, filters(phase("cn.WestSale", negated = true)))) shouldBe
                listOf("cn/not-analytically-reachable filters[0].phase.phase")
        }

        test("⚑CN-P9: grouped by the existence's key, the semi-join keeps it — no second aggregate") {
            val r =
                lowered(
                    lower(
                        sales,
                        ask,
                        revenue,
                        "\"time\": { \"periodCode\": \"2026-01\" }",
                        asOf("2026-01-31"),
                        groupBy("cn.Customer"),
                        filters(phase("cn.ActiveCustomer")),
                    ),
                )
            r.dotPath shouldBe
                "sales[customer.name: *, time.month: 1, time.year: 2026].net @ sum where customer.name in " +
                "(sales[customer.name: *, time.day: \"2025-01-31\"..\"2026-01-31\"].net @ sum)"
            r.plan!!.nodeCase shouldBe PlanNode.NodeCase.FILTER
        }

        test("⚑CN-P9: with no `as_of` the window counts back from the clock; a pinned key is refused") {
            val jan = "\"time\": { \"periodCode\": \"2026-01\" }"
            val r = lowered(lower(sales, ask, revenue, jan, filters(phase("cn.ActiveCustomer"))))
            r.dotPath shouldContain "time.day: \"2025-03-15\"..\"2026-03-15\""
            r.provenance.asOf shouldBe "2026-03-15"
            refused(
                lower(sales, ask, revenue, jan, filters(phase("cn.ActiveCustomer"), member("cn.Customer", "Lidl"))),
            ) shouldBe
                listOf("cn/not-analytically-reachable filters[0].phase.phase")
        }
    }) {
    companion object {
        /** `sales` plus what the branches need. Invented, like the world. */
        val PLUS: Map<String, String> =
            mapOf(
                "plus/cn.ttrm" to
                    """
                    model cn
                    def kind Product { label: { en: "Product" } }
                    def quality region { of: Sale }
                    def quality customer_code { of: Sale }
                    def quality anything_net { of: Customer }
                    def event Plan { involves: [Customer] }
                    def phase WestSale       { of: Sale, definition: ""${'"'}region = :home_region""${'"'} }
                    def phase SpringSale     { of: Sale, definition: ""${'"'}sold_on >= '2025-03-01' and sold_on <= '2025-05-31'""${'"'} }
                    def phase WindowSale     { of: Sale, definition: ""${'"'}within(sold_on, from: '2025-03-01', to: '2025-05-31')""${'"'} }
                    def phase RecentSale     { of: Sale, definition: ""${'"'}within(period.at, last: weeks(2))""${'"'} }
                    def phase NamedSale      { of: Sale, definition: ""${'"'}customer in ('Kaufland', 'Lidl')""${'"'} }
                    def phase WestSpringSale { of: Sale, definition: ""${'"'}WestSale and SpringSale""${'"'} }
                    def phase NotKaufland    { of: Sale, definition: ""${'"'}customer <> 'Kaufland'""${'"'} }
                    def phase OnlyAfter      { of: Sale, definition: ""${'"'}sold_on >= '2025-03-01'""${'"'} }
                    def phase Unvalued       { of: Sale, definition: ""${'"'}region = :unvalued""${'"'} }
                    def phase Planned        { of: Customer, definition: ""${'"'}exists Plan involving this""${'"'} }
                    def phase Lapsed         { of: Customer, definition: ""${'"'}not exists Sale involving this where within(period.at, last: days(90))""${'"'} }
                    def phase Frequent       { of: Customer, definition: ""${'"'}count(Sale involving this) >= 2""${'"'} }
                    """.trimIndent(),
                "plus/bindings.ttrm" to
                    """
                    model binding
                    def cn2md_kind    product_g       { kind: cn.Product, target: md.Product }
                    def cn2md_quality region_g        { quality: cn.region, target: md.Customer.region }
                    def cn2md_quality customer_code_g { quality: cn.customer_code, target: md.Customer.code }
                    def cn2md_quality anything_net_g  { quality: cn.anything_net, target: md.net }
                    def cn2md_event   plan_g          { event: cn.Plan, target: md.plan }
                    """.trimIndent(),
                "plus/automation.ttrm" to
                    """
                    model automation
                    def parameter home_region { type: text, value: "West" }
                    def parameter unvalued { type: text }
                    """.trimIndent(),
            )

        /** A golden world plus [extra] files, loaded with its md tier (one temp root, so `MdRepo` reads them too). */
        fun world(
            name: String,
            extra: Map<String, String>,
        ): ResolvedCnModel {
            val src = GoldenWorlds.root.resolve(name)
            val dir = Files.createTempDirectory("cn-lower")
            Files.walk(src).use { s -> s.filter { it.isRegularFile() && it.extension == "ttrm" }.toList() }.forEach {
                val to = dir.resolve(src.relativize(it).toString())
                Files.createDirectories(to.parent)
                Files.copy(it, to)
            }
            for ((rel, text) in extra) {
                val to = dir.resolve(rel)
                Files.createDirectories(to.parent)
                Files.writeString(to, text)
            }
            val files = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.sorted().toList() }
            return ResolvedCnModel.of(
                DefinitionFixtures.load(files.associateBy { dir.relativize(it).toString() }),
                snapshotHash = "unit:$name+",
                md = MdRepo.loadFrom(dir),
            )
        }
    }
}
