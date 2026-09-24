// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.lexicon.Reach
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.SourceTag
import org.tatrman.ttr.lexicon.TargetClass
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * RV-P3.1 T7 — **the first test in this codebase that closes the producer→consumer loop.**
 *
 * Everything before it tested one half: P1.2 asserted a `CompileResult` the compiler still held in
 * memory, P1.4 asserted a reader over an archive a test had packed for it. Here the real command
 * runs over the P1.2 fixture estate and the bytes it writes are read back the way lex-matcher's
 * `LexiconArchiveSource` and the P1.6 `GroundingSliceSource` read one — `SnapshotReader.read` →
 * `kind == lexicon` → `CompiledLexicon.fromJson`.
 *
 * It runs against the fixture **in place** (via the `fixtureEstate` system property) rather than a
 * copy, so a change to the compiler's own fixture cannot silently stop being what the CLI is
 * tested on.
 */
class FixtureEstateRoundTripSpec :
    FunSpec({

        val estate = Path.of(System.getProperty("fixtureEstate") ?: error("fixtureEstate not set"))

        test("the fixture estate is where the build expects it") {
            estate.resolve("model").exists() shouldBe true
            estate.resolve("lexicon").exists() shouldBe true
        }

        test("the CLI builds the fixture estate and the archive round-trips through the consumer path") {
            val out = Files.createTempDirectory("fixture-out").resolve("lexicon.tar.zst")

            val outcome = LexiconBuildCli.run(repoRoot = estate, out = out)

            withClue(outcome.stderr) { outcome.exitCode shouldBe LexiconBuildCli.EXIT_OK }

            val archive = LexiconBuildCli.readBack(out)
            archive.id shouldBe outcome.id

            // The estate's own declared vocabulary survived the whole trip.
            val terms = archive.lexicon.entries.map { it.termNormalized }
            terms shouldContain "zákazník"
            terms shouldContain "aktivní"

            // ...and the model's labels rode along as the METADATA layer, unauthored (RV-39).
            archive.lexicon.entries.map { it.sourceTag } shouldContain SourceTag.METADATA
        }

        test("the RV-42 grounding slices are present as GROUNDING_TRIGGER rows") {
            val out = Files.createTempDirectory("fixture-ground").resolve("lexicon.tar.zst")
            LexiconBuildCli.run(repoRoot = estate, out = out).exitCode shouldBe LexiconBuildCli.EXIT_OK

            val triggers =
                LexiconBuildCli
                    .readBack(out)
                    .lexicon
                    .entries
                    .filter { it.targetClass == TargetClass.GROUNDING_TRIGGER }

            triggers.map { it.targetRef }.toSet() shouldContain "ground:chrono"
            triggers.map { it.targetRef }.toSet() shouldContain "ground:money"
            triggers.map { it.targetRef }.toSet() shouldContain "ground:geo"
        }

        test("LP §3.3 — the predicate slice is present as STRING_PREDICATE rows") {
            val out = Files.createTempDirectory("fixture-pred").resolve("lexicon.tar.zst")
            LexiconBuildCli.run(repoRoot = estate, out = out).exitCode shouldBe LexiconBuildCli.EXIT_OK

            val lexicon = LexiconBuildCli.readBack(out).lexicon
            val predicates = lexicon.entries.filter { it.targetClass == TargetClass.STRING_PREDICATE }

            predicates.map { it.targetRef }.toSet() shouldBe
                LexiconValidator.PREDICATE_KINDS.map { "pred:$it" }.toSet()
            // The whole point of P2a, through the real CLI: `pred:` rows ride an ordinary estate
            // build with nothing authored, exactly as the grounding slices do.
            predicates.map { it.termNormalized } shouldContain "obsahuje"
        }

        test("the operator stdlib reaches the artifact's second document") {
            val out = Files.createTempDirectory("fixture-ops").resolve("lexicon.tar.zst")
            LexiconBuildCli.run(repoRoot = estate, out = out).exitCode shouldBe LexiconBuildCli.EXIT_OK

            // `operator-library.json` is a second document inside the same archive; a reader that
            // only ever opened `lexicon.json` would not notice it going missing.
            LexiconBuildCli
                .readBack(out)
                .operators.operators.keys shouldContain "op:trend"
        }

        test("MH — the archive carries the E-R reach, at schema v5, through the real CLI path") {
            val out = Files.createTempDirectory("fixture-reach").resolve("lexicon.tar.zst")
            LexiconBuildCli.run(repoRoot = estate, out = out).exitCode shouldBe LexiconBuildCli.EXIT_OK

            val lexicon = LexiconBuildCli.readBack(out).lexicon

            lexicon.header.schemaVersion shouldBe "ttr-lexicon-compiled/v5"
            // `model/er/relations.ttrm` declares both, and only the mandatory one may claim to be
            // mandatory — the flag is `cardinality.to`'s lower bound, read off the estate.
            lexicon.targets.getValue("er.entity.store").reachedFrom shouldBe
                listOf(
                    Reach("er.entity.customer", mandatory = false),
                    Reach("er.entity.store_sales", mandatory = true),
                )
            // The direction is `to`, not `from`: the fact that points at the dimension has no reach
            // of its own.
            lexicon.targets.getValue("er.entity.store_sales").reachedFrom shouldBe emptyList()
        }

        test("MH — the collision warning survives the real CLI path too") {
            val out = Files.createTempDirectory("fixture-collision").resolve("lexicon.tar.zst")
            val outcome = LexiconBuildCli.run(repoRoot = estate, out = out)

            outcome.warnings.map { it.code } shouldContain "RG-LEXC-004"
            outcome.exitCode shouldBe LexiconBuildCli.EXIT_OK
        }

        test("a dangling ref in the fixture warns without stopping the build (RV-20)") {
            val out = Files.createTempDirectory("fixture-warn").resolve("lexicon.tar.zst")

            val outcome = LexiconBuildCli.run(repoRoot = estate, out = out)

            // The fixture deliberately carries `er.entity.ghost`, so this asserts the estate's
            // intent as much as the CLI's behaviour.
            outcome.warnings.map { it.code } shouldContain "RG-LEXC-001"
            outcome.exitCode shouldBe LexiconBuildCli.EXIT_OK
            out.exists() shouldBe true
        }

        test("--check over the fixture agrees with the archive the same run just wrote") {
            val out = Files.createTempDirectory("fixture-check").resolve("lexicon.tar.zst")
            LexiconBuildCli.run(repoRoot = estate, out = out).exitCode shouldBe LexiconBuildCli.EXIT_OK
            val written = out.readBytes()

            val checked = LexiconBuildCli.run(repoRoot = estate, out = out, check = true)

            checked.exitCode shouldBe LexiconBuildCli.EXIT_OK
            out.readBytes() shouldBe written
        }
    })
