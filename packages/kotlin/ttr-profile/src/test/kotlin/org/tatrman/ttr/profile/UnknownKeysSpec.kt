// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

/**
 * MW-P5 S5.2c T1 (✅R350-D, ⚑MW-P5-S52-5 → ✅D-MW-89; profiles contracts §2 amended 2026-11-17): a profile header's
 * `unknown: error | ignore` (default `error`, the P3 invariant). With `ignore`, a key no op consumes is dropped and
 * reported once per (file, record kind, key path) in `warnings[]` — `not in this profile`, with the count of records it
 * was dropped from — never once per record, never silently.
 */
class UnknownKeysSpec :
    FunSpec({
        val operations =
            entityOps +
                "- {op: rename, in: entity, at: /nazev, to: {kind: entity, property: description}}\n" +
                "- {op: ignore, in: entity, at: /stitky/0, reason: the first tag only}\n"

        fun profile(unknown: String?) =
            ProfileReader.read(
                "profile: demo-cs/1\ngrammar: '>=0.22 <1.0'\n" +
                    (unknown?.let { "unknown: $it\n" } ?: "") +
                    "operations:\n$operations\n",
                "profile.yaml",
            )

        // ✅R350-D's three predicates, on two records; a list the profile reads one element of; a file-scoped key.
        val one =
            "entity:\n" +
                "  A:\n" +
                "    nazev: Strana\n" +
                "    dct:created: '2024-01-01'\n" +
                "    rdfs:label: [{'@value': Party}]\n" +
                "    skosxl:prefLabel: [{'@id': '_:b0'}]\n" +
                "    stitky: [x, y]\n" +
                "  B:\n" +
                "    nazev: Agent\n" +
                "    dct:created: '2024-01-02'\n" +
                "    stitky: [p, q, r]\n" +
                "meta: {exported: today}\n"
        val two = "entity:\n  C: {nazev: Termin, dct:created: '2024-01-03'}\n"

        fun trees() = listOf(ForeignTree.read(one, "one.yaml"), ForeignTree.read(two, "two.yaml"))

        fun unknownKey(chain: List<ProfileDefinition>) =
            shouldThrow<ProfileException> { ProfileEngine.apply(trees(), chain) }.let {
                it.code shouldBe "ys/unknown-key"
                it.location.path shouldBe "/entity/A/dct:created"
                it.detail shouldBe "Unconsumed key dct:created"
            }

        test("(a) unknown: ignore drops the unconsumed keys; one warning per (file, key) with its record count") {
            val result = ProfileEngine.apply(trees(), listOf(profile("ignore")))
            result.defs().map { it.getValue("id").jsonPrimitive.content } shouldBe listOf("A", "B", "C")
            result.report.warnings.map {
                listOf(it.location.file, it.location.path, it.scope, it.key, it.count, it.message, it.record)
            } shouldBe
                listOf(
                    listOf("one.yaml", "/entity/A/dct:created", "entity", "/dct:created", 2, "not in this profile", null),
                    listOf("one.yaml", "/entity/A/rdfs:label", "entity", "/rdfs:label", 1, "not in this profile", null),
                    listOf(
                        "one.yaml",
                        "/entity/A/skosxl:prefLabel",
                        "entity",
                        "/skosxl:prefLabel",
                        1,
                        "not in this profile",
                        null,
                    ),
                    // Three dropped elements, two records: the count is records, not occurrences.
                    listOf("one.yaml", "/entity/A/stitky/1", "entity", "/stitky/*", 2, "not in this profile", null),
                    // A file-scoped key: no record kind; the count is its occurrences.
                    listOf("one.yaml", "/meta", null, "/meta", 1, "not in this profile", null),
                    listOf("two.yaml", "/entity/C/dct:created", "entity", "/dct:created", 1, "not in this profile", null),
                )
            // The report's warnings[] carries the aggregate (contracts §2): `in`, `key` and `count` beside the location.
            result.reportJson().getValue("warnings").jsonArray.first() shouldBe
                buildJsonObject {
                    put("file", "one.yaml")
                    put("line", 4)
                    put("column", 5)
                    put("path", "/entity/A/dct:created")
                    put("in", "entity")
                    put("key", "/dct:created")
                    put("count", 2)
                    put("message", "not in this profile")
                }
            // A dropped key is a warning, not an ignore: ignored[] holds only what an `ignore` op named.
            result.ignored.map { it.location.path } shouldBe listOf("/entity/A/stitky/0", "/entity/B/stitky/0")
        }

        test("(b) unknown: error on the same input is ys/unknown-key, as before") {
            unknownKey(listOf(profile("error")))
        }

        test("(c) without the key a profile is `error`; in a chain the nearest profile that declares it decides") {
            unknownKey(listOf(profile(null)))
            // A child that does not declare it inherits its ancestor's (a reader's overlay or package child, say) …
            ProfileEngine.apply(trees(), listOf(profile("ignore"), profile(null))).report.warnings.size shouldBe 6
            // … and a child that declares it wins.
            unknownKey(listOf(profile("ignore"), profile("error")))
        }

        test("(e) the schema takes `error` and `ignore` only") {
            profile("error").unknown shouldBe UnknownKeys.ERROR
            profile("ignore").unknown shouldBe UnknownKeys.IGNORE
            profile(null).unknown shouldBe null
            listOf("skip", "'true'", "[ignore]").forEach { value ->
                shouldThrow<ProfileException> { profile(value) }.let {
                    it.code shouldBe "ys/profile-invalid"
                    it.location.path shouldContain "/unknown"
                }
            }
        }
    })
