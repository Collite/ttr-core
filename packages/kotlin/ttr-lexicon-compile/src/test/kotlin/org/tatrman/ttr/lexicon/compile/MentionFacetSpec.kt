// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.TargetFacts
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadResult
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * LP-P2a T9 (✅LP-10, contracts §2.1) — `targets[ref].nameRef/codeRef/codeFormat`, built from REAL
 * `.ttrm` through the analyzer (review-103 F6).
 *
 * ⚑LPQ-5, the gap this closes: the resolver attributes a quoted literal to the head's declared
 * `semantics { name: · code: }`, and the compiled archive had no channel for it. These three fields
 * are that channel; LP-P2b projects them into `ResolverEntityType`.
 *
 * ⚠ Why real `.ttrm`, not a hand-built `Model`: the first version of this spec built its model in
 * Kotlin and gave a code attribute `ResolvedAttributeSemantics(role = "code", codeFormat =
 * "^S[0-9]{4}$")` — a role that does not exist, carrying a regex no analyzer ever produced. The
 * real producer only ever wrote a period `code_format:` MASK (`yyyyMM`), which the resolver then
 * compiled as a regex that matches nothing anyone types (F6). A fixture neater than its producer
 * hid exactly that. Every model below therefore goes through the path `ttr-lexicon build` takes:
 * `MetadataLoader` over `FileBasedSource`, which runs `SemanticsAnalyzer` on every file.
 *
 * The rule the fields are written under is the file's oldest one: a fact is COPIED from the model
 * or it is absent. Never composed from a ref string, never inferred from a column called `name`.
 */
