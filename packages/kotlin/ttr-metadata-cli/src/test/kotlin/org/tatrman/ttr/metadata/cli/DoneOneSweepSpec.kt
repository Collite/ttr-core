// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW S3.7 T1 — the phase DONE 1 sweep (plan §4): `tests/conformance/demo-cs-index.json` is the checklist. Every `raises`
 * entry is run through the CLI and must report its code at its authored `<file>:<line>`; every code of the diagnostic
 * catalogue but the reserved ones must be raised by an entry; every `covers` area must find its evidence in its fixtures.
 */
class DoneOneSweepSpec :
    FunSpec({
        val conformance = Path.of("../../../tests/conformance").toAbsolutePath().normalize()
        val index = Json.parseToJsonElement(conformance.resolve("demo-cs-index.json").readText()).jsonObject
        val entries = index.getValue("raises").jsonArray.map { it.jsonObject }

        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

        fun JsonObject.texts(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        /** The CLI's output (stdout + stderr) for one `raises` entry. */
        fun output(entry: JsonObject): String =
            when {
                "lint" in entry -> {
                    val root = conformance.resolve(entry.text("lint"))
                    val out = runMetadataCommand(listOf("lint", "--root", "$root", "--strict", "--format", "github"))
                    out.stdout + out.stderr
                }
                "snapshot" in entry -> {
                    // A 6 MiB export (three-byte padding keeps it inside the reader's budget) captured with the fixture's
                    // profiles as the working directory's catalogue, without --large.
                    val work = Files.createTempDirectory("s37-sweep")
                    val profiles = conformance.resolve(entry.text("snapshot"))
                    Files.walk(profiles).use { paths -> paths.filter { it.isRegularFile() }.toList() }.forEach {
                        work
                            .resolve("profiles")
                            .resolve(profiles.relativize(it).toString())
                            .also { file ->
                                file.parent.createDirectories()
                            }.writeText(it.readText())
                    }
                    val pad = "…".repeat(1_000)
                    val records = (1..2_100).joinToString(",") { """"T$it": {"popis": "T$it $pad"}""" }
                    work.resolve("export.json").writeText("{\"entity\": {$records}}\n")
                    check(Files.size(work.resolve("export.json")) > 6L * 1024 * 1024)
                    val binary =
                        Path
                            .of(
                                requireNotNull(System.getProperty("metadata.installedBinary")),
                            ).toAbsolutePath()
                    val args =
                        listOf(
                            "snapshot",
                            "--from",
                            "file:export.json",
                            "--profile",
                            entry.text("profile"),
                            "--out",
                            "s",
                        )
                    val process = ProcessBuilder(listOf("$binary") + args).directory(work.toFile()).start()
                    val stdout = process.inputStream.bufferedReader().readText()
                    val stderr = process.errorStream.bufferedReader().readText()
                    process.waitFor()
                    stdout + stderr
                }
                else -> error("an index entry needs lint or snapshot: $entry")
            }

        /** True when [output] reports [code] at `<file>:<line>` ([at]): a github annotation or a `file:line: error` line. */
        fun reports(
            output: String,
            code: String,
            at: String,
        ): Boolean {
            val file = Regex.escape(at.substringBeforeLast(':'))
            val line = at.substringAfterLast(':')
            val github = Regex("""^::(?:error|warning) file=(?:.*/)?$file,line=$line,col=\d+::$code """)
            val plain = Regex("""^(?:.*/)?$file:$line: error $code:""")
            return output.lines().any { github.containsMatchIn(it) || plain.containsMatchIn(it) }
        }

        val raised = mutableSetOf<String>()

        entries.forEach { entry ->
            val code = entry.text("code")
            test("$code is raised at ${entry.text("at")}") {
                val out = output(entry)
                withClue(out) { reports(out, code, entry.text("at")) shouldBe true }
                raised += code
            }
        }

        test("the catalogue is the checklist: every code but the reserved ones is raised by an indexed fixture") {
            val reserved = index.texts("reserved").toSet()
            // Reserved means raised nowhere yet; the catalogue says so in raisedBy.
            DiagnosticCodes.all
                .filter { it.raisedBy.startsWith("reserved") }
                .map { it.code }
                .toSet() shouldBe reserved
            val catalogue = DiagnosticCodes.all.map { it.code }.toSet()
            (catalogue - reserved - entries.map { it.text("code") }.toSet()).shouldBeEmpty()
            (entries.map { it.text("code") }.toSet() - catalogue).shouldBeEmpty()
            (catalogue - reserved - raised).shouldBeEmpty()
        }

        test(
            "every covered area has its evidence in its fixtures: nine families + link, overlay, inheritance, ranges …",
        ) {
            val covers = index.getValue("covers").jsonObject
            val ops =
                listOf("package", "kinds", "rename", "values", "shape", "nest", "unnest", "ignore", "codes", "link")
            val required =
                ops.map { "op $it" } +
                    listOf(
                        "overlay",
                        "inheritance",
                        "grammar ranges",
                        "declarations",
                        "unknown-key refusal",
                        "conflicts",
                        "source positions",
                        "emission by package x model",
                    )
            (required.toSet() - covers.keys).shouldBeEmpty()
            covers.forEach { (area, value) ->
                val spec = value.jsonObject
                val texts =
                    spec.texts("in").flatMap { path ->
                        val root = conformance.resolve(path)
                        check(Files.isDirectory(root)) { "$area: no fixture directory $path" }
                        Files
                            .walk(root)
                            .use { paths ->
                                paths.filter { it.isRegularFile() }.toList()
                            }.map { it.readText() }
                    }
                spec.texts("evidence").forEach { evidence ->
                    withClue("$area: $evidence") { texts.any { Regex(evidence).containsMatchIn(it) } shouldBe true }
                }
                val codes = entries.map { it.text("code") }.toSet()
                withClue(area) { (spec.texts("raises").toSet() - codes).shouldBeEmpty() }
            }
        }
    })
