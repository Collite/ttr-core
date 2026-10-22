// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.assertion.v1.Assertion
import org.tatrman.statement.v1.Statement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * CN-P3 S3.2 — `statementValidate` / `assertionValidate` slug by slug and slot by slot, each with a frame that passes
 * and one that is refused (T1–T6). Over the golden worlds, and — for the shapes they lack — `clinic` plus a few defs
 * ([PLUS]: an event whose grounding misses a participant path, one with no `period:`, an end that is always present,
 * a soft quality, a valued state) and a two-package world ([TWO_PACKAGES]).
 */
class FrameValidationSpec :
    FunSpec({
        val sales = GoldenWorlds["sales"].cn
        val hartland = GoldenWorlds["hartland"].cn
        val clinic = GoldenWorlds["clinic"].cn
        val plus = world("clinic", PLUS)
        val twoPackages = model(TWO_PACKAGES)

        fun statement(json: String): Statement =
            Statement.newBuilder().also { JsonFormat.parser().merge(json, it) }.build()

        fun assertion(json: String): Assertion =
            Assertion.newBuilder().also { JsonFormat.parser().merge(json, it) }.build()

        fun shown(ds: List<Diagnostic>): List<String> = ds.map { "${it.slug} ${it.subject}" }

        fun s(
            name: String,
            model: ResolvedCnModel,
            frame: String,
            vararg expected: String,
        ) = test("statement: $name") {
            shown(CnConceptService(model).statementValidate(statement(frame))) shouldBe expected.toList()
        }

        fun a(
            name: String,
            model: ResolvedCnModel,
            frame: String,
            vararg expected: String,
        ) = test("assertion: $name") {
            shown(CnConceptService(model).assertionValidate(assertion(frame))) shouldBe expected.toList()
        }

        val ask = "\"ask\": \"ASK_VALUE\""
        val revenue = "\"measures\": [{ \"qname\": \"cn.revenue\" }]"
        val jan = "\"time\": { \"periodCode\": \"2026-01\" }"
        val always = "\"time\": { \"allTime\": true }"

        fun st(vararg parts: String) = parts.joinToString(", ", "{ ", " }")

        fun filter(f: String) = "\"filters\": [$f]"

        // ---- T2: ask, measures, time --------------------------------------------------------------------------
        s("a value ask over an md measure, a period code", sales, st(ask, revenue, jan))
        s("no ask", sales, st(revenue, jan), "cn/unsupported-ask ask")
        s("ASK_SET", sales, st("\"ask\": \"ASK_SET\"", revenue, jan), "cn/unsupported-ask ask")
        s("no measure", sales, st(ask, jan), "cn/malformed-frame measures")
        s("no time", sales, st(ask, revenue), "cn/missing-time time")
        s("all_time: false", sales, st(ask, revenue, "\"time\": { \"allTime\": false }"), "cn/missing-time time")
        s("a year", sales, st(ask, revenue, "\"time\": { \"periodCode\": \"2026\" }"))
        s("a quarter", sales, st(ask, revenue, "\"time\": { \"periodCode\": \"2026Q1\" }"))
        s(
            "month 13",
            sales,
            st(ask, revenue, "\"time\": { \"periodCode\": \"2026-13\" }"),
            "cn/malformed-frame time.periodCode",
        )
        s("last(30,days)", sales, st(ask, revenue, "\"time\": { \"relative\": \"last(30,days)\" }"))
        for (bad in listOf("last(0,days)", "last(3,years)", "yesterday")) {
            s(bad, sales, st(ask, revenue, "\"time\": { \"relative\": \"$bad\" }"), "cn/malformed-frame time.relative")
        }

        fun range(
            from: String,
            to: String?,
        ): String {
            val toPart = to?.let { ", \"to\": { \"value\": \"$it\", \"granularity\": \"DAY\" }" } ?: ""
            return "\"time\": { \"range\": { \"from\": { \"value\": \"$from\", \"granularity\": \"DAY\" }$toPart } }"
        }
        s("a range", sales, st(ask, revenue, range("2026-01-01", "2026-02-01")))
        s("an empty range", sales, st(ask, revenue, range("2026-02-01", "2026-02-01")), "cn/malformed-frame time.range")
        s("a range with no end", sales, st(ask, revenue, range("2026-02-01", null)), "cn/malformed-frame time.range")
        s(
            "a day written short",
            sales,
            st(ask, revenue, range("2026-1-1", "2026-02-01")),
            "cn/malformed-frame time.range.from",
        )
        s(
            "an as-of that is no date",
            sales,
            st(ask, revenue, jan, "\"asOf\": { \"value\": \"2026-02-30\", \"granularity\": \"DAY\" }"),
            "cn/malformed-frame asOf",
        )

        // ---- T1: refs ------------------------------------------------------------------------------------------
        s(
            "candidates",
            hartland,
            st(
                ask,
                "\"measures\": [{ \"candidates\": [{ \"qname\": \"cn.revenue\" }, { \"qname\": \"cn.quantity\" }] }]",
                always,
            ),
            "cn/ambiguous-statement measures[0]",
        )
        s("an empty ref", sales, st(ask, "\"measures\": [{}]", jan), "cn/ambiguous-statement measures[0]")
        s(
            "not `cn.<Name>`",
            sales,
            st(ask, "\"measures\": [{ \"qname\": \"revenue\" }]", jan),
            "cn/ambiguous-statement measures[0]",
        )
        s(
            "no such concept",
            sales,
            st(ask, "\"measures\": [{ \"qname\": \"cn.nope\" }]", jan),
            "cn/ambiguous-statement measures[0]",
        )
        s(
            "one name, two packages",
            twoPackages,
            st(
                ask,
                "\"measures\": [{ \"qname\": \"cn.amount\" }]",
                "\"groupBy\": [{ \"qname\": \"cn.Thing\" }]",
                always,
            ),
            "cn/ambiguous-statement groupBy[0]",
        )
        s(
            "a role as a measure",
            sales,
            st(ask, "\"measures\": [{ \"qname\": \"cn.Customer\" }]", jan),
            "cn/slot-mismatch measures[0]",
        )
        s(
            "a phase as a group-by",
            sales,
            st(ask, revenue, jan, "\"groupBy\": [{ \"qname\": \"cn.LargeSale\" }]"),
            "cn/slot-mismatch groupBy[0]",
        )

        // ---- T3: reachability per consumer (AM-4, AM-5) --------------------------------------------------------
        s(
            "an er-only quality as a measure",
            hartland,
            st(ask, "\"measures\": [{ \"qname\": \"cn.sale_date\" }]", always),
            "cn/not-analytically-reachable measures[0]",
        )
        s("by an md role", sales, st(ask, revenue, jan, "\"groupBy\": [{ \"qname\": \"cn.Customer\" }]"))
        s(
            "by a value kind, through the reverse hop (⚑CN-P8)",
            hartland,
            st(ask, revenue, always, "\"groupBy\": [{ \"qname\": \"cn.Channel\" }]"),
        )
        s(
            "by an er kind no md element hops to",
            hartland,
            st(ask, revenue, always, "\"groupBy\": [{ \"qname\": \"cn.Item\" }]"),
            "cn/not-analytically-reachable groupBy[0]",
        )

        fun member(
            concept: String,
            value: String,
        ) = filter(
            "{ \"member\": { \"concept\": { \"qname\": \"$concept\" }, \"member\": { \"label\": \"x\", \"resolvedValue\": \"$value\" } } }",
        )
        s("a member filter", sales, st(ask, revenue, jan, member("cn.Customer", "Kaufland")))
        s(
            "an unresolved member",
            sales,
            st(ask, revenue, jan, member("cn.Customer", "")),
            "cn/ambiguous-statement filters[0].member.member",
        )
        s(
            "a quality as a member filter",
            sales,
            st(ask, revenue, jan, member("cn.revenue", "1")),
            "cn/slot-mismatch filters[0].member.concept",
        )
        s(
            "a member of a value kind, through the reverse hop",
            hartland,
            st(ask, revenue, always, member("cn.Channel", "web")),
        )
        s(
            "a member of an er kind no md element hops to",
            hartland,
            st(ask, revenue, always, member("cn.Item", "SKU-1")),
            "cn/not-analytically-reachable filters[0].member.concept",
        )

        fun quality(body: String) = filter("{ \"quality\": { $body } }")
        val rq = "\"quality\": { \"qname\": \"cn.revenue\" }"
        s(
            "a quality filter",
            sales,
            st(ask, revenue, jan, quality("$rq, \"op\": \"GT\", \"value\": { \"floatValue\": 100 }")),
        )
        s(
            "no comparison",
            sales,
            st(ask, revenue, jan, quality("$rq, \"value\": { \"floatValue\": 100 }")),
            "cn/malformed-frame filters[0].quality.op",
        )
        s(
            "no value",
            sales,
            st(ask, revenue, jan, quality("$rq, \"op\": \"GT\"")),
            "cn/malformed-frame filters[0].quality.value",
        )
        s(
            "a null by GT",
            sales,
            st(ask, revenue, jan, quality("$rq, \"op\": \"GT\", \"value\": { \"isNull\": true }")),
            "cn/malformed-frame filters[0].quality.value",
        )
        s(
            "a role as a quality filter",
            sales,
            st(
                ask,
                revenue,
                jan,
                quality(
                    "\"quality\": { \"qname\": \"cn.Customer\" }, \"op\": \"EQ\", \"value\": { \"stringValue\": \"x\" }",
                ),
            ),
            "cn/slot-mismatch filters[0].quality.quality",
        )

        fun phase(qname: String) = filter("{ \"phase\": { \"phase\": { \"qname\": \"$qname\" } } }")
        s(
            "ActiveCustomer (an existence over the grain event, ⚑CN-P9)",
            sales,
            st(ask, revenue, jan, phase("cn.ActiveCustomer")),
        )
        s("LargeSale (a measure predicate)", sales, st(ask, revenue, jan, phase("cn.LargeSale")))
        s(
            "WebSale (a value-kind phase, through the reverse hop)",
            hartland,
            st(ask, revenue, always, phase("cn.WebSale")),
        )
        s(
            "DebtorCustomer reads er only",
            hartland,
            st(ask, revenue, always, phase("cn.DebtorCustomer")),
            "cn/definition-unreachable(md) filters[0].phase.phase",
            "cn/not-analytically-reachable filters[0].phase.phase",
        )
        s(
            "a cycle is re-reported (both of its definitions)",
            hartland,
            st(ask, revenue, always, phase("cn.LoopA")),
            "cn/definition-cycle filters[0].phase.phase",
            "cn/definition-cycle filters[0].phase.phase",
        )
        s(
            "a kind as a phase filter",
            sales,
            st(ask, revenue, jan, phase("cn.Customer")),
            "cn/slot-mismatch filters[0].phase.phase",
        )

        // ---- filter shapes -------------------------------------------------------------------------------------
        val customer = "\"concept\": { \"qname\": \"cn.Customer\" }"
        val unresolved = "{ \"member\": { $customer, \"member\": { \"label\": \"x\" } } }"
        val kaufland = "{ \"member\": { $customer, \"member\": { \"resolvedValue\": \"Kaufland\" } } }"
        s(
            "a group",
            sales,
            st(ask, revenue, jan, filter("{ \"group\": { \"op\": \"ANY\", \"filters\": [$kaufland, $kaufland] } }")),
        )
        s(
            "NOT over two",
            sales,
            st(ask, revenue, jan, filter("{ \"group\": { \"op\": \"NOT\", \"filters\": [$kaufland, $kaufland] } }")),
            "cn/malformed-frame filters[0].group",
        )
        s(
            "ALL over none",
            sales,
            st(ask, revenue, jan, filter("{ \"group\": { \"op\": \"ALL\" } }")),
            "cn/malformed-frame filters[0].group",
        )
        s(
            "a nested finding names its path",
            sales,
            st(ask, revenue, jan, filter("{ \"group\": { \"op\": \"ANY\", \"filters\": [$kaufland, $unresolved] } }")),
            "cn/ambiguous-statement filters[0].group.filters[1].member.member",
        )
        s("an expression filter", sales, st(ask, revenue, jan, filter("{ \"expr\": { \"plExpr\": \"revenue > 1\" } }")))
        s(
            "an empty expression",
            sales,
            st(ask, revenue, jan, filter("{ \"expr\": {} }")),
            "cn/malformed-frame filters[0].expr.plExpr",
        )
        s(
            "an expression that does not parse",
            sales,
            st(ask, revenue, jan, filter("{ \"expr\": { \"plExpr\": \"revenue >\" } }")),
            "cn/malformed-frame filters[0].expr.plExpr",
        )
        s("a filter with no form", sales, st(ask, revenue, jan, filter("{}")), "cn/malformed-frame filters[0]")

        // ---- order, compare, snapshot --------------------------------------------------------------------------
        val byRevenue =
            "\"orderLimit\": { \"by\": [{ \"by\": { \"qname\": \"cn.revenue\" }, \"descending\": true }], " +
                "\"limit\": 5 }"
        s("ordered by its measure", sales, st(ask, revenue, jan, byRevenue))
        s(
            "ordered by another quality",
            sales,
            st(ask, revenue, jan, "\"orderLimit\": { \"by\": [{ \"by\": { \"qname\": \"cn.gross_revenue\" } }] }"),
            "cn/malformed-frame orderLimit.by[0].by",
        )
        s(
            "compared with a period",
            sales,
            st(ask, revenue, jan, "\"compare\": { \"periodCode\": \"2025-01\", \"mode\": \"DELTA\" }"),
        )
        s(
            "a comparison with no mode",
            sales,
            st(ask, revenue, jan, "\"compare\": { \"periodCode\": \"2025-01\" }"),
            "cn/malformed-frame compare.mode",
        )
        s(
            "a comparison against nothing",
            sales,
            st(ask, revenue, jan, "\"compare\": { \"mode\": \"RATIO\" }"),
            "cn/malformed-frame compare",
        )
        s(
            "compared with an unresolved member",
            sales,
            st(
                ask,
                revenue,
                jan,
                "\"compare\": { \"members\": { \"left\": { \"resolvedValue\": \"A\" }, \"right\": { \"label\": \"B\" } }, \"mode\": \"ABSOLUTE\" }",
            ),
            "cn/ambiguous-statement compare.members.right",
        )
        s("its own snapshot", sales, st(ask, revenue, jan, "\"snapshotHash\": \"golden:sales\""))
        test("statement: another snapshot is a warning, not a refusal") {
            val d =
                CnConceptService(
                    sales,
                ).statementValidate(statement(st(ask, revenue, jan, "\"snapshotHash\": \"v0\"")))
            d.map { "${it.slug} ${it.severity}" } shouldBe listOf("cn/snapshot-mismatch WARNING")
            d.single().message shouldContain "golden:sales"
        }

        // ---- T5: one order ------------------------------------------------------------------------------------
        test("statement: findings sort by subject — indices as numbers — then slug; two runs are identical") {
            val ms =
                (0..10).joinToString(", ") { i ->
                    if (i == 2 ||
                        i == 10
                    ) {
                        "{}"
                    } else {
                        "{ \"qname\": \"cn.revenue\" }"
                    }
                }
            val frame = statement(st("\"measures\": [$ms]"))
            val svc = CnConceptService(sales)
            val first = svc.statementValidate(frame)
            shown(first) shouldBe
                listOf(
                    "cn/unsupported-ask ask",
                    "cn/ambiguous-statement measures[2]",
                    "cn/ambiguous-statement measures[10]",
                    "cn/missing-time time",
                )
            svc.statementValidate(frame) shouldBe first
            first.all { it.message.isNotEmpty() } shouldBe true
        }

        // ---- T4: assertions -----------------------------------------------------------------------------------
        val patient =
            "{ \"label\": \"patient\", \"concept\": { \"qname\": \"cn.Patient\" }, " +
                "\"member\": { \"resolvedValue\": \"1001\" } }"
        val from =
            "{ \"label\": \"from\", \"concept\": { \"qname\": \"cn.Encounter\" }, " +
                "\"member\": { \"resolvedValue\": \"501\" } }"
        val minute = "\"period\": { \"start\": { \"value\": \"2026-03-14T09:30\", \"granularity\": \"MINUTE\" } }"
        val fresh = "\"identity\": { \"new\": true }"

        fun ev(qname: String) = "\"event\": { \"qname\": \"$qname\" }"

        fun parts(vararg ps: String) = "\"participants\": [${ps.joinToString(", ")}]"

        fun quals(vararg qs: String) = "\"qualities\": [${qs.joinToString(", ")}]"

        fun qv(
            qname: String,
            value: String = "{ \"stringValue\": \"x\" }",
            unit: String? = null,
        ) = "{ \"quality\": { \"qname\": \"$qname\" }, \"value\": $value${unit?.let { ", \"unit\": \"$it\"" } ?: ""} }"
        val transfer = ev("cn.Transfer")

        a("a transfer", clinic, st(transfer, fresh, parts(patient, from), quals(qv("cn.transfer_kind")), minute))
        a("a kind as the event", clinic, st(ev("cn.Person"), fresh), "cn/slot-mismatch event")
        a("no event", clinic, st(fresh), "cn/ambiguous-assertion event")
        a("no identity", clinic, st(transfer, minute), "cn/malformed-frame identity")
        a(
            "a known identity",
            clinic,
            st(transfer, "\"identity\": { \"known\": { \"values\": { \"transfer_id\": \"77\" } } }", minute),
        )
        a(
            "a known identity off the key",
            clinic,
            st(transfer, "\"identity\": { \"known\": { \"values\": { \"at\": \"x\" } } }", minute),
            "cn/malformed-frame identity.known",
        )
        a(
            "a participant with no label",
            clinic,
            st(
                transfer,
                fresh,
                parts("{ \"concept\": { \"qname\": \"cn.Patient\" }, \"member\": { \"resolvedValue\": \"1\" } }"),
                minute,
            ),
            "cn/unknown-participant-label participants[0]",
        )
        a("a label twice", clinic, st(transfer, fresh, parts(from, from), minute), "cn/malformed-frame participants[1]")
        a(
            "a participant of the wrong concept",
            clinic,
            st(
                transfer,
                fresh,
                parts(
                    "{ \"label\": \"from\", \"concept\": { \"qname\": \"cn.Patient\" }, \"member\": { \"resolvedValue\": \"1\" } }",
                ),
                minute,
            ),
            "cn/slot-mismatch participants[0].concept",
        )
        a(
            "a participant with no member",
            clinic,
            st(transfer, fresh, parts("{ \"label\": \"from\" }"), minute),
            "cn/ambiguous-assertion participants[0].member",
        )
        a(
            "a participant whose member is unresolved",
            clinic,
            st(transfer, fresh, parts("{ \"label\": \"from\", \"member\": { \"label\": \"E-9\" } }"), minute),
            "cn/ambiguous-assertion participants[0].member",
        )
        val adm =
            "\"period\": { \"start\": { \"value\": \"2026-03-01\", \"granularity\": \"DAY\" }, " +
                "\"end\": { \"value\": \"2026-03-04\", \"granularity\": \"DAY\" } }"
        val encounter = "{ \"label\": \"encounter\", \"member\": { \"resolvedValue\": \"501\" } }"
        a("an admission", plus, st(ev("cn.Admission"), fresh, parts(patient), adm))
        a(
            "a participant the grounding has no path for",
            plus,
            st(ev("cn.Admission"), fresh, parts(patient, encounter), adm),
            "cn/participant-path-broken participants[1]",
        )

        a("a quality of the event", clinic, st(transfer, fresh, quals(qv("cn.transfer_kind")), minute))
        a(
            "a quality of another event",
            clinic,
            st(transfer, fresh, quals(qv("cn.department")), minute),
            "cn/slot-mismatch qualities[0].quality",
        )
        a(
            "a kind as a quality",
            clinic,
            st(transfer, fresh, quals(qv("cn.Diagnosis")), minute),
            "cn/slot-mismatch qualities[0].quality",
        )
        a(
            "no such quality",
            clinic,
            st(transfer, fresh, quals(qv("cn.nope")), minute),
            "cn/ambiguous-assertion qualities[0].quality",
        )
        a(
            "a defined quality",
            clinic,
            st(transfer, fresh, quals(qv("cn.transfer_delay")), minute),
            "cn/not-assertable qualities[0].quality",
        )
        a(
            "a quality with no value",
            clinic,
            st(transfer, fresh, quals("{ \"quality\": { \"qname\": \"cn.transfer_kind\" } }"), minute),
            "cn/malformed-frame qualities[0].value",
        )
        a(
            "a quality landing on another entity",
            plus,
            st(transfer, fresh, quals(qv("cn.transfer_department")), minute),
            "cn/not-assertable qualities[0].quality",
        )
        a("a soft quality on the entity", plus, st(ev("cn.Admission"), fresh, quals(qv("cn.department_id")), adm))
        a(
            "a soft quality the entity lacks",
            plus,
            st(ev("cn.Admission"), fresh, quals(qv("cn.ward")), adm),
            "cn/not-assertable qualities[0].quality",
        )

        val obs = ev("cn.Observation")
        val obsAt = "\"period\": { \"start\": { \"value\": \"2026-03-14T07:15\", \"granularity\": \"MINUTE\" } }"
        a(
            "the declared unit (⚑CN-P10)",
            clinic,
            st(obs, fresh, quals(qv("cn.observed_value", "{ \"floatValue\": 6 }", "mmol/L")), obsAt),
        )
        a("no unit stated", clinic, st(obs, fresh, quals(qv("cn.observed_value", "{ \"floatValue\": 6 }")), obsAt))
        a(
            "another unit",
            clinic,
            st(obs, fresh, quals(qv("cn.observed_value", "{ \"floatValue\": 108 }", "mg/dL")), obsAt),
            "cn/unit-mismatch qualities[0].unit",
        )
        a(
            "a unit where none is declared",
            clinic,
            st(obs, fresh, quals(qv("cn.analyte", unit = "mg")), obsAt),
            "cn/unit-mismatch qualities[0].unit",
        )

        a("an instant event with no period", clinic, st(transfer, fresh), "cn/missing-time period")
        a(
            "an instant event with an end",
            clinic,
            st(
                transfer,
                fresh,
                "\"period\": { \"start\": { \"value\": \"2026-03-14T09:30\", \"granularity\": \"MINUTE\" }, \"end\": { \"value\": \"2026-03-14T10:00\", \"granularity\": \"MINUTE\" } }",
            ),
            "cn/malformed-frame period.end",
        )
        a(
            "a start that is no instant",
            clinic,
            st(
                transfer,
                fresh,
                "\"period\": { \"start\": { \"value\": \"2026-03-14 09:30\", \"granularity\": \"MINUTE\" } }",
            ),
            "cn/malformed-frame period.start",
        )

        fun encounterPeriod(rest: String) =
            "\"period\": { \"start\": { \"value\": \"2026-03-01\", \"granularity\": \"DAY\" }$rest }"
        val enc = ev("cn.Encounter")
        a("a running encounter (end_open: open)", clinic, st(enc, fresh, encounterPeriod("")))
        a("END_OPEN said", clinic, st(enc, fresh, encounterPeriod(", \"endState\": \"END_OPEN\"")))
        a(
            "END_UNKNOWN on an open end",
            clinic,
            st(enc, fresh, encounterPeriod(", \"endState\": \"END_UNKNOWN\"")),
            "cn/malformed-frame period.endState",
        )
        a(
            "END_CLOSED with no end",
            clinic,
            st(enc, fresh, encounterPeriod(", \"endState\": \"END_CLOSED\"")),
            "cn/malformed-frame period.endState",
        )
        a(
            "an end before the start",
            clinic,
            st(enc, fresh, encounterPeriod(", \"end\": { \"value\": \"2026-02-01\", \"granularity\": \"DAY\" }")),
            "cn/malformed-frame period.end",
        )
        a(
            "an end that is always present, missing",
            plus,
            st(
                ev("cn.Admission"),
                fresh,
                "\"period\": { \"start\": { \"value\": \"2026-03-01\", \"granularity\": \"DAY\" } }",
            ),
            "cn/missing-time period.end",
        )
        a("an act with no period:", plus, st(ev("cn.Act"), fresh))
        a("an act given a period", plus, st(ev("cn.Act"), fresh, minute), "cn/malformed-frame period")

        val episode = ev("cn.FractureEpisode")
        val since = encounterPeriod("")

        fun asOf(day: String) = "\"assertedAsOf\": { \"value\": \"$day\", \"granularity\": \"DAY\" }"
        a("a state claim with its as-of", clinic, st(episode, fresh, since, asOf("2026-03-14")))
        a("a state claim with no as-of (AM-38)", clinic, st(episode, fresh, since), "cn/missing-as-of assertedAsOf")
        a(
            "a state claimed before it starts",
            clinic,
            st(episode, fresh, since, asOf("2026-02-20")),
            "cn/basis-mismatch period.start",
        )
        a("an occurrence needs no as-of", clinic, st(transfer, fresh, minute))
        a(
            "a valued state is a state claim",
            plus,
            st(ev("cn.Admission"), fresh, quals(qv("cn.pain_level", "{ \"intValue\": 3 }")), adm),
            "cn/missing-as-of assertedAsOf",
        )
        a(
            "an assertion on another snapshot",
            clinic,
            st(transfer, fresh, minute, "\"snapshotHash\": \"v0\""),
            "cn/snapshot-mismatch snapshotHash",
        )

        // ---- every frame a later stage lowers or explains validates clean (its lower refuses on any error) -------
        for (c in GoldenCases.cases.filter { it.call in LOWERED }) {
            test("golden ${c.id} [${c.call}] validates clean") {
                val svc = CnConceptService(GoldenWorlds[c.world].cn)
                val ds =
                    if (c.call.startsWith("statement")) {
                        svc.statementValidate(c.statement())
                    } else {
                        svc.assertionValidate(c.assertion())
                    }
                shown(ds.filter { it.severity == Severity.ERROR }) shouldBe emptyList()
            }
        }
    }) {
    companion object {
        val LOWERED = setOf("statementLower", "statementExplain", "assertionLower", "assertionExplain")

        /** `clinic` plus the shapes the golden world lacks (T4's negatives). Invented, like the world. */
        val PLUS: Map<String, String> =
            mapOf(
                "plus/cn.ttrm" to
                    """
                    model cn
                    def event Admission {
                        involves: [Patient, Encounter],
                        period: { start: adm_start, end: adm_end },
                        qualities: [adm_start, adm_end, department_id, ward]
                    }
                    def quality pain_level { of: Admission, valued_state: true }
                    def quality transfer_department { of: Transfer }
                    def event Act { involves: [Patient] }
                    """.trimIndent(),
                "plus/bindings.ttrm" to
                    """
                    model binding
                    def cn2er_event admission_g {
                        event: cn.Admission,
                        target: er.entity.encounter,
                        period: { start: er.entity.encounter.admitted_at, end: er.entity.encounter.discharged_at },
                        participants: { patient: [er.relation.encounter_patient] }
                    }
                    def cn2er_quality pain_level_g { quality: cn.pain_level, target: er.entity.encounter.department_id }
                    def cn2er_quality transfer_department_g { quality: cn.transfer_department, target: er.entity.encounter.department_id }
                    def cn2er_event act_g {
                        event: cn.Act,
                        target: er.entity.observation,
                        participants: { patient: [er.relation.observation_patient] }
                    }
                    """.trimIndent(),
            )

        /** One concept name in two packages — a frame ref to it is ambiguous, never the first. */
        val TWO_PACKAGES: Map<String, String> =
            mapOf(
                "a/cn.ttrm" to "package shop.a\nmodel cn\ndef kind Thing {}\n",
                "b/cn.ttrm" to "package shop.b\nmodel cn\ndef kind Thing {}\ndef event Sale { involves: [Thing] }\n" +
                    "def quality amount { of: Sale }\n",
                "b/md.ttrm" to
                    "package shop.b\nmodel md\ndef domain Money { type: decimal }\n" +
                    "def measure amount { domain: md.Money, class: additive, aggregation: sum }\n",
                "b/bindings.ttrm" to
                    "package shop.b\nmodel binding\ndef cn2md_quality amount_g { quality: cn.amount, target: md.amount }\n",
            )

        private fun write(extra: Map<String, String>): Map<String, Path> {
            val dir = Files.createTempDirectory("cn-frames")
            return extra.mapValues { (rel, text) ->
                dir.resolve(rel).also {
                    Files.createDirectories(it.parent)
                    Files.writeString(it, text)
                }
            }
        }

        fun world(
            name: String,
            extra: Map<String, String>,
        ): ResolvedCnModel {
            val dir = GoldenWorlds.root.resolve(name)
            val files =
                Files.walk(dir).use { s -> s.filter { it.isRegularFile() && it.extension == "ttrm" }.sorted().toList() }
            val all = files.associateBy { dir.relativize(it).toString() } + write(extra)
            return ResolvedCnModel.of(DefinitionFixtures.load(all), snapshotHash = "unit:$name+")
        }

        fun model(files: Map<String, String>): ResolvedCnModel =
            ResolvedCnModel.of(DefinitionFixtures.load(write(files)), snapshotHash = "unit")
    }
}
