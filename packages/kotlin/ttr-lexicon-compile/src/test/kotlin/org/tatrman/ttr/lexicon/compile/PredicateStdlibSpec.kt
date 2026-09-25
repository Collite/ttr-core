// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.LexiconStopWords
import org.tatrman.ttr.lexicon.MatchMethod
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TermNormalizer
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import java.nio.file.Files
import java.time.Instant

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

        /** Every shipped form with its target, in file order. */
        fun shipped() =
            LexiconStdlib
                .predicateSlices()
                .flatMap { file -> file.entries.flatMap { entry -> entry.terms.map { entry.target to it } } }

        /** The target each (normalized form, lang) compiles to. */
        fun targetOf(
            form: String,
            lang: Lang,
        ): String? =
            shipped()
                .filter { (_, term) -> TermNormalizer.normalize(term.text) == TermNormalizer.normalize(form) }
                .filter { (_, term) -> term.lang == lang }
                .map { it.first }
                .singleOrNull()

        test("the slice covers every predicate, the three negations included — no kind ships without vocabulary") {
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

        test("EVERY form is EXACT, single- and multi-word alike (review-103 ruling 1)") {
            // Was: "multi-word forms are TOKENS" (§3.3). A TOKENS row is scored over the QUERY's
            // tokens, so the one-word window `názvem` matched *s názvem přesně* and fired
            // `pred:equals` on its own (F1). The windows are contiguous and at most three words
            // wide, so TOKENS bought no word-order freedom the resolver could use — only fragments.
            val rows = compileStdlib().lexicon.entries

            rows.size shouldBeGreaterThanOrEqual 1
            rows.filter { it.method != MatchMethod.Exact.wire }.map { it.termNormalized } shouldBe emptyList()
            // Pinned on the phrases that used to be TOKENS, so the assertion above cannot pass
            // vacuously on a slice that lost them.
            val byTerm = rows.associateBy { it.termNormalized }
            listOf("začínající na", "s prefixem", "starts with", "not containing", "s názvem přesně").forEach { form ->
                withClue(form) {
                    byTerm.getValue(TermNormalizer.normalize(form)).method shouldBe MatchMethod.Exact.wire
                }
            }
        }

        test("no shipped form is one RG-LEX-031 or RG-LEX-032 would refuse") {
            // `predicateSlices()` throws on a rejection, so the slice already passed both guards on
            // the way in. This says the same thing from the other side, where a reviewer can see
            // it: the shipped file cannot quietly become the counter-example to its own rules.
            shipped().forEach { (_, term) ->
                val tokens = TermNormalizer.normalize(term.text).split(' ')
                withClue(term.text) {
                    tokens.size shouldBeLessThanOrEqual LexiconValidator.MAX_PREDICATE_FORM_TOKENS
                    if (tokens.size == 1) {
                        TermNormalizer.fold(term.text).length shouldBeGreaterThanOrEqual 2
                        LexiconStopWords.isStop(term.text, term.lang) shouldBe false
                    } else {
                        tokens.all { LexiconStopWords.isStop(it, term.lang) } shouldBe false
                    }
                }
            }
        }

        test("F17 — the natural English phrasings ship, including the docs' own example") {
            // `language-reference.md` leads with *Show stores starting with "Abl"*, and the slice
            // used to ship only *starts with* / *beginning with*: `starting` is three edits from
            // `starts`, and the bare `with` ties starts_with with ends_with, so the example ran
            // as `contains`.
            listOf("starting with", "start with", "begin with", "begins with", "starts with", "beginning with")
                .forEach { form -> withClue(form) { targetOf(form, Lang.EN) shouldBe "pred:starts_with" } }
            listOf("end with", "ending with", "ends with", "ending in")
                .forEach { form -> withClue(form) { targetOf(form, Lang.EN) shouldBe "pred:ends_with" } }
        }

        test("F12 — a negated phrase is its negation's form, never its positive's (D1)") {
            mapOf(
                "not starting with" to "pred:not_starts_with",
                "not beginning with" to "pred:not_starts_with",
                "not ending with" to "pred:not_ends_with",
                "not containing" to "pred:not_contains",
                "not equal to" to "pred:not_equals",
            ).forEach { (form, target) -> withClue(form) { targetOf(form, Lang.EN) shouldBe target } }
            // Czech negates morphologically, so the `ne-` forms are listed one by one.
            mapOf(
                "nezačínající na" to "pred:not_starts_with",
                "nezačíná na" to "pred:not_starts_with",
                "nezačínají na" to "pred:not_starts_with",
                "nekončící na" to "pred:not_ends_with",
                "nekončí na" to "pred:not_ends_with",
                "neobsahující" to "pred:not_contains",
                "nerovná se" to "pred:not_equals",
            ).forEach { (form, target) -> withClue(form) { targetOf(form, Lang.CS) shouldBe target } }
        }

        test("F12 — the Czech participles ship in every case §3.3 promised, positive and negated") {
            // *firem neobsahujících "s.r.o."* used to find no trigger at all and fall to the name
            // default, `contains` — the OPPOSITE filter.
            val endings = listOf("ící", "ícího", "ícímu", "ícím", "ících", "ícími")
            mapOf(
                "obsahuj" to ("" to "pred:contains"),
                "neobsahuj" to ("" to "pred:not_contains"),
                "začínaj" to (" na" to "pred:starts_with"),
                "nezačínaj" to (" na" to "pred:not_starts_with"),
                "konč" to (" na" to "pred:ends_with"),
                "nekonč" to (" na" to "pred:not_ends_with"),
            ).forEach { (stem, tailAndTarget) ->
                val (tail, target) = tailAndTarget
                endings.forEach { ending ->
                    val form = "$stem$ending$tail"
                    withClue(form) { targetOf(form, Lang.CS) shouldBe target }
                }
            }
        }

        test("the bare *s názvem* / *named* are NOT forms — only the `exactly` phrasings mean equals") {
            // §3.3 put *přesně*/*exactly* into the equals forms precisely so that a name quoted
            // after *named* keeps the name default. A form for the bare phrase would undo that.
            targetOf("s názvem", Lang.CS) shouldBe null
            targetOf("názvem", Lang.CS) shouldBe null
            targetOf("named", Lang.EN) shouldBe null
            targetOf("s názvem přesně", Lang.CS) shouldBe "pred:equals"
            targetOf("named exactly", Lang.EN) shouldBe "pred:equals"
        }

        test("an estate EXTENDS the shipped slice rather than replacing it") {
            val estate =
                dataFile(
                    "estate/predicates.lex.yaml",
                    """
                    schema: ttr-lexicon/v1
                    entries:
                      - terms: [ { text: "v popisu", lang: cs, method: EXACT } ]
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
            // nothing calls would pass every test above. So this goes through `LexiconBuild.run`
            // itself (review-103 N4) — hand-assembling the three stdlib parts and calling the
            // compiler would pass even if the build stopped layering one of them in.
            val empty =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "t"),
                    version = ModelVersion("v1", Instant.EPOCH),
                    schemas = emptyMap(),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val repo = Files.createTempDirectory("pred-build") // no lexicon/, no model/: stdlib only

            val outcome = LexiconBuild.run(repo, empty, snapshotHash, builtAt, "ttr-lexicon-compile/test")

            outcome.ok shouldBe true
            val entries = outcome.result.lexicon.entries
            entries
                .map { it.targetClass }
                .toSet() shouldContainExactly
                setOf(TargetClass.GROUNDING_TRIGGER, TargetClass.STRING_PREDICATE, TargetClass.OPERATOR)
            outcome.result.operators.operators.keys shouldContainExactlyInAnyOrder
                LexiconStdlib.OPERATORS.map { "op:$it" }
            entries.filter { it.targetClass == TargetClass.STRING_PREDICATE }.map { it.targetRef }.toSet() shouldBe
                LexiconValidator.PREDICATE_KINDS.map { "pred:$it" }.toSet()
        }
    })
