// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.conformance.ConformanceDump
import org.tatrman.ttr.semantics.conformance.SemanticsConformanceDump
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class YamlMigrationSpec :
    FunSpec({
        fun request(
            source: String,
            from: String = "0.22",
            to: String = "0.22",
        ): JsonObject =
            obj("operation" to text("migrate"), "from" to text(from), "to" to text(to), "source" to text(source))

        fun invoke(bytes: ByteArray): Triple<Int, String, String> {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val status = YamlMigrationCli.run(bytes.inputStream(), stdout, stderr)
            return Triple(status, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        }

        test("same-version normalization is idempotent and reports changed accurately") {
            val source = "{ttr: '0.22', model: er}\n"
            val result = YamlMigration.migrate(request(source))
            result["changed"] shouldBe JsonPrimitive(true)
            val canonical = result.getValue("source").jsonPrimitive.content
            canonical shouldBe "ttr: \"0.22\"\nmodel: \"er\"\n"
            YamlMigration.migrate(request(canonical))["changed"] shouldBe JsonPrimitive(false)
            val (status, stdout, stderr) = invoke(request(canonical).toString().toByteArray())
            status shouldBe 0
            stderr shouldBe ""
            Json.parseToJsonElement(stdout) shouldBe YamlMigration.migrate(request(canonical))
        }
        test("a 0.21 document migrates to 0.22 by its marker alone, and then reads as 0.22") {
            val description = "ttr: '0.21' Příliš žluťoučký 🐈"

            fun marked(v: String) =
                "# 🐈 a comment before the marker\nttr: '$v'\nmodel: er\ndefs:\n - kind: entity\n   id: Item\n" +
                    "   properties:\n     description: \"$description\"\n"
            val result = YamlMigration.migrate(request(marked("0.21"), "0.21", "0.22"))
            result["changed"] shouldBe JsonPrimitive(true)
            val migrated = result.getValue("source").jsonPrimitive.content
            migrated shouldBe
                YamlMigration
                    .migrate(request(marked("0.22")))
                    .getValue("source")
                    .jsonPrimitive.content
            migrated shouldContain "ttr: \"0.22\"\n"
            migrated shouldContain description
            YamlMigration.migrate(request(migrated))["changed"] shouldBe JsonPrimitive(false)
            // flow style too
            YamlMigration
                .migrate(request("{ttr: \"0.21\", model: er}", "0.21", "0.22"))
                .getValue("source")
                .jsonPrimitive.content shouldBe "ttr: \"0.22\"\nmodel: \"er\"\n"
        }
        for (source in listOf("ttr: '0.20'\nmodel: er\n", "ttr: '0.22'\nmodel: er\n", "model: er\n")) {
            test("a 0.21 migration refuses a document not marked 0.21: ${JsonPrimitive(source)}") {
                val (status, stdout, stderr) = invoke(request(source, "0.21", "0.22").toString().toByteArray())
                status shouldBe 2
                stdout shouldBe ""
                stderr shouldContain "<yaml>:"
            }
        }
        test("the 0.21 migration names the marker it expected, at the marker") {
            val (_, _, stderr) = invoke(request("model: er\nttr: '0.22'\n", "0.21", "0.22").toString().toByteArray())
            stderr shouldBe "<yaml>:2:6: Expected ttr: \"0.21\" for a migration from 0.21\n"
        }
        for ((from, to) in listOf(
            "0.20" to "0.21",
            "0.20" to "0.22",
            "0.21" to "0.21",
            "0.22" to "0.21",
            "0.22.0" to "0.22",
        )) {
            test("unregistered version pair $from to $to refuses before parsing") {
                val (status, stdout, stderr) = invoke(request("invalid: [", from, to).toString().toByteArray())
                status shouldBe 2
                stdout shouldBe ""
                stderr shouldContain "<yaml>:1:1: No registered canonical YAML migration"
            }
        }
        for (source in listOf(
            "model: er\n",
            "ttr: '0.20'\n",
            "profile: dfp/1\ngrammar: '0.21'\n",
            "ttr: '0.22'\nunknown: 1\n",
        )) {
            test("undeclared dialect or invalid marker refuses ${JsonPrimitive(source)}") {
                val (status, stdout, stderr) = invoke(request(source).toString().toByteArray())
                status shouldBe 2
                stdout shouldBe ""
                stderr shouldContain "<yaml>:"
            }
        }
        test("invalid typed properties retain their YAML location") {
            val (_, stdout, stderr) =
                invoke(
                    request(
                        "ttr: '0.22'\nmodel: er\ndefs:\n - kind: entity\n   id: E\n   properties:\n     unknown: 1\n",
                    ).toString().toByteArray(),
                )
            stdout shouldBe ""
            stderr shouldContain "<yaml>:7:15:"
        }
        for (input in listOf(
            "[]",
            "{}",
            "null",
            "{",
            "{} {}",
            request("{}").toString().replace("\"migrate\"", "\"execute\""),
            request("{}").toString().dropLast(1) + ",\"extra\":true}",
            request("{}").toString().replace("\"source\":\"{}\"", "\"source\":null"),
            request("{}").toString().dropLast(1) + ",\"source\":\"{}\"}",
        )) {
            test("malformed or unsupported JSON request ${JsonPrimitive(input)}") {
                val (status, stdout, stderr) = invoke(input.toByteArray())
                status shouldBe 2
                stdout shouldBe ""
                stderr.isNotBlank() shouldBe true
            }
        }
        test("invalid UTF-8 is refused with no response") {
            val (status, stdout, stderr) = invoke(byteArrayOf(0xc3.toByte(), 0x28))
            status shouldBe 2
            stdout shouldBe ""
            stderr shouldContain "<request>:1:1:"
        }
        val roster = YamlFixtureRoster(Path.of("../../../tests/conformance/fixtures"))
        for (scenario in roster.positive) {
            test("migration preserves canonical bytes AST and scenario semantics: ${scenario.name}") {
                val before = mutableListOf<SemanticsConformanceDump.DocInput>()
                val after = mutableListOf<SemanticsConformanceDump.DocInput>()
                for (file in scenario.files) {
                    val uri = roster.root.relativize(file).toString()
                    val bytes = Files.readString(roster.twin(file))
                    val migrated = YamlMigration.migrate(request(bytes))
                    migrated["changed"] shouldBe JsonPrimitive(false)
                    val source = migrated.getValue("source").jsonPrimitive.content
                    source shouldBe bytes
                    val original = CanonicalYaml.read(bytes, uri).ast
                    val result = CanonicalYaml.read(source, uri).ast
                    ConformanceDump.dumpTree(result) shouldBe ConformanceDump.dumpTree(original)
                    before += SemanticsConformanceDump.DocInput(uri, original)
                    after += SemanticsConformanceDump.DocInput(uri, result)
                }
                SemanticsConformanceDump.dumpDocs(after) shouldBe SemanticsConformanceDump.dumpDocs(before)
            }
        }
    })
