// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
            CaptureFilter.ignored(source, profile).toSet() shouldBe setOf("/entity/A/poznamka", "/audit")
            CaptureFilter.prune(source.root.value, CaptureFilter.ignored(source, profile)) shouldBe
                Json.parseToJsonElement("""{"entity":{"A":{"popis":"x","sloupce":{"kod":{}}}},"novy":"z"}""")
            val whole = listOf(recordProfile("- {op: ignore, files: [a.yaml]}\n"))
            CaptureFilter.ignored(source, whole) shouldBe listOf("")
            CaptureFilter.prune(source.root.value, listOf("")) shouldBe JsonObject(emptyMap())
            // An ignore that would cover a record stays (apply refuses it as ys/profile-conflict).
            val covering = listOf(recordProfile(ops + "- {op: ignore, in: entity, at: /sloupce}\n"))
            CaptureFilter.ignored(source, covering).toSet() shouldBe setOf("/entity/A/poznamka", "/audit")
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
    })
