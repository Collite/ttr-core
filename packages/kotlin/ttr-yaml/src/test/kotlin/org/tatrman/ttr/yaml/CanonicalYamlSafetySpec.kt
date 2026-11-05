// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.ScalarEvent

class CanonicalYamlSafetySpec :
    FunSpec({
        val header = "ttr: '0.22'\nmodel: er\ndefs:\n"
        val properties = header + "  - kind: kind\n    id: Order\n    properties:\n"
        val mergeMessage = "Mapping keys must be strings; merge keys are not supported"
        val budgetMessage = "The incoming YAML document exceeds the limit: 3000000 code points."

        listOf("<<", "!!merge \"<<\"", "\"<<\"").forEach { key ->
            test("block merge key $key is refused before composition with P2 wording") {
                val error =
                    shouldThrow<CanonicalYamlException> {
                        CanonicalYaml.read(properties + "      $key: {description: \"x\"}\n", "merge.yaml")
                    }
                error.message shouldBe "merge.yaml:7:7: $mergeMessage"
                error.keyPath shouldBe "/defs/0/properties/<<"
            }
        }

        test("flow merge keys are refused before composition with P2 wording") {
            val error =
                shouldThrow<CanonicalYamlException> {
                    CanonicalYaml.read(
                        header + "  - {kind: kind, id: Order, properties: {<<: {description: \"x\"}}}\n",
                        "flow.yaml",
                    )
                }
            error.message shouldBe "flow.yaml:4:42: $mergeMessage"
            error.keyPath shouldBe "/defs/0/properties/<<"
        }

        test("merge-looking values keep the base canonical output") {
            val entity = header + "  - kind: entity\n    id: Product\n    properties:\n"
            val expected = CanonicalYaml.write(CanonicalYaml.read(entity + "      description: \"<<\"\n"))
            listOf(
                "      description: \"<<\"\n",
                "      description: '<<'\n",
                "      description: !!str <<\n",
                "      description: |-\n        <<\n",
                "      description: >-\n        <<\n",
                "      ? description\n      : \"<<\"\n",
            ).forEach { body ->
                CanonicalYaml.write(CanonicalYaml.read(entity + body)) shouldBe expected
            }
            val tagged = CanonicalYaml.write(CanonicalYaml.read(entity + "      tags: [\"<<\", '>>']\n"))
            CanonicalYaml.write(CanonicalYaml.read(entity + "      tags:\n        - \"<<\"\n        - '>>'\n")) shouldBe
                tagged
            val flow = header + "  - {kind: entity, id: Product, properties: {? description : \"<<\"}}\n"
            CanonicalYaml.write(CanonicalYaml.read(flow)) shouldBe expected
            val both =
                CanonicalYaml.write(
                    CanonicalYaml.read(
                        entity + "      description: \"<<\"\n      tags: [\"<<\"]\n",
                    ),
                )
            CanonicalYaml.write(
                CanonicalYaml.read(
                    header + "  - {kind: entity, id: Product, properties: {description: \"<<\", tags: [\"<<\"]}}\n",
                ),
            ) shouldBe both
        }

        test(
            "plain merge-looking values retain the tag diagnostic and merge keys use the approved earlier diagnostic",
        ) {
            val entity = header + "  - kind: entity\n    id: Product\n    properties:\n"
            shouldThrow<CanonicalYamlException> { CanonicalYaml.read(entity + "      description: <<\n", "value.yaml") }
                .message shouldBe "value.yaml:7:20: Unsupported YAML tag tag:yaml.org,2002:merge"
            shouldThrow<CanonicalYamlException> { CanonicalYaml.read(entity + "      <<: x\n", "key.yaml") }
                .message shouldBe "key.yaml:7:7: $mergeMessage"
        }

        test("a second document fails at its start before its later merge key in both reader modes") {
            val valid = "ttr: '0.22'\nmodel: cn\ndefs: []\n"
            listOf<(String) -> Any>(
                { CanonicalYaml.read(it, "documents.yaml") },
                { YamlData.read(it, "documents.yaml") },
            ).forEach { read ->
                shouldThrow<CanonicalYamlException> { read(valid + "---\n<<: {a: 1}\n") }.message shouldBe
                    "documents.yaml:4:1: but found another document"
                shouldThrow<CanonicalYamlException> { read(valid + "...\n<<: {a: 1}\n") }.message shouldBe
                    "documents.yaml:5:1: expected '<document start>', but found '<block mapping start>'"
                read("---\n$valid...\n")
            }
        }

        test("whole input budget includes trailing comments at the exact code-point boundary") {
            val valid = header + "  - kind: kind\n    id: Order\n    properties: {}\n"
            val prefix = valid + "# 😀"
            val boundary = prefix + "x".repeat(MAX_YAML_CODE_POINTS - prefix.codePointCount(0, prefix.length))
            CanonicalYaml.read(boundary).ast.errors shouldBe emptyList()
            val error = shouldThrow<CanonicalYamlException> { CanonicalYaml.read(boundary + "x", "budget.yaml") }
            error.message shouldBe "budget.yaml:1:1: $budgetMessage"
            error.keyPath shouldBe ""
        }

        test("oversized content keeps the canonical reader existing exact message") {
            val error =
                shouldThrow<CanonicalYamlException> {
                    CanonicalYaml.read(
                        properties + "      description: \"" + "x".repeat(MAX_YAML_CODE_POINTS) + "\"\n",
                        "budget.yaml",
                    )
                }
            error.message shouldBe "budget.yaml:1:1: $budgetMessage"
        }

        test("existing canonical safety diagnostics remain byte-identical to master") {
            // These messages and authored marks were compared with the master reader before the repair.
            listOf(
                "      description: &a x\n" to "7:20: Anchors and aliases are not supported",
                "      description: *a\n" to "7:20: found undefined alias a",
                "      ? [a, b]\n      : x\n" to
                    "1:1: Non scalar key is detected but it is not configured to be allowed.",
                "      description: !!python/object x\n" to
                    "7:20: Unsupported YAML tag tag:yaml.org,2002:python/object",
                "      description: x\n      description: y\n" to "8:7: Duplicate mapping key description",
                "      description: .inf\n" to "7:20: Expected a finite number",
                "      description: [broken\n" to "8:1: expected ',' or ']', but got <stream end>",
            ).forEach { (suffix, message) ->
                shouldThrow<CanonicalYamlException> { CanonicalYaml.read(properties + suffix, "old.yaml") }
                    .message shouldBe "old.yaml:$message"
            }
        }

        test("event pointers keep key-value alternation after alias and collection keys") {
            listOf("*a", "? [a, b]").forEach { key ->
                val paths = YamlEventPaths()
                val scalars = mutableMapOf<String, String>()
                for (event in Parse(LoadSettings.builder().build()).parseString("x: {$key : 1, next: 2}")) {
                    val path = paths.accept(event)
                    if (event is ScalarEvent) scalars[event.value] = path
                }
                scalars.getValue("1") shouldBe "/x"
                scalars.getValue("next") shouldBe "/x/next"
                scalars.getValue("2") shouldBe "/x/next"
            }
        }

        test("astral characters at fixed-size reader boundaries survive YAML and migration marker reads") {
            listOf(1024, 2049).forEach { boundary ->
                val scalar = "x".repeat(boundary - 3) + "😀"
                YamlData.read("a: $scalar").value["a"].toString() shouldBe "\"$scalar\""
                val prefix = "# " + "x".repeat(boundary - 2) + "😀\n"
                val source = prefix + "ttr: '0.22'\nmodel: er\ndefs: []\n"
                CanonicalYaml.read(source).ast.errors shouldBe emptyList()
                // A differing marker must be found before the canonical reader, so this exercises the marker's Reader.
                val old = source.replace("0.22", "0.21")
                val request =
                    kotlinx.serialization.json.buildJsonObject {
                        put("operation", kotlinx.serialization.json.JsonPrimitive("migrate"))
                        put("from", kotlinx.serialization.json.JsonPrimitive("0.21"))
                        put("to", kotlinx.serialization.json.JsonPrimitive("0.22"))
                        put("source", kotlinx.serialization.json.JsonPrimitive(old))
                    }
                YamlMigration.migrate(request)["source"].toString().contains("0.22") shouldBe true
            }
        }
        test("document-one composition errors precede the second document guard") {
            val error =
                shouldThrow<CanonicalYamlException> {
                    CanonicalYaml.read("ttr: '0.22'\nmodel: cn\ndefs: *missing\n---\nx: 1\n", "earliest.yaml")
                }
            error.message shouldBe "earliest.yaml:3:7: found undefined alias missing"
        }
    })
