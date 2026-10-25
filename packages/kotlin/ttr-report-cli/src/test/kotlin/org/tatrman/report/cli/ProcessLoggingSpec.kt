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
        val distribution = Path.of(requireNotNull(System.getProperty("ttr.report.dist")))

        "shipped logging bridge matches the API and uses only the existing NOP backend" {
            val jars =
                Files.list(distribution.resolve("lib")).use { paths ->
                    paths.map { it.fileName.toString() }.toList()
                }
            val api = jars.single { it.startsWith("log4j-api-") }
            val version = api.removePrefix("log4j-api-").removeSuffix(".jar")
            jars.filter { it.startsWith("log4j-") }.sorted() shouldBe
                listOf(api, "log4j-to-slf4j-$version.jar").sorted()
            jars.filter { it.startsWith("slf4j-") }.map { it.substringBeforeLast('-') }.sorted() shouldBe
                listOf("slf4j-api", "slf4j-nop")
            jars.none { it.startsWith("logback-") } shouldBe true
        }

        "fresh CLI processes generate and render quietly while refusals remain visible" {
            val world = Path.of(requireNotNull(javaClass.getResource("/worlds/reports/modeler.toml")).toURI()).parent
            val root = Files.createTempDirectory("report-process")

            fun run(args: List<String>): CliOutcome {
                val stdout = root.resolve("stdout.txt")
                val stderr = root.resolve("stderr.txt")
                val process =
                    ProcessBuilder(
                        listOf(distribution.resolve("bin/ttr-report").toString()) + args,
                    ).apply {
                        environment()["JAVA_HOME"] = System.getProperty("java.home")
                        for (key in listOf(
                            "JAVA_TOOL_OPTIONS",
                            "_JAVA_OPTIONS",
                            "JDK_JAVA_OPTIONS",
                            "JAVA_OPTS",
                            "TTR_REPORT_OPTS",
                        )) {
                            environment().remove(key)
                        }
                    }.redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile())
                        .start()
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
