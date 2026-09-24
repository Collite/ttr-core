// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TermNormalizer

/**
 * LP-P2a T3 (contracts §3.3) — the string-predicate slice, at build level.
 *
 * The third application of the RV-35 skill-entry pattern, after operators and grounding: trigger
 * words become ordinary lexicon entries under a prefix-derived target class, compiled through
 * exactly the path an estate's own `.lex.yaml` takes. What makes THIS slice different from the
 * other two is what it competes with — a predicate form is matched against the running text around
 * a quoted literal, so the guard on which words may be forms (RG-LEX-031) is part of the contract
 * rather than a nicety.
 */
class PredicateStdlibSpec :
    FunSpec({

        val snapshotHash = "sha256:" + "7a".repeat(32)
        val builtAt = "2026-09-24T00:00:00Z"

        fun compileStdlib(extra: List<LexiconDataFile> = emptyList()) =
            LexiconCompiler.compile(
                LexiconSources(area = LexiconArea(LexiconStdlib.predicateSlices() + extra, emptyList())),
                ModelRefIndex.EMPTY,
                snapshotHash,
                builtAt,
            )

        fun dataFile(
            name: String,
            yaml: String,
        ): LexiconDataFile =
            LexiconValidator
                .loadDataFile(yaml, name)
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                .value

        test("the slice covers all five predicates — no kind ships without vocabulary") {
            LexiconStdlib
                .predicateSlices()
                .flatMap { file -> file.entries.map { it.target } }
                .toSet() shouldContainExactlyInAnyOrder
                LexiconValidator.PREDICATE_KINDS.map { "pred:$it" }
        }

        test("each predicate carries at least three forms in BOTH languages") {
            // Czech inflects and English does not, so a per-language floor is the only version of
            // "enough forms" that means anything: three cs forms and one en form would be a slice
            // that works in one language and silently does not in the other.
            val byTargetAndLang =
                LexiconStdlib
                    .predicateSlices()
                    .flatMap { file -> file.entries.flatMap { entry -> entry.terms.map { entry.target to it } } }
                    .groupBy { (target, term) -> target to term.lang }

            for (kind in LexiconValidator.PREDICATE_KINDS) {
                for (lang in listOf(Lang.CS, Lang.EN)) {
                    withClue("pred:$kind forms in ${lang.wire}") {
                        byTargetAndLang["pred:$kind" to lang].orEmpty().size shouldBeGreaterThanOrEqual 3
                    }
                }
            }
        }

        test("compiling the slice alone yields STRING_PREDICATE rows and no warnings") {
            val result = compileStdlib()

            result.warnings shouldBe emptyList()
            result.lexicon.entries
                .map { it.targetClass }
                .toSet() shouldBe setOf(TargetClass.STRING_PREDICATE)
            // `pred:` refs never consult the model index — an EMPTY index must not make them dangle.
            result.lexicon.entries.size shouldBe
                LexiconStdlib.predicateSlices().sumOf { file -> file.entries.sumOf { it.terms.size } }
            // Bodies are an operator concept; a predicate has none.
            result.operators.operators shouldBe emptyMap()
            // And no `targets` entry: the map is MODEL_OBJECT refs only, so a class that is not a
            // model object must leave it empty rather than claim an `objectKind` for a behaviour.
            result.lexicon.targets shouldBe emptyMap()
        }

        test("multi-word forms are TOKENS, single words EXACT (§3.3)") {
            val byTerm = compileStdlib().lexicon.entries.associateBy { it.termNormalized }

            // A phrase may be separated in a real question and its order is not fixed.
            listOf("začínající na", "s prefixem", "starts with", "not containing").forEach { form ->
                withClue(form) {
                    byTerm.getValue(TermNormalizer.normalize(form)).method shouldBe MatchMethod.Tokens.wire
                }
            }
            // A single word gets no typo budget: these compete with entity names for the same span.
            listOf("obsahuje", "neobsahující", "prefix", "exactly").forEach { form ->
                withClue(form) {
                    byTerm.getValue(TermNormalizer.normalize(form)).method shouldBe MatchMethod.Exact.wire
                }
            }
        }

        test("no shipped form is one the RG-LEX-031 guard would refuse") {
            // `predicateSlices()` throws on a rejection, so the slice already passed the guard on
            // the way in. This says the same thing from the other side, where a reviewer can see
            // it: the shipped file cannot quietly become the counter-example to its own rule.
            val singles =
                LexiconStdlib
                    .predicateSlices()
                    .flatMap { file -> file.entries.flatMap { it.terms } }
                    .filter { !TermNormalizer.normalize(it.text).contains(' ') }

            singles.forEach { term ->
                withClue(term.text) {
                    TermNormalizer.fold(term.text).length shouldBeGreaterThanOrEqual 2
                    org.tatrman.ttr.lexicon.LexiconStopWords
                        .isStop(term.text, term.lang) shouldBe false
                }
            }
        }

        test("an estate EXTENDS the shipped slice rather than replacing it") {
            val estate =
                dataFile(
                    "estate/predicates.lex.yaml",
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms: [ { text: "v popisu", lang: cs, method: TOKENS } ]
                        target: pred:contains
                    """.trimIndent(),
                )

            val byTerm = compileStdlib(listOf(estate)).lexicon.entries.associateBy { it.termNormalized }

            byTerm["v popisu"]?.targetRef shouldBe "pred:contains"
            byTerm["obsahuje"]?.targetRef shouldBe "pred:contains" // still there
        }

        test("a form may be BOTH a predicate and something else — overlap is the lattice's normal state") {
            // Same posture as grounding's operator overlap: two annotations on one span is what the
            // lattice is FOR (RV-9/33). The resolver narrows; the compiler does not choose.
            val estate =
                dataFile(
                    "estate/ops.lex.yaml",
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms: [ { text: "obsahuje", lang: cs } ]
                        target: op:show
                    """.trimIndent(),
                )

            compileStdlib(listOf(estate))
                .lexicon.entries
                .filter { it.termNormalized == "obsahuje" }
                .map { it.targetClass } shouldContainExactlyInAnyOrder
                listOf(TargetClass.STRING_PREDICATE, TargetClass.OPERATOR)
        }

        test("a pred: form colliding with a model name raises nothing — the same boundary ground: has") {
            // LP-P2a T6. `RG-LEXC-004` takes MODEL_OBJECT rows only (see `collisionWarnings`), so a
            // slice form that folds to an entity's name is not reported — exactly as a `ground:`
            // trigger colliding with a column label is not, and for the same reason: the two are
            // different species at runtime, and the Binder already keeps a behaviour ref and a
            // model ref apart. Pinned so a later change to the warning's scope is a decision
            // somebody makes rather than one that happens.
            val estate =
                dataFile(
                    "estate/names.lex.yaml",
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms: [ { text: "obsahuje", lang: cs } ]
                        target: er.entity.thing
                    """.trimIndent(),
                )

            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area = LexiconArea(LexiconStdlib.predicateSlices() + estate, emptyList()),
                    ),
                    ModelRefIndex { if (it == "er.entity.thing") TargetClass.MODEL_OBJECT else null },
                    snapshotHash,
                    builtAt,
                )

            result.warnings shouldBe emptyList()
            result.lexicon.entries
                .filter { it.termNormalized == "obsahuje" }
                .map { it.targetClass } shouldContainExactlyInAnyOrder
                listOf(TargetClass.STRING_PREDICATE, TargetClass.MODEL_OBJECT)
        }

        test("the whole build layers the slice in, beside grounding and the operator stdlib") {
            // The slice is only worth shipping if `LexiconBuild` actually reaches for it; a loader
            // nothing calls would pass every test above.
            val result =
                LexiconCompiler.compile(
                    LexiconSources(
                        area =
                            LexiconArea(
                                LexiconStdlib.groundingSlices() + LexiconStdlib.predicateSlices(),
                                LexiconStdlib.skills(),
                            ),
                    ),
                    ModelRefIndex.EMPTY,
                    snapshotHash,
                    builtAt,
                )

            result.lexicon.entries
                .map { it.targetClass }
                .toSet() shouldContainExactly
                setOf(TargetClass.GROUNDING_TRIGGER, TargetClass.STRING_PREDICATE, TargetClass.OPERATOR)
        }
    })
