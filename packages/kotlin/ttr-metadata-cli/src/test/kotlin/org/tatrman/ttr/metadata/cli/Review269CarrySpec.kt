// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** MW S3.6 T0 — review-269 re-review 1 carry-over (✅R269-C): N1, N2 and N3's CLI nits. */
class Review269CarrySpec :
    FunSpec({
        val conformance = Path.of("../../../tests/conformance").toAbsolutePath().normalize()
        val links = conformance.resolve("dialect/demo-cs-links/tree")

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

        // N1's "a dangling link outside the root is written through" became "refused" under ✅R277-D (S3.7 T0); its
        // layouts, review-277 F6's and the link cycle live in Review277CarrySpec.

        test("N2: a duplicated identity no run names is left out of the catalog with one warning naming both files") {
            val tree = copy(links, Files.createTempDirectory("r269-n2"))
            tree.resolve("profiles/archive").createDirectories()
            tree.resolve("profiles/archive/base-old.yaml").writeText(tree.resolve("profiles/base.yaml").readText())
            val text = run("lint", "--root", "$tree", "--strict")
            text.exitCode shouldBe 0
            val warnings = text.stdout.lines().filter { "profile-duplicate" in it }
            warnings.size shouldBe 1
            warnings.single() shouldContain
                "profiles profiles/archive/base-old.yaml and profiles/base.yaml both declare demo-base/1"
            run("profile", "check", "--root", "$tree").exitCode shouldBe 0
            run("lint", "--root", "$tree", "--format", "github").stdout shouldContain "::warning "
            // A run that names it stays usage, naming both files (review-269 F8).
            val named = run("lint", "--root", "$tree", "--profile", "demo-base/1")
            named.exitCode shouldBe 2
            named.stderr shouldContain "profiles profiles/archive/base-old.yaml and profiles/base.yaml both declare"
        }

        test("N2: a profile that extends the duplicated identity fails where it asks, ys/profile-not-found") {
            val tree = copy(links, Files.createTempDirectory("r269-n2-extends"))
            tree.resolve("profiles/twin.yaml").writeText(tree.resolve("profiles/base.yaml").readText())
            val middle = tree.resolve("profiles/middle.yaml")
            middle.writeText(middle.readText().replace("extends: ./base.yaml", "extends: demo-base/1"))
            val out = run("lint", "--root", "$tree", "--strict", "--format", "github")
            out.exitCode shouldBe 1
            out.stdout shouldContain "profiles/middle.yaml"
            out.stdout shouldContain "ys/profile-not-found No catalog entry for demo-base/1"
        }

        test("N3 (F2): --root through a symlink is compared on the real root") {
            val work = Files.createTempDirectory("r269-n3-root")
            val tree = copy(links, work.resolve("tree"))
            val before = files(tree)
            Files.createSymbolicLink(work.resolve("link"), tree)
            exec(work, "profile", "apply", "--root", "link", "--out", "tree/gen").exitCode shouldBe 2
            files(tree) shouldBe before
        }

        test("N3 (F6): a yaml checkout's unknown layout says the tree is served with layout off") {
            val project = Files.createTempDirectory("r269-n3-layout")
            project.resolve("modeler.toml").writeText("[packages]\nlayout = \"loose\"\n")
            copy(links, project.resolve("model"))
            val out = run("lint", "--root", "$project")
            out.stdout shouldContain "Unknown [packages].layout value 'loose'"
            out.stdout shouldContain "A yaml tree is served with layout off."
            out.stdout.contains("Falling back to flexible") shouldBe false
        }
    })
