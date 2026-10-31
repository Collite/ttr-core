// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.parser.conformance

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** Public invented vectors validate normalized YAML values; no YAML codec/profile execution here. */
class CanonicalYamlSchemaSpec :
    FunSpec({
        val mapper = ObjectMapper()
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val vectors = mapper.readTree(Files.readString(Path.of("../../grammar/tests/canonical-schema-vectors.json")))

        fun resource(name: String): String =
            requireNotNull(CanonicalYamlSchemaSpec::class.java.getResourceAsStream("/schemas/$name")) {
                "Missing packaged canonical resource: $name"
            }.bufferedReader().use { it.readText() }

        vectors.groupBy { it["group"].asText() }.forEach { (group, cases) ->
            context(group) {
                cases.forEach { vector ->
                    test(vector["name"].asText()) {
                        val schema = factory.getSchema(resource("${vector["schema"].asText()}.schema.json"))
                        val errors = schema.validate(vector["value"])
                        withClue(errors.joinToString("\n")) {
                            errors.isEmpty() shouldBe vector["valid"].asBoolean()
                        }
                    }
                }
            }
        }

        context("deterministic packaged resources") {
            listOf("model-yaml.schema.json", "profile.schema.json", "canonical-metadata.json").forEach { name ->
                test(name) {
                    resource(name) shouldBe Files.readString(Path.of("../../grammar/schemas/$name"))
                    mapper
                        .readTree(resource(name))[if (name.endsWith("schema.json")) "x-ttr-version" else "version"]
                        .asText() shouldBe "0.21"
                }
            }
        }
    })
