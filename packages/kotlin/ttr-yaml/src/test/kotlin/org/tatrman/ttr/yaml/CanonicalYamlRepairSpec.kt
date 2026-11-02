// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.conformance.ConformanceDump
import org.tatrman.ttr.parser.model.*
import java.io.File
import java.util.jar.JarFile

/** Review-201 regressions: these cases deliberately reach beyond the shared text corpus. */
class CanonicalYamlRepairSpec :
    FunSpec({
        fun cycle(text: String): CanonicalDocument {
            val original = CanonicalYaml.fromTtr(text, "in.ttrm")
            val bytes = CanonicalYaml.write(original)
            val read = CanonicalYaml.read(bytes, "in.yaml")
            CanonicalYaml.write(read) shouldBe bytes
            read.value shouldBe original.value
            ConformanceDump.dumpTree(read.ast) shouldBe ConformanceDump.dumpTree(original.ast)
            val native = CanonicalYaml.fromTtr(CanonicalYaml.toTtr(read))
            native.value shouldBe original.value
            ConformanceDump.dumpTree(native.ast) shouldBe ConformanceDump.dumpTree(original.ast)
            return read
        }

        fun yaml(
            model: String,
            kind: String,
            properties: String,
        ): String =
            "ttr: \"0.22\"\nmodel: $model\ndefs:\n  - kind: $kind\n    id: P\n    properties:\n" +
                properties.prependIndent("      ")

        fun located(
            source: String,
            offset: Int,
            file: String,
            action: () -> Unit,
        ): CanonicalYamlException {
            val error = shouldThrow<CanonicalYamlException>(action)
            val prefix = source.take(offset)
            error.file shouldBe file
            error.line shouldBe prefix.count { it == '\n' } + 1
            error.column shouldBe prefix.length - prefix.lastIndexOf('\n')
            return error
        }

        listOf("overwrite", "diff").forEach { journaling ->
            test("F1 data objects in md2db shape and $journaling") {
                cycle(
                    "model binding\ndef md2db_cubelet c { cubelet: md.sales, target: db.table.fact, " +
                        "shape: wide, journaling: $journaling }",
                )
            }
        }
        test("F1 semantics null data object") {
            cycle("model er\ndef entity E { semantics { x: null } }")
        }
        test("F1 every parser model data object remains the same singleton") {
            val anchor = SourceLocation::class.java
            val root =
                File(
                    anchor.protectionDomain.codeSource.location
                        .toURI(),
                )
            val prefix = "org/tatrman/ttr/parser/model/"
            val names =
                if (root.isDirectory) {
                    File(root, prefix)
                        .walkTopDown()
                        .filter { it.extension == "class" }
                        .map {
                            it
                                .relativeTo(root)
                                .invariantSeparatorsPath
                                .removeSuffix(".class")
                                .replace('/', '.')
                        }.toList()
                } else {
                    JarFile(root).use { jar ->
                        jar
                            .entries()
                            .asSequence()
                            .filter {
                                it.name.startsWith(prefix) && it.name.endsWith(".class")
                            }.map { it.name.removeSuffix(".class").replace('/', '.') }
                            .toList()
                    }
                }
            val objects =
                names
                    .map {
                        Class.forName(it, false, anchor.classLoader).kotlin
                    }.filter { it.isData }
                    .mapNotNull { it.objectInstance }
            objects.size shouldBeGreaterThan 2
            val locations = YamlLocations(TtrSyntax("x"), YamlNodes("x"), "", JsonObject(emptyMap()))
            objects.forEach { (locations.copy(it) === it) shouldBe true }
        }
        for (name in listOf("ref", "triple", "call")) {
            for (extra in listOf("", ", \"sms\": { en: \"SMS\" }")) {
                test("F2 member $name with extra=$extra is not a generic wrapper") {
                    val doc =
                        cycle(
                            "model md\ndef domain D { restrict: { members: { \"$name\": { en: \"Label\" }$extra } } }",
                        )
                    (doc.ast.definitions.single() as MdDomainDef).restrictMembers shouldBe
                        if (extra.isEmpty()) listOf(name) else listOf(name, "sms")
                }
            }
        }
        listOf("x", "\"\"\"text\"\"\"", "f()").forEach { value ->
            test(
                "F2 genuine generic restrict value $value",
            ) { cycle("model md\ndef domain D { restrict: { members: $value } }") }
        }
        test("F3 ordered fields retain order and each field has its own YAML key span") {
            val doc = cycle("model world\ndef world W { def storage S { def schema s { zeta: text, alpha: int } } }")
            val fields =
                (doc.ast.definitions.single() as WorldDef)
                    .storages
                    .single()
                    .schemas
                    .single()
                    .fields
            fields.map { it.name } shouldBe listOf("zeta", "alpha")
            val bytes = CanonicalYaml.write(doc)
            fields.forEach { field ->
                val index = bytes.indexOf("${field.name}:")
                field.source.line shouldBe bytes.take(index).count { it == '\n' } + 1
                field.source.column shouldBe index - bytes.lastIndexOf('\n', index) - 1
            }
        }
        test("F3 YAML member order and per-member key spans survive normalization") {
            cycle("model md\ndef domain D { restrict: { members: { \"z\": {en: \"Z\"}, \"a\": {en: \"A\"} } } }")
            val source = yaml("md", "domain", "restrict:\n  members:\n    z: {en: Z}\n    a: {en: A}")
            val doc = CanonicalYaml.read(source, "members.yaml")
            (doc.ast.definitions.single() as MdDomainDef).restrictMembers shouldBe listOf("z", "a")
            val bytes = CanonicalYaml.write(doc)
            CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
            val nodes = YamlNodes("members.yaml")
            nodes.read(source)
            val z = nodes.source("/defs/0/properties/restrict/members/z", key = true)
            val a = nodes.source("/defs/0/properties/restrict/members/a", key = true)
            z.line shouldBe 9
            a.line shouldBe 10
            z.column shouldBe 10
            a.column shouldBe 10
        }
        test("F4 unknown expression opener stays untagged with indentation and newline") {
            val doc = cycle("model cn\ndef kind K { definition: \"\"\"exists\n  x > 1\n\"\"\" }")
            val block =
                (doc.ast.definitions.single() as ConceptDef)
                    .body.properties
                    .filterIsInstance<ConceptProperty.DefinitionBlock>()
                    .single()
                    .block
            block.text shouldBe "exists\n  x > 1\n"
            block.form shouldBe "tripleString"
            CanonicalYaml.write(doc).contains("tag:") shouldBe false
        }
        test("F4 registered expression and source tags still peel") {
            CanonicalYaml.write(cycle("model cn\ndef kind K { definition: \"\"\"pl\n  x > 1\n\"\"\" }")) shouldContain
                "tag: \"pl\""
            CanonicalYaml.write(
                cycle("model er\ndef query Q { sourceText: \"\"\"postgres\n  select 1\n\"\"\" }"),
            ) shouldContain
                "tag: \"postgres\""
        }
        test("F5 exponent spellings and tiny finite numbers stabilize after the first write") {
            val values = listOf("1e3", "-2E-2", "1.5e+10", "1.0", "1", "1e-1500")
            val doc =
                cycle(
                    "model automation\ndef program P { ${values.mapIndexed {
                        i,
                        v,
                        ->
                        "x$i: $v"
                    }.joinToString(", ")} }",
                )
            val nums = (doc.ast.definitions.single() as AutomationDef).body.entries.map { it.value as AutoValue.Num }
            nums.map { it.text } shouldBe values
        }
        test("F5 nonfinite values fail on the native and YAML token") {
            val native = "model automation\ndef program P {\n  a: 1e400\n}"
            located(native, native.indexOf("1e400"), "native.ttrm") { CanonicalYaml.fromTtr(native, "native.ttrm") }
            val source = yaml("automation", "program", "a: 1e400")
            located(source, source.indexOf("1e400"), "number.yaml") { CanonicalYaml.read(source, "number.yaml") }
        }
        test("F5 pathological exponents stay compact within the timeout") {
            withTimeout(2000) {
                val doc = cycle("model automation\ndef program P { a: 1e-999999999 }")
                val number =
                    (doc.ast.definitions.single() as AutomationDef)
                        .body.entries
                        .single()
                        .value as AutoValue.Num
                number.text shouldBe "1e-999999999"
            }
        }
        test("F6 trailing quote expression reads losslessly and native conversion fails at the block value") {
            val source = yaml("cn", "kind", "definition:\n  block: 'status == \"open\"'")
            val doc = CanonicalYaml.read(source, "expression.yaml")
            val block =
                (doc.ast.definitions.single() as ConceptDef)
                    .body.properties
                    .filterIsInstance<ConceptProperty.DefinitionBlock>()
                    .single()
                    .block
            block.text shouldBe "status == \"open\""
            val bytes = CanonicalYaml.write(doc)
            CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
            located(source, source.indexOf("'status"), "expression.yaml") { CanonicalYaml.toTtr(doc) }
        }
        test("F6 trailing quote generic triple reads losslessly and refuses native conversion at its value") {
            val source = yaml("cn", "kind", "custom:\n  triple: 'say \"hi\"'")
            val doc = CanonicalYaml.read(source, "triple.yaml")
            val bytes = CanonicalYaml.write(doc)
            CanonicalYaml.write(CanonicalYaml.read(bytes)) shouldBe bytes
            located(source, source.indexOf("'say"), "triple.yaml") { CanonicalYaml.toTtr(doc) }
        }
        test("F6 tagged trailing quotes remain representable") {
            cycle("model cn\ndef kind K { definition: \"\"\"pl\nstatus == \"open\"\n\"\"\" }")
        }
        test("F7 native schema rejection points at the actual property") {
            val source = "model automation\n\ndef role R {\n    grounds: X\n}"
            located(source, source.indexOf("grounds"), "loc.ttrm") { CanonicalYaml.fromTtr(source, "loc.ttrm") }
        }
        for ((body, duplicate) in listOf(
            "model md\ndef domain D { restrict: { members: { \"a\": {en: \"1\"}, \"a\": {en: \"2\"} } } }" to "\"a\"",
            "model md\ndef domain D { restrict: { members: { \"object\": {en: \"1\"}, \"object\": {en: \"2\"} } } }" to
                "\"object\"",
            "model world\ndef world W { def storage S { def schema s { a: text, a: int } } }" to "a:",
        )) {
            test("F8 duplicate $body is refused at its second key") {
                located(
                    body,
                    body.lastIndexOf(duplicate),
                    "duplicate.ttrm",
                ) { CanonicalYaml.fromTtr(body, "duplicate.ttrm") }
            }
        }
        test("F8 CN object duplicates still use the parser last-wins rule") {
            cycle("model cn\ndef kind K { custom: { a: 1, a: 2 } }")
        }
        test("F9 exact canonical graph order") {
            CanonicalYaml.write(
                CanonicalYaml.fromTtr(
                    "graph main { objects: [Z], tags: [\"x\"], description: \"d\", model: er, layout: {} }",
                ),
            ) shouldBe
                """
                ttr: "0.22"
                graph:
                  id: "main"
                  properties:
                    model: "er"
                    description: "d"
                    tags:
                      - "x"
                    objects:
                      - "Z"
                    layout: {}
                """.trimIndent() + "\n"
        }
        test("F9 exact canonical security statement order") {
            val expected =
                """
                ttr: "0.22"
                security:
                  -
                    -
                      verb: "own"
                      object: "A"
                      owner: "admin"
                    -
                      verb: "classify"
                      object: "A"
                      classification: "secret"
                    -
                      verb: "grant"
                      privilege: "read"
                      object: "A"
                      to: "staff"
                    -
                      verb: "mask"
                      object: "A"
                """.trimIndent() + "\n"
            val doc =
                CanonicalYaml.fromTtr(
                    "security { own A: admin, classify A: secret, grant read on A to staff, mask A }",
                )
            CanonicalYaml.write(doc) shouldBe expected
            CanonicalYaml.write(CanonicalYaml.read(expected)) shouldBe expected
        }
        listOf('\u007f', '\u0080', '\u009f').forEach { c ->
            test("F10 YAML printable escaping in keys values and multiline text U+${c.code}") {
                cycle(
                    "model md\ndef domain D { restrict: { members: { \"x${c}y\": { en: \"x${c}y\", " +
                        "cs: \"\"\"a\n${c}b\n\"\"\" } } } }",
                )
            }
        }
        test("F10 lone surrogates and supplementary text use reader-safe quoted strings") {
            for (value in listOf("\ud800", "\udfff", "\uffff", "\ud83d\ude00")) {
                cycle("model cn\ndef kind K { custom: \"$value\" }")
            }
        }
        test("F10 printable supplementary multiline strings still use literal blocks") {
            val doc = cycle("model cn\ndef kind K { custom: \"\"\"\nx\ud83d\ude00\ny\n\"\"\" }")
            CanonicalYaml.write(doc) shouldContain "|2"
        }
        test("F10 invalid raw YAML characters report their input position") {
            val source = yaml("cn", "kind", "custom: \"x\u007fy\"")
            located(source, source.indexOf('\u007f'), "raw.yaml") { CanonicalYaml.read(source, "raw.yaml") }
        }
        for (property in listOf("description", "custom")) {
            test("F11 pl sugar is refused at non-expression $property") {
                val source = yaml("cn", "kind", "$property: !pl hello")
                located(
                    source,
                    source.indexOf("!pl"),
                    "tag.yaml",
                ) { CanonicalYaml.read(source, "tag.yaml") }.message!! shouldContain
                    "Unsupported YAML tag !pl here"
            }
        }
        test("F11 approved expression sugar still works") {
            CanonicalYaml.read(yaml("cn", "kind", "definition: !pl x"))
        }
        for (value in listOf("x", "\"text\"")) {
            test("F12 cn binding filter block key is escaped for generic $value") {
                val doc = cycle("model cn\ndef cn2md_kind B { kind: cn.X, target: md.Y, filter: { block: $value } }")
                CanonicalYaml.write(doc) shouldContain "object:"
            }
        }
        test("F12 actual binding expression retains embedded form") {
            cycle("model cn\ndef cn2md_kind B { kind: cn.X, target: md.Y, filter: \"x > 1\" }")
        }
        for (value in listOf("1 // c", "1 /* c */")) {
            test("F13 calc comment $value is refused at the argument") {
                val source = yaml("md", "map", "calc:\n  name: fn\n  args:\n    input: '$value'")
                located(source, source.indexOf("'$value'"), "calc.yaml") { CanonicalYaml.read(source, "calc.yaml") }
            }
        }
        for (value in listOf("1", "\"x\"")) {
            test("F13 one retained calc value $value is accepted") {
                CanonicalYaml.read(yaml("md", "map", "calc: {name: fn, args: {input: '$value'}}"))
            }
        }
        for (version in listOf("1.1", "1.3")) {
            test("F14 YAML $version directive refused at the directive") {
                val source = "%YAML $version\n---\nttr: \"0.22\"\n"
                located(source, 0, "version.yaml") { CanonicalYaml.read(source, "version.yaml") }
            }
        }
        test("F14 YAML 1.2 and absent directives accepted") {
            CanonicalYaml.read("%YAML 1.2\n---\nttr: \"0.22\"\n")
            CanonicalYaml.read("ttr: \"0.22\"\n")
        }
    })
