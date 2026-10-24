// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.doc.DocExtractor
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.lsp.analysis.AnalysisEngine
import org.tatrman.ttrp.lsp.docs.OpenDocument
import org.tatrman.ttrp.lsp.project.ProjectContext
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.tatrman.ttrp.project.TtrpManifest
import org.tatrman.ttrp.resolve.ModelRepo
import org.tatrman.ttrp.resolve.TtrpChecker
import java.nio.file.Files
import java.nio.file.Path

class RecipeSnapshotCacheSpec :
    FunSpec({
        test("recipe edits and additional documents reuse one model load until project invalidation") {
            val root =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-doc")) }
            val fixtures = root.resolve("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
            val md =
                Files.readString(
                    root.resolve(
                        "packages/kotlin/ttrp-doc/src/test/resources/docs/releasing-a-placed-order.md",
                    ),
                )
            val ctx =
                ProjectContext(
                    TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures),
                    fixtures.resolve("model"),
                    emptyList(),
                )
            var loads = 0
            val engine =
                AnalysisEngine(ProjectResolver { ctx }) { project ->
                    val snapshot = ModelRepo.indexOf(project.modelsRoot).also { loads++ }
                    TtrpPipeline(
                        project.manifest,
                        project.modelsRoot,
                        frontendChecker =
                            TtrpChecker(
                                project.manifest,
                                project.modelsRoot,
                                modelIndex = snapshot,
                            ),
                    )
                }
            val canonical =
                DocExtractor
                    .extract(md, "release.md")
                    .recipes
                    .single()
                    .text
            val source = OpenDocument("file:///release.ttrp", canonical, 1, "ttrp")
            val first = engine.analyze(source)
            first.recipes
                .single()
                .plan.ok shouldBe true
            first.report shouldBe
                first.recipes
                    .single()
                    .plan.frontendReport
            loads shouldBe 1
            engine
                .analyze(source.copy(version = 2, text = canonical + "\n"))
                .recipes
                .single()
                .plan.ok shouldBe true
            engine
                .analyze(OpenDocument("file:///release.md", md, 1, "markdown"))
                .recipes
                .single()
                .plan.ok shouldBe true
            engine
                .analyze(OpenDocument("file:///second.md", md, 1, "markdown"))
                .recipes
                .single()
                .plan.ok shouldBe true
            loads shouldBe 1
            engine.invalidateAll()
            engine
                .analyze(source)
                .recipes
                .single()
                .plan.ok shouldBe true
            loads shouldBe 2
        }
    })
