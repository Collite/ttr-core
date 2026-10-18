// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.v1

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.assertion.v1.Assertion
import org.tatrman.assertion.v1.Identity
import org.tatrman.assertion.v1.KeyValues
import org.tatrman.assertion.v1.Participant
import org.tatrman.assertion.v1.Provenance
import org.tatrman.assertion.v1.QualityValue
import org.tatrman.statement.v1.Ask
import org.tatrman.statement.v1.BoolGroup
import org.tatrman.statement.v1.BoolOp
import org.tatrman.statement.v1.Compare
import org.tatrman.statement.v1.CompareMode
import org.tatrman.statement.v1.ComparisonOp
import org.tatrman.statement.v1.DateRange
import org.tatrman.statement.v1.ExprFilter
import org.tatrman.statement.v1.Filter
import org.tatrman.statement.v1.MemberFilter
import org.tatrman.statement.v1.MemberPair
import org.tatrman.statement.v1.OrderKey
import org.tatrman.statement.v1.OrderLimit
import org.tatrman.statement.v1.PhaseFilter
import org.tatrman.statement.v1.QualityFilter
import org.tatrman.statement.v1.Statement
import org.tatrman.statement.v1.TimeSpec

/**
 * CN-P0 S0.4 T4 (cn contracts §2) — every message of the three cn frame packages survives protobuf JsonFormat's
 * CANONICAL JSON (the door/agent mapping) losslessly, and printing is stable (print ∘ parse ∘ print = print).
 * Every message is built fully populated, so a field that does not round-trip cannot hide behind a default.
 */
