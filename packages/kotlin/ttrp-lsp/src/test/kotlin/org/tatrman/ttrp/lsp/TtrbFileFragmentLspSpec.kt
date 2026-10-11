// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentLinkParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.tatrman.ttrp.lsp.test.TtrpLspHarness
import java.nio.file.Files
import java.nio.file.Path

/**
 * B5 in the editor: `container … from "rules/x.ttrb-cs"` — the LSP opens the file (go-to-definition
 * and a document link on the `from` clause), and a finding INSIDE the fragment file is published on
 * the program's `from` clause, citing the file's own line.
 */
class TtrbFileFragmentLspSpec :
    StringSpec({
        val programs: Path =
            run {
                val rel = Path.of("packages/kotlin/ttrp-frontend/src/test/resources/ttrb-rules/programs")
                var dir: Path? = Path.of("").toAbsolutePath()
                while (dir != null) {
                    if (Files.isDirectory(dir.resolve(rel))) return@run dir.resolve(rel)
                    dir = dir.parent
                }
                error("could not locate the ttrb-rules fixture programs")
            }

        fun uri(rel: String): String = programs.resolve(rel).toUri().toString()

        fun text(rel: String): String = Files.readString(programs.resolve(rel))

        "go-to-definition on the `from \"…\"` clause opens the fragment file" {
            TtrpLspHarness(
                org.tatrman.ttrp.lsp.project
                    .FilesystemProjectResolver(),
            ).use { h ->
                h.initialize()
                val u = uri("rozhodnuti-soubor.ttrp")
                val t = text("rozhodnuti-soubor.ttrp")
                h.open(u, t)
                val at = Fixtures.positionOf(t, "\"rules/rozhodnuti")
                val locations =
                    h.remote.textDocumentService
                        .definition(DefinitionParams(TextDocumentIdentifier(u), at))
                        .get()
                        .left
                locations.size shouldBe 1
                locations.single().uri shouldEndWith "rules/rozhodnuti.ttrb-cs"
                locations
                    .single()
                    .range.start.line shouldBe 0
            }
        }

        "a document link on the `from` clause targets the fragment file" {
            TtrpLspHarness(
                org.tatrman.ttrp.lsp.project
                    .FilesystemProjectResolver(),
            ).use { h ->
                h.initialize().capabilities.documentLinkProvider shouldBe org.eclipse.lsp4j.DocumentLinkOptions(false)
                val u = uri("rozhodnuti-soubor.ttrp")
                val t = text("rozhodnuti-soubor.ttrp")
                h.open(u, t)
                val links =
                    h.remote.textDocumentService
                        .documentLink(
                            DocumentLinkParams(TextDocumentIdentifier(u)),
                        ).get()
                links.size shouldBe 1
                links.single().target shouldBe programs.resolve("rules/rozhodnuti.ttrb-cs").toUri().toString()
                links
                    .single()
                    .range.start.line shouldBe Fixtures.positionOf(t, "from \"rules").line
            }
        }

        "the file-backed program publishes no diagnostics" {
            TtrpLspHarness(
                org.tatrman.ttrp.lsp.project
                    .FilesystemProjectResolver(),
            ).use { h ->
                h.initialize()
                val u = uri("rozhodnuti-soubor.ttrp")
                h.open(u, text("rozhodnuti-soubor.ttrp"))
                h.awaitDiagnostics(u).shouldBeEmpty()
            }
        }

        "the formatter keeps a file-backed container's `from` clause — it never inlines the file" {
            val u = uri("rozhodnuti-soubor.ttrp")
            val formatted =
                org.tatrman.ttrp.lsp.format
                    .TtrpFormatter()
                    .format(text("rozhodnuti-soubor.ttrp"), u)
            formatted shouldContain "container rozhodnuti target erp from \"rules/rozhodnuti.ttrb-cs\""
            formatted.contains("\"\"\"") shouldBe false
        }

        "a finding inside the fragment file is published on the `from` clause, citing the file's line" {
            TtrpLspHarness(
                org.tatrman.ttrp.lsp.project
                    .FilesystemProjectResolver(),
            ).use { h ->
                h.initialize()
                val u = uri("negative/soubor-vadny.ttrp")
                val t = text("negative/soubor-vadny.ttrp")
                h.open(u, t)
                val d = h.awaitDiagnostics(u).single()
                d.code.left.startsWith("TTRP-B-") shouldBe true
                d.range.start.line shouldBe Fixtures.positionOf(t, "from \"").line
                d.message shouldContain "vadne.ttrb-cs:3:"
            }
        }
    })
