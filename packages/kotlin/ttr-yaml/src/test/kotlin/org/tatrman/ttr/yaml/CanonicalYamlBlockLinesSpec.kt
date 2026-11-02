// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

class CanonicalYamlBlockLinesSpec :
    FunSpec({
        for (content in listOf(
            "first\n\nthird\n",
            "first\n   \nthird",
            "SELECT 1   \nnext",
            "\nfirst\n\n",
            "\n\nfirst",
        )) {
            test("literal block preserves content ${JsonPrimitive(content)}") {
                val input =
                    buildJsonObject {
                        put("ttr", "0.22")
                        put("model", "automation")
                        put(
                            "defs",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("kind", "program")
                                        put("id", "D")
                                        put("properties", buildJsonObject { put("a", content) })
                                    },
                                )
                            },
                        )
                    }
                val doc = CanonicalYaml.read(input.toString())
                val bytes = CanonicalYaml.write(doc)
                bytes shouldContain "|2"
                if ("\n\n" in content) bytes shouldContain "\n\n"
                if (content.endsWith("\n\n")) bytes shouldContain "|2+"
                CanonicalYaml.read(bytes).value shouldBe doc.value
                CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
            }
        }
    })
