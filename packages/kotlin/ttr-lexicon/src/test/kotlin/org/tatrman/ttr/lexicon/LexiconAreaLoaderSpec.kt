// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * RV-P1.1 T7 — a whole `lexicon/` tree becomes one typed area.
 *
 * This is the shape RV-P1.2's compiler consumes, so the counts, the per-file defaults and
 * the provenance are all load-bearing rather than incidental.
 */
class LexiconAreaLoaderSpec :
    FunSpec({

        val sample: Path =
            Path.of(
                requireNotNull(this::class.java.getResource("/sample-lexicon-area")) {
                    "missing sample-lexicon-area fixture tree"
                }.toURI(),
            )

        test("aliases + values + skills load into one area") {
            val area = LexiconAreaLoader.load(sample).shouldBeInstanceOf<LexiconLoad.Ok<LexiconArea>>().value

            area.dataFiles.size shouldBe 4 // 2 aliases + 2 values
            area.skills.size shouldBe 2
            area.entries.size shouldBe 5
            // entities 4 + measures 2 + accounts 1 + regions 2
            area.termCount shouldBe 9
        }

        test("a non-lexicon file beside the tree is ignored, not rejected") {
            // NOTES.md sits at the area root: markdown, but not under skills/.
            val area = LexiconAreaLoader.load(sample).shouldBeInstanceOf<LexiconLoad.Ok<LexiconArea>>().value

            area.skills.map { it.opId } shouldContainExactlyInAnyOrder listOf("op:trend", "op:top-n")
        }

        test("per-file defaults apply per file, not across the area") {
            val area = LexiconAreaLoader.load(sample).shouldBeInstanceOf<LexiconLoad.Ok<LexiconArea>>().value

            val bySource = area.dataFiles.associateBy { it.provenance.file }

            // entities: defaults cs / TYPOS(1) — the `en` term overrides only lang.
            val entities = bySource.getValue("aliases/entities.lex.yaml")
            entities.entries[0].terms[0].method shouldBe MatchMethod.Typos(1)
            entities.entries[0].terms[1].lang shouldBe Lang.EN
            entities.entries[0].terms[1].method shouldBe MatchMethod.Typos(1)

            // measures: defaults cs|en / EXACT.
            val measures = bySource.getValue("aliases/measures.lex.yaml")
            measures.entries[0].terms.map { it.lang } shouldContainExactly listOf(Lang.CS_EN, Lang.CS_EN)
            measures.entries[0].terms.map { it.method } shouldContainExactly
                listOf(MatchMethod.Exact, MatchMethod.Exact)

            // accounts: defaults cs / TOKENS.
            bySource
                .getValue("values/accounts.lex.yaml")
                .entries[0]
                .terms[0]
                .method shouldBe MatchMethod.Tokens

            // regions: NO defaults block — every term states its own.
            bySource
                .getValue("values/regions.lex.yaml")
                .entries[0]
                .terms
                .map { it.method } shouldContainExactly
                listOf(MatchMethod.Exact, MatchMethod.Exact)
        }

        test("provenance names the file relative to the area root, and the authored line") {
            val area = LexiconAreaLoader.load(sample).shouldBeInstanceOf<LexiconLoad.Ok<LexiconArea>>().value

            val entities = area.dataFiles.single { it.provenance.file == "aliases/entities.lex.yaml" }
            entities.entries[0].terms[0].provenance shouldBe Provenance("aliases/entities.lex.yaml", 5)
            entities.entries[0].terms[1].provenance shouldBe Provenance("aliases/entities.lex.yaml", 6)
            entities.entries[1].terms[0].provenance shouldBe Provenance("aliases/entities.lex.yaml", 9)

            // A skill's triggers are inside frontmatter — the offset must be applied.
            val trend = area.skills.single { it.opId == "op:trend" }
            trend.triggers[0].provenance shouldBe Provenance("skills/trend.md", 5)
            trend.triggers[1].provenance shouldBe Provenance("skills/trend.md", 6)
        }

        test("LP §3.3 — `predicates/` is a data directory, and a `pred:` row loads like any other") {
            val root = kotlin.io.path.createTempDirectory("lexicon-area-pred")
            root.resolve("predicates").createDirectories()
            root.resolve("predicates/string.lex.yaml").writeText(
                """
                schema: ttr-lexicon/v1
                entries:
                  - terms: [ { text: "v popisu", lang: cs, method: TOKENS } ]
                    target: pred:contains
                """.trimIndent(),
            )

            val area = LexiconAreaLoader.load(root).shouldBeInstanceOf<LexiconLoad.Ok<LexiconArea>>().value

            // The directory is layout, never meaning — the CLASS comes from the `pred:` prefix at
            // compile time. What this asserts is only that the folder is walked at all: without it
            // an estate could not extend the shipped slice in the place the docs send it to.
            area.entries.single().target shouldBe "pred:contains"
            area.entries
                .single()
                .terms
                .single()
                .provenance shouldBe Provenance("predicates/string.lex.yaml", 3)
        }

        test("every bad file is reported, not just the first") {
            val broken = tempTree()

            val rejected = LexiconAreaLoader.load(broken).shouldBeInstanceOf<LexiconLoad.Rejected>()

            rejected.codes shouldContainExactlyInAnyOrder
                listOf(LexiconErrors.UNKNOWN_METHOD, LexiconErrors.NO_TRIGGERS)
            rejected.violations.map { it.provenance.file } shouldContainExactlyInAnyOrder
                listOf("aliases/bad.lex.yaml", "skills/bad.md")
        }
    })

private fun tempTree(): Path {
    val root = kotlin.io.path.createTempDirectory("lexicon-area")
    root.resolve("aliases").createDirectories()
    root.resolve("skills").createDirectories()
    root.resolve("aliases/bad.lex.yaml").writeText(
        """
        schema: ttr-lexicon/v1
        entries:
          - terms:
              - { text: "středisko", method: FUZZY }
            target: er.CostCenter
        """.trimIndent(),
    )
    root.resolve("skills/bad.md").writeText(
        """
        ---
        schema: ttr-skill/v1
        op: op:trend
        triggers: []
        version: 1
        ---
        Body.
        """.trimIndent(),
    )
    return root
}
