// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import org.tatrman.concept.cli.CnCommand
import org.tatrman.concept.cli.CnHygieneCommand
import org.tatrman.concept.cli.TtrCommand
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * CN-P2 S2.7 T2 — the hygiene report v2 (cn contracts §7) against the golden in `tests/conformance/cn-hygiene/`: the
 * data (`expected.json`), the text `ttr cn hygiene` prints (`expected.md`), the part the editor's report shares
 * (`expected-editor.md` up to `## Definitions`), and the CLI's exits.
 */
class CnHygieneSpec :
    StringSpec({
        val golden = DefinitionFixtures.repo.resolve("tests/conformance/cn-hygiene")

        fun load(root: Path): ResolvedCnModel {
            val snapshot = FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = root)).load()
            return ResolvedCnModel.of(
                ModelReconciler(ModelDescriptor(id = "h", name = "h")).reconcile(listOf(snapshot)).model,
            )
        }
        val model = load(golden)
        val report = CnHygiene.report(model)
        val fileName = { f: String -> golden.relativize(Path.of(f)).toString() }

        "the report as data is the golden (expected.json)" {
            HygieneRender.json(report, fileName) shouldBe
                Json.parseToJsonElement(Files.readString(golden.resolve("expected.json")))
        }

        "the report as text is the golden (expected.md)" {
            HygieneRender.markdown(report, fileName) shouldBe Files.readString(golden.resolve("expected.md"))
        }

        "the editor's report shares everything up to the definitions (expected-editor.md)" {
            val editor = Files.readString(golden.resolve("expected-editor.md"))
            editor shouldStartWith HygieneRender.body(report)
            editor.substring(HygieneRender.body(report).length) shouldStartWith "\n## Definitions\n"
        }

        "every row the golden is built for" {
            fun row(name: String) = report.concepts.single { it.qname == "cn.$name" }
            row("Shopper").grade shouldBe GroundingGrade.ER_GROUNDED
            row("Sale").hop shouldBe
                HopColumn("md.cubelet.sales", "er.entity.sale", message = row("Sale").hop!!.message)
            row("Store").hop!!.slug shouldBe "md/er-unmapped"
            row("Store").grade shouldBe GroundingGrade.MD_GROUNDED_HOP_MISSING
            row("Supplier").grade shouldBe GroundingGrade.UNGROUNDED
            row("Supplier").relations shouldBe listOf("supplies → cn.Store")
            row("Shopper").openEntries shouldBe listOf("loyalty_programme")
            row("StoreVisit").danglingGroundings shouldBe
                listOf(DanglingRef("visit_g", "er.entity.store_visit.visited_at"))
            // "Shopper" on the entity folds to "shopper" — the lexicon's collision fold
            row("Shopper").redundantTerms.single().element shouldBe "er.entity.shopper"
            row("FrequentShopper").phaseAsDefault shouldBe listOf(PhaseAsDefault("frequent", "shopper", "cn.Shopper"))
            // a phase lifts from no element (L17-2): its own "shopper" is not a redundant duplicate
            row("FrequentShopper").redundantTerms shouldBe emptyList()
            report.definitions.map { it.subject to it.slug } shouldBe
                listOf(
                    "cn.LoyalShopper" to CnDefinitionLints.CYCLE,
                    "cn.VipShopper" to CnDefinitionLints.CYCLE,
                )
        }

        "the service answers hygieneReport; every other family is still CN-P3" {
            val svc = CnConceptService(model)
            svc.hygieneReport() shouldBe report
            shouldThrow<UnsupportedOperationException> {
                svc.statementValidate(
                    org.tatrman.statement.v1.Statement
                        .getDefaultInstance(),
                )
            }
        }

        "a model with no cn: an empty report" {
            val empty = Files.createTempDirectory("cn-none")
            val r = CnHygiene.report(load(empty))
            r shouldBe HygieneReport()
            HygieneRender.markdown(r) shouldContain "0 concepts\n"
        }

        fun ttr() = TtrCommand().subcommands(CnCommand().subcommands(CnHygieneCommand()))

        "ttr cn hygiene: the text, or the json, and exit 0 without --fail-on" {
            ttr().test("cn hygiene $golden").let {
                it.statusCode shouldBe 0
                it.stdout shouldBe Files.readString(golden.resolve("expected.md"))
            }
            ttr().test("cn hygiene $golden --format json").let {
                it.statusCode shouldBe 0
                Json.parseToJsonElement(it.stdout) shouldBe
                    Json.parseToJsonElement(Files.readString(golden.resolve("expected.json")))
            }
        }

        "ttr cn hygiene --fail-on: the dangling grounding and the cycle are errors, the phase-as-default a warning" {
            ttr().test("cn hygiene $golden --fail-on error").statusCode shouldBe 1
            ttr().test("cn hygiene $golden --fail-on warning").statusCode shouldBe 1
            val clean = Files.createTempDirectory("cn-clean")
            Files.createDirectories(clean.resolve("hartland/hygiene"))
            Files.writeString(
                clean.resolve("hartland/hygiene/a.ttrm"),
                "package hartland.hygiene\nmodel cn\n\ndef kind Supplier { }\n",
            )
            ttr().test("cn hygiene $clean --fail-on warning").statusCode shouldBe 0
        }

        "ttr cn hygiene on a path that is not a directory: usage, exit 2" {
            ttr().test("cn hygiene ${golden.resolve("expected.md")}").statusCode shouldBe 2
        }
    })
