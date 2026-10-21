// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import org.tatrman.concept.cli.runTtr
import org.tatrman.concept.cli.ttr
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

        "authored text is escaped for markdown; an underscore inside a word stays (review-119 L-5, = TS mdEscape)" {
            HygieneRender.md("*vendor*") shouldBe "\\*vendor\\*"
            HygieneRender.md("_x_") shouldBe "\\_x\\_"
            HygieneRender.md("loyalty_programme") shouldBe "loyalty_programme"
            HygieneRender.md("`a|b` [c] <d> ~e~ \\") shouldBe "\\`a\\|b\\` \\[c\\] \\<d\\> \\~e\\~ \\\\"
            HygieneRender.md("é_é") shouldBe "é_é"
            // review-120 F20: a name wrapped in `_`, an entity, a line break
            HygieneRender.md("cn._Hidden_") shouldBe "cn.\\_Hidden\\_"
            HygieneRender.md("&lt;b") shouldBe "\\&lt;b"
            HygieneRender.md("a\nb") shouldBe "a b"
        }

        "a nested list in a participant path is read through, as TS does: the dangling ref is reported (L-6)" {
            val root = Files.createTempDirectory("cn-nested")
            val pkg = Files.createDirectories(root.resolve("hartland/visits"))
            Files.writeString(
                pkg.resolve("a-er.ttrm"),
                """
                package hartland.visits
                model er

                def entity visit {
                    attributes: [ def attribute visit_id { type: int, isKey: true }, def attribute at { type: date } ]
                }
                def entity shopper { attributes: [ def attribute shopper_id { type: int, isKey: true } ] }
                def relation rel_visit_shopper { from: er.entity.visit, to: er.entity.shopper }
                """.trimIndent(),
            )
            Files.writeString(
                pkg.resolve("b-cn.ttrm"),
                """
                package hartland.visits
                model cn

                def kind Shopper { grounds: er.entity.shopper }
                def event Visit { involves: [Shopper], period: { at: at } }
                """.trimIndent(),
            )
            Files.writeString(
                pkg.resolve("c-bind.ttrm"),
                """
                package hartland.visits
                model binding

                def cn2er_event visit_g {
                    event: cn.Visit,
                    target: er.entity.visit,
                    participants: { shopper: [[er.relation.rel_nope], er.relation.rel_visit_shopper] },
                    period: { at: er.entity.visit.at }
                }
                """.trimIndent(),
            )
            val visit = CnHygiene.report(load(root)).concepts.single { it.qname == "cn.Visit" }
            visit.danglingGroundings shouldBe listOf(DanglingRef("visit_g", "er.relation.rel_nope"))
        }

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

        "a model on the deprecated roles: [fact] loads clean: the stock cnc roles ride along (review-120 F3)" {
            val root = Files.createTempDirectory("cn-roles")
            val pkg = Files.createDirectories(root.resolve("hartland/sales"))
            Files.writeString(
                pkg.resolve("a-er.ttrm"),
                "package hartland.sales\nmodel er\n\n" +
                    "def entity sale { roles: [fact], attributes: [ def attribute sale_id { type: int, isKey: true } ] }\n",
            )
            Files.writeString(
                pkg.resolve("b-cn.ttrm"),
                "package hartland.sales\nmodel cn\n\ndef kind Sale { grounds: er.entity.sale }\n",
            )
            runTtr(listOf("cn", "hygiene", "$root", "--fail-on", "error")) shouldBe 0
        }

        "the ttr exit code: a usage error is 2, never the 1 of findings at --fail-on (review-119 M-8)" {
            runTtr(listOf("cn", "hygiene", "$golden", "--bogus")) shouldBe 2
            runTtr(listOf("cn", "hygiene", "$golden", "--format", "xml")) shouldBe 2
            runTtr(listOf("cn", "hygiene")) shouldBe 2
            runTtr(listOf("cn")) shouldBe 2
            runTtr(listOf("cn", "hygiene", "${golden.resolve("expected.md")}")) shouldBe 2
            runTtr(listOf("cn", "hygiene", "$golden")) shouldBe 0
            runTtr(listOf("cn", "hygiene", "$golden", "--fail-on", "error")) shouldBe 1
            runTtr(listOf("--help")) shouldBe 0
        }
    })
