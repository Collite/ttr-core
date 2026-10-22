// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.assertion.v1.Assertion
import org.tatrman.concept.v1.EndState

/**
 * CN-P3 S3.5 — `assertionLower` branch by branch (T1–T3), over `clinic` and — for the shapes the goldens lack — [PLUS]:
 * an event whose `patient` runs two hops through its `encounter` (an obligation), a value-kind participant (a column of
 * the event's row), a participant whose relation the event's row does not hold and one that joins on two attributes
 * (refused), an imprecise instant with a stored upper bound, typed soft qualities, a period whose absent end reads
 * `unknown`. Invented, like the world. Every refusal lowering makes, `assertionValidate` reports too (A-CN-49).
 */
class AssertionLowerSpec :
    FunSpec({
        val clinic = GoldenWorlds["clinic"].cn
        val plus = FrameValidationSpec.world("clinic", PLUS)

        fun assertion(json: String): Assertion =
            Assertion.newBuilder().also { JsonFormat.parser().merge(json, it) }.build()

        fun lower(
            model: ResolvedCnModel,
            json: String,
        ): RowObjects = CnConceptService(model).assertionLower(assertion(json))

        fun shown(ds: List<Diagnostic>): List<String> = ds.map { "${it.slug} ${it.subject}" }

        /** Lowering refuses with [expected]; validate reports the same findings. */
        fun refused(
            model: ResolvedCnModel,
            json: String,
            vararg expected: String,
        ) {
            val r = lower(model, json)
            r.rows.shouldBeEmpty()
            shown(r.diagnostics) shouldBe expected.toList()
            shown(CnConceptService(model).assertionValidate(assertion(json))) shouldBe expected.toList()
        }

        fun frame(vararg parts: String) = parts.joinToString(", ", "{ ", " }")

        fun ev(qname: String) = "\"event\": { \"qname\": \"$qname\" }"

        val fresh = "\"identity\": { \"new\": true }"

        fun known(
            k: String,
            v: String,
        ) = "\"identity\": { \"known\": { \"values\": { \"$k\": \"$v\" } } }"

        fun part(
            label: String,
            value: String,
        ) = "{ \"label\": \"$label\", \"member\": { \"resolvedValue\": \"$value\" } }"

        fun parts(vararg ps: String) = "\"participants\": [${ps.joinToString(", ")}]"

        fun quality(
            name: String,
            value: String,
            verbatim: String = "",
        ) = "{ \"quality\": { \"qname\": \"cn.$name\" }, \"value\": $value" +
            (if (verbatim.isEmpty()) "" else ", \"verbatim\": \"$verbatim\"") + " }"

        fun quals(vararg qs: String) = "\"qualities\": [${qs.joinToString(", ")}]"

        fun at(
            value: String,
            g: String,
        ) = "\"period\": { \"start\": { \"value\": \"$value\", \"granularity\": \"$g\" } }"

        val minute = at("2026-03-14T09:30", "MINUTE")
        val transfer = ev("cn.Transfer")
        val move = ev("cn.BedMove")
        val march = at("2026-03", "MONTH")

        // ---- T1–T3 over the golden frame ------------------------------------------------------------------------
        test("the transfer: the event row, the as-of, the provenance and the quality's verbatim beside it") {
            val r =
                CnConceptService(clinic).assertionLower(
                    GoldenCases.cases
                        .single {
                            it.id ==
                                "transfer-assertion/transfer"
                        }.assertion(),
                )
            r.diagnostics.shouldBeEmpty()
            r.rows shouldBe
                listOf(
                    RowObject(
                        "transfer",
                        mapOf(
                            "patient_id" to "1001",
                            "from_encounter" to "501",
                            "to_encounter" to "502",
                            "at" to "2026-03-14T09:30",
                            "transfer_kind" to "step-down",
                        ),
                    ),
                )
            r.rows
                .single()
                .values.keys
                .toList() shouldBe listOf("patient_id", "from_encounter", "to_encounter", "at", "transfer_kind")
            r.assertedAsOf shouldBe "2026-03-14"
            r.meta.provenance?.producer shouldBe "manual:steward"
            r.meta.provenance?.sourceRef shouldBe "doc://discharge-0001#p2"
            r.meta.provenance
                ?.confidence
                ?.value shouldBe 0.9
            r.meta.locale shouldBe "en"
            r.meta.verbatims shouldBe mapOf("transfer_kind" to "moved to the step-down unit")
            r.meta.endState shouldBe EndState.END_STATE_UNSPECIFIED
            r.obligations.shouldBeEmpty()
        }

        test("a known identity: its key value first; the frame's derived_from carried, no as-of") {
            val r =
                CnConceptService(
                    clinic,
                ).assertionLower(GoldenCases.cases.single { it.id == "transfer-assertion/transfer-known" }.assertion())
            r.rows.single().values shouldBe
                mapOf(
                    "transfer_id" to "77",
                    "from_encounter" to "501",
                    "to_encounter" to "502",
                    "at" to "2026-03-14T09:30",
                )
            r.assertedAsOf shouldBe null
            r.meta.derivedFrom shouldBe listOf("a-0001")
        }

        test("a period and a quality on one column: written once when they agree, refused when they do not") {
            val same =
                quals(
                    quality(
                        "transferred_at",
                        "{ \"instantValue\": { \"value\": \"2026-03-14T09:30\", \"granularity\": \"MINUTE\" } }",
                    ),
                )
            lower(clinic, frame(transfer, fresh, minute, same)).rows.single().values shouldBe
                mapOf("at" to "2026-03-14T09:30")
            val other =
                quals(
                    quality(
                        "transferred_at",
                        "{ \"instantValue\": { \"value\": \"2026-03-14T09:45\", \"granularity\": \"MINUTE\" } }",
                    ),
                )
            refused(clinic, frame(transfer, fresh, minute, other), "cn/malformed-frame qualities[0].value")
            lower(clinic, frame(transfer, fresh, minute, other)).diagnostics.single().message shouldContain
                "`transfer.at` is written twice — `2026-03-14T09:30` by period.start, `2026-03-14T09:45` by qualities[0].value"
        }

        test("NULL: refused where the attribute is not optional") {
            refused(
                clinic,
                frame(transfer, fresh, minute, quals(quality("transfer_kind", "{ \"isNull\": true }"))),
                "cn/slot-mismatch qualities[0].value",
            )
        }

        test("a member's value its foreign key's type does not take") {
            refused(
                clinic,
                frame(transfer, fresh, minute, parts(part("from", "E-501"))),
                "cn/slot-mismatch participants[0].member",
            )
        }

        test("a validate refusal is lower's: no rows, the findings") {
            refused(
                clinic,
                frame(transfer, fresh, minute, parts("{ \"label\": \"from\", \"member\": { \"label\": \"E-9\" } }")),
                "cn/ambiguous-assertion participants[0].member",
            )
        }

        // ---- T2: the participant shapes -------------------------------------------------------------------------
        test(
            "a two-hop participant anchored by a one-hop one: nothing on the row, an obligation; a value kind: its column",
        ) {
            val r =
                lower(
                    plus,
                    frame(
                        move,
                        fresh,
                        parts(part("encounter", "501"), part("patient", "1001"), part("bed", "B-7")),
                        march,
                    ),
                )
            r.diagnostics.shouldBeEmpty()
            r.rows.single().values shouldBe
                mapOf("encounter_id" to "501", "bed" to "B-7", "moved_on" to "2026-03", "moved_on_hi" to "2026-04")
            r.obligations shouldBe
                listOf(
                    Obligation(
                        "patient",
                        "1001",
                        listOf("move_encounter", "encounter_patient"),
                        "encounter_id",
                        "encounter",
                    ),
                )
        }

        test("a two-hop participant nobody anchors is refused") {
            refused(
                plus,
                frame(move, fresh, parts(part("patient", "1001")), march),
                "cn/participant-path-broken participants[0]",
            )
        }

        test("a relation the event's row does not hold, and one on two attributes, are refused") {
            refused(
                plus,
                frame(move, fresh, parts(part("note", "9")), march),
                "cn/participant-path-broken participants[0]",
            )
            lower(plus, frame(move, fresh, parts(part("note", "9")), march)).diagnostics.single().message shouldContain
                "whose key `bed_note` holds"
            refused(
                plus,
                frame(move, fresh, parts(part("room", "4")), march),
                "cn/participant-path-broken participants[0]",
            )
            lower(plus, frame(move, fresh, parts(part("room", "4")), march)).diagnostics.single().message shouldContain
                "joins on 2 attributes"
        }

        // ---- T3: values by type, the period -----------------------------------------------------------------
        test("soft qualities by the attribute's type: an integer, a decimal, a boolean, text, NULL") {
            val r =
                lower(
                    plus,
                    frame(
                        move,
                        known("move_id", "12"),
                        march,
                        quals(
                            quality("severity", "{ \"intValue\": 3 }"),
                            quality("score", "{ \"floatValue\": 2.50 }"),
                            quality("urgent", "{ \"boolValue\": true }"),
                            quality("remark", "{ \"isNull\": true }"),
                            quality("ward", "{ \"stringValue\": \"5\" }"),
                        ),
                    ),
                )
            r.diagnostics.shouldBeEmpty()
            r.rows.single().values shouldBe
                mapOf(
                    "move_id" to "12",
                    "moved_on" to "2026-03",
                    "moved_on_hi" to "2026-04",
                    "severity" to "3",
                    "score" to "2.5",
                    "urgent" to "true",
                    "remark" to null,
                    "ward" to "5",
                )
            lower(plus, frame(move, fresh, march, quals(quality("score", "{ \"floatValue\": 1e10 }"))))
                .rows
                .single()
                .values["score"] shouldBe "10000000000"
        }

        test("values the attribute's type does not take") {
            val instant = "{ \"instantValue\": { \"value\": \"2026-03-14\", \"granularity\": \"DAY\" } }"
            refused(
                plus,
                frame(
                    move,
                    known("move_id", "abc"),
                    march,
                    quals(
                        quality("severity", "{ \"stringValue\": \"high\" }"),
                        quality("score", "{ \"boolValue\": true }"),
                        quality("urgent", "{ \"intValue\": 1 }"),
                        quality("ward", instant),
                    ),
                ),
                "cn/slot-mismatch identity.known",
                "cn/slot-mismatch qualities[0].value",
                "cn/slot-mismatch qualities[1].value",
                "cn/slot-mismatch qualities[2].value",
                "cn/slot-mismatch qualities[3].value",
            )
        }

        test("an absent end: NULL, the end state the event's `end_open:` reads (or the frame's) in the meta") {
            val adm = "\"period\": { \"start\": { \"value\": \"2026-03-01T08:00\", \"granularity\": \"MINUTE\" } }"
            val open = lower(clinic, frame(ev("cn.Encounter"), fresh, parts(part("patient", "1001")), adm))
            open.diagnostics.shouldBeEmpty()
            open.rows.single().values shouldBe
                mapOf("patient_id" to "1001", "admitted_at" to "2026-03-01T08:00", "discharged_at" to null)
            open.meta.endState shouldBe EndState.END_OPEN
            lower(plus, frame(ev("cn.Stay"), fresh, adm)).meta.endState shouldBe EndState.END_UNKNOWN
            val ended =
                "\"period\": { \"start\": { \"value\": \"2026-03-01T08:00\", \"granularity\": \"MINUTE\" }, " +
                    "\"end\": { \"value\": \"2026-03-04\", \"granularity\": \"DAY\" } }"
            val closed = lower(clinic, frame(ev("cn.Encounter"), fresh, ended))
            closed.rows.single().values["discharged_at"] shouldBe "2026-03-04"
            closed.meta.endState shouldBe EndState.END_STATE_UNSPECIFIED
        }

        test("a start-only event (`period: { start }`, no `end:`): its start alone; an end is malformed (R125-2/3)") {
            val start = "\"period\": { \"start\": { \"value\": \"2026-03-01\", \"granularity\": \"DAY\" } }"
            val r = lower(plus, frame(ev("cn.Admission"), fresh, start))
            r.diagnostics.shouldBeEmpty()
            r.rows.single().values shouldBe mapOf("admitted_on" to "2026-03-01")
            r.meta.endState shouldBe EndState.END_STATE_UNSPECIFIED
            CnConceptService(plus).assertionValidate(assertion(frame(ev("cn.Admission"), fresh, start))).shouldBeEmpty()
            val ended =
                "\"period\": { \"start\": { \"value\": \"2026-03-01\", \"granularity\": \"DAY\" }, " +
                    "\"end\": { \"value\": \"2026-03-04\", \"granularity\": \"DAY\" } }"
            refused(plus, frame(ev("cn.Admission"), fresh, ended), "cn/malformed-frame period.end")
            val open =
                "\"period\": { \"start\": { \"value\": \"2026-03-01\", \"granularity\": \"DAY\" }, " +
                    "\"endState\": \"END_OPEN\" }"
            refused(plus, frame(ev("cn.Admission"), fresh, open), "cn/malformed-frame period.end")
        }
    }) {
    companion object {
        /** `clinic` plus the shapes the golden world lacks. Invented, like the world. */
        val PLUS: Map<String, String> =
            mapOf(
                "plus/er.ttrm" to
                    """
                    model er
                    def entity bed_move {
                        attributes: [
                            def attribute move_id { type: int, isKey: true },
                            def attribute encounter_id { type: int },
                            def attribute bed { type: text, optional: true },
                            def attribute ward { type: int, optional: true },
                            def attribute room_no { type: int, optional: true },
                            def attribute moved_on { type: date },
                            def attribute moved_on_hi { type: date, optional: true },
                            def attribute severity { type: int, optional: true },
                            def attribute score { type: decimal, optional: true },
                            def attribute urgent { type: bool, optional: true },
                            def attribute remark { type: text, optional: true }
                        ]
                    }
                    def entity bed_note {
                        attributes: [ def attribute note_id { type: int, isKey: true }, def attribute move_id { type: int } ]
                    }
                    def entity room {
                        attributes: [ def attribute ward { type: int, isKey: true }, def attribute room_no { type: int, isKey: true } ]
                    }
                    def entity admission {
                        attributes: [ def attribute admission_id { type: int, isKey: true }, def attribute admitted_on { type: date } ]
                    }
                    def relation move_encounter { from: er.entity.bed_move, to: er.entity.encounter, join: [{ from: er.entity.bed_move.encounter_id, to: er.entity.encounter.encounter_id }] }
                    def relation note_move { from: er.entity.bed_note, to: er.entity.bed_move, join: [{ from: er.entity.bed_note.move_id, to: er.entity.bed_move.move_id }] }
                    def relation move_room { from: er.entity.bed_move, to: er.entity.room, join: [{ from: er.entity.bed_move.ward, to: er.entity.room.ward }, { from: er.entity.bed_move.room_no, to: er.entity.room.room_no }] }
                    """.trimIndent(),
                "plus/cn.ttrm" to
                    """
                    model cn
                    def kind Bed {}
                    def kind Note {}
                    def kind Room {}
                    def event BedMove {
                        involves: [Encounter, Patient, Bed, Note, Room],
                        period: { at: moved_on, precision: month },
                        qualities: [severity, score, urgent, remark, ward]
                    }
                    def event Stay {
                        extent: durative,
                        period: { start: stay_start, end: stay_end, end_open: unknown },
                        qualities: [stay_start, stay_end]
                    }
                    def event Admission {
                        extent: durative,
                        period: { start: admitted_on }
                    }
                    """.trimIndent(),
                "plus/bindings.ttrm" to
                    """
                    model binding
                    def cn2er_kind bed_g { kind: cn.Bed, target: er.entity.bed_move.bed }
                    def cn2er_kind note_g { kind: cn.Note, target: er.entity.bed_note }
                    def cn2er_kind room_g { kind: cn.Room, target: er.entity.room }
                    def cn2er_event bed_move_g {
                        event: cn.BedMove,
                        target: er.entity.bed_move,
                        period: { at: er.entity.bed_move.moved_on, at_hi: er.entity.bed_move.moved_on_hi },
                        participants: {
                            encounter: [er.relation.move_encounter],
                            patient: [er.relation.move_encounter, er.relation.encounter_patient],
                            note: [er.relation.note_move],
                            room: [er.relation.move_room]
                        }
                    }
                    def cn2er_event admission_g {
                        event: cn.Admission,
                        target: er.entity.admission,
                        period: { start: er.entity.admission.admitted_on }
                    }
                    def cn2er_event stay_g {
                        event: cn.Stay,
                        target: er.entity.encounter,
                        period: { start: er.entity.encounter.admitted_at, end: er.entity.encounter.discharged_at }
                    }
                    """.trimIndent(),
            )
    }
}
