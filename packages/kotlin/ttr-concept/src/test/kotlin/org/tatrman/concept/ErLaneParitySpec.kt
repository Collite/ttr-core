// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.statement.v1.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText

/**
 * review-154 E0 (⚑R152-a) — **the er lane places; it does not re-decide.** One row per md refusal: every `refuse(` of
 * `StatementLowerer.Lowering` and of the rules both lanes share (`relativeStart`, `instantDay`) — **27**, counted from
 * the source below, so a new md refusal fails this spec until it is classified here.
 *
 *  - **PLACEMENT** — md has no single carrier, or cannot place a slot: the er lane **reads** the statement.
 *  - **PRINCIPLE** — the question is ill-posed on any layer (a non-additive measure with no aggregation, a HAVING on a
 *    measure the read does not aggregate, a negated phase that joins): the er lane **refuses for md's reason** — one
 *    finding, E-R's reason folded in — but for a join-free negation, which is the same question (`NOT COALESCE(…, FALSE)`).
 *  - **SHARED** — a construct neither lane reads (a `compare`, an `ANY` group, …): both refuse, one finding.
 *
 * A row's frame runs on `er-parity` (one md carrier, the event on E-R — md's refusals are reached, the er lane runs
 * behind them) unless the site needs another world: `hartland` (several carriers), `channel-reverse` (a dateless
 * carrier), `sales` (nothing on E-R — md's refusal stands alone). A site no golden world reaches is a **guard**, its
 * reason written down.
 */
