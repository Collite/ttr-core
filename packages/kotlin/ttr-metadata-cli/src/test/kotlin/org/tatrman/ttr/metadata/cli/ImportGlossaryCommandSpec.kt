// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MW-P5 S5.2 T5 — `import-glossary --from <file> --profile skos/1|om-glossary/1 --out <tmp>`: the committed expectation
 * (`tests/conformance/readers/glossary/model`) byte-equal from the Turtle file, lint-strict beside the authored core;
 * the syntax follows the extension or `--syntax`; `--dry-run` writes nothing.
 */
class ImportGlossaryCommandSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/glossary").toAbsolutePath().normalize()

        fun files(directory: Path): Map<String, String> =
            Files.walk(directory).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                directory.relativize(it).joinToString("/") to it.readText()
            }

        fun workspace(): Path =
            Files.createTempDirectory("s52-cli").also { root ->
                files(fixture.resolve("sources")).forEach { (path, text) ->
                    root.resolve("sources/$path").also { it.parent.createDirectories() }.writeText(text)
                }
            }

        fun import(
            root: Path,
            input: String,
            profile: String = "skos/1",
            vararg extra: String,
        ) = runMetadataCommand(
            listOf(
                "import-glossary",
                "--from",
                "${root.resolve("sources/$input")}",
                "--profile",
                profile,
                "--out",
                "${root.resolve("model")}",
            ) + extra,
        )

        test("writes the committed expectation byte-equal from Turtle, and the package lints strict beside its core") {
            val root = workspace()
            val out = import(root, "trade.ttl")
            withClue(out.stderr) { out.exitCode shouldBe 0 }
            out.stdout shouldStartWith "### Import report — `skos/1` (turtle front-end)"
            files(root.resolve("model")) shouldBe files(fixture.resolve("model"))

            root
                .resolve(
                    "model/glossary/core.ttrm",
                ).writeText(fixture.resolve("authored/glossary/core.ttrm").readText())
            val lint = runMetadataCommand(listOf("lint", "--root", "${root.resolve("model")}", "--strict"))
            withClue(lint.stdout + lint.stderr) {
                lint.exitCode shouldBe 0
                lint.stdout shouldContain "0 issues"
            }
        }

        test("the syntax follows the extension or --syntax; an unknown extension is a usage error") {
            val root = workspace()
            import(root, "trade.rdf").let {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                it.stdout shouldContain "(rdfxml front-end)"
            }
            import(root, "trade.csv", "om-glossary/1").let {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                it.stdout shouldContain "(csv front-end)"
            }
            val other =
                root
                    .resolve(
                        "sources/trade.skos",
                    ).also { it.writeText(root.resolve("sources/trade.ttl").readText()) }
            import(root, "trade.skos").let {
                it.exitCode shouldBe 2
                it.stderr shouldContain "no syntax for .skos; name one with --syntax turtle|rdfxml|csv"
            }
            import(root, "${other.fileName}", "skos/1", "--syntax", "turtle").exitCode shouldBe 0
        }

        test("--dry-run prints the diff and writes nothing") {
            val root = workspace()
            import(root, "trade.ttl", "skos/1", "--dry-run").let {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                it.stdout shouldContain "+++ b/glossary/lexicon.cs.ttrm"
            }
            root.resolve("model").exists() shouldBe false
        }

        test("review-350 F3: any file name imports, and a relative IRI's sourceName does not change with it") {
            val turtle =
                "@prefix s: <http://www.w3.org/2004/02/skos/core#> .\n" +
                    "<#Rel> a s:Concept ; s:prefLabel \"rel\"@en .\n"
            val sidecars =
                listOf("50%.ttl", "x[1].ttl", "a#b.ttl", "Trade Glossary.ttl").map { name ->
                    val root = Files.createTempDirectory("s52-cli-names")
                    root.resolve("sources/$name").also { it.parent.createDirectories() }.writeText(turtle)
                    val out = import(root, name)
                    withClue("$name: ${out.stderr}") { out.exitCode shouldBe 0 }
                    root
                        .resolve("model/glossary.sources.json")
                        .readText()
                        .lines()
                        .drop(2)
                        .joinToString("\n")
                }
            sidecars.distinct().size shouldBe 1
            sidecars.first() shouldContain "\"sourceName\":\"file:///#Rel\""
        }
    })
