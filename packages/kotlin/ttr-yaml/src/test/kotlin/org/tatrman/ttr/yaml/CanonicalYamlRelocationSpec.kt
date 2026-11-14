// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.model.EmbeddedBlock
import org.tatrman.ttr.parser.model.TaggedBlockValue
import java.nio.file.Files
import java.nio.file.Path

/** MW S3.4 — a relocated embedded block keeps its text span, line starts and indent in one file (review-260 F8). */
class CanonicalYamlRelocationSpec :
    FunSpec({
        fun nodes(value: Any?): List<Any> =
            when {
                value == null -> emptyList()
                value is List<*> -> value.flatMap(::nodes)
                value is Map<*, *> -> value.values.flatMap(::nodes)
                value.javaClass.packageName.startsWith("org.tatrman.ttr.parser") && !value.javaClass.isEnum ->
                    listOf(value) +
                        generateSequence<Class<*>>(value.javaClass) { it.superclass }
                            .flatMap { it.declaredFields.asSequence() }
                            .filter {
                                !java.lang.reflect.Modifier
                                    .isStatic(it.modifiers)
                            }.flatMap { field ->
                                if (field.trySetAccessible()) nodes(field.get(value)) else emptyList()
                            }.toList()
                else -> emptyList()
            }

        fun blocks(value: Any?): List<EmbeddedBlock> = nodes(value).filterIsInstance<EmbeddedBlock>()

        val fixture = Path.of("../../../tests/conformance/fixtures/118-cn-temporal-bindings/cn.yaml")
        val canonical = Files.readString(fixture).replace("block: \"code = 'web'\"", "block: \"code = 'web'\\n  or x\"")
        val authored = "w:\n  def: |-\n    code = 'web'\n      or x\n"
        val data = YamlData.read(authored, "/abs/d.yaml")

        fun block(relocation: Relocation?) =
            blocks(CanonicalYaml.read(canonical, "/abs/d.yaml", relocation).ast).single {
                it.text == "code = 'web'\n  or x"
            }

        test("line 2 of a relocated block lands on line 2 of the block in the authored file") {
            val relocated =
                block(
                    object : Relocation {
                        override fun source(pointer: String) = null

                        override fun block(
                            pointer: String,
                            text: String,
                        ) = data.block("/w/def", text)
                    },
                )
            val second = relocated.location(1, 2, length = 2)
            withClue(second) {
                second.line shouldBe 4
                second.column shouldBe 6
                second.offsetStart shouldBe authored.indexOf("or x")
                second.offsetEnd shouldBe authored.indexOf("or x") + 2
            }
            relocated.textSource.line shouldBe 3
            relocated.textSource.offsetStart shouldBe authored.indexOf("code")
        }

        test("a block the authored file cannot hold keeps text span, line starts and indent all generated") {
            val generated = block(null)
            val refused =
                block(
                    object : Relocation {
                        override fun source(pointer: String) = null

                        override fun block(
                            pointer: String,
                            text: String,
                        ) = data.block("/w/def", "another text")
                    },
                )
            refused shouldBe generated
            data.block("/w", "x") shouldBe null
        }

        test("re-review RR1-2: a tagged block read back as a string takes span and indent from the authored scalar") {
            // Every line indented: no TTR triple literal holds it, so the projection writes a string literal and the
            // typed AST turns that string into the tagged block.
            val text = "  SELECT a\n    FROM t"
            val document =
                "ttr: \"0.22\"\ndefs:\n  - kind: query\n    id: Q\n    properties:\n" +
                    "      sourceText: {tag: sql, block: \"  SELECT a\\n    FROM t\"}\n"
            val dialect = "Q:\n  sql: |2-\n      SELECT a\n        FROM t\n"
            val authored = YamlData.read(dialect, "/abs/q.yaml")
            val tagged =
                nodes(
                    CanonicalYaml
                        .read(
                            document,
                            "/abs/q.yaml",
                            object : Relocation {
                                override fun source(pointer: String) = null

                                override fun block(
                                    pointer: String,
                                    text: String,
                                ) = authored.block("/Q/sql", text)
                            },
                        ).ast,
                ).filterIsInstance<TaggedBlockValue>()
                    .single()
            withClue(tagged) {
                tagged.value shouldBe text
                tagged.valueSource.file shouldBe "/abs/q.yaml"
                tagged.valueSource.line shouldBe 3
                tagged.indentWidth shouldBe 4
                // Line k of the block sits on line `valueSource.line + k`, after `indentWidth` columns.
                text.lines().forEachIndexed { k, logical ->
                    dialect.lines()[tagged.valueSource.line - 1 + k].substring(tagged.indentWidth) shouldBe logical
                }
            }
        }

        test("re-review RR1-3: a plain scalar spanning lines folds them, so it maps no block; one line still does") {
            val folded = YamlData.read("block: SELECT a\n  FROM t\n", "/abs/f.yaml")
            folded.block("/block", "SELECT a FROM t") shouldBe null
            val plain = YamlData.read("block: SELECT a\n", "/abs/p.yaml").block("/block", "SELECT a")
            withClue(plain) {
                plain?.source?.line shouldBe 1
                plain?.source?.column shouldBe 7
                plain?.lineOffsets shouldBe listOf(7)
            }
        }
    })
