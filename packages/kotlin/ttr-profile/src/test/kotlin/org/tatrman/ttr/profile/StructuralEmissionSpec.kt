// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import kotlin.io.path.readText

class StructuralEmissionSpec :
    FunSpec({
        test("structural executor emits the six authored files and carries ignores and code declarations") {
            val files =
                (1..6).map { i ->
                    demoRoot
                        .resolve("input")
                        .toFile()
                        .listFiles()!!
                        .single { it.name.startsWith("0$i-") }
                        .toPath()
                }
            val inputs =
                files.map { ForeignTree.read(it.readText(), it.fileName.toString()) } +
                    ForeignTree.read("other: consumer", "layouts/view.yaml")
            val result = StructuralExecutor.apply(inputs, demoChain().flatMap { it.operations })
            result.documents.map {
                it.value
                    .getValue("model")
                    .jsonPrimitive.content
            } shouldBe listOf("er", "binding", "db")
            result.defs().size shouldBe 7
            result.ignored.map { it.reason }.toSet() shouldBe setOf("Layout source", "Other consumer")
            result.report.codes["E_SAME"] shouldBe "ys/value-unmapped"
            result.documents.all { it.write().endsWith('\n') } shouldBe true
        }
        // S3.3 T5 (contracts §3): a canonical failure on a transformed value names the authored key, not just its record.
        test("canonical value validation points to the authored key instead of the generated JSON") {
            val error =
                shouldThrow<ProfileException> {
                    runOps(
                        "entity:\n  Customer:\n    popis: [wrong]\n",
                        entityOps + "- {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}",
                    )
                }
            error.code shouldBe "ys/profile-invalid"
            error.location.path shouldBe "/entity/Customer/popis"
            error.location.line shouldBe 3
            error.location.column shouldBe 5
        }
    })
