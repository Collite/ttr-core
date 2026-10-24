// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** runReportCommand returns coded diagnostics, but cannot capture third-party process stderr. */
class ProcessLoggingSpec :
    StringSpec({
        "fresh CLI processes generate and render quietly while refusals remain visible" {
            val world = Path.of(requireNotNull(javaClass.getResource("/worlds/reports/modeler.toml")).toURI()).parent
            val root = Files.createTempDirectory("report-process")

            fun run(args: List<String>): CliOutcome {
                val stdout = root.resolve("stdout.txt")
                val stderr = root.resolve("stderr.txt")
                val process =
                    ProcessBuilder(
                        listOf(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            "org.tatrman.report.cli.MainKt",
                        ) + args,
                    ).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
                try {
                    process.waitFor(60, TimeUnit.SECONDS) shouldBe true
                    return CliOutcome(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            // Calcite's pre-existing ANTLR generator/runtime warning is tracked separately (review-127 R2).
            val antlrVersion = Regex("ANTLR (Tool|Runtime) version .+ does not match the current runtime version .+")

            fun successful(result: CliOutcome) {
                withClue(result) {
                    result.exitCode shouldBe 0
                    result.stdout shouldBe ""
                    result.stderr
                        .lineSequence()
                        .filter {
                            it.isNotBlank() &&
                                !antlrVersion.matches(
                                    it,
                                )
                        }.toList() shouldBe
                        emptyList()
                }
            }
            try {
                val generated =
                    run(
                        listOf(
                            "new",
                            world.toString(),
                            "--from",
                            "er.query.report_returns_review_q",
                            "--id",
                            "returns_review_q",
                            "--title",
                            "Returns",
                            "--out",
                            root.resolve("returns_review_q").toString(),
                        ),
                    )
                successful(generated)
                val rendered =
                    run(
                        listOf(
                            "render",
                            world.toString(),
                            "returns_review_q",
                            "--rows",
                            "ROWS=${world.resolve("rows.csv")}",
                            "--out",
                            root.resolve("rendered.xlsx").toString(),
                        ),
                    )
                successful(rendered)
                val refused = run(listOf("render", root.toString(), "absent", "--rows", "ROWS=absent.csv", "--html"))
                refused.exitCode shouldBe 1
                refused.stderr shouldContain "ttr-report: manifest-missing:"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
