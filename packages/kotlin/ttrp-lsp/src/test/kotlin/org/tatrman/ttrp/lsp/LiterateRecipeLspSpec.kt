// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.*
import org.tatrman.ttrp.lsp.project.ProjectContext
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.tatrman.ttrp.lsp.test.TtrpLspHarness
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

class LiterateRecipeLspSpec :
    FunSpec({
        val root =
            generateSequence(Path.of("").toAbsolutePath()) {
                it.parent
            }.first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-doc")) }
        val fixtures = root.resolve("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
        val resolver =
            ProjectResolver {
                ProjectContext(
                    TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures),
                    fixtures.resolve("model"),
                    emptyList(),
                )
            }
        val md =
            Files.readString(
                root.resolve("packages/kotlin/ttrp-doc/src/test/resources/docs/releasing-a-placed-order.md"),
            )
        test("ordinary Markdown documents do not receive recipe diagnostics") {
            TtrpLspHarness(resolver).use { h ->
                h.initialize()
                val uri = "file:///README.md"
                h.open(uri, "# Notes\n\n```js\nconsole.log('hello')\n```\n\n```sql\nSELECT 1\n```\n", "markdown")
                h.awaitDiagnostics(uri) shouldBe emptyList()
            }
        }
        test("Markdown navigation refuses rename and never returns canonical positions") {
            TtrpLspHarness(resolver).use { h ->
                h.initialize()
                val uri = "file:///release.md"
                h.open(uri, md, "markdown")
                h.awaitDiagnostics(uri) shouldBe emptyList()
                val id = TextDocumentIdentifier(uri)
                val at = Fixtures.positionOf(md, "candidates placed_orders")
                val failure =
                    shouldThrow<java.util.concurrent.ExecutionException> {
                        h.remote.textDocumentService
                            .prepareRename(PrepareRenameParams(id, at))
                            .get()
                    }
                val error = failure.cause as org.eclipse.lsp4j.jsonrpc.ResponseErrorException
                error.responseError.code shouldBe
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest.value
                error.responseError.message shouldBe "rename is not available in a literate recipe yet"
                val edits =
                    h.remote.textDocumentService
                        .rename(RenameParams(id, at, "changed"))
                        .get()
                edits.documentChanges.orEmpty() shouldBe emptyList()
                edits.changes.orEmpty() shouldBe emptyMap()
                h.remote.textDocumentService
                    .definition(DefinitionParams(id, at))
                    .get()
                    .left shouldBe emptyList()
                h.remote.textDocumentService
                    .hover(HoverParams(id, Position(0, 2)))
                    .get() shouldBe null
            }
        }
        test("Markdown receives DOC diagnostics on its fence and REC diagnostics on mapped blocks") {
            TtrpLspHarness(resolver).use { h ->
                h.initialize()
                val uri = "file:///release.md"
                h.open(uri, md.replace("department: \"AR\"", "departmnt: \"AR\""), "markdown")
                val errors = h.awaitDiagnostics(uri)
                val rec = errors.first { it.code.left == "TTRP-REC-001" }
                md.lines()[rec.range.start.line] shouldContain "```ttrp"
                h.replace(uri, 2, md + "\n```js\n1\n```\n")
                h
                    .awaitDiagnostics(uri)
                    .single()
                    .code.left shouldBe "TTRP-DOC-004"
            }
        }
        test("later recipe header diagnostics use their own skeleton after Unicode text") {
            val source =
                "# 🚀 Multi\n\n" + md.replace("recipe: release_order", "recipe: first") +
                    "\n\n" +
                    md
                        .substringAfter("```ttrp preamble")
                        .substringAfter("```\n")
                        .replace("recipe: release_order", "recipe: second")
                        .substringBefore("```ttrp\n    actions")
            TtrpLspHarness(resolver).use { h ->
                h.initialize()
                val uri = "file:///multi.md"
                h.open(uri, source, "markdown")
                val errors = h.awaitDiagnostics(uri)
                val secondLine = source.lines().indexOf("recipe: second") - 1
                errors.filter { it.code.left == "TTRP-REC-000" }.map { it.range.start.line } shouldBe listOf(secondLine)
                val lenses =
                    h.remote.textDocumentService
                        .codeLens(CodeLensParams(TextDocumentIdentifier(uri)))
                        .get()
                lenses.size shouldBe 2
                lenses[1].range.start.line shouldBe secondLine
            }
        }
        test("recipe lenses show mode and context classes; parameter hover and action completion use imports") {
            TtrpLspHarness(resolver).use { h ->
                h.initialize()
                val uri = "file:///release.md"
                h.open(uri, md, "markdown")
                h.awaitDiagnostics(uri)
                val graph =
                    h.remote
                        .getGraph(
                            org.tatrman.ttrp.lsp.protocol
                                .GetGraphParams(uri, 1),
                        ).get()
                graph.recipe!!.lanes.map { it.role } shouldBe listOf("candidates", "context", "decision", "actions")
                graph.recipe!!
                    .lanes
                    .first()
                    .islands
                    .single()
                    .kind shouldBe "sql"
                val lenses =
                    h.remote.textDocumentService
                        .codeLens(CodeLensParams(TextDocumentIdentifier(uri)))
                        .get()
                lenses.single().command.title shouldContain "per-candidate"
                lenses.single().command.title shouldContain "shortfall"
                val hover =
                    h.remote.textDocumentService
                        .hover(
                            HoverParams(TextDocumentIdentifier(uri), Fixtures.positionOf(md, ":tolerance")),
                        ).get()
                hover.contents.right.value shouldContain "0.01"
                hover.contents.right.value shouldContain "Price tolerance"
                val completed =
                    h.remote.textDocumentService
                        .completion(
                            CompletionParams(TextDocumentIdentifier(uri), Fixtures.positionOf(md, "verdict.release ")),
                        ).get()
                completed.left.first { it.label == "release" }.detail shouldContain "order"
            }
        }
    })
