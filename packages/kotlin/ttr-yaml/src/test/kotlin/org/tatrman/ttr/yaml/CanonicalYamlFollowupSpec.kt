// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.model.MdDomainDef

/** Review-201's approved S2.3 follow-ups exercise YAML-authored input as well as native input. */
class CanonicalYamlFollowupSpec :
    FunSpec({
        fun yaml(
            properties: String,
            model: String = "md",
            kind: String = "domain",
        ): String =
            "ttr: \"0.21\"\nmodel: $model\ndefs:\n  - kind: $kind\n    id: D\n    properties:\n" +
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
        test("N3 million-digit decimal hex and native numbers are refused promptly at their token") {
            val decimal = "1".repeat(1_000_000)
            val hex = "0x" + "F".repeat(1_000_000)
            val cases =
                listOf(
                    yaml("a: $decimal", "automation", "program").replace("id: D", "id: \"D\"") to false,
                    yaml("a: $hex", "automation", "program") to false,
                    "model automation\ndef program D {\n  a: $decimal\n}\n" to true,
                )
            for ((source, native) in cases) {
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
                // Warm class loading, YAML scanning and the lexer before measuring the actual public entry point.
                repeat(4) { reject() }
                val start = System.nanoTime()
                val error = reject()
                val elapsed = System.nanoTime() - start
                error.detail shouldContain "Number literal too long"
                error.file shouldBe if (native) "large.ttrm" else "large.yaml"
                error.line shouldBe if (native) 3 else 7
                error.column shouldBe if (native) 6 else 10
                elapsed shouldBeLessThan 100_000_000L
            }
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
        test("N3 early refusal never limits numeric-looking strings blocks tags or comments") {
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
                "ttr: \"0.21\"\nmodel: automation\ndefs:\n - kind: program\n   id: D\n" +
                    "   properties: {\n      a: $digits\n      suffix\n   }\n"
            CanonicalYaml.read(flow).ast.ok shouldBe true
        }
        test("N3 simple numeric leaf preflight rejects before the full scanner, including quoted metadata") {
            val digits = "1".repeat(MAX_NUMBER_LENGTH + 1)
            for (value in listOf(digits, "0x$digits")) {
                val source = yaml("a: $value", "automation", "program").replace("id: D", "id: \"D\"")
                val error =
                    shouldThrow<CanonicalYamlException> { checkSimpleNumericLines(source, "early.yaml", yaml = true) }
                error.line shouldBe 7
                error.column shouldBe 10
            }
            shouldThrow<CanonicalYamlException> {
                checkSimpleNumericLines("model automation\ndef program D {\n a: $digits\n}", "early.ttrm", yaml = false)
            }.line shouldBe 3
        }
    })
