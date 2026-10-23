// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept.candidate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.concept.CnConceptService
import org.tatrman.concept.DefinitionFixtures
import org.tatrman.concept.Layer
import org.tatrman.concept.RenderOptions
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.concept.Severity
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttrp.resolve.MdRepo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * CN-P5 S5.2 T5 — the promotion output contract (cn contracts §6): a promoted candidate is a model TEXT change, a
 * unified diff against the package's files — a new `def <stereo> <Name> { … }` carrying `sources: ["<source_ref>"]`, a
 * `def term` in the locale's lexicon file, a `relations:` entry on the `from` concept — and a pull request whose body,
 * for a phase, carries the phase's er rendering. The goldens are the four example candidates' patches
 * (`candidates/promotion/`); `-Pcn.goldens.regen` rewrites them.
 *
 * A golden that merely matches itself proves nothing, so each patch is also APPLIED (`git apply`, the way a reviewer's
 * checkout takes it) and the patched package LOADED: it must parse, resolve, gain exactly the def the candidate
 * proposed, and raise no finding the package did not already have — the strict load report (it carries
 * `duplicate-qname`, which the plain snapshot cannot see) plus the cn definition errors (review-133 ⚑R133-d: the gate
 * S5.3 runs before it opens a PR).
 */
class PromotionSpec :
    FunSpec({
        val regen = System.getProperty("cn.goldens.regen") == "true"
        val resources = DefinitionFixtures.repo.resolve("packages/kotlin/ttr-concept/src/test/resources")
        val goldens = resources.resolve("candidates/promotion")
        val clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC)

        fun candidate(name: String): CnCandidate =
            CnCandidates
                .parse(resources.resolve("candidates/examples/$name.json").readText())
                .shouldBeInstanceOf<CandidateLoad.Ok>()
                .candidate

        /** A world's model package: every `.ttrm` file, by path under the world. */
        fun pkg(world: String): Map<String, String> {
            val dir = resources.resolve("worlds/$world")
            return Files.walk(dir).use { s ->
                s
                    .filter { it.isRegularFile() && it.extension == "ttrm" }
                    .sorted()
                    .toList()
                    .associate { dir.relativize(it).toString() to it.readText() }
            }
        }

        fun materialize(files: Map<String, String>): Path {
            val dir = Files.createTempDirectory("cn-promotion")
            for ((rel, text) in files) {
                dir.resolve(rel).also { it.parent.createDirectories() }.writeText(text)
            }
            return dir
        }

        class Loaded(
            val dir: Path,
            val report: List<String>,
            val cn: ResolvedCnModel,
        ) {
            val schema: CnSchema get() = cn.model.schemas["cn"] as CnSchema

            /** Every finding, paths relative to the package: the strict report, then the cn definition errors. */
            val findings: Set<String>
                get() =
                    report.toSet() +
                        cn
                            .definitionDiagnostics()
                            .filter { it.severity == Severity.ERROR }
                            .map { "DEF ${it.slug} ${it.subject}" }

            fun concept(name: String) = schema.concepts.values.single { it.qname.name == name }
        }

        fun load(dir: Path): Loaded {
            val result =
                MetadataLoader(FileBasedSource("cn", 100, LocalFsStorage(id = "cn", rootPath = dir)))
                    .load(LoadOptions(strict = true))
            val report =
                result.report!!.issues.map { i ->
                    val file = i.file?.removePrefix("$dir/") ?: ""
                    "${i.code} ${i.severity} $file ${i.message.replace("$dir/", "").replace("${dir.fileName}/", "")}"
                }
            return Loaded(
                dir,
                report,
                ResolvedCnModel.of(result.model!!, snapshotHash = "promotion", md = MdRepo.loadFrom(dir)),
            )
        }

        fun gitApply(
            dir: Path,
            diff: String,
        ) {
            val patch = Files.createTempFile("cn-promotion", ".diff").also { it.writeText(diff) }
            val p =
                ProcessBuilder("git", "apply", "--whitespace=nowarn", patch.toString())
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            withClueExit(p.exitValue(), out)
        }

        fun golden(
            file: String,
            actual: String,
        ) {
            val f = goldens.resolve(file)
            if (regen) {
                f.parent.createDirectories()
                f.writeText(actual)
            } else {
                f.readText() shouldBe actual
            }
        }

        /**
         * [c] promoted against [before]: the patch, applied with `git apply` to a copy (yielding exactly the patched
         * files), and the patched package loaded with no new finding — unless [newDefinitionErrors] says the definition
         * itself is the test's subject.
         */
        fun promote(
            c: CnCandidate,
            before: Map<String, String>,
            newDefinitionErrors: Boolean = false,
        ): Pair<PromotionPatch, Loaded> {
            val patch = Promotion.patch(c, before).shouldBeInstanceOf<PromotionResult.Patched>().patch
            val dir = materialize(before)
            gitApply(dir, patch.diff)
            for ((rel, text) in patch.files) dir.resolve(rel).readText() shouldBe text
            val original = load(materialize(before))
            val patched = load(dir)
            val fresh = patched.findings - original.findings
            (if (newDefinitionErrors) fresh.filterNot { it.startsWith("DEF ") }.toSet() else fresh) shouldBe emptySet()
            return patch to patched
        }

        data class Case(
            val example: String,
            val world: String,
            val qname: String,
            val check: (Loaded, CnCandidate) -> Unit,
        )

        val cases =
            listOf(
                Case("manual-relation", "hartland", "cn.Warehouse") { w, _ ->
                    w.concept("Warehouse").relations.map { it.typeName to it.to?.name } shouldContain
                        ("stocks" to "Item")
                    w.schema.relationTypes.keys
                        .map { it.name } shouldContain "stocks"
                },
                Case("au-synthesizer-concept", "hartland", "cn.LargeOrder") { w, c ->
                    val large = w.concept("LargeOrder")
                    large.stereotype shouldBe "phase"
                    large.of?.name shouldBe "Order"
                    large.sources shouldBe listOf(c.provenance.sourceRef!!)
                },
                Case("evidence-phase", "clinic", "cn.IschemicPatient") { w, c ->
                    val ischemic = w.concept("IschemicPatient")
                    ischemic.of?.name shouldBe "Patient"
                    ischemic.sources shouldBe listOf(c.provenance.sourceRef!!)
                },
                Case("span-catalogue-term", "clinic", "lexicon.prelozeni") { w, _ ->
                    w
                        .concept("Transfer")
                        .lexiconLinks
                        .flatMap { it.forms } shouldBe listOf("přeložení", "přeložení pacienta")
                },
            )

        for (case in cases) {
            test("${case.example}: the patch is the golden, applies, and the package gains exactly the proposed def") {
                val c = candidate(case.example)
                val before = pkg(case.world)
                val (patch, patched) = promote(c, before)
                patch.qname shouldBe case.qname
                golden("${case.example}.diff", patch.diff)
                Promotion.patch(c, before) shouldBe PromotionResult.Patched(patch) // deterministic
                case.check(patched, c)
            }
        }

        test("a phase's pull request carries its er rendering; every PR names the candidate and its sources line") {
            for (case in cases) {
                val c = candidate(case.example)
                val patch =
                    Promotion.patch(c, pkg(case.world)).shouldBeInstanceOf<PromotionResult.Patched>().patch
                val concept = c.proposal as? Proposal.Concept
                val er =
                    if (concept?.stereo == Stereo.PHASE) {
                        val loaded = load(materialize(pkg(case.world) + patch.files))
                        CnConceptService(loaded.cn, clock)
                            .definitionRender(
                                ConceptRef.newBuilder().setQname(case.qname).build(),
                                Layer.ER,
                                "postgresql",
                                RenderOptions(),
                            ).text
                            .also { it.shouldNotBeBlank() }
                    } else {
                        null
                    }
                val pr = Promotion.pullRequest(c, patch, er)
                pr.body shouldContain c.id
                c.provenance.sourceRef?.let { pr.body shouldContain it }
                if (er != null) pr.body shouldContain er
                golden("${case.example}.pr.md", "# ${pr.title}\n\n${pr.body}")
            }
        }

        test("canon_ref is the merged commit and the def's qname") {
            Promotion.canonRef("4f2a9c1e", "cn.LargeOrder") shouldBe "4f2a9c1e:cn.LargeOrder"
        }

        // ----- refusals: a patch the steward could not merge is never emitted -----

        fun refused(
            c: CnCandidate,
            world: String,
            promoted: (String) -> String? = { null },
        ): String = Promotion.patch(c, pkg(world), promoted).shouldBeInstanceOf<PromotionResult.Refused>().reason

        val synthesized = candidate("au-synthesizer-concept")

        fun concept(c: CnCandidate) = c.proposal as Proposal.Concept

        test("a concept the package already defines") {
            val dup = synthesized.copy(proposal = concept(synthesized).copy(name = "PendingOrder"))
            refused(dup, "hartland") shouldContain "cn.PendingOrder"
        }

        test("a concept whose anchor the package does not define") {
            val orphan = synthesized.copy(proposal = concept(synthesized).copy(of = "cn.Shipment"))
            refused(orphan, "hartland") shouldContain "cn.Shipment"
        }

        test("a relation from or to a concept the package does not define") {
            val rel = candidate("manual-relation")
            refused(rel.copy(proposal = Proposal.Relation("stocks", "cn.Depot", "cn.Item")), "hartland") shouldContain
                "cn.Depot"
            refused(
                rel.copy(proposal = Proposal.Relation("stocks", "cn.Warehouse", "cn.Gadget")),
                "hartland",
            ) shouldContain
                "cn.Gadget"
        }

        test("a relation the from concept already states") {
            val rel = candidate("manual-relation")
            val once = Promotion.patch(rel, pkg("hartland")).shouldBeInstanceOf<PromotionResult.Patched>().patch
            Promotion
                .patch(rel, pkg("hartland") + once.files)
                .shouldBeInstanceOf<PromotionResult.Refused>()
                .reason shouldContain "stocks"
        }

        test("a term for a concept the package does not define, or for a queued concept not promoted yet") {
            val term = candidate("span-catalogue-term")
            val p = term.proposal as Proposal.Term
            refused(term.copy(proposal = p.copy(targetQname = "cn.Discharge")), "clinic") shouldContain "cn.Discharge"
            val queued = term.copy(proposal = p.copy(targetQname = null, proposedConceptRef = synthesized.id))
            refused(queued, "clinic") shouldContain synthesized.id
            // Promoted as cn.LargeOrder — into hartland, not this package: still refused.
            refused(queued, "clinic") { "cn.LargeOrder" } shouldContain "cn.LargeOrder"
        }

        test("a queued concept's term follows it once promoted") {
            val term = candidate("span-catalogue-term")
            val p = term.proposal as Proposal.Term
            val queued =
                term.copy(proposal = p.copy(targetQname = null, proposedConceptRef = synthesized.id, locale = "en"))
            val withLarge =
                pkg("hartland") +
                    Promotion
                        .patch(
                            synthesized,
                            pkg("hartland"),
                        ).shouldBeInstanceOf<PromotionResult.Patched>()
                        .patch.files
            val patch =
                Promotion
                    .patch(queued, withLarge) { if (it == synthesized.id) "cn.LargeOrder" else null }
                    .shouldBeInstanceOf<PromotionResult.Patched>()
                    .patch
            patch.diff shouldContain "for: cn.LargeOrder"
        }

        test("a relation on a multi-line def, then a second one into the relations: list it opened") {
            val rel = candidate("manual-relation")
            val before = pkg("hartland")
            val first =
                Promotion
                    .patch(rel.copy(proposal = Proposal.Relation("ships_from", "cn.Order", "cn.Warehouse")), before)
                    .shouldBeInstanceOf<PromotionResult.Patched>()
                    .patch
            first.diff shouldContain "+    qualities: [order_value, state, channel, placed_date, delivered_date],\n" +
                "+    relations: [ { type: ships_from, to: Warehouse } ]\n"
            val second =
                Promotion
                    .patch(
                        rel.copy(proposal = Proposal.Relation("ships_to", "cn.Order", "cn.Party")),
                        before + first.files,
                    ).shouldBeInstanceOf<PromotionResult.Patched>()
                    .patch
            second.diff shouldContain
                "relations: [ { type: ships_from, to: Warehouse }, { type: ships_to, to: Party } ]"

            val dir = materialize(before)
            gitApply(dir, first.diff)
            gitApply(dir, second.diff)
            val patched = load(dir)
            (patched.findings - load(materialize(before)).findings) shouldBe emptySet()
            patched.concept("Order").relations.map { it.typeName to it.to?.name } shouldBe
                listOf("ships_from" to "Warehouse", "ships_to" to "Party")
        }

        // ----- review-133 -----

        test("F3: a write the grammar would refuse is refused, never emitted") {
            // Built past the reader (which refuses the name, F1): the read-back is the net under every text write.
            val keyword = synthesized.copy(proposal = Proposal.Concept(Stereo.KIND, "date"))
            refused(keyword, "hartland") shouldContain "would not parse"
        }

        test("F1: a term whose first form is a keyword gets a legal id") {
            val term = candidate("span-catalogue-term")
            val p = term.proposal as Proposal.Term
            val (patch, loaded) = promote(term.copy(proposal = p.copy(forms = listOf("date", "datum"))), pkg("clinic"))
            patch.qname shouldBe "lexicon.date_term"
            patch.diff shouldContain "def term date_term {"
            loaded.concept("Transfer").lexiconLinks.flatMap { it.forms } shouldContain "date"
        }

        test(
            "F2: a definition comes back as the candidate wrote it — a closing quote, a lone first word, a `pl` line",
        ) {
            for ((text, valid) in listOf(
                "state = \"held\"" to true,
                "state\n    = 'held'" to true,
                "pl\nstate = 'held'" to false,
            )) {
                val c = synthesized.copy(proposal = concept(synthesized).copy(definitionText = text))
                val (_, loaded) = promote(c, pkg("hartland"), newDefinitionErrors = !valid)
                val definition = loaded.concept("LargeOrder").definition!!
                definition.language shouldBe "pl"
                definition.text shouldBe text // exactly: a tagged block's text ends before its closing line
            }
        }

        test("F4: a relation lands by tokens — comments in the list, after the last property, in an empty body") {
            // A cn file whose relation lists and bodies carry comments where a character scan goes wrong.
            val depots =
                """
                |model cn
                |
                |def relation_type stocks {}
                |def kind Depot {
                |    relations: [
                |        { type: stocks, to: Item },   // the main one
                |    ]
                |}
                |def kind Depot2 {
                |    label: { en: "Depot" }   // note: a, b
                |}
                |def kind Depot3 {
                |    // nothing yet { not code }
                |}
                |def kind Depot4 { relations: [ { type: stocks, to: Item }, ] }
                |def kind Depot5 { label: { en: "Depot" } /* a, b */ }
                |
                """.trimMargin()
            val before = pkg("hartland") + ("cn-depots.ttrm" to depots)
            val rel = candidate("manual-relation")
            val expected =
                mapOf(
                    "Depot" to listOf("stocks" to "Item", "stocks2" to "Party"),
                    "Depot2" to listOf("stocks2" to "Party"),
                    "Depot3" to listOf("stocks2" to "Party"),
                    "Depot4" to listOf("stocks" to "Item", "stocks2" to "Party"),
                    "Depot5" to listOf("stocks2" to "Party"),
                )
            for ((depot, relations) in expected) {
                val (patch, loaded) =
                    promote(
                        rel.copy(proposal = Proposal.Relation("stocks2", "cn.$depot", "cn.Party")),
                        before,
                    )
                io.kotest.assertions.withClue(patch.diff) {
                    loaded.concept(depot).relations.map { it.typeName to it.to?.name } shouldBe relations
                }
            }
            // A comment stays on the line it was written on.
            val (patch, _) = promote(rel.copy(proposal = Proposal.Relation("stocks2", "cn.Depot2", "cn.Party")), before)
            patch.diff shouldContain
                "+    label: { en: \"Depot\" },   // note: a, b\n+    relations: [ { type: stocks2, to: Party } ]\n"
        }

        test("F6: concepts and relation types share one cn name space — each refuses the other's names") {
            val withType = pkg("hartland") + ("cn-types.ttrm" to "model cn\n\ndef relation_type stocks {}\n")
            val kind = synthesized.copy(proposal = Proposal.Concept(Stereo.KIND, "stocks"))
            Promotion.patch(kind, withType).shouldBeInstanceOf<PromotionResult.Refused>().reason shouldContain
                "relation type"
            val rel = candidate("manual-relation")
            refused(
                rel.copy(proposal = Proposal.Relation("Item", "cn.Warehouse", "cn.Party")),
                "hartland",
            ) shouldContain
                "cn.Item is a concept"
        }

        test("F6: the strict load report sees a duplicate def the plain snapshot does not") {
            // Two files, one cn name: the snapshot keeps one silently; the strict report names both. (Within ONE file the
            // loader reports nothing at all — the promotion's own name-space check is what refuses that case.)
            val split =
                pkg("hartland") + ("cn-a.ttrm" to "model cn\n\ndef relation_type stocks {}\n") +
                    ("cn-b.ttrm" to "model cn\n\ndef kind stocks {}\n")
            val findings = load(materialize(split)).findings - load(materialize(pkg("hartland"))).findings
            findings.count { it.startsWith("duplicate-qname ERROR") } shouldBe 2
        }

        test("F5: a concept declared by `concept { }` sugar is defined — an anchor, a term target, a relation's to") {
            val sugar =
                """
                |model er
                |
                |def entity credit_note {
                |    attributes: [ def attribute credit_note_id { type: int, isKey: true } ],
                |    concept { kind: CreditNote }
                |}
                |
                """.trimMargin()
            val before = pkg("hartland") + ("er-credit.ttrm" to sugar)
            val role =
                synthesized.copy(
                    proposal = Proposal.Concept(Stereo.ROLE, "Creditor", of = "cn.CreditNote", event = "cn.Order"),
                )
            val (rolePatch, roleLoaded) = promote(role, before)
            rolePatch.files.keys shouldBe setOf("cn-qualities.ttrm") // the first `model cn` file, never the er file
            roleLoaded.concept("Creditor").of?.name shouldBe "CreditNote"

            val term = candidate("span-catalogue-term")
            val words = (term.proposal as Proposal.Term).copy(targetQname = "cn.CreditNote", forms = listOf("dobropis"))
            val (_, termLoaded) = promote(term.copy(proposal = words), before)
            termLoaded.concept("CreditNote").lexiconLinks.flatMap { it.forms } shouldContain "dobropis"

            val rel = candidate("manual-relation")
            val (_, relLoaded) =
                promote(
                    rel.copy(proposal = Proposal.Relation("stocks", "cn.Warehouse", "cn.CreditNote")),
                    before,
                )
            relLoaded.concept("Warehouse").relations.map { it.to?.name } shouldContain "CreditNote"

            // Refused: re-declaring it, and a relation FROM it (no cn def carries its relations:).
            val kind = synthesized.copy(proposal = Proposal.Concept(Stereo.KIND, "CreditNote"))
            Promotion.patch(kind, before).shouldBeInstanceOf<PromotionResult.Refused>().reason shouldContain
                "er-credit.ttrm"
            Promotion
                .patch(rel.copy(proposal = Proposal.Relation("stocks", "cn.CreditNote", "cn.Item")), before)
                .shouldBeInstanceOf<PromotionResult.Refused>()
                .reason shouldContain "er-credit.ttrm"
        }

        test("a candidate that is not proposed any more") {
            val rel = candidate("manual-relation")
            val done = rel.copy(status = Status.REJECTED, resolution = Resolution("a.steward", "2026-10-24T08:00:00Z"))
            refused(done, "hartland") shouldContain "rejected"
        }
    })

private fun withClueExit(
    exit: Int,
    output: String,
) {
    io.kotest.assertions.withClue("git apply: $output") { exit shouldBe 0 }
}
