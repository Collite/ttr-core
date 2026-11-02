// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.ttrp.lsp.project.FilesystemProjectResolver
import org.tatrman.ttrp.lsp.test.TtrpLspHarness
import java.nio.file.Files
import java.nio.file.Path

/**
 * D-142: the recipe checker refuses a removed construct anywhere in the model the recipe is checked against, so some
 * of its findings are located in a model file (`.ttrm`), not in the open recipe. The editor shows such a finding on
 * the recipe's header, its message prefixed with where it was made — never at the model file's line and column
 * inside the recipe. In a literate document every finding made in another file (the manifest's as well) sits on the
 * recipe skeleton — never at a position of the extracted text.
 */
class ModelFileDiagnosticsSpec :
    FunSpec({
        val root =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-doc")) }
        val planted =
            "    def deployment cut_trigger { program: morning_release, target: server, run_as: caller, " +
                "trigger: schedule \"06:00 Europe/Prague\" }"
        val md =
            Files.readString(
                root.resolve("packages/kotlin/ttrp-doc/src/test/resources/docs/releasing-a-placed-order.md"),
            )

        /** A project over the Core fixture model with [planted] in `def world demo`; [block] gets its dir and line. */
        fun project(
            manifest: String,
            block: (dir: Path, at: Int) -> Unit,
        ) {
            val model = root.resolve("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model")
            val dir = Files.createTempDirectory("model-file-diagnostics")
            try {
                Files.writeString(dir.resolve("modeler.toml"), manifest)
                Files.walk(model).use { paths ->
                    paths.forEach { p ->
                        val dst = dir.resolve("models").resolve(model.relativize(p).toString())
                        if (Files.isDirectory(p)) Files.createDirectories(dst) else Files.copy(p, dst)
                    }
                }
                val demo = dir.resolve("models/hartland/worlds/demo.ttrm")
                val text = Files.readString(demo)
                Files.writeString(demo, text.substring(0, text.lastIndexOf('}')) + planted + "\n}\n")
                block(dir, Files.readAllLines(demo).indexOf(planted) + 1)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("a finding made in a model file sits on the recipe header, prefixed with the model file's position") {
            // (canonical `.ttrp` and literate `.md`)
            val recipe =
                Files.readString(
                    root.resolve("packages/kotlin/ttrp-frontend/src/test/resources/recipes/release_order.ttrp"),
                )
            project("[ttrp]\nworld = \"hartland.worlds.demo\"\n") { dir, at ->
                TtrpLspHarness(FilesystemProjectResolver()).use { h ->
                    h.initialize()
                    val uri = dir.resolve("release_order.ttrp").toUri().toString()
                    h.open(uri, recipe, "ttrp")
                    val cut = h.awaitDiagnostics(uri).single { it.code.left == "TTRP-CUT-003" }
                    cut.message shouldStartWith "demo.ttrm:$at:${planted.indexOf("trigger:")}: deployment `cut_trigger`"
                    val header = recipe.lines().indexOfFirst { it.startsWith("recipe") }
                    cut.range.start.line shouldBe header
                    cut.range.start.character shouldBe 0
                    cut.range.end.line shouldBe header
                    // the literate source: the same finding on the recipe's skeleton, with the same prefix
                    val mdUri = dir.resolve("release.md").toUri().toString()
                    h.open(mdUri, md, "markdown")
                    h.awaitDiagnostics(mdUri).single { it.code.left == "TTRP-CUT-003" }.message shouldStartWith
                        "demo.ttrm:$at:"
                }
            }
        }

        test("in a literate document, a manifest finding sits on the recipe skeleton, not at an extracted line") {
            project("[ttrp]\nworld = \"hartland.worlds.demo\"\nextra-model-root = [\"missing\"]\n") { dir, _ ->
                TtrpLspHarness(FilesystemProjectResolver()).use { h ->
                    h.initialize()
                    val mdUri = dir.resolve("release.md").toUri().toString()
                    h.open(mdUri, md, "markdown")
                    val diagnostics = h.awaitDiagnostics(mdUri)
                    val cfg = diagnostics.single { it.code.left == "TTRP-CFG-002" }
                    val cut = diagnostics.single { it.code.left == "TTRP-CUT-003" }
                    cfg.message shouldStartWith "modeler.toml:"
                    // the skeleton (md lines 13–22), where the model-file finding sits — not the extracted text's
                    // `recipe` line, which is md line 9, inside the preamble fence
                    cfg.range shouldBe cut.range
                    val fences =
                        md
                            .lines()
                            .withIndex()
                            .filter { it.value.startsWith("```") }
                            .map { it.index }
                    (cfg.range.start.line in fences[2]..fences[3]) shouldBe true
                }
            }
        }
    })
