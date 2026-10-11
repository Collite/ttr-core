// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.tatrman.ttrp.lsp.format.TtrpFormatter
import org.tatrman.ttrp.lsp.test.TtrpLspHarness

/**
 * B6 — the reject tables reach the editor: a TTR-B finding is published with the skin's message
 * and its suggested correct sentence, in an embedded `"""ttrb-cs` fragment and in a bare `.ttrb` /
 * `.ttrb-cs` document (without a `[ttrp] bare-target` the fragment's own sentence check runs).
 */
class TtrbLspDiagnosticsSpec :
    StringSpec({
        "an embedded \"\"\"ttrb-cs fragment: the Czech message + the suggested Czech sentence" {
            TtrpLspHarness().use { h ->
                h.initialize()
                val uri = "file:///pravidlo.ttrp"
                val text =
                    "uses world \"acme.worlds.dev\"\n" +
                        "container pravidlo(out r) target erp_pg \"\"\"ttrb-cs\n" +
                        "Načti accounts.\n" +
                        "Pošli e-mail zástupce s předmětem \"Ahoj\", klíčem číslo.\n" +
                        "\"\"\"\n"
                h.open(uri, text)
                val d = h.awaitDiagnostics(uri).single { it.code.left.startsWith("TTRP-B-") }
                d.code.left shouldBe "TTRP-B-104"
                d.message shouldContain "Věta s e-mailem uvádí příjemce"
                d.message shouldContain "↳ suggested: Pošli e-mail <sloupec>"
                d.range.start.line shouldBe 3
            }
        }

        "a bare .ttrb-cs document is checked sentence by sentence (Czech reject table)" {
            TtrpLspHarness().use { h ->
                h.initialize()
                val uri = "file:///pravidla/oprava.ttrb-cs"
                h.open(
                    uri,
                    "# ttr: dialect=b lang=cs\nNačti objednávky.\nAktualizuj objednávky.\n",
                    languageId = "ttrb-cs",
                )
                val d = h.awaitDiagnostics(uri).single()
                d.code.left shouldBe "TTRP-B-001"
                d.message shouldContain "TTR-B nemění řádky na místě"
                d.message shouldContain "Nastav <atribut> <entita>"
                d.range.start.line shouldBe 2
            }
        }

        "a bare .ttrb document uses the English table" {
            TtrpLspHarness().use { h ->
                h.initialize()
                val uri = "file:///rules/fix.ttrb"
                h.open(uri, "Load orders.\nUpdate orders.\n", languageId = "ttrb")
                val d = h.awaitDiagnostics(uri).single()
                d.code.left shouldBe "TTRP-B-001"
                d.message shouldContain "Set <attribute> of <entity>"
            }
        }

        "a clean bare .ttrb-cs document publishes nothing" {
            TtrpLspHarness().use { h ->
                h.initialize()
                val uri = "file:///pravidla/velke.ttrb-cs"
                val text =
                    "# ttr: dialect=b lang=cs\nNačti objednávky.\nKdyž částka je větší než 1000:\n" +
                        "    Pošli e-mail oddělení \"obchod\" s předmětem \"S\", šablonou \"t\" a klíčem číslo.\n"
                h.open(uri, text, languageId = "ttrb-cs")
                h.awaitDiagnostics(uri).shouldBeEmpty()
            }
        }

        "authoring context: the Czech verb roster + a ttrb-cs insertion target, valid against the schema" {
            TtrpLspHarness().use { h ->
                h.initialize()
                val uri = "file:///pravidlo.ttrp"
                val text =
                    "uses world \"acme.worlds.dev\"\n" +
                        "container pravidlo(out r) target erp_pg \"\"\"ttrb-cs\n" +
                        "Načti accounts.\n" +
                        "\"\"\"\n"
                h.open(uri, text)
                val bundle =
                    h.remote
                        .authoringContext(
                            org.tatrman.ttrp.lsp.protocol
                                .AuthoringContextParams(uri, Fixtures.positionOf(text, "Načti")),
                        ).get()
                        .bundle
                val rosters = bundle.getAsJsonObject("grammar").getAsJsonObject("dialectRosters")
                rosters.getAsJsonArray("ttrb-cs").map { it.asString }.first() shouldBe "Načti"
                rosters.getAsJsonArray("ttrb").map { it.asString } shouldContain "Keep/Take/Select"
                bundle
                    .getAsJsonObject("scope")
                    .getAsJsonObject("insertionTarget")
                    .get("dialect")
                    .asString shouldBe
                    "ttrb-cs"
                val schema =
                    com.networknt.schema.JsonSchemaFactory
                        .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                        .getSchema(
                            java.nio.file.Files.readString(
                                java.nio.file.Path
                                    .of("../../../docs/features/ttr-p/architecture/authoring-context.schema.json"),
                            ),
                        )
                schema
                    .validate(
                        bundle.toString(),
                        com.networknt.schema.InputFormat.JSON,
                    ).map { it.message }
                    .shouldBeEmpty()
            }
        }

        "bare TTR-B documents are never formatted" {
            TtrpFormatter.isBareFragmentFile("file:///x/pravidlo.ttrb-cs") shouldBe true
            TtrpLspHarness().use { h ->
                h.initialize()
                val uri = "file:///pravidla/velke.ttrb-cs"
                h.open(uri, "Načti objednávky.\n", languageId = "ttrb-cs")
                h.remote.textDocumentService
                    .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(2, true)))
                    .get()
                    .shouldBeEmpty()
            }
        }
    })
