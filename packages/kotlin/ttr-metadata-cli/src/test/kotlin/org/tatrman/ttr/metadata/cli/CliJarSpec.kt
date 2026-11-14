// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * MW S3.7 T3 — the released `ttr-metadata-cli-<v>.jar` (contracts §7) runs alone under `java -jar`, as ai-models runs
 * `model-validator.jar`: same verbs and exit codes as the installed distribution, nothing on stderr, and a
 * `sha256sum`-format checksum beside it.
 */
class CliJarSpec :
    FunSpec({
        val jar = Path.of(requireNotNull(System.getProperty("metadata.cliJar"))).toAbsolutePath()
        val conformance = Path.of("../../../tests/conformance").toAbsolutePath().normalize()

        fun java(vararg args: String): CliOutcome {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val process = ProcessBuilder(listOf(java, "-jar", "$jar") + args).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return CliOutcome(process.waitFor(), stdout, stderr)
        }

        test("java -jar lints a dialect tree green with nothing on stderr (no SLF4J or ANTLR noise)") {
            val out = java("lint", "--root", "${conformance.resolve("dialect/demo-cs/tree")}", "--strict")
            out.exitCode shouldBe 0
            out.stderr shouldBe ""
            out.stdout shouldEndWith "11 sources, 0 issues\n"
        }

        test("java -jar refuses the negative tree with the same annotations and exit code as the distribution") {
            val tree = conformance.resolve("dialect-negative/demo-cs/tree")
            val out = java("lint", "--root", "$tree", "--strict", "--format", "github")
            out.exitCode shouldBe 1
            out.stderr shouldBe ""
            val cwd = Path.of("").toAbsolutePath()
            val shown = if (tree.startsWith(cwd)) cwd.relativize(tree).joinToString("/") else "$tree"
            out.stdout shouldBe
                conformance
                    .resolve("dialect-negative/demo-cs/expected-github.txt")
                    .readText()
                    .replace("file=", "file=$shown/")
        }

        test("java -jar lint --codes prints the catalogue; the .sha256 beside the jar is its digest") {
            java("lint", "--codes").stdout shouldBe DiagnosticCodes.render()
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))
            jar.resolveSibling("${jar.name}.sha256").readText() shouldBe
                "${digest.joinToString("") { "%02x".format(it) }}  ${jar.name}\n"
        }
    })
