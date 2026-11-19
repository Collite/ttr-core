// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import org.tatrman.ttr.yaml.CanonicalYaml
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.*

class ScalarMapParitySpec :
    FunSpec({
        listOf(
            "+9007199254740993" to "9007199254740993",
            "+1.50" to "1.50",
            "+1e3" to "1E+3",
            ".50" to "0.50",
            "1." to "1",
            "0xFFFFFFFFFFFFFFFF" to "18446744073709551615",
        ).forEach { (source, expected) ->
            test("canonical scalar $source resolves without arithmetic rounding") {
                val yaml =
                    "ttr: '0.22'\nmodel: automation\n" +
                        "defs: [{kind: program, id: Numbers, properties: {custom: $source}}]"
                CanonicalYaml.write(CanonicalYaml.read(yaml)) shouldContain "custom: $expected\n"
            }
        }
        test("numeric ttr remains unconsumed") {
            val error = io.kotest.assertions.throwables.shouldThrow<ProfileException> {
                runOps("ttr: 1\nentity: {Customer: {}}", entityOps)
            }
            error.code shouldBe "ys/unknown-key"
            error.location.path shouldBe "/ttr"
            error.location.line shouldBe 1
            error.location.column shouldBe 1
        }
        listOf("numbers", "integer-keys").forEach { name ->
            test("shared $name canonical bytes") {
                val fixture = Path.of("../../../tests/conformance/dialect/$name")
                val root = fixture.resolve("tree")
                val chain =
                    ProfileResolver(
                        root,
                        emptyMap(),
                    ).resolve("./profiles/parity.yaml", root.resolve("ttr.yaml").toAbsolutePath())
                val input = root.resolve("$name.yaml")
                val result = ProfileEngine.apply(listOf(ForeignTree.read(input.readText(), input.name)), chain, root)
                result.documents.size shouldBe 1
                val bytes = result.documents.single().write()
                val expected = fixture.resolve("canonical/$name.yaml")
                bytes shouldBe expected.readText()
            }
        }
    })
