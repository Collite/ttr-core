// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.model.EmbeddedBlock
import java.nio.file.Files
import java.nio.file.Path

/** MW S3.4 — a relocated embedded block keeps its text span, line starts and indent in one file (review-260 F8). */
class CanonicalYamlRelocationSpec :
    FunSpec({
        fun blocks(value: Any?): List<EmbeddedBlock> =
            when {
                value == null -> emptyList()
                value is EmbeddedBlock -> listOf(value)
                value is List<*> -> value.flatMap(::blocks)
                value is Map<*, *> -> value.values.flatMap(::blocks)
                value.javaClass.packageName.startsWith("org.tatrman.ttr.parser") && !value.javaClass.isEnum ->
                    generateSequence<Class<*>>(value.javaClass) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter {
                            !java.lang.reflect.Modifier
                                .isStatic(it.modifiers)
                        }.flatMap { field -> if (field.trySetAccessible()) blocks(field.get(value)) else emptyList() }
                        .toList()
                else -> emptyList()
            }

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
    })