class CnFramesJsonRoundTripSpec :
    FunSpec({
        val printer = JsonFormat.printer()
        val parser = JsonFormat.parser()

        fun roundTrip(m: Message): Message {
            val b = m.newBuilderForType()
            parser.merge(printer.print(m), b)
            return b.build()
        }

        // ---- concept.v1 ----
        val confidence =
            Confidence
                .newBuilder()
                .setValue(0.92)
                .setProducer("resolver")
                .setMethod("TYPOS(1)")
                .build()
        val candidate =
            Candidate
                .newBuilder()
                .setQname(
                    "cn.DebtorCustomer",
                ).setConfidence(confidence)
                .setResumeToken("rt-1")
                .build()
        val ambiguous =
            ConceptRef
                .newBuilder()
                .addCandidates(
                    candidate,
                ).addCandidates(candidate.toBuilder().setQname("cn.Customer"))
                .build()
        val conceptRef = ConceptRef.newBuilder().setQname("cn.revenue").build()
        val member =
            MemberRef
                .newBuilder()
                .setLabel("Acme Market")
                .setResolvedValue("A123")
                .setConfidence(confidence)
                .setSource("MEMBER")
                .setResumeToken("rt-2")
                .build()
        val month =
            Instant
                .newBuilder()
                .setValue("2024-03")
                .setGranularity(Granularity.MONTH)
                .build()
        val day =
            Instant
                .newBuilder()
                .setValue("2024-03-15")
                .setGranularity(Granularity.DAY)
                .build()
        val period =
            Period
                .newBuilder()
                .setStart(month)
                .setEnd(day)
                .setEndState(EndState.END_UNKNOWN)
                .setPrecision(Granularity.MONTH)
                .build()
        val value = Value.newBuilder().setInstantValue(day).build()

        // ---- statement.v1 ----
        val memberFilter =
            MemberFilter
                .newBuilder()
                .setConcept(
                    ConceptRef.newBuilder().setQname("cn.Customer"),
                ).setMember(member)
                .build()
        val qualityFilter =
            QualityFilter
                .newBuilder()
                .setQuality(conceptRef)
                .setOp(ComparisonOp.GT)
                .setValue(Value.newBuilder().setFloatValue(1000.0))
                .build()
        val phaseFilter =
            PhaseFilter
                .newBuilder()
                .setPhase(
                    ConceptRef.newBuilder().setQname("cn.WebSale"),
                ).setNegated(true)
                .build()
        val exprFilter = ExprFilter.newBuilder().setPlExpr("revenue > 0").build()
        val group =
            BoolGroup
                .newBuilder()
                .setOp(BoolOp.ANY)
                .addFilters(Filter.newBuilder().setPhase(phaseFilter))
                .addFilters(Filter.newBuilder().setExpr(exprFilter))
                .build()
        val range =
            DateRange
                .newBuilder()
                .setFrom(day)
                .setTo(day.toBuilder().setValue("2024-04-01"))
                .build()
        val orderKey =
            OrderKey
                .newBuilder()
                .setBy(conceptRef)
                .setDescending(true)
                .build()
        val orderLimit =
            OrderLimit
                .newBuilder()
                .addBy(orderKey)
                .setLimit(10)
                .build()
        val pair =
            MemberPair
                .newBuilder()
                .setLeft(
                    member,
                ).setRight(member.toBuilder().setLabel("Globex").setResolvedValue("G7"))
                .build()
        val compare =
            Compare
                .newBuilder()
                .setMembers(pair)
                .setMode(CompareMode.DELTA)
                .build()
        val statement =
            Statement
                .newBuilder()
                .setAsk(Ask.ASK_VALUE)
                .addMeasures(conceptRef)
                .addFilters(Filter.newBuilder().setMember(memberFilter))
                .addFilters(Filter.newBuilder().setQuality(qualityFilter))
                .addFilters(Filter.newBuilder().setGroup(group))
                .setTime(TimeSpec.newBuilder().setRange(range))
                .addGroupBy(ConceptRef.newBuilder().setQname("cn.Channel"))
                .setOrderLimit(orderLimit)
                .setCompare(compare)
                .setSnapshotHash("sha256:00")
                .setLocale("cs")
                .setAsOf(day)
                .build()

        // ---- assertion.v1 ----
        val provenance =
            Provenance
                .newBuilder()
                .setProducer("evidence:extractor")
                .setSourceRef("doc://d-1#p3")
                .setRunId("run-7")
                .setConfidence(confidence)
                .setVerbatim("přeložen z JIP")
                .build()
        val participant =
            Participant
                .newBuilder()
                .setLabel("from")
                .setConcept(ConceptRef.newBuilder().setQname("cn.Encounter"))
                .setMember(member)
                .build()
        val qualityValue =
            QualityValue
                .newBuilder()
                .setQuality(ConceptRef.newBuilder().setQname("cn.transfer_delay"))
                .setValue(Value.newBuilder().setIntValue(90))
                .setUnit("min")
                .setVerbatim("90 minut")
                .build()
        val known = KeyValues.newBuilder().putValues("transfer_id", "T-1").build()
        val assertion =
            Assertion
                .newBuilder()
                .setEvent(ConceptRef.newBuilder().setQname("cn.Transfer"))
                .setIdentity(Identity.newBuilder().setKnown(known))
                .addParticipants(participant)
                .addQualities(qualityValue)
                .setPeriod(period)
                .setAssertedAsOf(day)
                .setProvenance(provenance)
                .setSnapshotHash("sha256:01")
                .setLocale("cs")
                .addDerivedFrom("a-0")
                .build()

        val messages: List<Pair<String, Message>> =
            listOf(
                "concept.v1.ConceptRef" to conceptRef,
                "concept.v1.ConceptRef (ambiguous)" to ambiguous,
                "concept.v1.Candidate" to candidate,
                "concept.v1.Confidence" to confidence,
                "concept.v1.MemberRef" to member,
                "concept.v1.Instant" to month,
                "concept.v1.Period" to period,
                "concept.v1.Value" to value,
                "statement.v1.Statement" to statement,
                "statement.v1.Filter (member)" to Filter.newBuilder().setMember(memberFilter).build(),
                "statement.v1.MemberFilter" to memberFilter,
                "statement.v1.QualityFilter" to qualityFilter,
                "statement.v1.PhaseFilter" to phaseFilter,
                "statement.v1.BoolGroup" to group,
                "statement.v1.ExprFilter" to exprFilter,
                "statement.v1.TimeSpec (all_time)" to TimeSpec.newBuilder().setAllTime(true).build(),
                "statement.v1.DateRange" to range,
                "statement.v1.OrderKey" to orderKey,
                "statement.v1.OrderLimit" to orderLimit,
                "statement.v1.MemberPair" to pair,
                "statement.v1.Compare" to compare,
                "assertion.v1.Assertion" to assertion,
                "assertion.v1.Identity (new)" to Identity.newBuilder().setNew(true).build(),
                "assertion.v1.KeyValues" to known,
                "assertion.v1.Participant" to participant,
                "assertion.v1.QualityValue" to qualityValue,
                "assertion.v1.Provenance" to provenance,
            )

        for ((name, m) in messages) {
            test("$name — canonical JSON round-trips losslessly and prints stably") {
                val back = roundTrip(m)
                back shouldBe m
                printer.print(back) shouldBe printer.print(m)
            }
        }

        test("statement.v1 — as_of is field 10 and ASK_SET keeps number 2 (reserved, rejected by validate in v1)") {
            Statement.getDescriptor().findFieldByName("as_of").number shouldBe 10
            Ask.ASK_SET.number shouldBe 2
            printer.print(statement) shouldContain "\"asOf\""
        }

        test("assertion.v1 — asserted_as_of is a field of its own, apart from period (AM-38)") {
            Assertion.getDescriptor().findFieldByName("asserted_as_of").number shouldBe 6
            Assertion.getDescriptor().findFieldByName("period").number shouldBe 5
        }
    })
