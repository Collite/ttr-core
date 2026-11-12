// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TaggedBlockValue
import java.nio.file.Files

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

        /** Every [SourceLocation] in a typed AST (the parser's data classes, lists and maps). */
        fun locations(value: Any?): List<SourceLocation> =
            when {
                value == null -> emptyList()
                value is SourceLocation -> listOf(value)
                value is List<*> -> value.flatMap(::locations)
                value is Map<*, *> -> value.values.flatMap(::locations)
                value.javaClass.packageName.startsWith("org.tatrman.ttr.parser") && !value.javaClass.isEnum ->
                    generateSequence<Class<*>>(value.javaClass) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter {
                            !java.lang.reflect.Modifier
                                .isStatic(it.modifiers)
                        }.flatMap { field ->
                            if (field.trySetAccessible()) locations(field.get(value)) else emptyList()
                        }.toList()
                else -> emptyList()
            }

        test("review-260 F7: an overlay value takes its record's authored span, never generated coordinates") {
            val root = Files.createTempDirectory("r260-f7").toRealPath()
            val text = "entity:\n  A: {}\n  B: {}\n"
            Files.writeString(
                root.resolve("d.yaml"),
                "profile: d/1\ngrammar: '>=0.22 <1.0'\noperations:\n" +
                    "  - {op: package, mapping: {}, default: demo}\n" +
                    "  - {op: kinds, at: /entity, mapping: {entita: entity}}\n" +
                    "overlay:\n  B: {roles: [fact]}\n",
            )
            val label = root.resolve("a.yaml").toString()
            val chain = ProfileResolver(root, emptyMap()).resolve("./d.yaml", root.resolve("ttr.yaml"))
            val document =
                ProfileEngine
                    .apply(listOf(ForeignTree.read(text, "a.yaml", label)), chain, root, perFile = true)
                    .documents
                    .single()
            val b =
                document.canonical.ast.definitions
                    .single { it.name == "B" } as EntityDef
            val role = b.roles.single()
            role.toString() shouldContain "fact"
            // The overlay value is written for B, whose record is the mapping on line 3 of the dialect file.
            withClue(role.source) {
                role.source.file shouldBe label
                role.source.line shouldBe 3
                role.source.offsetStart shouldBe text.indexOf("{}", text.indexOf("B:"))
            }
            locations(document.canonical.ast).forEach { location ->
                withClue(location) {
                    location.file shouldBe label
                    location.offsetEnd shouldBeLessThanOrEqual text.length
                    location.line shouldBeLessThanOrEqual text.lines().size
                }
            }
        }

        test("review-260 F8: a relocated block maps its inner lines into the dialect file, never the generated text") {
            val text = "dotazy:\n  Q:\n    sql:\n      tag: sql\n      block: |\n        SELECT a\n          FROM t\n"
            val label = "/abs/q.yaml"
            val ops =
                "- {op: package, mapping: {}, default: demo}\n" +
                    "- {op: kinds, at: /dotazy, mapping: {d: query}}\n" +
                    "- {op: nest, in: query, at: /sql, to: {kind: query, path: [sourceText]}}"
            val document =
                ProfileEngine
                    .apply(
                        listOf(ForeignTree.read(text, "q.yaml", label)),
                        listOf(recordProfile(ops)),
                        null,
                        perFile = true,
                    ).documents
                    .single()
            val query =
                document.canonical.ast.definitions
                    .single() as QueryDef
            val block = query.sourceTextBlock as TaggedBlockValue
            block.value shouldBe "SELECT a\n  FROM t"
            // Line 2 of the block, `  FROM t`, is line 7 of the dialect file: `FROM` at column 10, offset below.
            withClue(block) {
                block.valueSource.file shouldBe label
                block.valueSource.line shouldBe 6
                block.valueSource.offsetStart shouldBe text.indexOf("SELECT a")
                block.indentWidth shouldBe 8
                (block.valueSource.line + 1) shouldBe 7
                (block.indentWidth + 2) shouldBe text.indexOf("FROM") - (text.indexOf("\n          FROM") + 1)
            }
        }
    })
