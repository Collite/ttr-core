// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadReport
import org.tatrman.ttr.metadata.LoadReportIssue
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.ProfileApplyResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** review-269 (ttr-core#398): F1–F8, at the CLI. */
class Review269Spec :
    FunSpec({
        val conformance = Path.of("../../../tests/conformance").toAbsolutePath().normalize()
        val links = conformance.resolve("dialect/demo-cs-links/tree")
        val negative = conformance.resolve("dialect-negative/demo-cs/tree")

        fun run(vararg args: String) = runMetadataCommand(args.toList())

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
            to: Path,
        ): Path {
            files(from).forEach { (path, text) ->
                to.resolve(path).also { it.parent.createDirectories() }.writeText(text)
            }
            return to
        }

        /** The installed binary run from [cwd]: relative `--root`/`--out`/`--profile` need a working directory. */
        fun exec(
            cwd: Path,
            vararg args: String,
        ): CliOutcome {
            val binary = Path.of(requireNotNull(System.getProperty("metadata.installedBinary"))).toAbsolutePath()
            val process = ProcessBuilder(listOf("$binary") + args).directory(cwd.toFile()).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return CliOutcome(process.waitFor(), stdout, stderr)
        }

        test("F1 ✅R269-A: profile apply and check exit 1 on any error without --strict; apply writes nothing") {
            val out = Files.createTempDirectory("r269-f1").resolve("out")
            val applied = run("profile", "apply", "--root", "$negative", "--out", "$out")
            applied.exitCode shouldBe 1
            out.exists() shouldBe false
            // One dropped file in an otherwise clean tree fails check, with or without --strict.
            val tree = copy(links, Files.createTempDirectory("r269-f1-tree"))
            tree.resolve("l3-preklep.yaml").writeText(negative.resolve("unknown-key.yaml").readText())
            listOf(listOf<String>(), listOf("--strict")).forEach { strict ->
                run(*(listOf("profile", "check", "--root", "$tree") + strict).toTypedArray()).exitCode shouldBe 1
            }
            // Warnings never fail: demo-cs-links applies, all six files.
            val clean = Files.createTempDirectory("r269-f1-clean").resolve("out")
            run("profile", "apply", "--root", "$links", "--out", "$clean").exitCode shouldBe 0
            files(clean).size shouldBe 6
            // lint keeps load's reading: without --strict a load with a model exits 0.
            run("lint", "--root", "$tree").exitCode shouldBe 0
        }

        test("F1: a file only the grouped run dropped is an error in the profile report; a repeat is not added twice") {
            val dropped =
                LoadWarning("checkout", "/t/b.yaml", 2, 1, "ys/profile-conflict: Repeated canonical definition")
            val conflict = LoadWarning("checkout", "/t/a.yaml", 1, 1, "apply: demo/er.yaml is written by both a and b")
            val loaded = LoadReport(issues = emptyList())
            val applied = ProfileApplyResult(TreeMap(), listOf(conflict), listOf(dropped))
            LintCli.merge(loaded, applied).issues.map { it.file to it.severity } shouldContainExactly
                listOf("/t/b.yaml" to LoadIssue.Severity.ERROR, "/t/a.yaml" to LoadIssue.Severity.ERROR)
            // The per-file load already reports it: the grouped run's copy is not repeated.
            val known =
                LoadReport(
                    issues =
                        listOf(
                            LoadReportIssue(
                                "/t/b.yaml",
                                2,
                                "dropped-file",
                                LoadIssue.Severity.ERROR,
                                dropped.message,
                            ),
                        ),
                )
            LintCli.merge(known, applied).issues.map { it.code } shouldContainExactly listOf("dropped-file", "other")
            // A real tree: two dialect files author one entity; the load and the grouped run both drop b.yaml, once.
            val tree = Files.createTempDirectory("r269-f1-grouped")
            tree.resolve("profiles").createDirectories()
            tree.resolve("profiles/p.yaml").writeText(GROUPED_PROFILE)
            tree.resolve("ttr.yaml").writeText("profile: ./profiles/p.yaml\ngrammar: '>=0.22 <1.0'\n")
            tree.resolve("model.ignore").writeText("profiles/\n")
            tree.resolve("a.yaml").writeText("entity:\n  X: {popis: A}\n")
            tree.resolve("b.yaml").writeText("entity:\n  X: {popis: B}\n")
            val check = run("profile", "check", "--root", "$tree")
            check.exitCode shouldBe 1
            check.stdout.lines().count { "ys/profile-conflict" in it } shouldBe 1
        }

        test("F2: no output may land inside the model root, through a parent --out or a symlink") {
            val parent = Files.createTempDirectory("r269-f2")
            val demo = copy(links, parent.resolve("demo"))
            val before = files(demo)
            val into = exec(parent, "profile", "apply", "--root", "demo", "--out", ".")
            into.exitCode shouldBe 2
            into.stderr shouldContain "would write demo/binding.yaml, demo/er.yaml into the model root"
            files(demo) shouldBe before
            Files.createSymbolicLink(parent.resolve("link"), demo)
            exec(parent, "profile", "apply", "--root", "demo", "--out", "link/x").exitCode shouldBe 2
            files(demo) shouldBe before
            // An --out that contains the root is legal while no output lands inside it.
            val other = Files.createTempDirectory("r269-f2-other")
            copy(links, other.resolve("tree"))
            exec(other, "profile", "apply", "--root", "tree", "--out", ".").exitCode shouldBe 0
            other.resolve("demo/er.yaml").isRegularFile() shouldBe true
            other.resolve("report.json").isRegularFile() shouldBe true
        }

        test("F3 ✅R269-B: a --profile path is relative to the working directory; an identity resolves from the root") {
            val parent = Files.createTempDirectory("r269-f3")
            val model = copy(links, parent.resolve("model"))
            val path =
                exec(parent, "lint", "--root", "model", "--profile", "model/profiles/overlay-inline.yaml", "--strict")
            path.exitCode shouldBe 0
            path.stdout shouldContain "3 sources"
            exec(model, "lint", "--root", ".", "--profile", "profiles/overlay-inline.yaml", "--strict").stdout shouldBe
                exec(model, "lint", "--root", ".", "--strict").stdout
            exec(model, "lint", "--root", ".", "--profile", "demo-overlay-inline/1", "--strict").exitCode shouldBe 0
            exec(model, "lint", "--root", ".", "--profile", "canonical/1", "--strict").exitCode shouldBe 1
            val missing = exec(parent, "lint", "--root", "model", "--profile", "nope.yaml")
            missing.exitCode shouldBe 2
            missing.stderr shouldContain "--profile: no profile file nope.yaml"
            // A profile file outside the root is confined (✅R260-B): one clean ys/profile-not-found, its files dropped.
            val outside = Files.createTempDirectory("r269-f3-outside").resolve("p.yaml")
            outside.writeText(links.resolve("profiles/base.yaml").readText())
            val confined =
                exec(parent, "lint", "--root", "model", "--profile", "$outside", "--strict", "--format", "github")
            confined.exitCode shouldBe 1
            val notFound = confined.stdout.lines().filter { "ys/profile-not-found" in it }
            notFound.first() shouldContain
                "file=model/ttr.yaml,line=1,col=1::ys/profile-not-found Profile is outside root"
            notFound.drop(1).all { "dropped:" in it } shouldBe true
        }

        test("F4: an apply conflict names the storage file and the dialect file the document came from") {
            val project = Files.createTempDirectory("r269-f4")
            project.resolve("modeler.toml").writeText("[packages]\nlayout = \"off\"\n")
            val model = copy(links, project.resolve("model"))
            val applied = Files.createTempDirectory("r269-f4-out").resolve("out")
            run("profile", "apply", "--root", "$links", "--out", "$applied").exitCode shouldBe 0
            model.resolve("demo").createDirectories()
            model.resolve("demo/er.yaml").writeText(applied.resolve("demo/er.yaml").readText())
            val text = run("profile", "check", "--root", "$project")
            text.exitCode shouldBe 1
            text.stdout shouldContain
                "model/l2-objednavky.yaml:1: error other: apply: demo/er.yaml is written by both demo/er.yaml and " +
                "l2-objednavky.yaml"
            exec(project.parent, "profile", "check", "--root", "${project.fileName}", "--format", "github")
                .stdout shouldContain
                "::error file=${project.fileName}/model/l2-objednavky.yaml,line=1,col=1::other apply"
        }

        test("F5: --format github columns are 1-based for semantics errors as for ys/ ones") {
            val canonical = conformance.resolve("dialect/demo-cs/canonical")
            val line = exec(canonical.parent, "lint", "--root", "canonical", "--strict", "--format", "github")
            line.stdout shouldContain "::error file=canonical/04-nest.yaml,line=10,col=9::semantics-invalid TTR-SEM-204"
            canonical
                .resolve("04-nest.yaml")
                .readText()
                .lines()[9]
                .indexOf("role") + 1 shouldBe 9
        }

        test("F6: a checkout whose model/ is a dialect tree is served with layout off, whatever the manifest says") {
            val project = Files.createTempDirectory("r269-f6")
            project.resolve("modeler.toml").writeText("[packages]\nlayout = \"strict\"\n")
            copy(links, project.resolve("model"))
            val out = run("lint", "--root", "$project", "--strict", "--format", "github")
            out.exitCode shouldBe 0
            out.stdout.contains("package-declaration-mismatch") shouldBe false
        }

        test("F7: lint on a checkout without model/ reports load's no-model-dir and exits 1") {
            val project = Files.createTempDirectory("r269-f7")
            project.resolve("modeler.toml").writeText("")
            val load = MetadataLoadCli.run(project, strict = true)
            val text = run("lint", "--root", "$project", "--strict")
            text.exitCode shouldBe 1
            text.stdout shouldBe load.stderr
            val github = run("lint", "--root", "$project", "--strict", "--format", "github")
            github.exitCode shouldBe 1
            github.stdout
                .lines()
                .filter { it.isNotEmpty() }
                .single() shouldContain "::no-model-dir "
            // A root that is not a directory stays usage.
            run("lint", "--root", "${project.resolve("missing")}").exitCode shouldBe 2
        }

        test("F8: two catalogue files declaring one identity are a usage error naming both") {
            val tree = copy(links, Files.createTempDirectory("r269-f8"))
            tree.resolve("profiles/twin.yaml").writeText(tree.resolve("profiles/base.yaml").readText())
            val out = run("lint", "--root", "$tree", "--profile", "demo-base/1")
            out.exitCode shouldBe 2
            out.stderr shouldContain "profiles profiles/base.yaml and profiles/twin.yaml both declare demo-base/1"
        }

        test("F8: github cuts only a fan-out `#<model>` suffix; a `#` in a file name is kept") {
            val tree = Files.createTempDirectory("r269-f8-hash")
            tree.resolve("a#b.yaml").writeText("ttr: \"0.22\"\npackage: p\nmodel: er\nentity:\n  X: {nope: 1}\n")
            val out = run("lint", "--root", "$tree", "--format", "github")
            out.stdout
                .lines()
                .filter { it.startsWith("::error") }
                .forEach { it shouldContain "a#b.yaml" }
            out.stdout shouldContain "a#b.yaml"
        }
    })

private val GROUPED_PROFILE =
    """
    profile: grp/1
    grammar: '>=0.22 <1.0'
    operations:
      - {op: package, mapping: {}, default: demo}
      - {op: kinds, at: /entity, mapping: {entita: entity}}
      - {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}
    """.trimIndent() + "\n"
