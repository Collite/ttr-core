// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

class CanonicalYamlVectorsSpec :
    FunSpec({
        val vectors =
            Json
                .parseToJsonElement(
                    Files.readString(Path.of("../../grammar/tests/canonical-schema-vectors.json")),
                ).jsonArray
        vectors
            .map { it.jsonObject }
            .filter {
                it.string("schema") == "model-yaml" &&
                    it.getValue("valid").jsonPrimitive.boolean
            }.forEach { v ->
                test("schema-positive normalization ${v.string("name")}") {
                    val first = CanonicalYaml.read(v.getValue("value").toString(), "vector.yaml")
                    val bytes = CanonicalYaml.write(first)
                    CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
                }
            }
        vectors
            .map { it.jsonObject }
            .filter {
                it.string("schema") == "model-yaml" &&
                    !it.getValue("valid").jsonPrimitive.boolean
            }.forEach { v ->
                test("schema-negative rejection ${v.string("name")}") {
                    shouldThrow<CanonicalYamlException> {
                        CanonicalYaml.read(
                            v.getValue("value").toString(),
                            "negative.yaml",
                        )
                    }
                }
            }
    })
