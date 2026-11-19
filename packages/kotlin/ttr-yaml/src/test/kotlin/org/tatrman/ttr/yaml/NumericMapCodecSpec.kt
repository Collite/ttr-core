// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.serialization.json.*

/** Review-382 F5, D-MW-107: order follows the grammar construct, not the key's shape. */
class NumericMapCodecSpec :
    FunSpec({
        listOf("unordered-entity", "ordered-kind").forEach { name ->
            test("shared $name codec bytes") {
                val fixture = Path.of("../../../tests/conformance/dialect/integer-keys/codec")
                val raw = Json.parseToJsonElement(fixture.resolve("$name.json").readText()).jsonObject
                val normalized = CanonicalNormalization(raw.getValue("model").jsonPrimitive.content).document(raw)
                val properties =
                    normalized
                        .getValue("defs")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("properties")
                val keys = CanonicalGrammar.entries(properties).map { it.first }
                keys shouldBe if (name == "unordered-entity") listOf("10", "9", "b") else listOf("10", "2", "b", "1")
                YamlText.write(normalized) shouldBe fixture.resolve("$name.yaml").readText()
            }
        }
    })
