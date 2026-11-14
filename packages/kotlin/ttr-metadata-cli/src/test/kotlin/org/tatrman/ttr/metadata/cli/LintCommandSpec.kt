// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** MW S3.5 T1 — `profile apply`, `profile check` and `lint` (contracts §5). */
class LintCommandSpec :
    FunSpec({
        val conformance = Path.of("../../../tests/conformance").toAbsolutePath().normalize()
        val positive = conformance.resolve("dialect/demo-cs/tree")
        val links = conformance.resolve("dialect/demo-cs-links/tree")
        val negative = conformance.resolve("dialect-negative/demo-cs")

        fun run(vararg args: String) = runMetadataCommand(args.toList())

        /** Every file under [root] by relative path, with its text. */
        fun files(root: Path): Map<String, String> =
            if (!Files.exists(root)) {
                emptyMap()
            } else {
                Files.walk(root).use { paths -> paths.filter { it.isRegularFile() }.toList() }.associate {
                    root.relativize(it).joinToString("/") to it.readText()
                }
            }

        fun copy(
            from: Path,
            to: Path = Files.createTempDirectory("s35-tree"),
        ): Path {
            files(from).forEach { (path, text) ->
                to.resolve(path).also { it.parent.createDirectories() }.writeText(text)
            }
            return to
        }

        // review-269 ✅R269-A: apply fails on any error, so the positive vector is demo-cs-links (warnings only); the demo-cs
        // tree holds two strict errors (S3.7 T0).
        val appliedLinks =
            setOf(
                "demo/binding.yaml",
                "demo/er.yaml",
                "prodej/er.yaml",
                "demo.sources.json",
                "prodej.sources.json",
                "report.json",
            )

        test("apply writes <package>/<model>.yaml, <package>.sources.json and report.json, byte-equal across runs") {
            val first = Files.createTempDirectory("s35-out").resolve("a")
            val second = Files.createTempDirectory("s35-out").resolve("b")
            run("profile", "apply", "--root", "$links", "--out", "$first").exitCode shouldBe 0
            run("profile", "apply", "--root", "$links", "--out", "$second").exitCode shouldBe 0
            val written = files(first)
            written.keys shouldBe appliedLinks
            written shouldBe files(second)
            Json
                .parseToJsonElement(
                    written.getValue("demo.sources.json"),
                ).jsonObject["entries"]!!
                .jsonArray.size shouldBe
                0
            val report = Json.parseToJsonElement(written.getValue("report.json")).jsonObject
            report.keys shouldBe setOf("documents", "ignored", "overridden")
            report["documents"]!!.jsonArray.map { (it as JsonObject)["model"].toString() } shouldBe
                listOf("\"binding\"", "\"er\"", "\"er\"")
        }

        test("apply of a canonical tree with canonical/1 is byte-identical to the input files") {
            // demo-cs's twin carries the same two strict errors as its tree, so the CLI identity runs on demo-cs-links;
            // ProfileApplySpec pins the identity of both twins at the library.
            listOf("demo-cs-links").forEach { case ->
                val canonical = conformance.resolve("dialect/$case/canonical")
                val out = Files.createTempDirectory("s35-identity").resolve("out")
                run(
                    "profile",
                    "apply",
                    "--root",
                    "$canonical",
                    "--profile",
                    "canonical/1",
                    "--out",
                    "$out",
                ).exitCode shouldBe
                    0
                withClue(case) { files(out).filterKeys { it.endsWith(".yaml") } shouldBe files(canonical) }
            }
        }

        test("check writes nothing and exits 1 with located diagnostics on the authored key") {
            val tree = copy(negative.resolve("tree"))
            val before = files(tree)
            val out = run("profile", "check", "--root", "$tree", "--strict")
            out.exitCode shouldBe 1
            out.stdout shouldContain "unknown-key.yaml:3: error dropped-file: ys/unknown-key: Unconsumed key preklep"
            out.stdout shouldContain "conflict.yaml:2: error dropped-file: ys/profile-conflict: Two operations write"
            files(tree) shouldBe before
            // A check is the same pipeline: a positive tree passes it.
            run("profile", "check", "--root", "$links", "--strict").exitCode shouldBe 0
        }

        test(
            "lint --strict --format github: one ::error per error on the dialect file, exit 1; json is the report shape",
        ) {
            val github = run("lint", "--root", "${negative.resolve("tree")}", "--strict", "--format", "github")
            github.exitCode shouldBe 1
            val tree = negative.resolve("tree")
            val cwd = Path.of("").toAbsolutePath()
            val shown = if (tree.startsWith(cwd)) cwd.relativize(tree).joinToString("/") else "$tree"
            github.stdout shouldBe negative.resolve("expected-github.txt").readText().replace("file=", "file=$shown/")
            run("lint", "--root", "$links", "--strict", "--format", "github").exitCode shouldBe 0
            // A fan-out file's second document (`<file>#<model>`) is annotated on the file itself, once per issue.
            val fanOut =
                run(
                    "lint",
                    "--root",
                    "$positive",
                    "--format",
                    "github",
                ).stdout.lines().filter { it.isNotEmpty() }
            fanOut.any { "03-mixed.yaml::ttr/unimported-reference" in it } shouldBe true
            fanOut.none { '#' in it.removePrefix("::").substringBefore("::") } shouldBe true
            fanOut shouldBe fanOut.distinct()
            val json = Json.parseToJsonElement(run("lint", "--root", "$tree", "--format", "json").stdout).jsonObject
            json.keys shouldBe setOf("version", "sources", "issues")
            json["issues"]!!.jsonArray.map { it.jsonObject.keys }.toSet() shouldBe
                setOf(setOf("file", "line", "column", "code", "severity", "message"))
            json["issues"]!!.jsonArray.any {
                (it.jsonObject["file"] as JsonPrimitive).content == "unknown-key.yaml"
            } shouldBe
                true
            // §21 (g): lint loads through the yaml source, so agents/ is never read, however broken.
            val agents = copy(links)
            agents.resolve("agents/golem.yaml").also { it.parent.createDirectories() }.writeText("{ not: [yaml\n")
            val withAgents = run("lint", "--root", "$agents", "--strict", "--format", "json")
            withAgents.exitCode shouldBe 0
            withAgents.stdout.contains("agents/") shouldBe false
        }

        test("lint on a .ttrm checkout is load --strict: the same diagnostics, report and exit code") {
            listOf(
                "clean",
                "dropped-file",
                "reconcile-error",
                "layout-strict",
                "layout-off",
                "manifest-invalid",
            ).forEach {
                val world = Path.of(javaClass.getResource("/worlds/$it")!!.toURI())
                val load = MetadataLoadCli.run(world, strict = true, json = true)
                withClue(it) {
                    val text = run("lint", "--root", "$world", "--strict")
                    text.exitCode shouldBe load.exitCode
                    text.stdout shouldBe load.stderr
                    val json =
                        Json.parseToJsonElement(
                            run("lint", "--root", "$world", "--strict", "--format", "json").stdout,
                        )
                    val withoutColumns =
                        JsonObject(
                            json.jsonObject +
                                (
                                    "issues" to
                                        kotlinx.serialization.json.JsonArray(
                                            json.jsonObject["issues"]!!.jsonArray.map {
                                                JsonObject(
                                                    it.jsonObject - "column",
                                                )
                                            },
                                        )
                                ),
                        )
                    withoutColumns shouldBe Json.parseToJsonElement(load.stdout)
                }
            }
        }

        test("T6: the installed binary runs lint, profile check and profile apply from a temp dir") {
            val binary = Path.of(requireNotNull(System.getProperty("metadata.installedBinary"))).toAbsolutePath()
            val work = Files.createTempDirectory("s35-smoke")
            copy(links, work.resolve("tree"))
            copy(negative.resolve("tree"), work.resolve("negative"))

            fun exec(vararg args: String): Pair<Int, String> {
                val process =
                    ProcessBuilder(listOf("$binary") + args)
                        .directory(work.toFile())
                        .redirectErrorStream(false)
                        .start()
                val stdout = process.inputStream.bufferedReader().readText()
                process.errorStream.bufferedReader().readText()
                return process.waitFor() to stdout
            }
            val (lintExit, lint) = exec("lint", "--root", "negative", "--strict", "--format", "github")
            lintExit shouldBe 1
            lint shouldBe negative.resolve("expected-github.txt").readText().replace("file=", "file=negative/")
            exec("profile", "check", "--root", "negative", "--strict").first shouldBe 1
            exec("profile", "apply", "--root", "tree", "--out", "out").first shouldBe 0
            files(work.resolve("out")).keys shouldBe appliedLinks
            exec("lint", "--codes").second shouldBe DiagnosticCodes.render()
            exec("lint").first shouldBe 2
        }
    })
