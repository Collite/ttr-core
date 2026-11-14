// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.io.path.writeText

/** MW S3.6 T1/T4/T5 — the sidecar writer (contracts §6) and the capture's hygiene functions (XM D-15). */
class SnapshotHygieneSpec :
    FunSpec({
        val ops =
            "- {op: package, mapping: {a: alfa}, default: demo}\n" +
                "- {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                "- {op: kinds, in: entity, at: /sloupce, mapping: {sloupec: attribute}}\n" +
                "- {op: ignore, in: entity, at: /poznamka, reason: internal}\n" +
                "- {op: ignore, at: /audit}\n"

        fun tree(
            text: String,
            file: String = "a.yaml",
        ) = ForeignTree.read(text, file)

        test("sidecar: the run's records in bytewise qname order, absent values left out, no timestamps") {
            val result =
                ProfileEngine.apply(
                    listOf(tree("entity:\n  Zeta: {}\n  Alfa: {sloupce: {kod: {}}}\n  Ábel: {}\n")),
                    listOf(recordProfile(ops)),
                )
            val text = SourcesJsonWriter.write("generated-from: x", result.sources)
            text shouldBe SourcesJsonWriter.write("generated-from: x", result.sources.reversed())
            val json = Json.parseToJsonElement(text).jsonObject
            json.keys shouldContainExactly setOf("generated-from", "entries")
            val entries = json.getValue("entries").jsonArray.map { it.jsonObject }
            // Bytewise: 'Z' (0x5A) before 'Á' (0xC3 0x81); a nested record after its parent.
            entries.map { (it.getValue("qname") as kotlinx.serialization.json.JsonPrimitive).content } shouldBe
                listOf("alfa.er.entity.Alfa", "alfa.er.entity.Alfa.kod", "alfa.er.entity.Zeta", "alfa.er.entity.Ábel")
            entries.map { it.keys }.toSet() shouldBe setOf(setOf("qname", "kind", "sourceName", "locator"))
            text shouldNotContain "20" + "26-"
            // UTF-8 bytes, not UTF-16 units: U+FF21 (EF BC A1) sorts before U+1D538 (F0 9D 94 B8), whose surrogate
            // D835 a String compare would put first.
            val wide = SourceEntry("p.er.entity.\uFF21", "entity", "p", null, "A", null)
            val astral = SourceEntry("p.er.entity.\uD835\uDD38", "entity", "p", null, "B", null)
            Json
                .parseToJsonElement(SourcesJsonWriter.write(null, listOf(astral, wide)))
                .jsonObject
                .getValue("entries")
                .jsonArray
                .map {
                    (
                        it.jsonObject.getValue(
                            "sourceName",
                        ) as kotlinx.serialization.json.JsonPrimitive
                    ).content
                } shouldBe
                listOf("A", "B")
            SourcesJsonWriter.write(null, emptyList()) shouldBe "{\n  \"entries\": []\n}\n"
        }

        test("sidecar: a bare-DB import names by sourceName only") {
            val entry = SourceEntry("s.db.dbo.T", "table", "s", "urn:t", "T", "dbo/T")
            val json = Json.parseToJsonElement(SourcesJsonWriter.write(null, listOf(entry), sourceNameOnly = true))
            json.jsonObject
                .getValue("entries")
                .jsonArray
                .single() shouldBe
                Json.parseToJsonElement("""{"qname":"s.db.dbo.T","kind":"table","sourceName":"T"}""")
        }

        test("sidecar: SourcesJsonReader reads back exactly the entries the writer wrote (S3.3 parity)") {
            val result =
                ProfileEngine.apply(
                    listOf(tree("entity:\n  A: {sloupce: {kod: {}}}\n  B: {}\n")),
                    listOf(recordProfile(ops)),
                )
            val withIri = result.sources + SourceEntry("alfa.db.dbo.T", "table", "alfa", "urn:t", "T", "dbo/T")
            val root = Files.createTempDirectory("s36-sidecar")
            root.resolve("alfa.sources.json").writeText(SourcesJsonWriter.write("generated-from: x", withIri))
            SourcesJsonReader
                .read(
                    root,
                ).map { listOf(it.qname, it.kind, it.pkg, it.sourceName, it.iri, it.path) }
                .toSet() shouldBe
                withIri.map { listOf(it.qname, it.kind, it.pkg, it.sourceName, it.foreignId, it.locator) }.toSet()
        }

        test("capture: the profile's ignore, scoped and unscoped, by pointer; a whole-file ignore; never a record") {
            val source = tree("entity:\n  A: {popis: x, poznamka: y, sloupce: {kod: {}}}\naudit: {log: 1}\nnovy: z\n")
            val profile = listOf(recordProfile(ops))
            CaptureFilter.plan(source, profile).ignored.toSet() shouldBe setOf("/entity/A/poznamka", "/audit")
            CaptureFilter.prune(source.root.value, CaptureFilter.plan(source, profile).ignored) shouldBe
                Json.parseToJsonElement("""{"entity":{"A":{"popis":"x","sloupce":{"kod":{}}}},"novy":"z"}""")
            val whole = listOf(recordProfile("- {op: ignore, files: [a.yaml]}\n"))
            CaptureFilter.plan(source, whole).ignored shouldBe listOf("")
            CaptureFilter.prune(source.root.value, listOf("")) shouldBe JsonObject(emptyMap())
            // An ignore that would cover a record stays (apply refuses it as ys/profile-conflict).
            val covering = listOf(recordProfile(ops + "- {op: ignore, in: entity, at: /sloupce}\n"))
            CaptureFilter.plan(source, covering).ignored.toSet() shouldBe setOf("/entity/A/poznamka", "/audit")
            // An array element is addressed by index.
            CaptureFilter.prune(Json.parseToJsonElement("""{"a":[1,2,3]}"""), listOf("/a/1")) shouldBe
                Json.parseToJsonElement("""{"a":[1,3]}""")
        }

        test("capture: principals give way to role names, usage counters go, at any depth and spelling") {
            val export =
                Json.parseToJsonElement(
                    """
                    {"t": {"owner": "Jana Dvořáková", "Owners": [{"name": "Petr Svoboda"}, "Eva Malá"], "created_by": "Karel",
                           "steward": null, "experts": [], "popis": "Owner: nikdo",
                           "usageCount": 42, "usage_summary": {"weekly": 3}, "cols": [{"LastAccessed": "x", "n": "c"}]}}
                    """.trimIndent(),
                )
            Sanitise.counters(Sanitise.principals(export)) shouldBe
                Json.parseToJsonElement(
                    """
                    {"t": {"owner": "owner", "Owners": ["owner"], "created_by": "author", "steward": null, "experts": [],
                           "popis": "Owner: nikdo", "cols": [{"n": "c"}]}}
                    """.trimIndent(),
                )
            Sanitise.key("Last-Modified_By") shouldBe "lastmodifiedby"
            Sanitise.roles.getValue(Sanitise.key("Last-Modified_By")) shouldBe "editor"
        }

        test("capture: a record whose key is a principal or counter name is content, kept byte-equal (review-277 F2)") {
            val source =
                tree(
                    "entity:\n  Zakaznik:\n    popis: Zákazník\n    owner: alice\n    votes: 3\n    sloupce:\n" +
                        "      kod: {}\n      owner: {typ: text, createdBy: Karel}\n      created_by: {typ: text}\n" +
                        "      votes: {typ: integer}\n  owners: {popis: entita jménem owners}\n",
                )
            val records = CaptureFilter.plan(source, listOf(recordProfile(ops))).records
            records shouldBe
                setOf(
                    "/entity/Zakaznik",
                    "/entity/Zakaznik/sloupce/kod",
                    "/entity/Zakaznik/sloupce/owner",
                    "/entity/Zakaznik/sloupce/created_by",
                    "/entity/Zakaznik/sloupce/votes",
                    "/entity/owners",
                )
            Sanitise.counters(Sanitise.principals(source.root.value, records), records) shouldBe
                Json.parseToJsonElement(
                    """
                    {"entity": {"Zakaznik": {"popis": "Zákazník", "owner": "owner",
                       "sloupce": {"kod": {}, "owner": {"typ": "text", "createdBy": "author"},
                                   "created_by": {"typ": "text"}, "votes": {"typ": "integer"}}},
                     "owners": {"popis": "entita jménem owners"}}}
                    """.trimIndent(),
                )
            // Without the records every such key is matched by name.
            Sanitise.counters(Sanitise.principals(source.root.value)).toString() shouldNotContain "integer"
        }

        test(
            "capture: an export the profile does not fully know is captured whole; apply is the gate (review-277 N1)",
        ) {
            val kinds =
                "- {op: package, mapping: {}, default: demo}\n" +
                    "- {op: kinds, at: /entity, kind: /typ, mapping: {tabulka: entity}}\n"
            val source =
                tree("entity:\n  T: {typ: tabulka, owner: alice}\n  V: {typ: pohled, owner: bob}\n  W: {popis: bez}\n")
            listOf(kinds, kinds + "- {op: ignore, at: /audit}\n").forEach { ops ->
                val plan = CaptureFilter.plan(source, listOf(recordProfile(ops)))
                // V (unmapped kind) and W (no kind string) are not records; discovery goes on to the next source.
                plan.records shouldBe setOf("/entity/T")
                plan.ignored shouldBe emptyList()
                val clean = Sanitise.counters(Sanitise.principals(source.root.value, plan.records), plan.records)
                CaptureFilter.prune(clean, plan.ignored) shouldBe
                    Json.parseToJsonElement(
                        """
                        {"entity": {"T": {"typ": "tabulka", "owner": "owner"}, "V": {"typ": "pohled", "owner": "owner"},
                                    "W": {"popis": "bez"}}}
                        """.trimIndent(),
                    )
            }
            // A missing id and a kinds.at that selects a scalar are skipped the same way.
            val ids =
                "- {op: kinds, at: /entity, id: /nazev, mapping: {e: entity}}\n" +
                    "- {op: kinds, at: /scalar, mapping: {e: entity}}\n"
            CaptureFilter
                .plan(
                    tree("entity:\n  A: {nazev: A}\n  B: {popis: x}\nscalar: 1\n"),
                    listOf(recordProfile(ids)),
                ).records shouldBe setOf("/entity/A")
            // apply keeps the strict plan: the same export is still refused.
            val refused =
                shouldThrow<ProfileException> { ProfileEngine.apply(listOf(source), listOf(recordProfile(kinds))) }
            refused.code shouldBe "ys/unknown-kind"
            refused.detail shouldContain "pohled"
        }
    })
