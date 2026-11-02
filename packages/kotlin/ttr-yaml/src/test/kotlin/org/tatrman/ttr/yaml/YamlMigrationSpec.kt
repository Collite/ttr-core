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
            from: String = "0.21",
            to: String = "0.21",
        ): JsonObject =
            obj("operation" to text("migrate"), "from" to text(from), "to" to text(to), "source" to text(source))

        fun invoke(bytes: ByteArray): Triple<Int, String, String> {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val status = YamlMigrationCli.run(bytes.inputStream(), stdout, stderr)
            return Triple(status, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        }

        test("same-version normalization is idempotent and reports changed accurately") {
            val source = "{ttr: '0.21', model: er}\n"
            val result = YamlMigration.migrate(request(source))
            result["changed"] shouldBe JsonPrimitive(true)
            val canonical = result.getValue("source").jsonPrimitive.content
            canonical shouldBe "ttr: \"0.21\"\nmodel: \"er\"\n"
            YamlMigration.migrate(request(canonical))["changed"] shouldBe JsonPrimitive(false)
            val (status, stdout, stderr) = invoke(request(canonical).toString().toByteArray())
            status shouldBe 0
            stderr shouldBe ""
            Json.parseToJsonElement(stdout) shouldBe YamlMigration.migrate(request(canonical))
        }
        for ((from, to) in listOf("0.20" to "0.21", "0.21" to "0.22", "0.20" to "0.20", "0.21.0" to "0.21")) {
            test("unregistered version pair $from to $to refuses before parsing") {
                val (status, stdout, stderr) = invoke(request("invalid: [", from, to).toString().toByteArray())
                status shouldBe 2
                stdout shouldBe ""
                stderr shouldContain "<request>:1:1: No registered canonical YAML migration"
            }
        }
        for (source in listOf(
            "model: er\n",
            "ttr: '0.20'\n",
            "profile: dfp/1\ngrammar: '0.21'\n",
            "ttr: '0.21'\nunknown: 1\n",
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
                        "ttr: '0.21'\nmodel: er\ndefs:\n - kind: entity\n   id: E\n   properties:\n     unknown: 1\n",
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
        )) {
            test("malformed or unsupported JSON request ${JsonPrimitive(input)}") {
                val (status, stdout, stderr) = invoke(input.toByteArray())
                status shouldBe 2
                stdout shouldBe ""
                stderr.startsWith("<request>:1:1:") shouldBe true
            }
        }
        test("invalid UTF-8 is refused with no response") {
            val (status, stdout, stderr) = invoke(byteArrayOf(0xc3.toByte(), 0x28))
            status shouldBe 2
            stdout shouldBe ""
            stderr shouldContain "<request>:1:1:"
        }
        test("duplicate JSON keys cannot bypass validation with a valid last source") {
            val valid = request("ttr: \"0.21\"\n").toString()
            val duplicate = valid.dropLast(1) + ",\"source\":" + JsonPrimitive("ttr: \"0.21\"\n") + "}"
            val (status, stdout, stderr) = invoke(duplicate.toByteArray())
            status shouldBe 2
            stdout shouldBe ""
            stderr.startsWith("<request>:1:1:") shouldBe true
            stderr shouldContain "Duplicate field"
        }
        test("request byte limit refuses 64 MiB plus one before JSON decoding") {
            val (status, stdout, stderr) = invoke(ByteArray(64 * 1024 * 1024 + 1) { 32 })
            status shouldBe 2
            stdout shouldBe ""
            stderr shouldBe "<request>:1:1: Migration request exceeds 64 MiB\n"
        }
        test("typed parser errors refuse migration even when codec bytes round trip") {
            val source = Files.readString(Path.of("../../../tests/conformance/yaml-cases/repeated.yaml"))
            val (status, stdout, stderr) = invoke(request(source).toString().toByteArray())
            status shouldBe 2
            stdout shouldBe ""
            stderr.startsWith("<yaml>:14:17: ttr/duplicate-property:") shouldBe true
            stderr.lines().size shouldBe 2
        }
        for (error in listOf(StackOverflowError(), ExceptionInInitializerError("init failed"))) {
            test("CLI last-resort boundary contains ${error.javaClass.simpleName}") {
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                val input =
                    object : java.io.InputStream() {
                        override fun read(): Int = throw error
                    }
                YamlMigrationCli.run(input, stdout, stderr) shouldBe 2
                stdout.size() shouldBe 0
                val message = stderr.toString(Charsets.UTF_8)
                message shouldBe
                    if (error is StackOverflowError) {
                        "<yaml>:1:1: nesting too deep for the canonical YAML codec\n"
                    } else {
                        "<request>:1:1: internal error: ExceptionInInitializerError: init failed\n"
                    }
            }
        }
        val migrationRoot = Path.of("../../../tests/conformance/yaml-migration")
        for (file in migrationInputs(migrationRoot)) {
            test("changed-input migration retains AST and semantics: ${file.fileName}") {
                val before = Files.readString(file)
                val result = YamlMigration.migrate(request(before))
                result["changed"] shouldBe JsonPrimitive(true)
                val after = result.getValue("source").jsonPrimitive.content
                after shouldBe
                    Files.readString(
                        file.resolveSibling(
                            file.fileName.toString().removeSuffix(".yaml") + ".canonical.yaml",
                        ),
                    )
                YamlMigration.migrate(request(after))["changed"] shouldBe JsonPrimitive(false)
                val uri = file.fileName.toString()
                val original = CanonicalYaml.read(before, uri).ast
                val migrated = CanonicalYaml.read(after, uri).ast
                ConformanceDump.dumpTree(migrated) shouldBe ConformanceDump.dumpTree(original)
                SemanticsConformanceDump.dumpDocs(listOf(SemanticsConformanceDump.DocInput(uri, migrated))) shouldBe
                    SemanticsConformanceDump.dumpDocs(listOf(SemanticsConformanceDump.DocInput(uri, original)))
            }
        }
        val roster = YamlFixtureRoster(Path.of("../../../tests/conformance/fixtures"))
        for (scenario in roster.positive) {
            test("canonical roster migration fixed point: ${scenario.name}") {
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

internal fun migrationInputs(root: Path): List<Path> =
    Files.list(root).use { files ->
        files.filter { it.toString().endsWith(".yaml") && !it.toString().endsWith(".canonical.yaml") }.sorted().toList()
    }

/** Deliberate local authoring command, never part of test or CI. */
object GenerateYamlMigrationExpectations {
    @JvmStatic
    fun main(args: Array<String>) {
        for (file in migrationInputs(Path.of("../../../tests/conformance/yaml-migration"))) {
            Files.writeString(
                file.resolveSibling(file.fileName.toString().removeSuffix(".yaml") + ".canonical.yaml"),
                CanonicalYaml.write(CanonicalYaml.read(Files.readString(file))),
            )
        }
    }
}
