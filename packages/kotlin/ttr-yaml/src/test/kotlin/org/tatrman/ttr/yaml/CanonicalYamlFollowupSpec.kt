// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
    })
