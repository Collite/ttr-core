// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

internal fun recordProfile(operations: String): ProfileDefinition =
    ProfileReader.read("profile: demo-cs/1\ngrammar: '>=0.22 <1.0'\noperations:\n$operations\n", "profile.yaml")

class RecordOperandsSpec :
    FunSpec({
        test("record scope is required on writers, optional on kinds and ignore, and forbidden on package and codes") {
            recordProfile("  - {op: rename, in: entity, at: /nazev, to: {kind: entity, property: description}}")
                .operations.size shouldBe 1
            recordProfile("  - {op: kinds, in: entity, at: /sloupce, mapping: {sloupec: attribute}}")
                .operations.size shouldBe 1
            recordProfile("  - {op: ignore, in: entity, at: /poznamka}").operations.size shouldBe 1
            listOf("package", "codes").forEach { op ->
                val error =
                    shouldThrow<ProfileException> {
                        recordProfile("  - op: $op\n    in: entity\n    mapping: {}")
                    }
                error.code shouldBe "ys/profile-invalid"
                error.location.path shouldBe "/operations/0/in"
                error.location.line shouldBe 5
                error.location.column shouldBe 5
            }
            listOf("rename", "values", "shape", "nest", "unnest", "link").forEach { op ->
                val extra =
                    when (op) {
                        "values" -> ", mapping: {}"
                        "shape" -> ", template: x"
                        "link" -> ", by: path"
                        else -> ""
                    }
                shouldThrow<ProfileException> {
                    recordProfile("  - {op: $op, at: /x, to: {kind: entity, property: description}$extra}")
                }.code shouldBe "ys/profile-invalid"
            }
        }

        test("multi-kind records need a discriminator and accept explicit record identity pointers") {
            shouldThrow<ProfileException> {
                recordProfile("  - {op: kinds, at: /records, mapping: {entita: entity, tabulka: table}}")
            }.code shouldBe "ys/profile-invalid"
            recordProfile(
                "  - {op: kinds, at: /records, kind: /typ, id: /nazev, mapping: {entita: entity, tabulka: table}}",
            ).operations.size shouldBe 1
            recordProfile("  - {op: kinds, at: /records, mapping: {entita: entity}}")
                .operations.size shouldBe 1
            ProfileResolver(
                java.nio.file.Path
                    .of("."),
                emptyMap(),
            ).resolve(null).single().operations.size shouldBe 0
        }
    })
