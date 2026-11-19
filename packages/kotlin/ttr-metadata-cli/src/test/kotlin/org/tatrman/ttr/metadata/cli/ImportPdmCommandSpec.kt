// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class ImportPdmCommandSpec :
    FunSpec({
        val fixture = Path.of("../../../tests/conformance/readers/pdm").toAbsolutePath().normalize()

        fun files(dir: Path) =
            Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                dir.relativize(it).joinToString("/") to
                    it.readText()
            }

        fun run(
            input: Path,
            out: Path,
            vararg extra: String,
        ) = runMetadataCommand(
            listOf("import-pdm", "--from", input.toString(), "--profile", "pdm/16", "--out", out.toString()) + extra,
        )
        test("a directory imports all models and bindings in one run; dry-run writes nothing") {
            val root = Files.createTempDirectory("s54-cli")
            val sources = root.resolve("sources")
            Files.createDirectories(sources)
            Files
                .list(
                    fixture.resolve("sources"),
                ).use { it.toList() }
                .forEach { Files.copy(it, sources.resolve(it.fileName)) }
            val out = root.resolve("model")
            run(sources, out, "--format", "json").also {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                it.stdout shouldBe fixture.resolve("model/report.json").readText()
            }
            files(out) shouldBe
                files(fixture.resolve("model")).mapValues { (_, text) ->
                    text.replace("generator 0.0.1-LOCAL", "generator ${MetadataVersion.value}")
                }
            run(sources, root.resolve("dry"), "--dry-run").also {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                it.stdout shouldContain "db_sales/binding.ttrm"
            }
            root.resolve("dry").exists() shouldBe false
        }
        test("one file and package override work; missing inputs and bad options exit 2") {
            val root = Files.createTempDirectory("s54-cli-single")
            run(fixture.resolve("sources/sales.cdm"), root.resolve("model"), "--package", "concepts").also {
                withClue(it.stderr) { it.exitCode shouldBe 0 }
                root.resolve("model/concepts/cn.ttrm").exists() shouldBe true
            }
            run(root.resolve("absent.pdm"), root.resolve("bad")).exitCode shouldBe 2
            runMetadataCommand(listOf("import-pdm", "--bad")).exitCode shouldBe 2
        }
        test("version refusal is located, exits 1 and creates no output") {
            val out = Files.createTempDirectory("s54-cli-version").resolve("model")
            run(fixture.resolve("pd15.pdm"), out).also {
                it.exitCode shouldBe 1
                it.stderr shouldContain "pd15.pdm:2:"
                it.stderr shouldContain "xm/pdm-version"
            }
            out.exists() shouldBe false
        }
    })
