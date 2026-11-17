// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

/**
 * MW-P5 S5.2a T1 (⚑MW-P5-S52-1 (b)/(c) → ✅D-MW-81; P-8 amended to eleven operations): the `each` op (a list shaped per
 * element, optionally keyed by an element field), the `split` operand of `values` (a string → its trimmed non-empty
 * parts) and the `kinds` null-skip (a `mapping` value `null` skips the record into `ignored[]`). Profiles contracts §2/§3.
 */
class EachSplitSkipSpec :
    FunSpec({
        val relations =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /vztahy, mapping: {vztah: relation}}\n"
        val joins =
            relations +
                "- {op: each, in: relation, at: /spojeni, to: {kind: relation, property: join}, " +
                "shape: {from: {ref: {input: \$item/z}}, to: {ref: {input: /cil}}}}\n" +
                "- {op: ignore, in: relation, at: /cil}\n"
        val descriptions =
            entityOps +
                "- {op: each, in: entity, at: /popisy, to: {kind: entity, property: description}, " +
                "key: /@language, shape: {input: \$item/@value}}\n"
        val queries =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /dotazy, mapping: {dotaz: query}}\n"

        fun splitOps(separator: String) =
            queries +
                "- {op: values, in: query, at: /vzory, to: {kind: query, path: [search, patterns]}, split: '$separator'}"

        fun join(
            from: String,
            to: String,
        ) = buildJsonObject {
            putJsonObject("from") { put("ref", from) }
            putJsonObject("to") { put("ref", to) }
        }

        fun relation(result: ApplyResult) =
            result
                .defs()
                .single()
                .getValue("properties")
                .jsonObject

        test(
            "each without key: three elements → three shaped, order kept; element and record substitutions; [] stays",
        ) {
            val result =
                runOps(
                    "vztahy:\n  R:\n    cil: Zakaznik.id\n    spojeni:\n" +
                        "      - {z: Objednavka.c}\n      - {z: Objednavka.a}\n      - {z: Objednavka.b}\n",
                    joins,
                )
            relation(result).getValue("join") shouldBe
                JsonArray(
                    listOf("c", "a", "b").map { join("Objednavka.$it", "Zakaznik.id") },
                )
            relation(runOps("vztahy:\n  R: {cil: Z.id, spojeni: []}\n", joins)).getValue("join") shouldBe
                JsonArray(emptyList())
            relation(runOps("vztahy:\n  R: {cil: Z.id}\n", joins))["join"] shouldBe null
        }

        test("each with key: [{@value, @language}] → {cs, en}; {} stays for an empty list") {
            val result =
                runOps(
                    "entity:\n  E:\n    popisy:\n" +
                        "      - {'@value': Objednávka, '@language': cs}\n      - {'@value': Order, '@language': en}\n",
                    descriptions,
                )
            result.properties().getValue("description") shouldBe
                buildJsonObject {
                    put("cs", "Objednávka")
                    put("en", "Order")
                }
            runOps("entity:\n  E: {popisy: []}\n", descriptions).properties().getValue("description") shouldBe
                JsonObject(emptyMap())
        }

        test("each, the related rule (⚑MW-P5-S52-2): [{@id, @local}] → relations [{type: related, to: <local>}]") {
            val related =
                "pojmy:\n  A:\n    stav: Approved\n    related:\n" +
                    "      - {'@id': 'http://x/g#B', '@local': B}\n      - {'@id': 'http://x/g#C', '@local': C}\n" +
                    "      - {'@value': literal, '@language': cs}\n"
            val result =
                runOps(
                    related,
                    "- {op: package, mapping: {}, default: demo}\n" +
                        "- {op: kinds, at: /pojmy, kind: /stav, mapping: {Approved: kind}}\n" +
                        "- {op: each, in: kind, at: /related, to: {kind: kind, property: relations}, " +
                        "shape: {type: related, to: {input: \$item/@local}}}\n",
                )
            // The literal element has no `@local`: its only input is missing, so the element is dropped (✅R251-B).
            // A cn `kind` keeps its body as an ordered entry list.
            result.defs().single().getValue("properties") shouldBe
                buildJsonArray {
                    addJsonObject {
                        putJsonArray("relations") {
                            listOf("B", "C").forEach {
                                addJsonObject {
                                    put("type", "related")
                                    put("to", it)
                                }
                            }
                        }
                    }
                }
        }

        test("each: a duplicate key is ys/each-duplicate-key and a missing one ys/each-missing-key, at the element") {
            val duplicate =
                shouldThrow<ProfileException> {
                    runOps(
                        "entity:\n  E:\n    popisy:\n" +
                            "      - {'@value': A, '@language': cs}\n      - {'@value': B, '@language': cs}\n",
                        descriptions,
                    )
                }
            duplicate.code shouldBe "ys/each-duplicate-key"
            duplicate.location.path shouldBe "/entity/E/popisy/1"
            duplicate.location.line shouldBe 5
            val missing =
                shouldThrow<ProfileException> {
                    runOps("entity:\n  E:\n    popisy:\n      - {'@value': A}\n", descriptions)
                }
            missing.code shouldBe "ys/each-missing-key"
            missing.location.path shouldBe "/entity/E/popisy/0"
            missing.location.line shouldBe 4
        }

        test("each: an `at` that is not a list is ys/each-not-a-list at the value") {
            listOf("Objednávka", "{cs: Objednávka}").forEach { value ->
                val error =
                    shouldThrow<ProfileException> { runOps("entity:\n  E:\n    popisy: $value\n", descriptions) }
                error.code shouldBe "ys/each-not-a-list"
                error.location.path shouldBe "/entity/E/popisy"
                error.location.line shouldBe 3
            }
        }

        test("split: 'a; b ;;c' with '; ' and with ';' → [a, b, c]; an absent input stays absent") {
            listOf("; ", ";").forEach { separator ->
                runOps("dotazy:\n  q: {vzory: 'a; b ;;c'}\n", splitOps(separator))
                    .properties()
                    .getValue("search") shouldBe
                    buildJsonObject { putJsonArray("patterns") { listOf("a", "b", "c").forEach(::add) } }
            }
            (
                runOps(
                    "dotazy:\n  q: {}\n",
                    splitOps(";"),
                ).defs().single()["properties"] as? JsonObject
            )?.get("search") shouldBe
                null
        }

        test("split on a non-string is ys/split-not-a-string at the value") {
            listOf("42", "[a, b]").forEach { value ->
                val error =
                    shouldThrow<ProfileException> { runOps("dotazy:\n  q:\n    vzory: $value\n", splitOps(";")) }
                error.code shouldBe "ys/split-not-a-string"
                error.location.path shouldBe "/dotazy/q/vzory"
                error.location.line shouldBe 3
            }
        }

        test(
            "kinds mapping {Approved: kind, Draft: null}: the Draft record emits nothing and is ignored[] once, with the reason",
        ) {
            val result =
                runOps(
                    "pojmy:\n  Zakaznik: {stav: Approved}\n  Navrh: {stav: Draft, poznamka: rozpracováno}\n",
                    "- {op: package, mapping: {}, default: demo}\n" +
                        "- {op: kinds, at: /pojmy, kind: /stav, mapping: {Approved: kind, Draft: null}}",
                )
            result.defs().map { it.string("id") } shouldBe listOf("Zakaznik")
            result.ignored shouldBe
                listOf(
                    Ignored(
                        ProfileSpan("one.yaml", 3, 3, 3, 8, "/pojmy/Navrh"),
                        "kind mapping null (/stav = Draft)",
                        record = "Navrh",
                    ),
                )
            result.reportJson().getValue("ignored").jsonArray.single().jsonObject.let {
                it.string("record") shouldBe "Navrh"
                it.string("reason") shouldBe "kind mapping null (/stav = Draft)"
            }
            // An unmapped value is still an error.
            shouldThrow<ProfileException> {
                runOps(
                    "pojmy:\n  X: {stav: Retired}\n",
                    "- {op: package, mapping: {}, default: demo}\n" +
                        "- {op: kinds, at: /pojmy, kind: /stav, mapping: {Approved: kind, Draft: null}}",
                )
            }.code shouldBe "ys/unknown-kind"
        }

        test(
            "each with key: an element whose template yields nothing is dropped before the key checks (review-338 B2)",
        ) {
            val ops =
                entityOps +
                    "- {op: each, in: entity, at: /popisy, to: {kind: entity, property: description}, " +
                    "key: /lang, shape: {input: \$item/value}}\n"
            listOf(
                "[{unused: x}, {lang: cs, value: Hello}]",
                "[{lang: cs}, {lang: cs, value: Hello}]",
                "[{lang: cs, value: Hello}, {lang: cs}]",
            ).forEach { list ->
                runOps("entity:\n  E:\n    popisy: $list\n", ops).properties().getValue("description") shouldBe
                    buildJsonObject { put("cs", "Hello") }
            }
        }

        test("split: an empty or blank separator loads strictly and splits on whitespace runs (review-338 B3)") {
            listOf("", " ", "  ").forEach { separator ->
                recordProfile(
                    "  - {op: values, in: entity, at: /znacky, to: {kind: entity, property: tags}, split: '$separator'}",
                ).operations.single { it.value.string("op") == "values" }
                runOps("dotazy:\n  q: {vzory: ' a  b c '}\n", splitOps(separator))
                    .properties()
                    .getValue("search") shouldBe
                    buildJsonObject { putJsonArray("patterns") { listOf("a", "b", "c").forEach(::add) } }
            }
        }

        test("kinds null-skip: nothing beneath a skipped node is discovered, in either op order (review-338 B1)") {
            val tree = "groups:\n  G:\n    status: Draft\n    entities:\n      E: {k: x}\n"
            val skip = "- {op: kinds, at: /groups, kind: /status, mapping: {Draft: null}}\n"
            val nested = "- {op: kinds, at: /groups/G/entities, mapping: {entity: entity}}\n"
            val nestedSkip = "- {op: kinds, at: /groups/G/entities, kind: /k, mapping: {x: null}}\n"
            listOf(skip + nested, nested + skip, nestedSkip + skip, skip + nestedSkip).forEach { kinds ->
                val result = runOps(tree, "- {op: package, mapping: {}, default: demo}\n" + kinds)
                result.documents shouldBe emptyList()
                result.sources shouldBe emptyList()
                result.ignored.map { it.location.path } shouldBe listOf("/groups/G")
            }
        }

        test(
            "kinds null-skip: a refused descendant beneath a skipped node raises nothing, in either op order (review-338 B1)",
        ) {
            val skip = "- {op: kinds, at: /groups, kind: /status, mapping: {Draft: null}}\n"
            val pkg = "- {op: package, mapping: {}, default: demo}\n"
            // No string id (a sequence element) and an unmapped kind: both are refusals discovery would raise.
            val cases =
                listOf(
                    "groups:\n  G:\n    status: Draft\n    entities:\n      - {}\n" to
                        "- {op: kinds, at: /groups/G/entities, mapping: {entity: entity}}\n",
                    "groups:\n  G:\n    status: Draft\n    entities:\n      E: {k: y}\n" to
                        "- {op: kinds, at: /groups/G/entities, kind: /k, mapping: {x: entity}}\n",
                )
            cases.forEach { (tree, nested) ->
                listOf(skip + nested, nested + skip).forEach { kinds ->
                    val result = runOps(tree, pkg + kinds)
                    result.documents shouldBe emptyList()
                    result.sources shouldBe emptyList()
                    result.ignored.map { it.location.path } shouldBe listOf("/groups/G")
                }
            }
            // Control: the same descendants without the skip are still refused.
            cases.zip(listOf("ys/record-id-missing", "ys/unknown-kind")).forEach { (case, code) ->
                val (tree, nested) = case
                shouldThrow<ProfileException> {
                    runOps(tree, pkg + nested)
                }.code shouldBe code
            }
        }

        test(
            "schema: a profile using each, split and a null kind loads strictly; each without at is ys/profile-invalid, located",
        ) {
            recordProfile(
                "  - {op: kinds, at: /pojmy, kind: /stav, mapping: {Approved: entity, Draft: null}}\n" +
                    "  - {op: each, in: entity, at: /popisy, to: {kind: entity, property: description}, " +
                    "key: /@language, shape: {input: \$item/@value}}\n" +
                    "  - {op: values, in: entity, at: /znacky, to: {kind: entity, property: tags}, split: ','}",
            ).operations.map { it.value.string("op") } shouldBe listOf("kinds", "each", "values")
            val invalid =
                shouldThrow<ProfileException> {
                    recordProfile(
                        "  - {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                            "  - {op: each, in: entity, to: {kind: entity, property: description}, shape: {input: \$item}}",
                    )
                }
            invalid.code shouldBe "ys/profile-invalid"
            invalid.location.path shouldBe "/operations/1"
            invalid.location.line shouldBe 5
            // `$item` belongs to `each`; `shape` and `split` keep their own operand rules.
            listOf(
                "  - {op: shape, in: entity, at: '', to: {kind: entity, property: description}, template: {input: \$item}}",
                "  - {op: values, in: entity, at: /x, to: {kind: entity, property: tags}, split: ',', mapping: {a: [b]}}",
                "  - {op: values, in: entity, at: /x, to: {kind: entity, property: tags}, split: ',', default: [b]}",
            ).forEach { op ->
                shouldThrow<ProfileException> { recordProfile(op) }.code shouldBe "ys/profile-invalid"
            }
        }
    })
