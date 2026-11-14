// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.model.MdDomainDef

/** Review-201's approved S2.3 follow-ups exercise YAML-authored input as well as native input. */
class CanonicalYamlFollowupSpec :
    FunSpec({
        fun yaml(
            properties: String,
            model: String = "md",
            kind: String = "domain",
        ): String =
            "ttr: \"0.22\"\nmodel: $model\ndefs:\n  - kind: $kind\n    id: D\n    properties:\n" +
                properties.prependIndent("      ") + "\n"

        for (clauses in listOf("{zeta: 1, alpha: 2}", "{alpha: 2, zeta: 1}")) {
            test("N1 YAML restrict clause ordering $clauses agrees with native") {
                val doc = CanonicalYaml.read(yaml("restrict: $clauses"))
                val native = CanonicalYaml.fromTtr("model md\ndef domain D { restrict: $clauses }")
                CanonicalYaml.write(doc) shouldBe CanonicalYaml.write(native)
                CanonicalYaml.write(CanonicalYaml.fromTtr(CanonicalYaml.toTtr(doc))) shouldBe CanonicalYaml.write(doc)
            }
        }
        for (clauses in listOf(
            "pattern: \"x\", members: {\"z\": {en: \"Z\"}, \"a\": {en: \"A\"}}",
            "members: {\"z\": {en: \"Z\"}, \"a\": {en: \"A\"}}, pattern: \"x\"",
        )) {
            test("N1 typed members keep their order inside canonical clauses $clauses") {
                val doc = CanonicalYaml.read(yaml("restrict: {$clauses}"))
                val native = CanonicalYaml.fromTtr("model md\ndef domain D { restrict: {$clauses} }")
                (doc.ast.definitions.single() as MdDomainDef).restrictMembers shouldBe listOf("z", "a")
                (native.ast.definitions.single() as MdDomainDef).restrictMembers shouldBe listOf("z", "a")
                CanonicalYaml.write(doc) shouldBe CanonicalYaml.write(native)
            }
        }
        for ((authored, normalized) in listOf(
            "+1" to "1",
            ".5" to "0.5",
            "0x1F" to "31",
            "0o17" to "15",
            "5." to "5",
        )) {
            test("N2 YAML-only number $authored normalizes to its native spelling") {
                val doc = CanonicalYaml.read(yaml("a: $authored", "automation", "program"))
                val native = CanonicalYaml.fromTtr("model automation\ndef program D { a: $normalized }")
                CanonicalYaml.write(doc) shouldBe CanonicalYaml.write(native)
                CanonicalYaml.write(CanonicalYaml.fromTtr(CanonicalYaml.toTtr(doc))) shouldBe CanonicalYaml.write(doc)
            }
        }
        for (spelling in listOf("01", "-0.0", "1.0", "1e3", "-2E-2")) {
            test("N2 native numeric spelling $spelling survives both routes") {
                val doc = CanonicalYaml.read(yaml("a: $spelling", "automation", "program"))
                val native = CanonicalYaml.fromTtr("model automation\ndef program D { a: $spelling }")
                CanonicalYaml.write(doc) shouldBe CanonicalYaml.write(native)
                CanonicalYaml.write(CanonicalYaml.fromTtr(CanonicalYaml.toTtr(doc))) shouldBe CanonicalYaml.write(doc)
            }
        }
        test("N3 real guards refuse million-digit numbers at their token in median under one second") {
            val decimal = "1".repeat(1_000_000)
            val cases =
                listOf(
                    Triple("# c\n" + yaml("a: $decimal", "automation", "program"), 8, 10),
                    Triple("# c\n" + yaml("a: 0x" + "F".repeat(1_000_000), "automation", "program"), 8, 10),
                    Triple(
                        "ttr: \"0.22\"\nmodel: automation\ndefs:\n - kind: program\n   id: D\n   properties: {a: $decimal}\n",
                        6,
                        20,
                    ),
                    Triple("// c\nmodel automation\ndef program D {\n  a: $decimal\n}\n", 4, 6),
                )
            for ((source, line, column) in cases) {
                val native = source.startsWith("//")

                fun reject(): CanonicalYamlException =
                    shouldThrow<CanonicalYamlException> {
                        if (native) {
                            CanonicalYaml.fromTtr(
                                source,
                                "large.ttrm",
                            )
                        } else {
                            CanonicalYaml.read(source, "large.yaml")
                        }
                    }
                repeat(4) { reject() }
                val elapsed =
                    (1..3)
                        .map {
                            val start = System.nanoTime()
                            val error = reject()
                            val duration = System.nanoTime() - start
                            error.detail shouldContain "Number literal too long"
                            error.file shouldBe if (native) "large.ttrm" else "large.yaml"
                            error.line shouldBe line
                            error.column shouldBe column
                            duration
                        }.sorted()
                elapsed[1] shouldBeLessThan 1_000_000_000L
            }
        }
        test("R204 the code-point YAML limit remains enforced (6 000 000 since ✅D-MW-75)") {
            val source = yaml("a: \"" + "x".repeat(MAX_YAML_CODE_POINTS + 1) + "\"", "automation", "program")
            val error = shouldThrow<CanonicalYamlException> { CanonicalYaml.read(source, "limit.yaml") }
            error.file shouldBe "limit.yaml"
            error.detail shouldContain "limit"
        }
        test("N3 finite decimal and hex literals at the bound remain accepted") {
            val decimal = "0." + "1".repeat(MAX_NUMBER_LENGTH - 2)
            val hex = "0x" + "0".repeat(MAX_NUMBER_LENGTH - 3) + "1"
            for (spelling in listOf(decimal, hex)) {
                val doc = CanonicalYaml.read(yaml("a: $spelling", "automation", "program"))
                CanonicalYaml.write(CanonicalYaml.read(CanonicalYaml.write(doc))) shouldBe CanonicalYaml.write(doc)
            }
            CanonicalYaml.fromTtr("model automation\ndef program D { a: $decimal }")
        }
        test("N3 numeric limits never apply to numeric-looking strings blocks tags or comments") {
            val digits = "1".repeat(MAX_NUMBER_LENGTH + 1)
            for (value in listOf(
                "'$digits'",
                "!!str $digits",
                "|\n  a: $digits",
                "${digits}suffix",
                "$digits\n  suffix",
                "\"text\n  a: $digits\"",
            )) {
                val doc = CanonicalYaml.read(yaml("a: $value", "automation", "program"))
                doc.ast.ok shouldBe true
            }
            CanonicalYaml.fromTtr("model automation\ndef program D {\n a: \"$digits\"\n}").ast.ok shouldBe true
            CanonicalYaml.fromTtr("model automation\ndef program D {\n // a: $digits\n a: 1\n}").ast.ok shouldBe true
            val flow =
                "ttr: \"0.22\"\nmodel: automation\ndefs:\n - kind: program\n   id: D\n" +
                    "   properties: {\n      a: $digits\n      suffix\n   }\n"
            CanonicalYaml.read(flow).ast.ok shouldBe true
        }
        test("R204 F1 triple strings and multiline plain scalars preserve numeric-looking text") {
            val digits = "1".repeat(MAX_NUMBER_LENGTH + 1)
            val triple = "\"\"\""
            for ((header, field, content) in listOf(
                Triple("model er\ndef entity E", "description", "he said \"hi\na: $digits\n"),
                Triple("model world\ndef query E", "sourceText", "SELECT \"Col\nx: $digits\n"),
            )) {
                val source = "$header {\n  $field: $triple$content$triple\n}"
                val doc = CanonicalYaml.fromTtr(source)
                doc.ast.ok shouldBe true
                val value =
                    doc.value
                        .getValue("defs")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue(field)
                val retained =
                    if (value is JsonPrimitive) {
                        value.content
                    } else {
                        value.jsonObject
                            .getValue(
                                "block",
                            ).jsonPrimitive.content
                    }
                retained shouldBe content
                CanonicalYaml.fromTtr(CanonicalYaml.toTtr(doc)).value shouldBe doc.value
            }
            val doc = CanonicalYaml.read(yaml("description: Some text\n  x:$digits", "er", "entity"))
            doc.ast.ok shouldBe true
            CanonicalYaml.toTtr(doc) shouldContain "Some text x:$digits"
        }
        test("R204 F2 guards cover signed radix block flow sequence and native list positions") {
            val digits = "1".repeat(MAX_NUMBER_LENGTH + 1)
            for (token in listOf(digits, "-$digits", "+$digits", "0x$digits", "0o$digits")) {
                for (value in listOf(token, "[$token]", "{x: $token}")) {
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.read(yaml("a: $value", "automation", "program"), "guard.yaml")
                        }
                    error.detail shouldContain "Number literal too long"
                    error.file shouldBe "guard.yaml"
                    error.line shouldBe 7
                    error.column shouldBe
                        when {
                            value.startsWith("[") -> 11
                            value.startsWith("{") -> 14
                            else -> 10
                        }
                }
            }
            for (token in listOf(digits, "-$digits")) {
                for (value in listOf(token, "[1, $token]")) {
                    val error =
                        shouldThrow<CanonicalYamlException> {
                            CanonicalYaml.fromTtr("model automation\ndef program D {\n a: $value\n}", "guard.ttrm")
                        }
                    error.detail shouldContain "Number literal too long"
                    error.file shouldBe "guard.ttrm"
                    error.line shouldBe 3
                    error.column shouldBe if (value.startsWith("[")) 9 else 5
                }
            }
        }
    })
