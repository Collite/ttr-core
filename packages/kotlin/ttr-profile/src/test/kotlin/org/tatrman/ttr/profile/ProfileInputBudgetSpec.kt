// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.yaml.MAX_YAML_CODE_POINTS

/**
 * MW S4.2a T1 (b) (✅D-MW-93, S4.2 Found (b)): a run whose dialect inputs total more than [MAX_YAML_CODE_POINTS] code
 * points is refused before the engine does any work, with the canonical budget's code and message, at the largest
 * input. The profile's path overlay does not exist: had the engine started, that would have been the error.
 */
class ProfileInputBudgetSpec :
    FunSpec({
        val profile =
            ProfileReader.read(
                "profile: budget/1\ngrammar: '>=0.18 <1.0'\noverlay: ./missing-overlay.yaml\noperations:\n" +
                    "  - {op: package, mapping: {}, default: demo}\n" +
                    "  - {op: kinds, at: /entity, mapping: {entity: entity}}\n",
                "budget.yaml",
            )

        /** A document of exactly [codePoints] code points (no single file over the per-document limit). */
        fun input(
            file: String,
            codePoints: Int,
        ): ForeignTree {
            val head = "entity: {E: {}}\n#"
            return ForeignTree.read(head + "x".repeat(codePoints - head.length), file)
        }

        test("6 000 001 input code points in one run are refused before the engine, at the largest input") {
            val half = MAX_YAML_CODE_POINTS / 2
            val error =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(listOf(input("small.yaml", half), input("large.yaml", half + 1)), listOf(profile))
                }
            error.code shouldBe "ys/profile-invalid"
            error.location.file shouldBe "large.yaml"
            error.location.line shouldBe 1
            error.location.column shouldBe 1
            error.detail shouldBe "Canonical document large.yaml exceeds $MAX_YAML_CODE_POINTS code points"
        }

        test("exactly the budget is not refused by the guard (the engine then reports the missing overlay)") {
            val half = MAX_YAML_CODE_POINTS / 2
            val error =
                shouldThrow<ProfileException> {
                    ProfileEngine.apply(listOf(input("a.yaml", half), input("b.yaml", half)), listOf(profile))
                }
            (error.detail.contains("exceeds")) shouldBe false
        }
    })
