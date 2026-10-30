// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.assertion.v1.Assertion

/**
 * review-175 — claims over a same-row participant (F1: `order: []`, the participant is the claim's own row) and over a
 * subkind (F7, ⚑D: the claim lands through its bearer's grounding; its definition is an obligation the row satisfies —
 * a settable discriminator is written, any other definition refuses). [STARTS] is invented, beside the time world's
 * tickets; Hartland's `OrderPlaced` and `WebSale` are the shipped world's.
 */
class SubkindAssertionSpec :
    FunSpec({
        val hartland = GoldenWorlds["hartland"].cn
        val starts = FrameValidationSpec.model(STARTS)

        fun assertion(json: String): Assertion =
            Assertion
                .newBuilder()
                .also { JsonFormat.parser().merge(json, it) }
                .build()

        fun shown(ds: List<Diagnostic>): List<String> = ds.map { "${it.slug} ${it.subject}" }

        fun frame(vararg parts: String) = parts.joinToString(", ", "{ ", " }")

        fun concept(qname: String) = "\"concept\": { \"qname\": \"$qname\" }"

        fun known(
            k: String,
            v: String,
        ) = "\"identity\": { \"known\": { \"values\": { \"$k\": \"$v\" } } }"

        val fresh = "\"identity\": { \"new\": true }"

        fun part(
            label: String,
            value: String,
        ) = "{ \"label\": \"$label\", \"member\": { \"resolvedValue\": \"$value\" } }"

        fun parts(vararg ps: String) = "\"participants\": [${ps.joinToString(", ")}]"

        fun quality(
            name: String,
            value: String,
        ) = "{ \"quality\": { \"qname\": \"cn.$name\" }, \"value\": { \"stringValue\": \"$value\" } }"

        fun quals(vararg qs: Pair<String, String>) =
            "\"qualities\": [" + qs.joinToString(", ") { (n, v) -> quality(n, v) } + "]"

        fun at(
            value: String,
            g: String,
        ) = "\"period\": { \"start\": { \"value\": \"$value\", \"granularity\": \"$g\" } }"

        fun lower(
            model: ResolvedCnModel,
            json: String,
        ) = CnConceptService(model).assertionLower(assertion(json))

        fun validate(
            model: ResolvedCnModel,
            json: String,
        ) = CnConceptService(model).assertionValidate(assertion(json))

        // ---- F1: a same-row participant ------------------------------------------------------------------------

        test("F1: an OrderPlaced claim naming its own order row validates and lowers the order's key") {
            val json =
                frame(
                    concept("cn.OrderPlaced"),
                    known("order_number", "17"),
                    parts(part("order", "17"), part("customer", "5")),
                    at("2026-03-14", "DAY"),
                )
            validate(hartland, json).shouldBeEmpty()
            val r = lower(hartland, json)
            r.diagnostics.shouldBeEmpty()
            r.rows.single().entity shouldBe "order"
            r.rows.single().values shouldBe
                linkedMapOf("order_number" to "17", "customer" to "5", "placed_date" to "2026-03-14")
        }

        test("F1: the same-row member is the row's key — another key value is written twice; a new identity refuses") {
            val other =
                frame(
                    concept("cn.OrderPlaced"),
                    known("order_number", "17"),
                    parts(part("order", "18")),
                    at("2026-03-14", "DAY"),
                )
            shown(lower(hartland, other).diagnostics) shouldBe listOf("cn/malformed-frame participants[0].member")
            val new = frame(concept("cn.OrderPlaced"), fresh, parts(part("order", "17")), at("2026-03-14", "DAY"))
            val r = lower(hartland, new)
            r.rows.shouldBeEmpty()
            shown(r.diagnostics) shouldBe listOf("cn/malformed-frame participants[0]")
            shown(validate(hartland, new)) shouldBe listOf("cn/malformed-frame participants[0]")
        }

        // ---- F7: subkind claims (⚑D) ----------------------------------------------------------------------------

        test("F7: a subkind claim lands through its bearer's grounding and writes its discriminator") {
            val json =
                frame(
                    concept("cn.FirstStart"),
                    known("id", "e1"),
                    parts(part("ticket", "T1")),
                    at("2026-03-14T09:30", "MINUTE"),
                )
            validate(starts, json).shouldBeEmpty()
            val r = lower(starts, json)
            r.diagnostics.shouldBeEmpty()
            r.rows.single().entity shouldBe "started"
            r.rows.single().values shouldBe
                linkedMapOf("id" to "e1", "subject" to "T1", "occurred" to "2026-03-14T09:30", "start_id" to "S1")
        }

        test("F7: a claim writing the discriminator's own value is the same row; another value is a slot mismatch") {
            val same =
                frame(
                    concept("cn.FirstStart"),
                    known("id", "e1"),
                    parts(part("ticket", "T1")),
                    at("2026-03-14T09:30", "MINUTE"),
                    quals("start_id" to "S1"),
                )
            lower(starts, same).rows.single().values["start_id"] shouldBe "S1"
            val other =
                frame(
                    concept("cn.FirstStart"),
                    known("id", "e1"),
                    parts(part("ticket", "T1")),
                    at("2026-03-14T09:30", "MINUTE"),
                    quals("start_id" to "S2"),
                )
            val r = lower(starts, other)
            r.rows.shouldBeEmpty()
            shown(r.diagnostics) shouldBe listOf("cn/slot-mismatch qualities[0].value")
            r.diagnostics.single().message shouldContain "`cn.FirstStart` is `start_id = 'S1'`"
            shown(validate(starts, other)) shouldBe listOf("cn/slot-mismatch qualities[0].value")
        }

        test("F7: a subkind of a subkind collects every definition up its chain (`this.<name>` alike)") {
            val json =
                frame(
                    concept("cn.FirstWebStart"),
                    known("id", "e2"),
                    parts(part("ticket", "T2")),
                    at("2026-03-14T09:30", "MINUTE"),
                )
            lower(starts, json).rows.single().values shouldBe
                linkedMapOf(
                    "id" to "e2",
                    "subject" to "T2",
                    "occurred" to "2026-03-14T09:30",
                    "channel" to "web",
                    "start_id" to "S1",
                )
        }

        test("F7: a definition that is not a settable discriminator refuses as not-assertable, naming the bearer") {
            for (name in listOf("RestartedStart", "PairedStart")) {
                val json =
                    frame(
                        concept("cn.$name"),
                        known("id", "e3"),
                        parts(part("ticket", "T3")),
                        at("2026-03-14T09:30", "MINUTE"),
                    )
                val r = lower(starts, json)
                r.rows.shouldBeEmpty()
                shown(r.diagnostics) shouldBe listOf("cn/not-assertable concept")
                r.diagnostics.single().message shouldBe
                    "`cn.$name`'s definition is not a settable discriminator; claim `cn.Started`"
                shown(validate(starts, json)) shouldBe listOf("cn/not-assertable concept")
            }
        }

        test("R1: a settable `members:` block is a discriminator; another value mismatches") {
            val json =
                frame(
                    concept("cn.MemberStart"),
                    known("id", "e4"),
                    parts(part("ticket", "T4")),
                    at("2026-03-14T09:30", "MINUTE"),
                )
            validate(starts, json).shouldBeEmpty()
            lower(starts, json).rows.single().values shouldBe
                linkedMapOf("id" to "e4", "subject" to "T4", "occurred" to "2026-03-14T09:30", "start_id" to "S9")
            val other =
                frame(
                    concept("cn.MemberStart"),
                    known("id", "e4"),
                    parts(part("ticket", "T4")),
                    at("2026-03-14T09:30", "MINUTE"),
                    quals("start_id" to "S1"),
                )
            shown(lower(starts, other).diagnostics) shouldBe listOf("cn/slot-mismatch qualities[0].value")
        }

        test("R1: a block-less subkind with no entity of its own refuses as not-assertable") {
            val json =
                frame(
                    concept("cn.BareStart"),
                    known("id", "e5"),
                    parts(part("ticket", "T5")),
                    at("2026-03-14T09:30", "MINUTE"),
                )
            val r = lower(starts, json)
            r.rows.shouldBeEmpty()
            shown(r.diagnostics) shouldBe listOf("cn/not-assertable concept")
            r.diagnostics.single().message shouldBe
                "`cn.BareStart` has no definition and no entity of its own; claim `cn.Started`"
            shown(validate(starts, json)) shouldBe listOf("cn/not-assertable concept")
        }

        test("R1: a block-less subkind on its own stacked entity lowers onto that entity") {
            val json =
                frame(
                    concept("cn.EscalatedStart"),
                    known("id", "e6"),
                    parts(part("ticket", "T6")),
                    at("2026-03-14T09:30", "MINUTE"),
                )
            validate(starts, json).shouldBeEmpty()
            val r = lower(starts, json)
            r.diagnostics.shouldBeEmpty()
            r.rows.single().entity shouldBe "escalated"
            r.rows.single().values shouldBe
                linkedMapOf("id" to "e6", "subject" to "T6", "occurred" to "2026-03-14T09:30")
            // Hartland's Invoice is block-less too and lands on its own entity (a `cn2er_kind`): no obligation
            val invoice =
                frame(
                    concept("cn.Invoice"),
                    known("invoice_id", "3"),
                    parts(part("customer", "5"), part("order", "17")),
                    "\"period\": { \"start\": { \"value\": \"2026-03-14\", \"granularity\": \"DAY\" } }",
                )
            shown(lower(hartland, invoice).diagnostics).filter { "not-assertable" in it }.shouldBeEmpty()
        }

        test("F7: Hartland's WebSale lowers a Sale row with its channel discriminator; a store channel mismatches") {
            val json =
                frame(
                    concept("cn.WebSale"),
                    known("sale_id", "9"),
                    parts(part("customer", "5"), part("item", "7")),
                    at("2026-03-14", "DAY"),
                )
            validate(hartland, json).shouldBeEmpty()
            val r = lower(hartland, json)
            r.diagnostics.shouldBeEmpty()
            r.rows.single().entity shouldBe "sales"
            r.rows.single().values shouldBe
                linkedMapOf(
                    "sale_id" to "9",
                    "customer" to "5",
                    "item" to "7",
                    "sold_date" to "2026-03-14",
                    "channel" to "web",
                )
            val store =
                frame(
                    concept("cn.WebSale"),
                    known("sale_id", "9"),
                    parts(part("customer", "5"), part("item", "7"), part("channel", "store")),
                    at("2026-03-14", "DAY"),
                )
            shown(lower(hartland, store).diagnostics) shouldBe listOf("cn/slot-mismatch participants[2].member")
        }

        test("F7: assertionExplain names the claimed subkind, not its bearer") {
            val json =
                frame(
                    concept("cn.WebSale"),
                    known("sale_id", "9"),
                    parts(part("customer", "5"), part("item", "7")),
                    at("2026-03-14", "DAY"),
                )
            val en = CnConceptService(hartland).assertionExplain(assertion(json), "en").text
            en shouldContain "Web sale"
            en shouldNotContain "Sale 9"
            CnConceptService(hartland).assertionExplain(assertion(json), "cs").text shouldContain "Prodej na webu"
        }
    }) {
    companion object {
        /**
         * The time world's tickets with an invented start stream: two settable subkinds, two that are not; a `members:`
         * discriminator, a block-less subkind, and one on its own stacked entity (review-175 R1).
         */
        val STARTS =
            mapOf(
                "er.ttrm" to
                    """
                    model er
                    def entity ticket { attributes: [def attribute id { type: text, isKey: true }] }
                    def entity started { attributes: [def attribute id { type: text, isKey: true }, def attribute subject { type: text }, def attribute start_id { type: text }, def attribute channel { type: text }, def attribute occurred { type: datetime }] }
                    def entity finished { attributes: [def attribute id { type: text, isKey: true }, def attribute subject { type: text }, def attribute occurred { type: datetime }] }
                    def relation started_ticket { from: er.entity.started, to: er.entity.ticket, join: [{ from: er.entity.started.subject, to: er.entity.ticket.id }] }
                    def relation finished_ticket { from: er.entity.finished, to: er.entity.ticket, join: [{ from: er.entity.finished.subject, to: er.entity.ticket.id }] }
                    def entity escalated { attributes: [def attribute id { type: text, isKey: true }, def attribute subject { type: text }, def attribute occurred { type: datetime }] }
                    def relation escalated_ticket { from: er.entity.escalated, to: er.entity.ticket, join: [{ from: er.entity.escalated.subject, to: er.entity.ticket.id }] }
                    """.trimIndent(),
                "cn.ttrm" to
                    """
                    model cn
                    def kind Ticket { grounds: er.entity.ticket }
                    def event Started { involves: [Ticket], period: { at: occurred }, qualities: [start_id, channel] }
                    def event Finished { involves: [Ticket], period: { at: occurred } }
                    def subkind FirstStart { of: Started, definition: ""${'"'}start_id = 'S1'""${'"'} }
                    def subkind FirstWebStart { of: FirstStart, definition: ""${'"'}this.channel = 'web'""${'"'} }
                    def subkind RestartedStart { of: Started, definition: ""${'"'}exists Finished involving this.ticket""${'"'} }
                    def subkind PairedStart { of: Started, definition: ""${'"'}start_id = 'S1' and channel = 'web'""${'"'} }
                    def subkind MemberStart { of: Started, members: ""${'"'}start_id = 'S9'""${'"'} }
                    def subkind BareStart { of: Started }
                    def subkind EscalatedStart { of: Started }
                    """.trimIndent(),
                "bindings.ttrm" to
                    """
                    model binding
                    def cn2er_event started { event: cn.Started, target: er.entity.started, period: { at: er.entity.started.occurred }, participants: { ticket: [er.relation.started_ticket] } }
                    def cn2er_event finished { event: cn.Finished, target: er.entity.finished, period: { at: er.entity.finished.occurred }, participants: { ticket: [er.relation.finished_ticket] } }
                    def cn2er_phase escalated { phase: cn.EscalatedStart, target: er.entity.escalated, period: { at: er.entity.escalated.occurred }, participants: { ticket: [er.relation.escalated_ticket] } }
                    """.trimIndent(),
            )
    }
}
