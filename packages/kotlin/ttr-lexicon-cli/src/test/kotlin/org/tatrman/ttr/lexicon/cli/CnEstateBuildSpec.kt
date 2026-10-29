// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.ConceptLift
import org.tatrman.ttr.lexicon.TargetFacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * CN-P4 S4.1 T6 — the real command over the v6 fixture estate (`ttr-lexicon-compile/src/test/resources/cn/`, in place):
 * it says which archive schema it wrote, counts the concepts, and the concept facts survive the consumer path
 * (`SnapshotReader` → `CompiledLexicon.fromJson`) — what lex-matcher and the resolver will read (S4.3/S4.4).
 */
class CnEstateBuildSpec :
    FunSpec({

        val estate = Path.of(System.getProperty("cnEstate") ?: error("cnEstate not set"))

        test("the build prints the v6 schema and the concept count; the archive reads back with its concept facts") {
            val out = Files.createTempDirectory("cn-out").resolve("lexicon.tar.zst")

            val outcome = LexiconBuildCli.run(repoRoot = estate, out = out, includeStdlib = false)

            withClue(outcome.stderr) { outcome.exitCode shouldBe LexiconBuildCli.EXIT_OK }
            outcome.stdout shouldContain "  schema: ttr-lexicon-compiled/v6\n"
            outcome.stdout shouldContain "  concepts: 12\n"

            val archive = LexiconBuildCli.readBack(out).lexicon
            archive.header.schemaVersion shouldBe "ttr-lexicon-compiled/v6"
            archive.targets.getValue("cn.Sale").let { it.objectKind to it.via } shouldBe
                (TargetFacts.CONCEPT to TargetFacts.VIA_MD_HOP)
            archive.targets.getValue("md.measure.revenue").liftsTo shouldBe
                listOf(ConceptLift("cn.revenue", TargetFacts.VIA_MD_HOP))
            val customer = archive.entries.first { it.termNormalized == "zákazník" && it.targetRef == "cn.Customer" }
            customer.defaultSense shouldBe true
        }

        test("db and cn terms in the edit's spelling reach the archive under the tiers' keys (ttr-core#325)") {
            // `@tatrman/edit` writes `<pkg>.db.<schema>.table.<t>` and `<pkg>.cn.concept.<Name>`. The db one used to
            // drop with RG-LEXC-001; the cn one compiled under `cn.concept.Party`, which no cn consumer reads.
            val copy = Files.createTempDirectory("cn-qualified")
            estate.resolve("model").toFile().copyRecursively(copy.resolve("model").toFile())
            copy.resolve("model/db").createDirectories()
            copy.resolve("model/db/invoice.ttrm").writeText(
                "package shop\n\nmodel db schema dbo\n\ndef table invoice {\n    columns: [def column id { type: int }]\n}\n",
            )
            copy.resolve("model/lexicon/en/qualified.ttrm").writeText(
                """
                package shop

                model lexicon locale en

                def term q_party_en { for: shop.cn.concept.Party, forms: ["party probe"] }

                def term q_invoice_en { for: shop.db.dbo.table.invoice, forms: ["invoice probe"] }

                def term q_invoice_id_en { for: shop.db.dbo.table.invoice.id, forms: ["invoice number probe"] }
                """.trimIndent() + "\n",
            )
            val out = copy.resolve("lexicon.tar.zst")

            val outcome = LexiconBuildCli.run(repoRoot = copy, out = out, includeStdlib = false)

            withClue(outcome.stderr + outcome.stdout) { outcome.exitCode shouldBe LexiconBuildCli.EXIT_OK }
            (outcome.stderr + outcome.stdout) shouldNotContain "RG-LEXC-001"
            val archive = LexiconBuildCli.readBack(out).lexicon
            archive.entries
                .filter { it.termNormalized.endsWith("probe") }
                .map { it.termNormalized to it.targetRef }
                .sortedBy { it.first } shouldBe
                listOf(
                    "invoice number probe" to "db.dbo.invoice.id",
                    "invoice probe" to "db.dbo.invoice",
                    "party probe" to "cn.Party",
                )
            archive.entries.filter { it.targetRef.startsWith("cn.concept.") } shouldBe emptyList()
        }

        test("the committed fixture lexicon decodes through the reader's codec (the S4.3 / S4.4 input)") {
            val golden = CompiledLexicon.fromJson(estate.resolve("expected/lexicon.json").readText())
            golden.header.schemaVersion shouldBe "ttr-lexicon-compiled/v6"
            golden.targets.count { it.value.objectKind == TargetFacts.CONCEPT } shouldBe 12
        }
    })
