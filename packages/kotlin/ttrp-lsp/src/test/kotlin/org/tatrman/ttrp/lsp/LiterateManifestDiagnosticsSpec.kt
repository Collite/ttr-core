// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.doc.DocExtractor
import org.tatrman.ttrp.lsp.project.FilesystemProjectResolver
import org.tatrman.ttrp.lsp.test.TtrpLspHarness
import java.nio.file.Files
import java.nio.file.Path

class LiterateManifestDiagnosticsSpec :
    FunSpec({
        test("bad extra-model-roots configuration stays visible on canonical and literate recipes") {
            val root =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-doc")) }
            val md =
                Files.readString(
                    root.resolve(
                        "packages/kotlin/ttrp-doc/src/test/resources/docs/releasing-a-placed-order.md",
                    ),
                )
            val dir = Files.createTempDirectory("literate-manifest")
            try {
                Files.writeString(
                    dir.resolve("modeler.toml"),
                    "[ttrp]\nworld = \"hartland.worlds.demo\"\nextra-model-root = [\"missing\"]\n",
                )
                Files.createSymbolicLink(
                    dir.resolve("models"),
                    root.resolve(
                        "packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model",
                    ),
                )
                TtrpLspHarness(FilesystemProjectResolver()).use { h ->
                    h.initialize()
                    val canonical =
                        DocExtractor
                            .extract(md, "release.md")
                            .recipes
                            .single()
                            .text
                    for ((name, source, language) in listOf(
                        Triple("release.ttrp", canonical, "ttrp"),
                        Triple("release.md", md, "markdown"),
                    )) {
                        val uri = dir.resolve(name).toUri().toString()
                        h.open(uri, source, language)
                        val cfg = h.awaitDiagnostics(uri).filter { it.code.left == "TTRP-CFG-002" }
                        cfg.size shouldBe 1
                        cfg.single().message.contains("extra-model-root") shouldBe true
                    }
                }
            } finally {
                Files.deleteIfExists(dir.resolve("models"))
                dir.toFile().deleteRecursively()
            }
        }
    })
