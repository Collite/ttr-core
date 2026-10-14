// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.lexicon.compile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.lexicon.Lang
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.SkillDef
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.TermNormalizer

/**
 * RV-P1.3 T1 — the operator stdlib's build-level gate, written before the six files (eight since
 * ttr-core#178 added op:count and op:avg).
 *
 * The collision rule is the load-bearing one: two operators that answer to the same word make the
 * lattice ambiguous for every question containing it, and no downstream layer can un-do that.
 */
class LexiconStdlibSpec :
    FunSpec({

        /** `cs|en` overlaps both single languages — otherwise a collision hides behind a lang label. */
        fun overlaps(
            a: Lang,
            b: Lang,
        ): Boolean = a == b || a == Lang.CS_EN || b == Lang.CS_EN

        test("the eight operators are all present, and nothing else is") {
            // The six ruled at ⚑RV-2, then op:count and op:avg (ttr-core#178).
            LexiconStdlib.skills().map { it.opId } shouldContainExactly
                listOf(
                    "op:show",
                    "op:trend",
                    "op:compare",
                    "op:drilldown",
                    "op:top-n",
                    "op:share-of",
                    "op:count",
                    "op:avg",
                )
        }

        test("every frontmatter validates against ttr-skill/v1") {
            // LexiconStdlib.skills() throws on a rejection, so reaching here is the assertion; the
            // checks below are the ones a schema cannot make.
            LexiconStdlib.skills().forEach { skill ->
                skill.triggers.isNotEmpty() shouldBe true
                skill.version shouldBe 1
                skill.body.isNotBlank() shouldBe true
            }
        }

        test("no two operators answer to the same word in an overlapping language") {
            val triggers =
                LexiconStdlib.skills().flatMap { skill ->
                    skill.triggers.map { Triple(TermNormalizer.normalize(it.text), it.lang, skill.opId) }
                }

            val collisions =
                triggers.flatMapIndexed { i, a ->
                    triggers
                        .drop(i + 1)
                        .filter { b ->
                            a.first == b.first && a.third != b.third && overlaps(a.second, b.second)
                        }.map { b -> "${a.first}: ${a.third} vs ${b.third}" }
                }

            collisions shouldBe emptyList()
        }

        test("compiling the stdlib alone yields every operator and no warnings") {
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(emptyList(), LexiconStdlib.skills())),
                    ModelRefIndex.EMPTY,
                    "sha256:" + "34".repeat(32),
                    "2026-08-03T00:00:00Z",
                )

            result.warnings shouldBe emptyList()
            result.operators.operators.keys
                .toList() shouldContainExactlyInAnyOrder
                LexiconStdlib.OPERATORS.map { "op:$it" }

            // Every trigger reaches the entry table as an OPERATOR-class row — the ordinary
            // lexicon path, not a special case (RV-35).
            result.lexicon.entries
                .map { it.targetClass }
                .toSet() shouldBe setOf(TargetClass.OPERATOR)
            result.lexicon.entries.size shouldBe LexiconStdlib.skills().sumOf { it.triggers.size }
        }

        test("H5's three triggers are live") {
            // The eval corpus's operator words — the reason this list exists at all.
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(emptyList(), LexiconStdlib.skills())),
                    ModelRefIndex.EMPTY,
                    "sha256:" + "34".repeat(32),
                    "2026-08-03T00:00:00Z",
                )
            val byTerm = result.lexicon.entries.associate { it.termNormalized to it.targetRef }

            byTerm["ukaž"] shouldBe "op:show"
            byTerm["vývoj"] shouldBe "op:trend"
            byTerm["porovnej"] shouldBe "op:compare"
        }

        test("an estate skill overrides the stdlib one for the same op") {
            val estate =
                LexiconStdlib
                    .skills()
                    .first { it.opId == "op:trend" }
                    .let { SkillDef(it.opId, it.triggers, it.requires, 2, "Estate trend behavior.", it.provenance) }

            // Loader order IS the precedence statement: stdlib first, estate second (P1.2 T5).
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(emptyList(), LexiconStdlib.skills() + estate)),
                    ModelRefIndex.EMPTY,
                    "sha256:" + "34".repeat(32),
                    "2026-08-03T00:00:00Z",
                )

            result.operators.operators
                .getValue("op:trend")
                .body shouldBe "Estate trend behavior."
            result.warnings.single().code shouldBe CompileWarning.OPERATOR_OVERRIDE
        }
    })