class MentionFacetSpec :
    FunSpec({

        val snapshotHash = "sha256:" + "c4".repeat(32)
        val builtAt = "2026-09-24T00:00:00Z"

        /** The model the files load to — the `ttr-lexicon build` composition, stock source included. */
        fun load(
            vararg files: Pair<String, String>,
            root: Path = Files.createTempDirectory("facet-model"),
        ): LoadResult {
            for ((path, src) in files) {
                val file = root.resolve(path)
                file.parent.createDirectories()
                file.writeText(src)
            }
            return MetadataLoader(
                listOf(
                    BuiltinStockSource(),
                    FileBasedSource(sourceId = "repo", priority = 100, storage = LocalFsStorage("repo", root)),
                ),
            ).load()
        }

        /** A model that loaded clean — the one the CLI would build from. */
        fun modelOf(
            vararg files: Pair<String, String>,
            root: Path = Files.createTempDirectory("facet-model"),
        ): Model {
            val result = load(*files, root = root)
            withClue(result.errors.joinToString("\n") { it.message }) {
                result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }.shouldBeEmpty()
            }
            return result.model.shouldNotBeNull()
        }

        /**
         * The compiled `targets` map. A ref is in it only when some row targets it: every entity below
         * carries a `displayLabel` (a METADATA row), and a db table — which has no label surface —
         * gets its row from [terms], an ordinary estate `.lex.yaml`.
         */
        fun targets(
            model: Model,
            terms: String? = null,
        ): Map<String, TargetFacts> =
            LexiconCompiler
                .compile(
                    LexiconSources(
                        area =
                            LexiconArea(
                                listOfNotNull(
                                    terms?.let {
                                        LexiconValidator
                                            .loadDataFile(it, "lexicon/aliases/facet.lex.yaml")
                                            .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                                            .value
                                    },
                                ),
                                emptyList(),
                            ),
                        model = model,
                    ),
                    ModelRefIndex.of(model),
                    snapshotHash,
                    builtAt,
                ).lexicon.targets

        val er =
            "model/er/facet.ttrm" to
                """
                model er

                def entity store { displayLabel: { cs: "Prodejna" },
                    semantics { name: nazev, code: kod, code_pattern: "^S[0-9]{4}$" },
                    attributes: [
                        def attribute nazev { type: text, displayLabel: { cs: "Název prodejny" } },
                        def attribute kod { type: text },
                    ]
                }

                // hartland's case (review-103 F7): a TPC-DS business key has no digit in it at all,
                // and only a declared pattern can say what one looks like.
                def entity promotion { displayLabel: { en: "Promotion" },
                    semantics { name: promo_name, code: promo_id, code_pattern: "^[A-P]{16}$" },
                    attributes: [
                        def attribute promo_name { type: text },
                        def attribute promo_id { type: text },
                    ]
                }

                // A period table whose code attribute declares no `code_format:` — the analyzer's
                // default mask `yyyyMM` is what the compiler must translate.
                def entity month { displayLabel: { cs: "Měsíc" },
                    semantics { kind: period_table, code: code },
                    attributes: [
                        def attribute start_date { type: date, semantics { role: period_start } },
                        def attribute end_date { type: date, semantics { role: period_end } },
                        def attribute code { type: text, semantics { role: period_code } },
                    ]
                }

                // A declared mask with a separator, and a declared pattern that must WIN over it.
                def entity week { displayLabel: { cs: "Týden" },
                    semantics { kind: period_table, code: code },
                    attributes: [
                        def attribute start_date { type: date, semantics { role: period_start } },
                        def attribute end_date { type: date, semantics { role: period_end } },
                        def attribute code { type: text, semantics { role: period_code, code_format: "yyyy-WW" } },
                    ]
                }
                def entity quarter { displayLabel: { cs: "Čtvrtletí" },
                    semantics { kind: period_table, code: code, code_pattern: "^[0-9]{4}Q[1-4]$" },
                    attributes: [
                        def attribute start_date { type: date, semantics { role: period_start } },
                        def attribute end_date { type: date, semantics { role: period_end } },
                        def attribute code { type: text, semantics { role: period_code, code_format: "yyyyQ" } },
                    ]
                }

                // A month-NAME mask: text, not digits. No regex is better than a wrong one.
                def entity named_month { displayLabel: { cs: "Pojmenovaný měsíc" },
                    semantics { kind: period_table, code: code },
                    attributes: [
                        def attribute start_date { type: date, semantics { role: period_start } },
                        def attribute end_date { type: date, semantics { role: period_end } },
                        def attribute code { type: text, semantics { role: period_code, code_format: "MMMM yyyy" } },
                    ]
                }

                // No semantics block at all, and an attribute that happens to be called `nazev` —
                // the exact case a name-sniffing implementation passes and a declaration-reading
                // one must not.
                def entity region { displayLabel: { cs: "Oblast" },
                    attributes: [ def attribute nazev { type: text } ]
                }
                """.trimIndent()

        val db =
            "model/db/facet.ttrm" to
                """
                model db

                def table invoice {
                    semantics { name: nazev, code: cislo },
                    columns: [
                        def column nazev { type: text },
                        def column cislo { type: text },
                    ]
                }
                """.trimIndent()

        test("an entity's declared name and code become FULL attribute refs, with its declared code_pattern") {
            val facts = targets(modelOf(er)).getValue("er.entity.store")

            // `er.entity.store.nazev`, not `nazev`: the resolver compares these to
            // `Attribution.attribute_ref`, and a local name would make every consumer re-join.
            facts.nameRef shouldBe "er.entity.store.nazev"
            facts.codeRef shouldBe "er.entity.store.kod"
            facts.codeFormat shouldBe "^S[0-9]{4}$"
        }

        test("D4 — a letter-only code is expressible, and the archive carries the pattern verbatim") {
            val facts = targets(modelOf(er)).getValue("er.entity.promotion")

            facts.codeRef shouldBe "er.entity.promotion.promo_id"
            facts.codeFormat shouldBe "^[A-P]{16}$"
            Regex(facts.codeFormat!!).matches("AAAAAAAABAAAAAAA") shouldBe true
        }

        test("F6 — a period code_format MASK reaches the archive as a REGEX, never as the mask") {
            val facts = targets(modelOf(er))

            // Was `yyyyMM`, which the resolver compiled as a regex matching the letters `yyyyMM`.
            val month = facts.getValue("er.entity.month")
            month.codeRef shouldBe "er.entity.month.code"
            month.codeFormat shouldBe """^\d{6}$"""
            Regex(month.codeFormat!!).matches("202501") shouldBe true
            Regex(month.codeFormat!!).matches("yyyyMM") shouldBe false

            val week = facts.getValue("er.entity.week")
            week.codeFormat shouldBe """^\d{4}\-\d{2}$"""
            Regex(week.codeFormat!!).matches("2025-07") shouldBe true
        }

        test("D4 — a declared code_pattern wins over the code attribute's mask") {
            targets(modelOf(er)).getValue("er.entity.quarter").codeFormat shouldBe "^[0-9]{4}Q[1-4]$"
        }

        test("a mask this translation does not understand gives NO regex, not a wrong one") {
            val facts = targets(modelOf(er)).getValue("er.entity.named_month")

            facts.codeRef shouldBe "er.entity.named_month.code"
            facts.codeFormat shouldBe null
        }

        test("the mask translation table (D4) — and every regex it emits compiles and matches its example") {
            mapOf(
                "yyyyMM" to ("""^\d{6}$""" to "202501"),
                "yyyyMMdd" to ("""^\d{8}$""" to "20250131"),
                "yyyy-MM" to ("""^\d{4}\-\d{2}$""" to "2025-01"),
                "yyyy.MM" to ("""^\d{4}\.\d{2}$""" to "2025.01"),
                "yyyyQ" to ("""^\d{5}$""" to "20251"),
                "yyyy/WW" to ("""^\d{4}\/\d{2}$""" to "2025/07"),
                "20yy" to ("""^20\d{2}$""" to "2025"),
                "yyyy MM" to ("""^\d{4}\ \d{2}$""" to "2025 01"),
            ).forEach { (mask, expected) ->
                val (regex, example) = expected
                withClue(mask) {
                    LexiconCompiler.maskToRegex(mask) shouldBe regex
                    Regex(regex).matches(example) shouldBe true
                }
            }
            // A letter outside the digit set, or a textual field, is not guessed at.
            listOf("", "MMMM yyyy", "MMM-yy", "yyyyQQQ", "G yyyy", "yyyyww", "EEE").forEach { mask ->
                withClue(mask) { LexiconCompiler.maskToRegex(mask) shouldBe null }
            }
        }

        test("a db table reaches the same facet through the columns branch") {
            val facts =
                targets(
                    modelOf(db),
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms: [ { text: "faktura", lang: cs } ]
                        target: db.dbo.invoice
                    """.trimIndent(),
                ).getValue("db.dbo.invoice")

            facts.nameRef shouldBe "db.dbo.invoice.nazev"
            facts.codeRef shouldBe "db.dbo.invoice.cislo"
            // The code column carries no period role and the block no pattern — absent, never a default.
            facts.codeFormat shouldBe null
        }

        test("a MEMBER carries no facet — it has no name column, it IS one") {
            val facts = targets(modelOf(er)).getValue("er.entity.store.nazev")

            facts.ownerRef shouldBe "er.entity.store"
            facts.nameRef shouldBe null
            facts.codeRef shouldBe null
            facts.codeFormat shouldBe null
        }

        test("an object with no semantics block gets nulls, not guesses from column names") {
            val facts = targets(modelOf(er)).getValue("er.entity.region")

            facts.nameRef shouldBe null
            facts.codeRef shouldBe null
            facts.codeFormat shouldBe null
        }

        test("a block the analyzer refuses reaches the compiler as NO facet, and the load says why") {
            // What the first version of this spec asserted with a hand-built `code:` naming a member
            // the entity does not have: the real producer never gets that far. The analyzer reports
            // TTR-SEM-212 and degrades the whole block, so the model carries no mention facet at all
            // — and the load error is what stops `ttr-lexicon build` (LexiconBuildCli: every
            // unrecognised model error is fatal).
            val result =
                load(
                    "model/er/bad.ttrm" to
                        """
                        model er
                        def entity store { displayLabel: { cs: "Prodejna" },
                            semantics { name: nazev, code: kod_ktery_neexistuje },
                            attributes: [ def attribute nazev { type: text } ]
                        }
                        """.trimIndent(),
                )

            result.errors.joinToString("\n") { it.message } shouldContain "TTR-SEM-212"
            val facts = targets(result.model.shouldNotBeNull()).getValue("er.entity.store")
            facts.nameRef shouldBe null
            facts.codeRef shouldBe null
        }

        test("the facet does not move contentHash — it is header facts, not vocabulary") {
            // Blanked, not deleted: a METADATA row's provenance carries its LINE, and the entry
            // table (which contentHash covers) would move for a reason that has nothing to do with
            // the facet.
            val silent =
                er.first to
                    er.second
                        .lines()
                        .joinToString("\n") { if (it.trimStart().startsWith("semantics { name:")) "" else it }

            fun hash(model: Model) =
                LexiconCompiler
                    .compile(
                        LexiconSources(area = LexiconArea(emptyList(), emptyList()), model = model),
                        ModelRefIndex.of(model),
                        snapshotHash,
                        builtAt,
                    ).lexicon.contentHash

            // One root for both loads: a model object's `sourceFile` is part of a METADATA row's
            // provenance, so two temp directories would move the hash for the same wrong reason.
            val root = Files.createTempDirectory("facet-hash")
            val withFacet = modelOf(er, root = root)
            val withoutFacet = modelOf(silent, root = root)
            targets(withoutFacet).getValue("er.entity.store").nameRef shouldBe null // the edit took
            hash(withFacet) shouldBe hash(withoutFacet)
        }
    })
