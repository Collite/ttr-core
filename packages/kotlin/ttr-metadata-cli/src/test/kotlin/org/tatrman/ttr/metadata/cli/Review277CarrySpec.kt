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

/**
 * MW S3.7 T0 — review-277 re-review 1 carry-over (✅R277-C), the CLI half: N2 (✅R277-D) — apply never writes through a
 * symbolic link at or below `--out`; `--out` itself may be one and is made real once.
 */
class Review277CarrySpec :
    FunSpec({
        val links =
            Path
                .of("../../../tests/conformance/dialect/demo-cs-links/tree")
                .toAbsolutePath()
                .normalize()

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

        /** Exit 2 with the ✅R277-D message naming [at], and no regular file written anywhere under [tmp]. */
        fun refused(
            tmp: Path,
            at: String,
            vararg args: String,
        ) {
            val before = files(tmp)
            val out = exec(tmp, "profile", "apply", *args)
            out.exitCode shouldBe 2
            out.stderr shouldContain "contains a symbolic link at $at; apply does not write through links"
            files(tmp) shouldBe before
        }

        test("N2: a relative link target through a symlinked directory is refused, not resolved (re-review 1 layout)") {
            val tmp = Files.createTempDirectory("r277-n2")
            copy(links, tmp.resolve("repo/model"))
            tmp.resolve("repo/deep/a").createDirectories()
            tmp.resolve("out").createDirectories()
            Files.createSymbolicLink(tmp.resolve("out/s"), tmp.resolve("repo/deep/a"))
            // The OS goes out/s → repo/deep/a, then ../.. → repo/model/gen; lexically it would be <tmp>/model/gen.
            Files.createSymbolicLink(tmp.resolve("out/report.json"), Path.of("s/../../model/gen"))
            refused(tmp, "report.json", "--root", "repo/model", "--out", "out")
            tmp.resolve("repo/model/gen").exists() shouldBe false
        }

        test("N2: review-277 F6's layout — a dangling link below a symlinked --out — is refused") {
            val tmp = Files.createTempDirectory("r277-n2-f6")
            copy(links, tmp.resolve("repo/model"))
            tmp.resolve("repo/outside/sub").createDirectories()
            tmp.resolve("w").createDirectories()
            Files.createSymbolicLink(tmp.resolve("w/o"), tmp.resolve("repo/outside/sub"))
            Files.createSymbolicLink(tmp.resolve("repo/outside/sub/report.json"), Path.of("../../model/gen"))
            refused(tmp, "report.json", "--root", "repo/model", "--out", "w/o")
            tmp.resolve("repo/model/gen").exists() shouldBe false
        }

        test("N2: review-269 N1's dangling links — into the root, through a hop, to outside, a cycle — are refused") {
            val work = Files.createTempDirectory("r277-n2-r269")
            copy(links, work.resolve("tree"))
            work.resolve("out/demo").createDirectories()
            val er = work.resolve("out/demo/er.yaml")
            Files.createSymbolicLink(er, work.resolve("tree/planted.yaml"))
            refused(work, "demo/er.yaml", "--root", "tree", "--out", "out")
            work.resolve("tree/planted.yaml").exists() shouldBe false
            Files.delete(er)
            Files.createSymbolicLink(work.resolve("hop"), work.resolve("tree/planted.yaml"))
            Files.createSymbolicLink(er, work.resolve("hop"))
            refused(work, "demo/er.yaml", "--root", "tree", "--out", "out")
            // Outside the root: was "written through" (review-269 N1), now refused (✅R277-D).
            Files.delete(er)
            val elsewhere = Files.createTempDirectory("r277-n2-elsewhere").resolve("er.yaml")
            Files.createSymbolicLink(er, elsewhere)
            refused(work, "demo/er.yaml", "--root", "tree", "--out", "out")
            elsewhere.exists() shouldBe false
            Files.delete(er)
            Files.createSymbolicLink(work.resolve("a"), work.resolve("b"))
            Files.createSymbolicLink(work.resolve("b"), work.resolve("a"))
            Files.createSymbolicLink(er, work.resolve("a"))
            refused(work, "demo/er.yaml", "--root", "tree", "--out", "out")
            // A live link to a directory is a link too.
            Files.delete(er)
            Files.delete(work.resolve("out/demo"))
            val directory = Files.createTempDirectory("r277-n2-dir")
            Files.createSymbolicLink(work.resolve("out/demo"), directory)
            refused(work, "demo", "--root", "tree", "--out", "out")
            files(directory) shouldBe emptyMap()
        }

        test("N2: a symlinked --out with plain directories below it writes normally") {
            val tmp = Files.createTempDirectory("r277-n2-ok")
            copy(links, tmp.resolve("tree"))
            val real = Files.createTempDirectory("r277-n2-real")
            real.resolve("demo").createDirectories()
            Files.createSymbolicLink(tmp.resolve("o"), real)
            exec(tmp, "profile", "apply", "--root", "tree", "--out", "o").exitCode shouldBe 0
            real.resolve("demo/er.yaml").isRegularFile() shouldBe true
            real.resolve("report.json").isRegularFile() shouldBe true
        }

        test("N2: a missing --out is created for the write and removed again when apply writes nothing") {
            val tmp = Files.createTempDirectory("r277-n2-fresh")
            copy(links, tmp.resolve("tree"))
            tmp.resolve("tree/zz-bad.yaml").writeText("entity: [\n")
            exec(tmp, "profile", "apply", "--root", "tree", "--out", "fresh/a/b").exitCode shouldBe 1
            tmp.resolve("fresh").exists() shouldBe false
            Files.delete(tmp.resolve("tree/zz-bad.yaml"))
            exec(tmp, "profile", "apply", "--root", "tree", "--out", "fresh/a/b").exitCode shouldBe 0
            tmp.resolve("fresh/a/b/demo/er.yaml").isRegularFile() shouldBe true
        }
    })
