// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/** S3.3 T1: `link` (contracts §3, ✅D-MW-67 (c′)). Package names stay single-segment (control room §18 (f)). */
class LinkOpsSpec :
    FunSpec({
        fun sidecarRoot(vararg files: Pair<String, String>): Path {
            val root = Files.createTempDirectory("s33-link")
            files.forEach { (name, text) ->
                root.resolve(name).also { it.parent.createDirectories() }.writeText(text)
            }
            return root
        }
        val db =
            """
            {"generated-from": "test", "entries": [
              {"qname": "sklad.db.dbo.T", "kind": "table", "foreignId": "urn:t", "sourceName": "T", "locator": "dbo/T"},
              {"qname": "sklad.db.dbo.T.ID", "kind": "column", "sourceName": "ID", "locator": "dbo/T/ID"}
            ]}
            """.trimIndent()
        val binding =
            "- {op: package, mapping: {}, default: demo}\n" +
                "- {op: kinds, at: /vazby, mapping: {vazba: er2db_entity}}\n"

        fun run(
            source: String,
            ops: String,
            root: Path? = null,
            file: String = "one.yaml",
        ) = ProfileEngine.apply(listOf(ForeignTree.read(source, file)), listOf(recordProfile(ops)), root)

        test("by sourceName, iri and path resolve sidecar entries; a target in another package is imported") {
            val root = sidecarRoot("nested/sklad.sources.json" to db)
            listOf("sourceName" to "T", "iri" to "urn:t", "path" to "dbo/T").forEach { (by, reference) ->
                val result =
                    run(
                        "vazby:\n  V: {cil: '$reference'}\n",
                        binding +
                            "- {op: link, in: er2db_entity, at: /cil, to: {kind: er2db_entity, path: [target, table]}, by: $by}",
                        root,
                    )
                result.properties().getValue("target") shouldBe buildJsonObject { put("table", "sklad.db.dbo.T") }
                result.documents
                    .single()
                    .value
                    .getValue("imports") shouldBe JsonArray(listOf(JsonPrimitive("sklad.*")))
            }
        }
        test(
            "the same-run index spans files and packages: sourceName, a nested path, imports sorted and deduplicated",
        ) {
            val ops =
                "- {op: package, mapping: {a: alfa, b: beta}, default: demo}\n" +
                    "- {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                    "- {op: kinds, in: entity, at: /sloupce, mapping: {sloupec: attribute}}\n" +
                    "- {op: kinds, at: /vztahy, mapping: {vztah: relation}}\n" +
                    "- {op: link, in: relation, at: /od, to: {kind: relation, property: from}, by: sourceName}\n" +
                    "- {op: link, in: relation, at: /do, to: {kind: relation, property: to}, by: path}"
            val result =
                ProfileEngine.apply(
                    listOf(
                        ForeignTree.read("entity:\n  B: {}\n", "b.yaml"),
                        ForeignTree.read("entity:\n  A: {sloupce: {kod: {}}}\n", "a.yaml"),
                        ForeignTree.read(
                            "vztahy:\n  R1: {od: B, do: A/kod}\n  R2: {od: A, do: B}\n",
                            "c.yaml",
                        ),
                    ),
                    listOf(recordProfile(ops)),
                )
            val demo = result.documents.single { it.value.string("package") == "demo" }
            demo.value.getValue("imports") shouldBe JsonArray(listOf(JsonPrimitive("alfa.*"), JsonPrimitive("beta.*")))
            demo.value
                .getValue("defs")
                .jsonArray
                .map { it.jsonObject.getValue("properties") } shouldBe
                listOf(
                    Json.parseToJsonElement("""{"from":"beta.er.entity.B","to":"alfa.er.entity.A.kod"}"""),
                    Json.parseToJsonElement("""{"from":"alfa.er.entity.A","to":"beta.er.entity.B"}"""),
                )
        }
        test("unresolved and ambiguous links are errors at the authored key; a list links each element") {
            val ops =
                entityOps + "- {op: kinds, at: /tabulka, mapping: {tabulka: table}}\n" +
                    "- {op: link, in: entity, at: /role, to: {kind: entity, property: roles}, by: sourceName}"
            run("entity:\n  A: {role: [B, A]}\n  B: {}\n", ops).defs().first().getValue("properties") shouldBe
                Json.parseToJsonElement("""{"roles":["demo.er.entity.B","demo.er.entity.A"]}""")
            val unresolved = shouldThrow<ProfileException> { run("entity:\n  A: {role: [B, X]}\n  B: {}\n", ops) }
            unresolved.code shouldBe "xm/link-unresolved"
            unresolved.location.path shouldBe "/entity/A/role/1"
            unresolved.location.line shouldBe 2
            unresolved.location.column shouldBe 17
            val ambiguous =
                shouldThrow<ProfileException> { run("entity:\n  A: {role: S}\n  S: {}\ntabulka:\n  S: {}\n", ops) }
            ambiguous.code shouldBe "xm/link-ambiguous"
            ambiguous.location.path shouldBe "/entity/A/role"
            ambiguous.location.column shouldBe 7
            ambiguous.detail shouldContain "demo.er.entity.S, demo.db.dbo.S"
        }
        test(
            "a link resolves only among the kinds its target accepts: er2db_entity.entity → the entity (✅D-MW-67 (c′))",
        ) {
            val fanout =
                "- {op: package, mapping: {}, default: demo}\n" +
                    "- {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                    "- {op: kinds, at: /entity, mapping: {entita: er2db_entity}}\n"
            val result =
                run(
                    "entity:\n  Odberatel: {}\n",
                    fanout +
                        "- {op: link, in: er2db_entity, at: /\$key, to: {kind: er2db_entity, property: entity}, by: sourceName}",
                )
            result.documents
                .single { it.value.string("model") == "binding" }
                .value
                .getValue("defs") shouldBe
                Json.parseToJsonElement(
                    """[{"kind":"er2db_entity","id":"Odberatel","properties":{"entity":"demo.er.entity.Odberatel"}}]""",
                )
            // A target naming no kind accepts both records of the node, so the same name is ambiguous.
            val ambiguous =
                shouldThrow<ProfileException> {
                    run(
                        "entity:\n  Odberatel: {}\n",
                        fanout +
                            "- {op: link, in: er2db_entity, at: /\$key, to: {kind: er2db_entity, property: target}, by: sourceName}",
                    )
                }
            ambiguous.code shouldBe "xm/link-ambiguous"
            ambiguous.detail shouldContain "demo.binding.er2db_entity.Odberatel"
        }
        test("a sidecar entry for a run record is one candidate; a malformed sidecar is ys/profile-invalid") {
            val same =
                sidecarRoot(
                    "demo.sources.json" to
                        """{"entries": [{"qname": "demo.er.entity.A", "kind": "entity", "sourceName": "A"}]}""",
                )
            val ops =
                entityOps + "- {op: link, in: entity, at: /role, to: {kind: entity, property: roles}, by: sourceName}"
            run("entity:\n  A: {role: [A]}\n", ops, same).properties().getValue("roles") shouldBe
                JsonArray(listOf(JsonPrimitive("demo.er.entity.A")))
            val bad = sidecarRoot("x/demo.sources.json" to """{"entries": [{"kind": "entity"}]}""")
            val error = shouldThrow<ProfileException> { run("entity:\n  A: {}\n", entityOps, bad) }
            error.code shouldBe "ys/profile-invalid"
            error.location.file shouldBe "x/demo.sources.json"
        }
    })
