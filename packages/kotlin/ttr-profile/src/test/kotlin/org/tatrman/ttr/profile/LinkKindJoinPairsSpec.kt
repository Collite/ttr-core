// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

/**
 * MW S3.7b T1 — (a) `link`'s `kind:` operand (✅D-MW-72): it names the referent kind where the target property names none
 * (`relation.from/to`), so an entity and its `er2db_entity` (✅D-MW-67 fan-out) no longer make every reference ambiguous.
 * (c) the `join-pairs` op (✅D-MW-77, P-8 amended to ten operations): exactly `a.x = b.y [AND c.z = d.w …]` →
 * `relation.join`, anything else refused at the value (`xm/join-pair-shape`).
 */
class LinkKindJoinPairsSpec :
    FunSpec({
        val fanout =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                "- {op: kinds, at: /entity, mapping: {entita: er2db_entity}}\n" +
                "- {op: kinds, in: entity, at: /sloupce, mapping: {sloupec: attribute}}\n" +
                "- {op: kinds, at: /vztahy, mapping: {vztah: relation}}\n"
        val model = "entity:\n  Objednavka: {sloupce: {Zakaznik: {}}}\n  Zakaznik: {}\n"

        fun run(
            source: String,
            ops: String,
        ) = ProfileEngine.apply(listOf(ForeignTree.read(source, "one.yaml")), listOf(recordProfile(ops)))

        fun relation(result: ApplyResult) =
            result
                .defs()
                .single {
                    it.string("kind") == "relation"
                }.getValue("properties")
                .jsonObject

        fun link(
            at: String,
            property: String,
            kind: String? = null,
        ) = "- {op: link, in: relation, at: /$at, to: {kind: relation, property: $property}, by: sourceName" +
            (kind?.let { ", kind: $it" } ?: "") + "}\n"

        test("(a) kind: entity picks the entity over its er2db_entity and over an attribute of the same name") {
            val result =
                run(
                    model + "vztahy:\n  R: {od: Objednavka, do: Zakaznik}\n",
                    fanout + link("od", "from", "entity") + link("do", "to", "entity"),
                )
            relation(result).getValue("from") shouldBe JsonPrimitive("demo.er.entity.Objednavka")
            relation(result).getValue("to") shouldBe JsonPrimitive("demo.er.entity.Zakaznik")
        }

        test("(a) without kind: the shared name stays xm/link-ambiguous (today's behaviour, unchanged)") {
            val ambiguous =
                shouldThrow<ProfileException> {
                    run(
                        model + "vztahy:\n  R: {od: Objednavka, do: Zakaznik}\n",
                        fanout + link("od", "from") + link("do", "to"),
                    )
                }
            ambiguous.code shouldBe "xm/link-ambiguous"
            ambiguous.detail shouldContain "demo.binding.er2db_entity.Objednavka"
        }

        test("(a) kind: naming a kind the name does not have is xm/link-kind-missing, located at the reference") {
            val missing =
                shouldThrow<ProfileException> {
                    run(model + "vztahy:\n  R: {od: Objednavka}\n", fanout + link("od", "from", "table"))
                }
            missing.code shouldBe "xm/link-kind-missing"
            missing.location.path shouldBe "/vztahy/R/od"
            missing.detail shouldContain "table"
            missing.detail shouldContain "demo.er.entity.Objednavka"
            // A name nothing carries is still xm/link-unresolved.
            shouldThrow<ProfileException> {
                run(model + "vztahy:\n  R: {od: Nikdo}\n", fanout + link("od", "from", "entity"))
            }.code shouldBe "xm/link-unresolved"
        }

        test("(a) kind: on a target whose property already names a different kind is ys/profile-invalid at load") {
            val invalid =
                shouldThrow<ProfileException> {
                    recordProfile(
                        "- {op: link, in: er2db_entity, at: /t, to: {kind: er2db_entity, path: [target, table]}, by: sourceName, kind: entity}",
                    )
                }
            invalid.code shouldBe "ys/profile-invalid"
            invalid.location.path shouldBe "/operations/0/kind"
            // The kind the target names, written again, is accepted.
            recordProfile(
                "- {op: link, in: er2db_entity, at: /t, to: {kind: er2db_entity, path: [target, table]}, by: sourceName, kind: table}",
            ).operations.size shouldBe 1
        }

        val joinOps = fanout + "- {op: join-pairs, in: relation, at: /spojeni, to: {kind: relation, property: join}}\n"

        // ✅R298-A: the refs are written in the attribute form the loader resolves, `er.entity.<Entity>.<attr>`.
        fun pair(
            from: String,
            to: String,
        ) = buildJsonObject {
            putJsonObject("from") { put("ref", "er.entity.$from") }
            putJsonObject("to") { put("ref", "er.entity.$to") }
        }

        test("(c) one pair and two AND-joined pairs read into relation.join, in order; the key is consumed") {
            val one = run(model + "vztahy:\n  R: {spojeni: 'Objednavka.Zakaznik = Zakaznik.id'}\n", joinOps)
            relation(one).getValue("join") shouldBe JsonArray(listOf(pair("Objednavka.Zakaznik", "Zakaznik.id")))
            val two =
                run(
                    model +
                        "vztahy:\n  R: {spojeni: \"objednávka.kód = zákazník.kód and\n    objednávka.rok=zákazník.rok\"}\n",
                    joinOps,
                )
            relation(two).getValue("join") shouldBe
                JsonArray(listOf(pair("objednávka.kód", "zákazník.kód"), pair("objednávka.rok", "zákazník.rok")))
        }

        test("(c) any other shape is xm/join-pair-shape at the value, naming the offending text") {
            listOf(
                "A.x <> B.y" to "A.x <> B.y",
                "dbo.A.x = B.y" to "dbo.A.x = B.y",
                "A.x = B.y OR C.z = D.w" to "A.x = B.y OR C.z = D.w",
                "UPPER(A.x) = B.y" to "UPPER(A.x) = B.y",
                "A.x = B.y AND" to "AND",
                "A = B" to "A = B",
            ).forEach { (join, offending) ->
                val refused =
                    shouldThrow<ProfileException> { run(model + "vztahy:\n  R: {spojeni: '$join'}\n", joinOps) }
                refused.code shouldBe "xm/join-pair-shape"
                refused.location.path shouldBe "/vztahy/R/spojeni"
                refused.detail shouldContain offending
            }
            // review-298 R5: a blank value is refused as such, not as a stray 'AND'.
            listOf("''", "'   '").forEach { blank ->
                val refused =
                    shouldThrow<ProfileException> { run(model + "vztahy:\n  R: {spojeni: $blank}\n", joinOps) }
                refused.code shouldBe "xm/join-pair-shape"
                refused.detail shouldContain "non-empty"
                refused.detail shouldNotContain "'AND'"
            }
            shouldThrow<ProfileException> {
                run(
                    model + "vztahy:\n  R: {spojeni: [A.x, B.y]}\n",
                    joinOps,
                )
            }.code shouldBe
                "xm/join-pair-shape"
        }
    })
