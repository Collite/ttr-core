// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.ConceptLift
import org.tatrman.ttr.lexicon.TargetFacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

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

        test("the committed fixture lexicon decodes through the reader's codec (the S4.3 / S4.4 input)") {
            val golden = CompiledLexicon.fromJson(estate.resolve("expected/lexicon.json").readText())
            golden.header.schemaVersion shouldBe "ttr-lexicon-compiled/v6"
            golden.targets.count { it.value.objectKind == TargetFacts.CONCEPT } shouldBe 12
        }
    })
