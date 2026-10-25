// SPDX-License-Identifier: Apache-2.0
package org.tatrman.report.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.Properties
import java.util.jar.JarFile

/** runReportCommand returns coded diagnostics, but cannot capture third-party process stderr. */
class ProcessLoggingSpec :
    StringSpec({
        "shipped logging bridge matches the API and uses only the existing NOP backend" {
            val components = mutableMapOf<String, MutableList<String>>()
            val slf4jProviders = mutableListOf<String>()
            val log4jProviders = mutableListOf<String>()
            Files.list(distribution().resolve("lib")).use { paths ->
                paths.filter { it.toString().endsWith(".jar") }.forEach { path ->
                    JarFile(path.toFile()).use { jar ->
                        for ((group, artifact) in listOf(
                            "org.slf4j" to "slf4j-api",
                            "org.slf4j" to "slf4j-nop",
                            "org.apache.logging.log4j" to "log4j-api",
                            "org.apache.logging.log4j" to "log4j-to-slf4j",
                        )) {
                            val entry = jar.getJarEntry("META-INF/maven/$group/$artifact/pom.properties") ?: continue
                            val properties = Properties().apply { jar.getInputStream(entry).use { load(it) } }
                            val version =
                                requireNotNull(properties.getProperty("version")) { "$path has no $artifact version" }
                            version.isNotBlank() shouldBe true
                            components.getOrPut(artifact) { mutableListOf() }.add(version)
                        }
                        slf4jProviders += providers(jar, "org.slf4j.spi.SLF4JServiceProvider")
                        log4jProviders += providers(jar, "org.apache.logging.log4j.spi.Provider")
                        // Legacy Log4j providers must not bypass the service-provider guard.
                        jar.getJarEntry("META-INF/log4j-provider.properties") shouldBe null
                    }
                }
            }
            withClue(components) {
                components.keys shouldBe setOf("slf4j-api", "slf4j-nop", "log4j-api", "log4j-to-slf4j")
                components.values.forEach { it.size shouldBe 1 }
                components["log4j-api"] shouldBe components["log4j-to-slf4j"]
                components["slf4j-api"] shouldBe components["slf4j-nop"]
            }
            slf4jProviders shouldBe listOf("org.slf4j.nop.NOPServiceProvider")
            log4jProviders shouldBe listOf("org.apache.logging.slf4j.SLF4JProvider")
        }

        "fresh CLI processes generate and render quietly while refusals remain visible" {
            val distribution = distribution()
            val world = Path.of(requireNotNull(javaClass.getResource("/worlds/reports/modeler.toml")).toURI()).parent
            val root = Files.createTempDirectory("report-process")

            fun run(args: List<String>): CliOutcome {
                val stdout = root.resolve("stdout.txt")
                val stderr = root.resolve("stderr.txt")
                val process =
                    ProcessBuilder(
                        listOf(
                            Path
                                .of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
                                ).toString(),
                            "-cp",
                            distribution.resolve("lib").toString() + java.io.File.separator + "*",
                            "org.tatrman.report.cli.MainKt",
                        ) + args,
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

private fun distribution(): Path =
    Path.of(
        checkNotNull(System.getProperty("ttr.report.dist")) {
            "Run :packages:kotlin:ttr-report-cli:distTest, or set -Dttr.report.dist=<installDist directory> in the IDE runner."
        },
    )

private fun providers(
    jar: JarFile,
    service: String,
): List<String> {
    val entry = jar.getJarEntry("META-INF/services/$service") ?: return emptyList()
    return jar.getInputStream(entry).bufferedReader().useLines { lines ->
        lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
    }
}
