// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

/**
 * MW-P5 S5.2 review-350 F1 (⚑MW-P5-S52-4 → ✅D-MW-87; profiles contracts §2/§3): a generic reader skips what it does not
 * understand and says so. `kinds` gains `unmapped: error | skip` (default `error`) and picks the first mapped element of
 * a list-valued kind; `each … key:` gains `missing: <literal>`; the report gains `warnings[]`.
 */
class UnmappedKindsEachKeysSpec :
    FunSpec({
        val pkg = "- {op: package, mapping: {}, default: demo}\n"

        fun terms(
            kinds: String,
            vararg more: String,
        ) = pkg + "- {op: kinds, at: /pojmy, id: /id, $kinds}\n" + more.joinToString("") { "$it\n" }

        test("unmapped absent = error: an unmapped or missing kind is ys/unknown-kind, as before") {
            val ops = terms("kind: /stav, mapping: {Approved: kind}")
            shouldThrow<ProfileException> { runOps("pojmy:\n  - {id: X, stav: Retired}\n", ops) }.let {
                it.code shouldBe "ys/unknown-kind"
                it.detail shouldBe "Unmapped record kind Retired"
            }
            shouldThrow<ProfileException> { runOps("pojmy:\n  - {id: X}\n", ops) }.let {
                it.code shouldBe "ys/unknown-kind"
                it.detail shouldBe "Unmapped record kind: no string at /stav"
            }
        }

        test("unmapped: skip lists an unmapped kind and a missing one in ignored[], record named; mapped ones stay") {
            val result =
                runOps(
                    "pojmy:\n  - {id: A, stav: Approved}\n  - {id: R, stav: Retired, x: 1}\n  - {id: M, y: 2}\n" +
                        "  - {id: D, stav: Draft}\n",
                    terms("kind: /stav, unmapped: skip, mapping: {Approved: kind, Draft: null}"),
                )
            result.defs().map { it.string("id") } shouldBe listOf("A")
            result.ignored.map { Triple(it.record, it.reason, it.location.path) } shouldBe
                listOf(
                    Triple("R", "kind unmapped (Retired)", "/pojmy/1"),
                    Triple("M", "kind missing (/stav)", "/pojmy/2"),
                    Triple("D", "kind mapping null (/stav = Draft)", "/pojmy/3"),
                )
            result.reportJson().containsKey("warnings") shouldBe false
        }

        test("a list-valued kind takes its first mapped element in list order; a null mapping counts as mapped") {
            val star =
                terms(
                    "kind: /typ/*/@l, unmapped: skip, mapping: {Concept: kind, Scheme: null}",
                    "- {op: ignore, in: kind, at: /typ}",
                )
            val result =
                runOps(
                    "pojmy:\n" +
                        "  - {id: A, typ: [{'@l': Class}, {'@l': Concept}]}\n" +
                        "  - {id: B, typ: [{'@l': Concept}, {'@l': Scheme}]}\n" +
                        "  - {id: S, typ: [{'@l': Thing}, {'@l': Scheme}, {'@l': Concept}]}\n" +
                        "  - {id: U, typ: [{'@l': Class}, {'@l': Thing}]}\n" +
                        "  - {id: E, typ: []}\n",
                    star,
                )
            result.defs().map { it.string("id") } shouldBe listOf("A", "B")
            result.ignored.filter { it.record != null }.map { it.record to it.reason } shouldBe
                listOf(
                    "S" to "kind mapping null (/typ/*/@l = Scheme)",
                    "U" to "kind unmapped (Class, Thing)",
                    "E" to "kind missing (/typ/*/@l)",
                )
            // A pointer that selects a list of strings picks the same way and consumes the list.
            runOps("pojmy:\n  - {id: A, typ: [X, Approved]}\n", terms("kind: /typ, mapping: {Approved: kind}"))
                .defs()
                .single()
                .string("kind") shouldBe "kind"
            // With unmapped absent, a list with no mapped element is ys/unknown-kind, naming its values.
            shouldThrow<ProfileException> {
                runOps(
                    "pojmy:\n  - {id: U, typ: [{'@l': Class}, {'@l': Thing}]}\n",
                    star.replace("unmapped: skip, ", ""),
                )
            }.detail shouldContain "Unmapped record kind Class, Thing"
        }

        test("each missing: <literal> keys an element without one, with one warning per record in the report") {
            val ops =
                terms(
                    "kind: /stav, mapping: {Approved: kind}",
                    "- {op: each, in: kind, at: /popisy, key: /@language, missing: en, " +
                        "to: {kind: kind, property: description}, shape: {input: \$item/@value}}",
                )
            val result =
                runOps(
                    "pojmy:\n  - id: A\n    stav: Approved\n    popisy:\n" +
                        "      - {'@value': Objednávka, '@language': cs}\n      - {'@value': Order}\n",
                    ops,
                )
            result
                .defs()
                .single()
                .getValue("properties")
                .toString() shouldContain
                "{\"description\":{\"cs\":\"Objednávka\",\"en\":\"Order\"}}"
            result.report.warnings.map { it.message to it.record } shouldBe listOf("each key missing → en" to "A")
            result.reportJson().getValue("warnings").jsonArray.single().jsonObject.let {
                it.getValue("record").jsonPrimitive.content shouldBe "A"
                it.getValue("message").jsonPrimitive.content shouldBe "each key missing → en"
                it.getValue("path").jsonPrimitive.content shouldBe "/pojmy/0/popisy"
            }
        }
    })
