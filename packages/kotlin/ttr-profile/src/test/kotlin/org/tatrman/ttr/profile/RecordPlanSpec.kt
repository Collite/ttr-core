// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecordPlanSpec :
    FunSpec({
        test("record discovery resolves nested kinds before their parent op and consumes identities only") {
            val tree = ForeignTree.read("entity:\n  Customer:\n    columns:\n      Code: {typ: string}\n")
            val profile =
                recordProfile(
                    """
                    - {op: kinds, in: entity, at: /columns, mapping: {c: attribute}}
                    - {op: kinds, at: /entity, mapping: {e: entity}}
                    """.trimIndent(),
                )
            val plan = RecordPlan(tree, profile.operations.map(::PlannedOp))
            plan.records.map { it.id } shouldBe listOf("Customer", "Code")
            plan.records.last().parent shouldBe plan.records.first()
            val unknown = shouldThrow<ProfileException> { plan.consumption.check(tree.root) }
            unknown.code shouldBe "ys/unknown-key"
            unknown.location.path shouldBe "/entity/Customer/columns/Code/typ"
            val value = JsonPointer("/entity/Customer/columns/Code/typ").resolve(tree.root)!!
            plan.consumption.consume(value)
            plan.consumption.check(tree.root)
            plan.conflicts.claim(plan.records.last(), listOf("semantics"), value)
            shouldThrow<ProfileException> {
                plan.conflicts.claim(
                    plan.records.last(),
                    listOf("semantics", "role"),
                    value,
                )
            }.code shouldBe "ys/profile-conflict"
        }
        test("the record plan refuses an unmapped discriminator, missing identity and repeated record discovery") {
            val profile = recordProfile("- {op: kinds, at: /items, kind: /typ, id: /id, mapping: {e: entity}}")
            val unknown =
                shouldThrow<ProfileException> {
                    RecordPlan(ForeignTree.read("items: [{id: A, typ: other}]"), profile.operations.map(::PlannedOp))
                }
            unknown.code shouldBe "ys/unknown-kind"
            unknown.location.path shouldBe "/items/0/typ"
            shouldThrow<ProfileException> {
                RecordPlan(ForeignTree.read("items: [{typ: e}]"), profile.operations.map(::PlannedOp))
            }.code shouldBe "ys/record-id-missing"
            val mapping = recordProfile("- {op: kinds, at: /entity, mapping: {e: entity}}")
            shouldThrow<ProfileException> {
                RecordPlan(
                    ForeignTree.read("entity: {A: {}}"),
                    (mapping.operations + mapping.operations).map(::PlannedOp),
                )
            }.code shouldBe "ys/profile-conflict"
        }
    })
