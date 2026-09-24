// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * RV-P3.0 T1 — declared matching profiles at the AUTHORING surface (RV-44; contracts §2 addendum;
 * `design/06-M-matching-profiles-options.md`).
 *
 * What this spec is for: the schema half must be right *before* an estate authors bulk content
 * against it (the sequencing reason `p3-0` runs before `p3-2`). So every clause of the addendum
 * that an author can get wrong is a case here, and each one names the clause it enforces.
 *
 * The one thing it does NOT test is scoring — that is the dispatcher's, in `tatrman-server`
 * (T4–T6). Here a profile is a parsed, validated fact and nothing more.
 */
class MatchProfileSpec :
    FunSpec({

        fun load(yaml: String) = LexiconValidator.loadDataFile(yaml, "profiles.lex.yaml")

        fun ok(yaml: String) = load(yaml).shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()

        fun rejected(yaml: String) = load(yaml).shouldBeInstanceOf<LexiconLoad.Rejected>()

        fun file(
            defaults: String = "",
            term: String,
        ) = """
            schema: ttr-lexicon/v1
            $defaults
            entries:
              - terms:
                  - $term
                target: er.Customer
            """.trimIndent()

        context("(a) `defaults.match` and per-term `match` parse per the addendum") {
            test("the addendum's own example — three strata on the file, one narrowing override") {
                val loaded =
                    ok(
                        """
                        schema: ttr-lexicon/v1
                        defaults:
                          lang: cs
                          match:
                            - { norm: canonical, exact: 1.00, typos: { distance: 1, penalty: 0.05 } }
                            - { norm: folded,    exact: 0.90 }
                            - { norm: lemma,     exact: 0.80 }
                        entries:
                          - terms:
                              - { text: "zákazník" }
                              - { text: "distribuční centrum", match: [ { norm: canonical, exact: 1.00 } ] }
                            target: er.Customer
                        """.trimIndent(),
                    ).value

                val inherited = loaded.entries.single().terms[0]
                inherited.matchProfile.shouldNotBeNull().rules shouldContainExactly
                    listOf(
                        NormRule(Norm.CANONICAL, exact = 1.00, typos = TyposRule(1, 0.05)),
                        NormRule(Norm.FOLDED, exact = 0.90),
                        NormRule(Norm.LEMMA, exact = 0.80),
                    )

                // The term's own `match` REPLACES the file default rather than merging into it:
                // an author narrowing one short code must not be left with the file's fuzz still on.
                val overridden = loaded.entries.single().terms[1]
                overridden.matchProfile.shouldNotBeNull().rules shouldContainExactly
                    listOf(NormRule(Norm.CANONICAL, exact = 1.00))
            }

            test("`tokens: {}` is a rule of its own — no `exact` needed") {
                val term =
                    ok(file(term = """{ text: "celkem", match: [ { norm: canonical, tokens: {} } ] }"""))
                        .value
                        .entries
                        .single()
                        .terms
                        .single()

                term.matchProfile.shouldNotBeNull().rules shouldContainExactly
                    listOf(NormRule(Norm.CANONICAL, tokens = true))
            }

            test("an empty `match:` list is not a profile") {
                rejected(file(term = """{ text: "zákazník", match: [] }""")).codes shouldBe
                    listOf(LexiconErrors.MISSING_REQUIRED)
            }

            test("a rule that declares no algorithm at all is inert, and rejected as such") {
                rejected(file(term = """{ text: "zákazník", match: [ { norm: folded } ] }""")).codes shouldBe
                    listOf(LexiconErrors.MISSING_REQUIRED)
            }

            test("unknown keys inside a rule are rejected — the profile shape is closed too") {
                rejected(
                    file(term = """{ text: "zákazník", match: [ { norm: folded, exact: 0.9, boost: 2 } ] }"""),
                ).codes shouldBe listOf(LexiconErrors.UNKNOWN_KEY)
            }
        }

        context("(b) ⚑M-1 — the norm vocabulary is closed, and the diagnostic names the set") {
            test("an unknown norm is RG-LEX-013") {
                val rejection = rejected(file(term = """{ text: "zákazník", match: [ { norm: stem, exact: 0.8 } ] }"""))
                rejection.codes shouldBe listOf(LexiconErrors.UNKNOWN_NORM)
                rejection.violations.single().message shouldBe
                    "unknown norm 'stem' — the set is closed: canonical | folded | lemma."
            }

            test("`verbatim` is not a norm — the omission is deliberate, not an oversight") {
                rejected(
                    file(term = """{ text: "DC", match: [ { norm: verbatim, exact: 1.00 } ] }"""),
                ).codes shouldBe listOf(LexiconErrors.UNKNOWN_NORM)
            }
        }

        context("(c) `typos` without a sibling `exact` on the same norm — the penalty needs its anchor") {
            test("same norm, no exact ⇒ RG-LEX-014") {
                rejected(
                    file(
                        term =
                            """{ text: "zákazník", match: [ { norm: folded, typos: { distance: 1, penalty: 0.05 } } ] }""",
                    ),
                ).codes shouldBe listOf(LexiconErrors.TYPOS_WITHOUT_EXACT)
            }

            test("an `exact` on a DIFFERENT norm does not anchor it") {
                rejected(
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms:
                          - text: "zákazník"
                            match:
                              - { norm: canonical, exact: 1.00 }
                              - { norm: folded, typos: { distance: 1, penalty: 0.05 } }
                        target: er.Customer
                    """.trimIndent(),
                ).codes shouldBe listOf(LexiconErrors.TYPOS_WITHOUT_EXACT)
            }
        }

        context("(d) `method:` AND `match:` on one node") {
            test("on one term ⇒ RG-LEX-015") {
                rejected(
                    file(
                        term = """{ text: "zákazník", method: TYPOS(1), match: [ { norm: canonical, exact: 1.0 } ] }""",
                    ),
                ).codes shouldBe listOf(LexiconErrors.METHOD_AND_MATCH)
            }

            test("in one `defaults` block ⇒ RG-LEX-015") {
                rejected(
                    """
                    schema: ttr-lexicon/v1
                    defaults:
                      method: TYPOS(1)
                      match: [ { norm: canonical, exact: 1.00 } ]
                    entries:
                      - terms: [ { text: "zákazník" } ]
                        target: er.Customer
                    """.trimIndent(),
                ).codes shouldBe listOf(LexiconErrors.METHOD_AND_MATCH)
            }

            test("across levels it is NOT an error — the term's own statement simply wins") {
                // A file default of `match:` with one term saying `method: EXACT` is ordinary
                // authoring: the term overrides, and it overrides WHOLE (no leftover fuzz).
                val terms =
                    ok(
                        """
                        schema: ttr-lexicon/v1
                        defaults:
                          match:
                            - { norm: canonical, exact: 1.00, typos: { distance: 1, penalty: 0.05 } }
                        entries:
                          - terms:
                              - { text: "zákazník" }
                              - { text: "DC", method: EXACT }
                            target: er.Customer
                        """.trimIndent(),
                    ).value.entries.single().terms

                terms[0].matchProfile.shouldNotBeNull()
                terms[0].method shouldBe MatchMethod.Typos(1)
                terms[1].matchProfile shouldBe null
                terms[1].method shouldBe MatchMethod.Exact
            }
        }

        context("(e) `method:` alone still valid — v1 files parse unchanged") {
            test("the pre-profile fixtures still load, and carry no authored profile") {
                fun fixture(path: String) =
                    requireNotNull(this::class.java.getResourceAsStream("/lexicon-schema-fixtures/$path")) {
                        "missing fixture: $path"
                    }.reader().readText()

                val loaded =
                    LexiconValidator
                        .loadDataFile(fixture("valid/values-with-defaults.lex.yaml"), "values-with-defaults.lex.yaml")
                        .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                        .value

                loaded.entries.flatMap { it.terms }.forEach { it.matchProfile shouldBe null }
                loaded.entries[0].terms[0].method shouldBe MatchMethod.Typos(1)
                loaded.entries[0].terms[1].method shouldBe MatchMethod.Exact
            }

            test("the schema id is untouched — profiles are additive to ttr-lexicon/v1") {
                LexiconValidator.LEXICON_SCHEMA_ID shouldBe "ttr-lexicon/v1"
            }
        }

        context("(f) bounds: scores in (0,1], distance ≥ 1, penalty > 0") {
            listOf(
                "exact above 1" to """{ text: "zákazník", match: [ { norm: canonical, exact: 1.4 } ] }""",
                "exact at 0" to """{ text: "zákazník", match: [ { norm: canonical, exact: 0 } ] }""",
                "exact negative" to """{ text: "zákazník", match: [ { norm: canonical, exact: -0.2 } ] }""",
                "exact not a number" to """{ text: "zákazník", match: [ { norm: canonical, exact: high } ] }""",
                "distance 0" to
                    """{ text: "zákazník", match: [ { norm: canonical, exact: 1.0, typos: { distance: 0, penalty: 0.05 } } ] }""",
                "distance fractional" to
                    """{ text: "zákazník", match: [ { norm: canonical, exact: 1.0, typos: { distance: 1.5, penalty: 0.05 } } ] }""",
                "penalty 0" to
                    """{ text: "zákazník", match: [ { norm: canonical, exact: 1.0, typos: { distance: 1, penalty: 0 } } ] }""",
            ).forEach { (name, term) ->
                test(name) {
                    rejected(file(term = term)).codes shouldBe listOf(LexiconErrors.SCORE_OUT_OF_RANGE)
                }
            }

            test("`exact: 1.0` and a distance of 4 are both in range — the sugar's 1..3 cap is the grammar's") {
                val term =
                    ok(
                        file(
                            term =
                                """{ text: "zákazník", match: [ { norm: canonical, exact: 1.0, typos: { distance: 4, penalty: 0.05 } } ] }""",
                        ),
                    ).value.entries.single().terms.single()

                term.matchProfile
                    .shouldNotBeNull()
                    .rules
                    .single()
                    .typos shouldBe TyposRule(4, 0.05)
                // …and the projection back to sugar clamps, because `TYPOS(4)` is unwritable.
                term.method shouldBe MatchMethod.Typos(3)
            }

            test("`typos` missing a key is a missing key, not an out-of-range one") {
                rejected(
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms:
                          - text: "zákazník"
                            match: [ { norm: canonical, exact: 1.0, typos: { distance: 1 } } ]
                        target: er.Customer
                    """.trimIndent(),
                ).codes shouldBe listOf(LexiconErrors.MISSING_REQUIRED)
            }
        }

        context("(g) ⚑M-4 — the short-term guard warns; the build still succeeds") {
            test("a ≤3-char term with an authored typos rule loads, with a warning naming the guard") {
                val loaded =
                    ok(
                        file(
                            term =
                                """{ text: "DC", match: [ { norm: canonical, exact: 1.0, typos: { distance: 1, penalty: 0.05 } } ] }""",
                        ),
                    )

                loaded.warnings.map { it.code } shouldBe listOf(LexiconWarnings.SHORT_TERM_TYPOS_GUARD)
                loaded.warnings.single().message shouldBe
                    "\"DC\" is 3 characters or fewer, so the short-term guard suppresses its typos " +
                    "rule — a one-edit neighbourhood around a token this short reaches most of its " +
                    "siblings. The build succeeds and the matcher will not fuzz it; drop the rule, " +
                    "or lengthen the authored form."
            }

            test("`method: TYPOS(n)` sugar on a short term warns identically — sugar is not a loophole") {
                ok(file(term = """{ text: "5xx", method: TYPOS(1) }""")).warnings.map { it.code } shouldBe
                    listOf(LexiconWarnings.SHORT_TERM_TYPOS_GUARD)
            }

            test("an INHERITED file default warns too — the declaration is the file's, the risk is the term's") {
                ok(
                    """
                    schema: ttr-lexicon/v1
                    defaults: { method: TYPOS(1) }
                    entries:
                      - terms: [ { text: "středisko" }, { text: "5xx" }, { text: "DC" } ]
                        target: er.CostCenter
                    """.trimIndent(),
                ).warnings.map { it.provenance.line } shouldBe listOf(4, 4)
            }

            test("a short term with no fuzz is silent, and so is a long one with fuzz") {
                ok(file(term = """{ text: "DC", method: EXACT }""")).warnings shouldBe emptyList()
                ok(file(term = """{ text: "středisko", method: TYPOS(2) }""")).warnings shouldBe emptyList()
            }

            test("the guard measures the CANONICAL form, so casing and padding cannot dodge it") {
                ok(file(term = """{ text: "  Dc ", method: TYPOS(1) }""")).warnings.map { it.code } shouldBe
                    listOf(LexiconWarnings.SHORT_TERM_TYPOS_GUARD)
            }
        }

        context("M-T6 — the sugar table, byte-exact") {
            test("EXACT / TYPOS(d) / TOKENS expand exactly as the addendum states") {
                MatchProfile.ofSugar(MatchMethod.Exact).rules shouldContainExactly
                    listOf(NormRule(Norm.CANONICAL, exact = 1.00))
                MatchProfile.ofSugar(MatchMethod.Typos(2)).rules shouldContainExactly
                    listOf(NormRule(Norm.CANONICAL, exact = 1.00, typos = TyposRule(2, 0.05)))
                MatchProfile.ofSugar(MatchMethod.Tokens).rules shouldContainExactly
                    listOf(NormRule(Norm.CANONICAL, tokens = true))
            }

            test("the projection back to sugar round-trips for all three") {
                listOf(MatchMethod.Exact, MatchMethod.Typos(1), MatchMethod.Typos(3), MatchMethod.Tokens)
                    .forEach { MatchProfile.ofSugar(it).sugarMethod() shouldBe it }
            }

            test("a richer profile projects to the widest algorithm it admits") {
                MatchProfile(listOf(NormRule(Norm.FOLDED, exact = 0.9))).sugarMethod() shouldBe MatchMethod.Exact
                MatchProfile(
                    listOf(
                        NormRule(Norm.CANONICAL, exact = 1.0, typos = TyposRule(2, 0.05)),
                        NormRule(Norm.FOLDED, exact = 0.9),
                    ),
                ).sugarMethod() shouldBe MatchMethod.Typos(2)
                MatchProfile(
                    listOf(NormRule(Norm.CANONICAL, exact = 1.0), NormRule(Norm.LEMMA, tokens = true)),
                ).sugarMethod() shouldBe MatchMethod.Tokens
            }
        }

        context("skill triggers are ordinary vocabulary (RV-35) and take profiles too") {
            test("a trigger's `match:` parses, and the short-term guard reaches it") {
                val skill =
                    LexiconValidator
                        .loadSkillFile(
                            """
                            ---
                            schema: ttr-skill/v1
                            op: op:trend
                            triggers:
                              - { text: "vývoj", match: [ { norm: lemma, exact: 0.85 } ] }
                              - { text: "tr", method: TYPOS(1) }
                            version: 1
                            ---
                            Retrieval: group by the finest requested time grain.
                            """.trimIndent(),
                            "trend.md",
                        ).shouldBeInstanceOf<LexiconLoad.Ok<SkillDef>>()

                skill.value.triggers[0]
                    .matchProfile
                    .shouldNotBeNull()
                    .rules shouldContainExactly
                    listOf(NormRule(Norm.LEMMA, exact = 0.85))
                skill.warnings.map { it.code } shouldBe listOf(LexiconWarnings.SHORT_TERM_TYPOS_GUARD)
            }
        }

        context("the catalogue documents every code these rules can emit") {
            test("the four new error codes are in LexiconErrors.ALL") {
                LexiconErrors.ALL.shouldContainExactly(
                    listOf(
                        "RG-LEX-001",
                        "RG-LEX-002",
                        "RG-LEX-003",
                        "RG-LEX-004",
                        "RG-LEX-005",
                        "RG-LEX-006",
                        "RG-LEX-007",
                        "RG-LEX-008",
                        "RG-LEX-009",
                        "RG-LEX-010",
                        "RG-LEX-011",
                        "RG-LEX-012",
                        "RG-LEX-013",
                        "RG-LEX-014",
                        "RG-LEX-015",
                        "RG-LEX-016",
                        // RV-P3 review — the typos-budget bound (`LexiconErrors.TYPOS_BUDGET_EXHAUSTS_SCORE`).
                        // It shipped in the catalogue without this list being updated; the spec is a
                        // completeness check, so the list follows the catalogue.
                        "RG-LEX-017",
                        // LP (P2a T1/T2) — the `pred:` slice. The 03x band, not 018: the RV bands
                        // are grouped by the arc that opened them, and a reader who meets
                        // `RG-LEX-030` in a log should be able to tell which feature to read about.
                        "RG-LEX-030",
                        "RG-LEX-031",
                    ),
                )
            }

            test("warnings live in their own 1xx band, so a code alone says whether it stops a build") {
                LexiconWarnings.ALL shouldContainExactly listOf("RG-LEX-101")
                LexiconWarnings.ALL.none { it in LexiconErrors.ALL } shouldBe true
            }
        }
    })
