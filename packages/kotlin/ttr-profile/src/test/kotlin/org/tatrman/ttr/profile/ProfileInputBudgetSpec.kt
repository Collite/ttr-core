// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.yaml.MAX_YAML_CODE_POINTS

/**
 * MW S4.2a T3 (✅D-MW-93, as ruled by ✅D-MW-96; review-370 B1): the canonical budget ([MAX_YAML_CODE_POINTS]) is
 * per file. A dialect input over it is refused by [ForeignTree.read], before it is decoded and before any profile runs,
 * with the budget's code and message at 1:1 of its storage label; inputs that each fit reach the engine whatever their
 * total. Each case goes through the public `ForeignTree.read` → `ProfileEngine.apply` path a loader uses.
 */
class ProfileInputBudgetSpec :
    FunSpec({
        /** A dialect file of exactly [codePoints] code points: one entity, padded with a comment. */
        fun text(
            entity: String,
            codePoints: Int,
        ): String {
            val head = "entity:\n  $entity: {}\n#"
            return head + "x".repeat(codePoints - head.length)
        }

        val profile = recordProfile(entityOps)

        test("files that each fit reach the engine although they total over the budget: two small documents") {
            val half = MAX_YAML_CODE_POINTS / 2
            val trees =
                listOf(
                    ForeignTree.read(text("A", half), "a.yaml", "/store/a.yaml"),
                    ForeignTree.read(text("B", half + 1), "b.yaml", "/store/b.yaml"),
                )
            val documents = ProfileEngine.apply(trees, listOf(profile), null, perFile = true).documents
            documents.map { it.file } shouldContainExactly listOf("a.yaml", "b.yaml")
            documents.forEach { it.write().length shouldBeLessThan 1_000 }
            documents[0].write() shouldContain "A"
            documents[1].write() shouldContain "B"
        }

        test("one file over the budget is refused at read, before any profile runs, at 1:1 of its storage label") {
            val error =
                shouldThrow<ProfileException> {
                    ForeignTree.read(text("A", MAX_YAML_CODE_POINTS + 1), "big.yaml", "/store/big.yaml")
                }
            error.code shouldBe "ys/profile-invalid"
            error.location.file shouldBe "/store/big.yaml"
            error.location.line shouldBe 1
            error.location.column shouldBe 1
            error.detail shouldBe "Canonical document /store/big.yaml exceeds $MAX_YAML_CODE_POINTS code points"
        }

        test("a file of exactly the budget is read and runs: its document is emitted") {
            val tree = ForeignTree.read(text("A", MAX_YAML_CODE_POINTS), "edge.yaml", "/store/edge.yaml")
            val documents = ProfileEngine.apply(listOf(tree), listOf(profile), null, perFile = true).documents
            documents.map { it.file } shouldContainExactly listOf("edge.yaml")
            documents.single().write() shouldContain "A"
        }
    })
