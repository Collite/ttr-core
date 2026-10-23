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
                    "${i.code} ${i.severity} $file ${i.message.replace("$dir/", "")}"
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