class ErLaneParitySpec :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC)
        val parity = GoldenWorlds["er-parity"].cn
        val hartland = GoldenWorlds["hartland"].cn
        val reverse = GoldenWorlds["channel-reverse"].cn
        val sales = GoldenWorlds["sales"].cn
        val plus = StatementLowerSpec.world("sales", StatementLowerSpec.PLUS)

        for (row in ROWS) {
            test("${row.kind} · `${row.site}`") {
                val case =
                    row.case
                        ?: return@test withClue("a guard says why no frame reaches it") {
                            row.guard!!.isNotBlank() shouldBe
                                true
                        }
                val model =
                    when (case.world) {
                        "er-parity" -> parity
                        "hartland" -> hartland
                        "channel-reverse" -> reverse
                        "sales" -> sales
                        else -> plus
                    }
                val s = Statement.newBuilder().also { JsonFormat.parser().merge(case.frame, it) }.build()
                val r = CnConceptService(model, clock).statementLower(s)
                val errors = r.diagnostics.filter { it.severity == Severity.ERROR }
                withClue(
                    "${row.site}\n${r.erSql}\n${r.diagnostics.joinToString(
                        "\n",
                    ) { "${it.slug} ${it.subject} — ${it.message}" }}",
                ) {
                    when (val e = case.expect) {
                        is Expect.Reads -> {
                            errors shouldBe emptyList()
                            for (part in e.sql) r.erSql shouldContain part
                        }
                        is Expect.Refused -> {
                            r.erSql shouldBe ""
                            errors.map { "${it.slug} ${it.subject}" } shouldBe e.findings
                            if (e.folded) errors.first().message shouldContain " — and on E-R: "
                        }
                        is Expect.MdOnly -> {
                            r.erSql shouldBe ""
                            errors.map { "${it.slug} ${it.subject}" } shouldBe e.findings
                            errors.forEach { it.message shouldNotContain "on E-R" }
                        }
                    }
                }
            }
        }

        test("the table covers every md refusal — 27 `refuse(` sites, each named by exactly one row") {
            val src =
                DefinitionFixtures.repo
                    .resolve("packages/kotlin/ttr-concept/src/main/kotlin/org/tatrman/concept/StatementLowerer.kt")
                    .readText()
            val lowering =
                src.substring(
                    src.indexOf("private inner class Lowering("),
                    src.indexOf("private inner class ErLane("),
                )
            val shared =
                src.substring(
                    src.indexOf("private fun relativeStart("),
                    src.indexOf("/** The world's `def parameter` values"),
                )
            val sites = lowering + shared
            Regex("""\brefuse\(""").findAll(sites).count() shouldBe 27
            ROWS.size shouldBe 27
            for (row in ROWS) withClue(row.site) { Regex(Regex.escape(row.site)).findAll(sites).count() shouldBe 1 }
        }

        test("a model with no md tier: md refuses, and E-R takes no aggregate without one — one finding") {
            val s =
                Statement.newBuilder().also {
                    JsonFormat.parser().merge(frame("\"measures\": [{ \"qname\": \"cn.revenue\" }]", Y2026), it)
                }
            val r = CnConceptService(parity.copy(md = null), clock).statementLower(s.build())
            r.diagnostics.filter { it.severity == Severity.ERROR }.map { "${it.slug} ${it.subject}" } shouldBe
                listOf("cn/not-analytically-reachable measures[0]")
            r.diagnostics.single().message shouldContain "grounds no md measure"
        }
    }) {
    enum class Kind { PLACEMENT, PRINCIPLE, SHARED }

    sealed interface Expect {
        class Reads(
            vararg val sql: String,
        ) : Expect

        class Refused(
            val findings: List<String>,
            val folded: Boolean = true,
        ) : Expect

        class MdOnly(
            val findings: List<String>,
        ) : Expect
    }

    class Case(
        val world: String,
        val frame: String,
        val expect: Expect,
    )

    class Row(
        val site: String,
        val kind: Kind,
        val case: Case? = null,
        val guard: String? = null,
    )

    companion object {
        const val ASK = "\"ask\": \"ASK_VALUE\""
        const val Y2026 = "\"time\": { \"periodCode\": \"2026\" }"
        const val JAN = "\"time\": { \"periodCode\": \"2026-01\" }"
        const val REVENUE = "\"measures\": [{ \"qname\": \"cn.revenue\" }]"
        const val NAR = "cn/not-analytically-reachable"

        fun frame(vararg parts: String) = (listOf(ASK) + parts).joinToString(", ", "{ ", " }")

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

        fun range(
            from: String,
            to: String,
            granularity: String,
        ) = "\"time\": { \"range\": { \"from\": { \"value\": \"$from\", \"granularity\": \"$granularity\" }, " +
            "\"to\": { \"value\": \"$to\", \"granularity\": \"$granularity\" } } }"

        fun groupBy(q: String) = "\"groupBy\": [{ \"qname\": \"$q\" }]"

        fun measures(vararg q: String) = "\"measures\": [${q.joinToString(", ") { "{ \"qname\": \"$it\" }" }}]"

        val ROWS =
            listOf(
                Row(
                    "a `compare` combines two md reads",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(REVENUE, Y2026, "\"compare\": { \"periodCode\": \"2025\", \"mode\": \"DELTA\" }"),
                        Expect.Refused(listOf("$NAR compare")),
                    ),
                ),
                Row(
                    "a dot-path reads one measure",
                    Kind.PLACEMENT,
                    Case(
                        "er-parity",
                        frame(measures("cn.revenue", "cn.quantity"), Y2026),
                        Expect.Reads("SUM(sale.quantity) AS quantity"),
                    ),
                ),
                Row(
                    "— a measure grounds on an md measure",
                    Kind.SHARED,
                    Case("er-parity", frame(measures("cn.sold_on"), Y2026), Expect.Refused(listOf("$NAR measures[0]"))),
                ),
                Row(
                    "\"a having-filter on `\${a.face.concept.ref}`",
                    Kind.PRINCIPLE,
                    Case(
                        "er-parity",
                        frame(
                            measures("cn.quantity"),
                            Y2026,
                            filters(quality("cn.revenue", "GT", "{ \"intValue\": \"1000\" }")),
                        ),
                        Expect.Refused(listOf("$NAR filters[0].quality.quality")),
                    ),
                ),
                Row(
                    "which the statement's time ",
                    Kind.PLACEMENT,
                    Case(
                        "er-parity",
                        frame(
                            REVENUE,
                            range("2026-01-01", "2026-02-01", "DAY"),
                            "\"asOf\": { \"value\": \"2026-01-31\", \"granularity\": \"DAY\" }",
                            filters(phase("cn.RecentSale")),
                        ),
                        Expect.Reads("sale.sold_on <= DATE '2026-01-31'", "sale.sold_on < DATE '2026-02-01'"),
                    ),
                ),
                Row(
                    "the existence is keyed by",
                    Kind.PLACEMENT,
                    Case(
                        "sales",
                        frame(REVENUE, JAN, filters(phase("cn.ActiveCustomer"), member("cn.Customer", "Lidl"))),
                        Expect.MdOnly(listOf("$NAR filters[0].phase.phase")),
                    ),
                ),
                Row(
                    "on the md attribute of",
                    Kind.PLACEMENT,
                    // E-R refuses for its own reason: `buyer_code` (an md attribute) has no E-R landing
                    Case(
                        "er-parity",
                        frame(REVENUE, Y2026, filters(quality("cn.buyer_code", "GT", "{ \"intValue\": \"5\" }"))),
                        Expect.Refused(
                            listOf("$NAR filters[0].quality.op", "$NAR filters[0].quality.quality"),
                            folded = false,
                        ),
                    ),
                ),
                Row(
                    "group — md coordinates are a conjunction (ALL)",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(
                            REVENUE,
                            Y2026,
                            filters("{ \"group\": { \"op\": \"ANY\", \"filters\": [${member("cn.Customer", "1")}] } }"),
                        ),
                        Expect.Refused(listOf("$NAR filters[0].group")),
                    ),
                ),
                Row(
                    "an expression filter (the E-2 hatch) does not lower on md in P3",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(REVENUE, Y2026, filters("{ \"expr\": { \"plExpr\": \"revenue > 1\" } }")),
                        Expect.Refused(listOf("$NAR filters[0].expr")),
                    ),
                ),
                Row(
                    "— no md dimension or attribute",
                    Kind.PLACEMENT,
                    Case(
                        "er-parity",
                        frame(REVENUE, Y2026, groupBy("cn.quantity")),
                        Expect.Reads("GROUP BY sale.quantity"),
                    ),
                ),
                Row(
                    "no cubelet carries the measure",
                    Kind.PLACEMENT,
                    Case(
                        "er-parity",
                        frame(measures("cn.refund"), Y2026, groupBy("cn.Customer")),
                        Expect.Reads("SUM(sale.refund)", "GROUP BY sale.customer"),
                    ),
                ),
                Row(
                    "all carry the statement",
                    Kind.PLACEMENT,
                    Case("hartland", frame(REVENUE, JAN), Expect.Reads("SUM(sales.ext_sales_price) AS revenue")),
                ),
                Row(
                    "has no md attribute on the carrying cubelet",
                    Kind.PLACEMENT,
                    guard =
                        "the carrier is chosen among the cubelets that carry every coordinate's dimension, so `bind` " +
                            "refuses only a face that cubelet does not bind (or two it binds) — no golden world declares one",
                ),
                Row(
                    "a negated phase (`not \${ph.ref}`) needs a complement md cannot say (G-MD-1)",
                    Kind.PRINCIPLE,
                    // join-free: the same question on E-R, two-valued
                    Case(
                        "er-parity",
                        frame(
                            REVENUE,
                            Y2026,
                            "\"asOf\": { \"value\": \"2026-01-31\", \"granularity\": \"DAY\" }",
                            filters(phase("cn.RecentSale", negated = true)),
                        ),
                        Expect.Reads(
                            "NOT COALESCE(sale.sold_on >= DATE '2026-01-01' AND sale.sold_on <= DATE '2026-01-31', FALSE)",
                        ),
                    ),
                ),
                Row(
                    "cannot render on md: \${r.message}",
                    Kind.PLACEMENT,
                    Case(
                        "er-parity",
                        frame(REVENUE, Y2026, filters(phase("cn.LateSale"))),
                        Expect.Reads("sale.sold_on >= '2026-06-01'"),
                    ),
                ),
                Row(
                    "has no date coordinate for the statement's time",
                    Kind.PLACEMENT,
                    // E-R refuses for its own reason (the reverse world's `Sale` has no period on E-R), folded into md's
                    Case("channel-reverse", frame(REVENUE, Y2026), Expect.Refused(listOf("$NAR time"))),
                ),
                Row(
                    "the time counts days",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(
                            REVENUE,
                            range("2026-01-01T08", "2026-01-02T08", "HOUR"),
                        ),
                        Expect.Refused(listOf("$NAR time.range.from")),
                    ),
                ),
                Row(
                    "a working-day window",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(REVENUE, "\"time\": { \"relative\": \"last(5,working_days)\" }"),
                        Expect.Refused(listOf("$NAR time.relative")),
                    ),
                ),
                Row(
                    "a sub-day window on a day",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(REVENUE, "\"time\": { \"relative\": \"last(5,hours)\" }"),
                        Expect.Refused(listOf("$NAR time.relative")),
                    ),
                ),
                Row(
                    "shares its name with another dimension's",
                    Kind.PLACEMENT,
                    Case(
                        "plus",
                        frame(
                            REVENUE,
                            Y2026,
                            filters(quality("cn.customer_code", "EQ", "{ \"stringValue\": \"K1\" }")),
                        ),
                        Expect.MdOnly(listOf("$NAR measures[0]")),
                    ),
                ),
                Row(
                    "the md path is ambiguous",
                    Kind.PLACEMENT,
                    guard =
                        "`components` pre-qualifies every coordinate — an attribute name unique in the model, or the " +
                            "dimension's grain attribute — so the resolver is handed one reading; the refusal is a guard",
                ),
                Row(
                    "the md path does not resolve on",
                    Kind.PRINCIPLE,
                    // its principled case: a non-additive measure with no aggregation (other resolver failures place)
                    Case(
                        "er-parity",
                        frame(measures("cn.unit_price"), Y2026),
                        Expect.Refused(listOf("$NAR measures[0]")),
                    ),
                ),
                Row(
                    "the md path does not lower:",
                    Kind.PLACEMENT,
                    guard = "no golden world binds md so that a resolved path fails to lower — the refusal is a guard",
                ),
                Row(
                    "is no column of `\${carrier.name}`'s fact table",
                    Kind.PLACEMENT,
                    guard = "an existence keyed by a hop-bound attribute — no golden world's existence key is one",
                ),
                Row(
                    "over the existence's keys cannot be re-aggregated",
                    Kind.PLACEMENT,
                    guard = "an existence over a measure aggregated by `avg` — no golden world's md declares one",
                ),
                Row(
                    "\"a comparison with null\"",
                    Kind.SHARED,
                    Case(
                        "er-parity",
                        frame(REVENUE, Y2026, filters(quality("cn.revenue", "EQ", "{ \"isNull\": true }"))),
                        Expect.Refused(listOf("$NAR filters[0].quality.value")),
                    ),
                ),
                Row(
                    "\"a quality filter with no value\"",
                    Kind.SHARED,
                    guard = "a quality filter with no value is malformed — the validator refuses it before either lane",
                ),
            )
    }
}
