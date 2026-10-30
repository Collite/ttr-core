// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.v1

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.assertion.v1.Assertion

/**
 * CN-P8 · review-175 F8 (⚑B) — `assertion.v1` field 1 `event` became `concept` in translator 0.13.0, a BREAKING change
 * on purpose: the binary encoding is unchanged, but the JSON key and the generated accessors are not, and there is no
 * `event` alias. These tests pin both halves, so nobody "fixes" the break into an alias.
 */
class AssertionConceptRenameSpec :
    FunSpec({
        test("field 1 keeps its number and wire bytes: an old `event` frame parses as `concept`") {
            Assertion.getDescriptor().findFieldByNumber(1).name shouldBe "concept"
            // field 1 (len 13) → ConceptRef field 1 (len 11) "cn.Transfer" — what a pre-0.13 writer sent as `event`
            val legacy = "0a0d0a0b636e2e5472616e73666572".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val parsed = Assertion.parseFrom(legacy)
            parsed.concept.qname shouldBe "cn.Transfer"
            parsed.toByteArray().toList() shouldBe legacy.toList()
        }

        test("the JSON key is `concept`; a strict parser refuses the old `event` key — no alias") {
            val printed = JsonFormat.printer().print(Assertion.newBuilder().setConcept(ref("cn.Transfer")).build())
            printed shouldContain "\"concept\""
            val old = """{ "event": { "qname": "cn.Transfer" }, "identity": { "new": true } }"""
            val e =
                shouldThrow<InvalidProtocolBufferException> {
                    JsonFormat.parser().merge(old, Assertion.newBuilder())
                }
            e.message shouldContain "event"
            // a lenient parser drops the key: the frame then names no concept at all
            val lenient = Assertion.newBuilder().also { JsonFormat.parser().ignoringUnknownFields().merge(old, it) }
            lenient.hasConcept() shouldBe false
        }
    })

private fun ref(qname: String): ConceptRef = ConceptRef.newBuilder().setQname(qname).build()
