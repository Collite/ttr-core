// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * RV-P1.1 T3 — the fixture tree IS the schema contract.
 *
 * Every file under `lexicon-schema-fixtures/valid/` must load; every file under
 * `invalid/` must be rejected **with the specific code its header comment names**. A
 * rejection with the wrong code is a failure: the error catalogue (docs) promises callers
 * a stable code per condition, so "it failed somehow" is not the assertion.
 */
class LexiconSchemaFixturesSpec :
    FunSpec({

        fun fixture(path: String): String =
            requireNotNull(this::class.java.getResourceAsStream("/lexicon-schema-fixtures/$path")) {
                "missing fixture: $path"
            }.reader().readText()

        context("valid fixtures load") {
            test("minimal alias file — every optional key omitted") {
                val load =
                    LexiconValidator.loadDataFile(
                        fixture("valid/aliases-minimal.lex.yaml"),
                        "aliases-minimal.lex.yaml",
                    )

                val file = load.shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>().value
                file.entries.size shouldBe 1
                file.entries.single().target shouldBe "er.CostCenter"
                val term =
                    file.entries
                        .single()
                        .terms
                        .single()
                term.text shouldBe "středisko"
                term.lang shouldBe Lang.CS
                // No file defaults and no own method: the entry-level fallback applies.
                term.method shouldBe MatchMethod.Exact
            }

            test("values file — per-file defaults apply, own keys win") {
                val load =
                    LexiconValidator.loadDataFile(
                        fixture("valid/values-with-defaults.lex.yaml"),
                        "values-with-defaults.lex.yaml",
                    )

                val file = load.shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>().value
                file.entries.size shouldBe 2

                val inherited = file.entries[0].terms[0]
                inherited.lang shouldBe Lang.CS // from defaults
                inherited.method shouldBe MatchMethod.Typos(1) // from defaults

                val overridden = file.entries[0].terms[1]
                overridden.lang shouldBe Lang.EN // own key wins
                overridden.method shouldBe MatchMethod.Exact // own key wins

                file.entries[1].target shouldBe "md.dimension.Account.class.expense"
            }

            test("grounding trigger file — `ground:` targets load like any other entry (RV-42)") {
                val load =
                    LexiconValidator.loadDataFile(
                        fixture("valid/grounding-triggers.lex.yaml"),
                        "grounding-triggers.lex.yaml",
                    )

                val file = load.shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>().value
                file.entries.map { it.target } shouldBe
                    listOf("ground:chrono", "ground:money", "ground:geo")
                // Nothing about a grounding file is special-cased: per-file defaults and
                // per-term overrides work exactly as they do for aliases and values.
                val chrono = file.entries[0].terms
                chrono[0].lang shouldBe Lang.CS // from defaults
                chrono[0].method shouldBe MatchMethod.Typos(1) // from defaults
                chrono[4].lang shouldBe Lang.EN // own key wins
                chrono[5].method shouldBe MatchMethod.Tokens // "fiscal year" is multi-word
                file.entries[1].terms[0].method shouldBe MatchMethod.Exact // "Kč"
            }

            test("predicate trigger file — `pred:` targets load like any other entry (LP §3.3)") {
                val load =
                    LexiconValidator.loadDataFile(
                        fixture("valid/predicate-triggers.lex.yaml"),
                        "predicate-triggers.lex.yaml",
                    )

                val file = load.shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>().value
                file.entries.map { it.target } shouldBe
                    listOf("pred:starts_with", "pred:contains", "pred:not_contains", "pred:not_starts_with")
                // Nothing about a predicate file is special-cased either: defaults and per-term
                // overrides work exactly as they do for aliases, values and grounding.
                val startsWith = file.entries[0].terms
                // Review-103 ruling 1: every `pred:` form is EXACT, the multi-word ones included.
                startsWith[0].method shouldBe MatchMethod.Exact // "začínající na", from defaults
                startsWith[2].lang shouldBe Lang.EN
                // `s textem` OPENS with a function word and is legal: RG-LEX-031 refuses a phrase
                // only when EVERY word in it is a function word.
                file.entries[1]
                    .terms[1]
                    .text shouldBe "s textem"
            }

            test("skill file — frontmatter typed, body kept verbatim") {
                val load = LexiconValidator.loadSkillFile(fixture("valid/skill-trend.md"), "skill-trend.md")

                val skill = load.shouldBeInstanceOf<LexiconLoad.Ok<SkillDef>>().value
                skill.opId shouldBe "op:trend"
                skill.triggers.size shouldBe 3
                skill.triggers[1].lang shouldBe Lang.CS_EN
                skill.triggers[1].method shouldBe MatchMethod.Exact
                skill.requires shouldBe listOf("time-grain")
                skill.version shouldBe 1
                skill.body shouldContainText "line chart by default"
                // The body is behavior, not data — it must survive unparsed.
                skill.body shouldContainText "≥2"
            }
        }

        context("invalid fixtures are rejected with their named code") {
            val dataFiles =
                listOf(
                    "unknown-method.lex.yaml" to "RG-LEX-001",
                    "missing-target.lex.yaml" to "RG-LEX-002",
                    "typos-without-distance.lex.yaml" to "RG-LEX-003",
                    "duplicate-term.lex.yaml" to "RG-LEX-006",
                    "unknown-top-level-key.lex.yaml" to "RG-LEX-007",
                    "schema-id-mismatch.lex.yaml" to "RG-LEX-008",
                    "bad-lang.lex.yaml" to "RG-LEX-010",
                    // RV-P1.6 T1 (RV-42) — the `ground:` kind vocabulary is closed.
                    "ground-unknown-kind.lex.yaml" to "RG-LEX-012",
                    // LP (P2a T1/T2) — the `pred:` kind vocabulary is closed, and a `pred:` form
                    // must be able to carry a trigger.
                    "pred-unknown-kind.lex.yaml" to "RG-LEX-030",
                    "pred-weak-form.lex.yaml" to "RG-LEX-031",
                    // Review-103 N5 / F1 — a phrase of function words only, and a form wider than
                    // any window the resolver looks at.
                    "pred-stopword-phrase.lex.yaml" to "RG-LEX-031",
                    "pred-wide-form.lex.yaml" to "RG-LEX-032",
                )

            dataFiles.forEach { (name, code) ->
                test("$name → $code") {
                    val load = LexiconValidator.loadDataFile(fixture("invalid/$name"), name)

                    val rejected = load.shouldBeInstanceOf<LexiconLoad.Rejected>()
                    rejected.codes shouldContain code
                    rejected.violations
                        .first { it.code == code }
                        .provenance.file shouldBe name
                }
            }

            val skillFiles =
                listOf(
                    "skill-op-not-prefixed.md" to "RG-LEX-004",
                    "skill-zero-triggers.md" to "RG-LEX-005",
                    "skill-missing-frontmatter.md" to "RG-LEX-009",
                    // RV-P1.6 T1 — a `ground:` ref in a skill's `op` is the wrong file
                    // kind: grounding triggers are data entries, not markdown bodies.
                    "skill-ground-target.md" to "RG-LEX-004",
                )

            skillFiles.forEach { (name, code) ->
                test("$name → $code") {
                    val load = LexiconValidator.loadSkillFile(fixture("invalid/$name"), name)

                    val rejected = load.shouldBeInstanceOf<LexiconLoad.Rejected>()
                    rejected.codes shouldContain code
                }
            }
        }

        context("provenance points at the authored line") {
            test("each term carries the line its text was written on") {
                val load =
                    LexiconValidator.loadDataFile(
                        fixture("valid/values-with-defaults.lex.yaml"),
                        "values-with-defaults.lex.yaml",
                    )

                val file = load.shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>().value
                // See the fixture: the three terms sit on lines 10, 11 and 14.
                file.entries[0]
                    .terms[0]
                    .provenance.line shouldBe 10
                file.entries[0]
                    .terms[1]
                    .provenance.line shouldBe 11
                file.entries[1]
                    .terms[0]
                    .provenance.line shouldBe 14
            }
        }
    })
