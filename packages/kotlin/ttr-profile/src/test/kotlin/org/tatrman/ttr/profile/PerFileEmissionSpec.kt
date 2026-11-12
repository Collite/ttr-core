// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.model.EntityDef

/** MW S3.4: per-file documents for a loader, with the authored spans in their typed AST. */
class PerFileEmissionSpec :
    FunSpec({
        val a = "entity:\n  A:\n    popis: Áčko\n"
        val b = "entity:\n  B: {}\n"

        fun trees() =
            listOf(ForeignTree.read(a, "x/a.yaml", "/abs/x/a.yaml"), ForeignTree.read(b, "b.yaml", "/abs/b.yaml"))
        val ops = entityOps + "- {op: rename, in: entity, at: /popis, to: {kind: entity, property: description}}"

        test("perFile emits one document per input file and names it; the default groups by package and model") {
            ProfileEngine.apply(trees(), listOf(recordProfile(ops))).documents.map { it.file } shouldContainExactly
                listOf(null)
            ProfileEngine
                .apply(trees(), listOf(recordProfile(ops)), null, perFile = true)
                .documents
                .map { it.file } shouldContainExactly listOf("x/a.yaml", "b.yaml")
        }
        test("the typed AST carries the dialect label and the authored line, column and offsets") {
            val document =
                ProfileEngine
                    .apply(
                        trees(),
                        listOf(recordProfile(ops)),
                        null,
                        perFile = true,
                    ).documents
                    .first()
            val entity =
                document.canonical.ast.definitions
                    .single() as EntityDef
            entity.name shouldBe "A"
            // The record is the mapping under `A:`, which starts on line 3 at `popis`.
            entity.source.file shouldBe "/abs/x/a.yaml"
            entity.source.line shouldBe 3
            entity.source.column shouldBe 4
            entity.source.offsetStart shouldBe a.indexOf("popis")
            entity.description shouldBe "Áčko"
            // Spans and selectors stay apart: the label never reaches a file selector or the package map.
            trees().map { it.file to it.label } shouldContainExactly
                listOf("x/a.yaml" to "/abs/x/a.yaml", "b.yaml" to "/abs/b.yaml")
        }
    })
