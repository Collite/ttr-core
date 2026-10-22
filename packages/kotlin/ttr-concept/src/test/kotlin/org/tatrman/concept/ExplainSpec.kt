// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.assertion.v1.Assertion
import org.tatrman.statement.v1.Statement
import org.tatrman.ttr.metadata.model.ErSchema
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * CN-P3 S3.6 — `statementExplain` / `assertionExplain` slot by slot in en and cs (T1, T4), a phase's definition as prose
 * over the three golden worlds' definitions (T2), ambiguity and the hop rule (T3), the locale fallback and the label
 * chain (A-CN-52). The clock is pinned, so `:as_of` reads one day.
 */
class ExplainSpec :
    FunSpec({
        val clock = Clock.fixed(java.time.Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC)
        val sales = GoldenWorlds["sales"].cn
        val hartland = GoldenWorlds["hartland"].cn
        val clinic = GoldenWorlds["clinic"].cn

        fun statement(json: String): Statement =
            Statement.newBuilder().also { JsonFormat.parser().merge(json, it) }.build()

        fun assertion(json: String): Assertion =
            Assertion.newBuilder().also { JsonFormat.parser().merge(json, it) }.build()

        fun said(
            model: ResolvedCnModel,
            json: String,
            locale: String,
        ): String = CnConceptService(model, clock).statementExplain(statement(json), locale).text

        fun claimed(
            model: ResolvedCnModel,
            json: String,
            locale: String,
        ): String = CnConceptService(model, clock).assertionExplain(assertion(json), locale).text

        fun st(vararg parts: String) = parts.joinToString(", ", "{ \"ask\": \"ASK_VALUE\", ", " }")

        val revenue = "\"measures\": [{ \"qname\": \"cn.revenue\" }]"
        val jan = "\"time\": { \"periodCode\": \"2026-01\" }"

        fun phase(
            name: String,
            negated: Boolean = false,
        ) = "{ \"phase\": { \"phase\": { \"qname\": \"cn.$name\" }, \"negated\": $negated } }"

        // ---- T1: the statement's slots --------------------------------------------------------------------------
        test("measures, a quality filter, group-by, a relative window, order and limit, a comparison") {
            val frame =
                st(
                    "\"measures\": [{ \"qname\": \"cn.revenue\" }, { \"qname\": \"cn.gross_revenue\" }]",
                    "\"filters\": [{ \"quality\": { \"quality\": { \"qname\": \"cn.gross_revenue\" }, " +
                        "\"op\": \"GT\", " +
                        "\"value\": { \"floatValue\": 100.50 } } }]",
                    "\"groupBy\": [{ \"qname\": \"cn.Customer\" }]",
                    "\"time\": { \"relative\": \"last(30,days)\" }",
                    "\"orderLimit\": { \"by\": [{ \"by\": { \"qname\": \"cn.revenue\" }, \"descending\": true }], \"limit\": 5 }",
                    "\"compare\": { \"periodCode\": \"2025-01\", \"mode\": \"DELTA\" }",
                )
            said(sales, frame, "en") shouldBe
                "Net revenue · gross revenue · gross revenue > 100.5 · by customer · last 30 days · " +
                "ordered by net revenue descending, first 5 · compared with January 2025 (difference)."
            said(sales, frame, "cs") shouldBe
                "Čistý obrat · hrubý obrat · hrubý obrat > 100.5 · členění: zákazník · posledních 30 dní · " +
                "řazení: čistý obrat sestupně, prvních 5 · srovnání: leden 2025 (rozdíl)."
        }

        test("time: a quarter, a year, a range, all time, one day, three weeks, an as-of") {
            fun time(
                t: String,
                locale: String,
            ) = said(sales, st(revenue, t), locale).removePrefix("Net revenue · ").removePrefix("Čistý obrat · ")
            val range =
                "\"time\": { \"range\": { \"from\": { \"value\": \"2026-01-01\", \"granularity\": \"DAY\" }, " +
                    "\"to\": { \"value\": \"2026-02-01\", \"granularity\": \"DAY\" } } }"
            time("\"time\": { \"periodCode\": \"2026Q1\" }", "en") shouldBe "Q1 2026."
            time("\"time\": { \"periodCode\": \"2026Q1\" }", "cs") shouldBe "1. čtvrtletí 2026."
            time("\"time\": { \"periodCode\": \"2026\" }", "cs") shouldBe "2026."
            time(range, "en") shouldBe "from 2026-01-01 until 2026-02-01."
            time(range, "cs") shouldBe "od 1. 1. 2026 do 1. 2. 2026."
            time("\"time\": { \"allTime\": true }", "en") shouldBe "all time."
            time("\"time\": { \"allTime\": true }", "cs") shouldBe "za celou dobu."
            time("\"time\": { \"relative\": \"last(1,days)\" }", "en") shouldBe "last day."
            time("\"time\": { \"relative\": \"last(1,days)\" }", "cs") shouldBe "poslední den."
            time("\"time\": { \"relative\": \"last(3,weeks)\" }", "cs") shouldBe "poslední 3 týdny."
            val asOf = "$jan, \"asOf\": { \"value\": \"2026-02-15\", \"granularity\": \"DAY\" }"
            time(asOf, "en") shouldBe "January 2026 · as of 2026-02-15."
            time(asOf, "cs") shouldBe "leden 2026 · ke dni 15. 2. 2026."
        }

        // ---- T2: a phase slot, its definition as prose -------------------------------------------------------
        test("a phase filter says its definition; negated; in an `or` group") {
            said(sales, st(revenue, "\"filters\": [${phase("ActiveCustomer")}]", jan), "en") shouldBe
                "Net revenue · active customer: exists sale involving customer where time within the last 365 days · " +
                "January 2026."
            said(sales, st(revenue, "\"filters\": [${phase("ActiveCustomer")}]", jan), "cs") shouldBe
                "Čistý obrat · aktivní zákazník: existuje prodej, účastník zákazník, kde čas za posledních 365 dní · " +
                "leden 2026."
            said(sales, st(revenue, "\"filters\": [${phase("LargeSale", negated = true)}]", jan), "en") shouldBe
                "Net revenue · not large sale: net revenue > large_sale (= 10000) · January 2026."
            val kaufland =
                "{ \"member\": { \"concept\": { \"qname\": \"cn.Customer\" }, " +
                    "\"member\": { \"label\": \"Kaufland\", \"resolvedValue\": \"Kaufland\" } } }"
            val any = "{ \"group\": { \"op\": \"ANY\", \"filters\": [${phase("LargeSale")}, $kaufland] } }"
            val all = "{ \"group\": { \"op\": \"ALL\", \"filters\": [$any, $kaufland] } }"
            val not = "{ \"group\": { \"op\": \"NOT\", \"filters\": [$kaufland] } }"
            said(sales, st(revenue, "\"filters\": [$any]", jan), "en") shouldBe
                "Net revenue · large sale: net revenue > large_sale (= 10000) or customer Kaufland · January 2026."
            said(sales, st(revenue, "\"filters\": [$all]", jan), "cs") shouldBe
                "Čistý obrat · (velký prodej: čistý obrat > large_sale (= 10000) nebo zákazník Kaufland) a " +
                "zákazník Kaufland · leden 2026."
            said(sales, st(revenue, "\"filters\": [$not]", jan), "en") shouldBe
                "Net revenue · not (customer Kaufland) · January 2026."
        }

        test("the verbaliser over the golden worlds' definitions") {
            fun prose(
                model: ResolvedCnModel,
                name: String,
                locale: String = "en",
            ): String? {
                val words = ExplainWords.of(locale)!!
                val labels = ExplainLabels(model, words)
                return DefinitionProse(model, words, labels, LocalDate.of(2026, 3, 15)).of(labels.named(name)!!)
            }
            prose(hartland, "PendingOrder") shouldBe "state in ('placed', 'held')"
            prose(hartland, "PendingOrder", "cs") shouldBe "state v ('placed', 'held')"
            prose(hartland, "OverdueInvoice") shouldBe "invoice status = 'open' and end < as_of (= 2026-03-15)"
            prose(hartland, "OverdueInvoice", "cs") shouldBe
                "invoice status = 'open' a konec < as_of (= 15. 3. 2026)"
            prose(hartland, "ForeignReturnLine") shouldBe "no order line involving request order, item"
            prose(hartland, "DebtorCustomer") shouldBe
                "exists invoice involving customer as customer where overdue invoice"
            prose(hartland, "LateReturnLine") shouldBe
                "request time - request order end > return_window (= 30) days"
            prose(hartland, "ShortLine") shouldBe "outage or on hand < line quantity"
            // a construct the verbaliser lacks (`abs`) — the source text, quoted
            prose(hartland, "MispricedLine") shouldBe
                "\"list_price is null or abs(unit_price - list_price) > :tolerance\""
            prose(clinic, "FrequentPatient") shouldBe
                "number of (encounter involving patient where start within the last 30 days) >= 2"
            prose(clinic, "FrequentPatient", "cs") shouldBe
                "počet (hospitalizace, účastník pacient, kde začátek za posledních 30 dní) >= 2"
            prose(clinic, "IschemicRecord") shouldBe "code under diagnosis 'X20-X25'"
            prose(clinic, "HistoryOfLegFracture") shouldBe
                "exists fracture episode e involving patient where e period ended before as_of (= 2026-03-15)"
            // the DC-12 reading, when the definition declares `imprecision:`
            prose(clinic, "PostStemiPossibly") shouldBe
                "exists onset involving patient where onset code under diagnosis 'X21' and time at or before " +
                "as_of (= 2026-03-15) (possibly)"
            prose(clinic, "PostStemiCertainly", "cs") shouldBe
                "existuje začátek onemocnění, účastník pacient, kde onset code pod diagnóza 'X21' a čas nejpozději " +
                "k as_of (= 15. 3. 2026) (jistě)"
            prose(clinic, "Rehospitalized30d") shouldBe
                "exists encounter e1, encounter e2 involving patient where e1 != e2 and e2 start - e1 end <= 30 days"
            prose(clinic, "TransferredWithin24h") shouldBe
                "exists transfer t involving patient where t time from t from start until t from start + 24 hours"
            prose(clinic, "transfer_delay") shouldBe "time - from end"
        }

        // ---- T3: ambiguity, the hop rule ----------------------------------------------------------------------
        test("an ambiguous slot reads its candidates — and the refusal") {
            val frame =
                st(
                    "\"measures\": [{ \"candidates\": [{ \"qname\": \"cn.revenue\" }, { \"qname\": \"cn.gross_revenue\" }] }]",
                    jan,
                )
            said(sales, frame, "en") shouldBe
                "Net revenue or gross revenue? · January 2026 — refused: cn/ambiguous-statement (measures[0])."
            said(sales, frame, "cs") shouldBe
                "Čistý obrat nebo hrubý obrat? · leden 2026 — zamítnuto: cn/ambiguous-statement (measures[0])."
        }

        test("no md or er element name in any golden frame's explanation — a hop-bound slot says nothing about it") {
            val frames =
                GoldenCases.cases.filter {
                    it.call.startsWith("statement") || it.call.startsWith("assertion")
                }
            frames.size shouldBeGreaterThan 20
            for (c in frames) {
                val model = GoldenWorlds[c.world].cn
                val svc = CnConceptService(model, clock)
                val forbidden = elementNames(model)
                for (locale in listOf("en", "cs")) {
                    val text =
                        if (c.call.startsWith("statement")) {
                            svc.statementExplain(c.statement(), locale).text
                        } else {
                            svc.assertionExplain(c.assertion(), locale).text
                        }
                    val outside = text.replace(Regex("\"[^\"]*\""), "\"…\"")
                    withClue("${c.id} [$locale]: $text") {
                        Regex("\\b(md|er|db)\\.\\w").containsMatchIn(outside) shouldBe false
                        forbidden
                            .filter { Regex("(?<![\\w.])${Regex.escape(it)}(?![\\w])").containsMatchIn(outside) }
                            .shouldBeEmpty()
                    }
                }
            }
        }

        // ---- the locale ---------------------------------------------------------------------------------------
        test("a locale with no words reads en, with a warning; a region tag reads its language; blank — the frame's") {
            val svc = CnConceptService(sales, clock)
            val e0 = GoldenCases.cases.single { it.id == "e0/explain-cs" }.statement()
            val de = svc.statementExplain(e0, "de")
            de.locale shouldBe "en"
            de.text shouldBe "Net revenue · customer Kaufland · January 2026."
            de.diagnostics.map { "${it.slug} ${it.severity} ${it.subject}" } shouldBe
                listOf("cn/explain-locale-fallback WARNING locale")
            svc.statementExplain(e0, "cs-CZ").text shouldBe "Čistý obrat · zákazník Kaufland · leden 2026."
            // the E-0 frame says `locale: cs`
            svc.statementExplain(e0, "").let {
                it.locale shouldBe "cs"
                it.diagnostics.shouldBeEmpty()
            }
        }

        test("the label chain: the locale's label → the locale's lexicon term → the English label → the name") {
            val model =
                FrameValidationSpec.model(
                    mapOf(
                        "cn.ttrm" to
                            """
                            model cn
                            def kind Gadget { label: { en: "Gadget", cs: "Udělátko" } }
                            def kind Widget { label: { en: "Widget" } }
                            def kind Gizmo  { label: { en: "Gizmo" } }
                            def kind SpareThing {}
                            """.trimIndent(),
                        "lexicon-cs.ttrm" to
                            """
                            model lexicon locale cs
                            def term gizmo_a { for: cn.Gizmo, forms: ["vynález", "vynálezy"] }
                            def term gizmo_b { for: cn.Gizmo, forms: ["přístroj"], default: true }
                            """.trimIndent(),
                        "automation.ttrm" to
                            """
                            model automation
                            def parameter grace { type: int, value: 30, label: { en: "grace period", cs: "lhůta" } }
                            def parameter bare { type: int, value: 2 }
                            """.trimIndent(),
                    ),
                )
            val cs = ExplainLabels(model, ExplainWords.of("cs")!!)
            listOf("Gadget", "Widget", "Gizmo", "SpareThing").map { cs.of(cs.named(it)!!) } shouldBe
                listOf("Udělátko", "Widget", "přístroj", "spare thing")
            cs.parameter("grace") shouldBe "lhůta"
            cs.parameter("bare") shouldBe "bare"
            ExplainLabels(model, ExplainWords.ENGLISH).parameter("grace") shouldBe "grace period"
            ExplainLabels.lowerFirst("ICU ward") shouldBe "ICU ward"
            ExplainLabels.lowerFirst("Net revenue") shouldBe "net revenue"
        }

        // ---- T4: the assertion -------------------------------------------------------------------------------
        val minute = "\"period\": { \"start\": { \"value\": \"2026-03-14T09:30\", \"granularity\": \"MINUTE\" } }"

        test("a known identity, a quality with its unit, a boolean; refused — what it claims, then why") {
            claimed(
                clinic,
                "{ \"event\": { \"qname\": \"cn.Transfer\" }, " +
                    "\"identity\": { \"known\": { \"values\": { \"transfer_id\": \"77\" } } }, " +
                    "\"participants\": [{ \"label\": \"from\", " +
                    "\"member\": { \"label\": \"E-501\", \"resolvedValue\": \"501\" } }], " +
                    "$minute }",
                "en",
            ) shouldBe "Transfer 77 · from: Encounter E-501 · at 2026-03-14 09:30"
            claimed(
                clinic,
                "{ \"event\": { \"qname\": \"cn.Observation\" }, \"identity\": { \"new\": true }, " +
                    "\"participants\": [{ \"label\": \"patient\", \"member\": { \"resolvedValue\": \"1001\" } }], " +
                    "\"qualities\": [{ \"quality\": { \"qname\": \"cn.observed_value\" }, " +
                    "\"value\": { \"floatValue\": 5.40 }, " +
                    "\"unit\": \"mmol/L\" }], $minute }",
                "cs",
            ) shouldBe "Pozorování · patient: Pacient 1001 · 14. 3. 2026 09:30 · naměřená hodnota: 5.4 mmol/L"
            claimed(
                clinic,
                "{ \"event\": { \"qname\": \"cn.Transfer\" }, \"identity\": { \"new\": true }, " +
                    "\"participants\": [{ \"label\": \"from\", \"member\": { \"label\": \"E-9\" } }], $minute }",
                "en",
            ) shouldBe
                "Transfer · from: Encounter E-9? · at 2026-03-14 09:30 — refused: cn/ambiguous-assertion (participants[0].member)"
        }

        test("an episode: an end, an open end, an end that reads unknown") {
            val patient =
                "\"participants\": [{ \"label\": \"patient\", " +
                    "\"member\": { \"label\": \"P-1001\", \"resolvedValue\": \"1001\" } }]"
            val start = "\"start\": { \"value\": \"2026-03-01T08:00\", \"granularity\": \"MINUTE\" }"

            fun encounter(period: String) =
                "{ \"event\": { \"qname\": \"cn.Encounter\" }, \"identity\": { \"new\": true }, $patient, $period }"
            claimed(clinic, encounter("\"period\": { $start }"), "en") shouldBe
                "Encounter · patient: Patient P-1001 · from 2026-03-01 08:00 (ongoing)"
            claimed(clinic, encounter("\"period\": { $start }"), "cs") shouldBe
                "Hospitalizace · patient: Pacient P-1001 · od 1. 3. 2026 08:00 (trvá)"
            val ended = "\"period\": { $start, \"end\": { \"value\": \"2026-03-04\", \"granularity\": \"DAY\" } }"
            claimed(clinic, encounter(ended), "en") shouldBe
                "Encounter · patient: Patient P-1001 · from 2026-03-01 08:00 to 2026-03-04"
            val plus = FrameValidationSpec.world("clinic", AssertionLowerSpec.PLUS)
            claimed(
                plus,
                "{ \"event\": { \"qname\": \"cn.Stay\" }, \"identity\": { \"new\": true }, \"period\": { $start } }",
                "en",
            ) shouldBe
                "Stay · from 2026-03-01 08:00 (end unknown)"
        }
    }) {
    companion object {
        /**
         * The world's md and er element names a sentence could leak — those that cannot be prose (snake_case or
         * camelCase), less the names cn itself may say (a concept's, a quality's, a participant label, a parameter).
         */
        fun elementNames(model: ResolvedCnModel): Set<String> {
            val er = model.model.schemas["er"] as? ErSchema
            val md = model.md?.model
            val names =
                buildSet {
                    er?.entities?.values?.forEach { e ->
                        add(e.qname.name)
                        e.attributes.forEach { add(it.qname.name.substringAfterLast('.')) }
                    }
                    er?.relations?.values?.forEach { add(it.qname.name) }
                    md?.let { m ->
                        addAll(m.cubelets.keys)
                        addAll(m.measures.keys)
                        addAll(m.dimensions.keys)
                        addAll(m.hierarchies.keys)
                        m.attributes.keys.forEach { add(it.substringAfterLast('.')) }
                    }
                }
            val cn =
                buildSet {
                    model.cn.concepts.values.forEach { c ->
                        add(c.qname.name)
                        addAll(c.qualities)
                        c.involves.forEach { add(it.label) }
                    }
                    (model.model.schemas["automation"] as? org.tatrman.ttr.metadata.model.AutomationSchema)
                        ?.parameters
                        ?.forEach { add(it.qname.name) }
                }
            return names.filter { '_' in it || Regex("[a-z][A-Z]").containsMatchIn(it) }.filterNot { it in cn }.toSet()
        }
    }
}
