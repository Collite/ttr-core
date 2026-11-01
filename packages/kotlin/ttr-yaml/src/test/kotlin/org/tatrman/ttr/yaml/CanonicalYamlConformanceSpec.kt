// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.ttr.parser.conformance.ConformanceDump
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.model.MdDomainDef
import org.tatrman.ttr.parser.model.WorldDef
import org.tatrman.ttr.semantics.conformance.SemanticsConformanceDump
import java.nio.file.Files
import java.nio.file.Path

/** Checks committed data only: additions to the live file/directory roster cannot bypass this gate. */
class CanonicalYamlConformanceSpec :
    FunSpec({
        val roster = YamlFixtureRoster(Path.of("../../../tests/conformance/fixtures"))

        test("every positive file in the current roster has a committed twin, with no orphan twins") {
            roster.requireTwins()
        }

        test("native extension stem collisions are refused before generating twins") {
            val root = Files.createTempDirectory("yaml-collision-")
            try {
                Files.writeString(root.resolve("a.ttrm"), "model er\n")
                Files.writeString(root.resolve("a.ttrg"), "graph G {}\n")
                shouldThrow<IllegalStateException> { YamlFixtureRoster(root) }.message!! shouldContain
                    "Twin stem collision"
                Files.exists(root.resolve("a.yaml")) shouldBe false
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        test("committed twins contain no indentation-only blank lines") {
            for (scenario in roster.positive) {
                for (file in scenario.files) {
                    withClue(roster.twin(file)) {
                        Files.readAllLines(roster.twin(file)).any { it.isNotEmpty() && it.isBlank() } shouldBe false
                    }
                }
            }
        }

        test("the roster rejects missing twins and discovers new root and directory inputs") {
            val root = Files.createTempDirectory("yaml-roster-")
            try {
                Files.writeString(root.resolve("one.ttrm"), "model er\n")
                shouldThrow<IllegalStateException> { YamlFixtureRoster(root).requireTwins() }.message!! shouldContain
                    "one.yaml"
                Files.writeString(root.resolve("one.yaml"), "ttr: \"0.21\"\nmodel: er\n")
                YamlFixtureRoster(root).requireTwins()
                val dir = Files.createDirectory(root.resolve("new-scenario"))
                Files.writeString(dir.resolve("a.ttrm"), "model md\n")
                shouldThrow<IllegalStateException> { YamlFixtureRoster(root).requireTwins() }.message!! shouldContain
                    "new-scenario/a.yaml"
                Files.writeString(dir.resolve("a.yaml"), "ttr: \"0.21\"\nmodel: md\n")
                YamlFixtureRoster(root).requireTwins()
                Files.writeString(dir.resolve("b.ttrm"), "model cn\n")
                shouldThrow<IllegalStateException> { YamlFixtureRoster(root).requireTwins() }.message!! shouldContain
                    "new-scenario/b.yaml"
                Files.writeString(root.resolve("orphan.yaml"), "ttr: \"0.21\"\n")
                Files.writeString(dir.resolve("b.yaml"), "ttr: \"0.21\"\nmodel: cn\n")
                shouldThrow<IllegalStateException> { YamlFixtureRoster(root).requireTwins() }.message!! shouldContain
                    "Orphan"
            } finally {
                Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }

        for (scenario in roster.positive) {
            test("committed YAML AST, semantics and fixed points: ${scenario.name}") {
                val nativeDocs = mutableListOf<SemanticsConformanceDump.DocInput>()
                val yamlDocs = mutableListOf<SemanticsConformanceDump.DocInput>()
                val roundTripDocs = mutableListOf<SemanticsConformanceDump.DocInput>()
                for (file in scenario.files) {
                    val uri =
                        roster.root
                            .relativize(file)
                            .toString()
                            .replace('\\', '/')
                    withClue(uri) {
                        val original = TtrLoader.parseString(Files.readString(file), uri)
                        original.errors shouldBe emptyList()
                        val bytes = Files.readString(roster.twin(file))
                        val doc = CanonicalYaml.read(bytes, uri)
                        doc.ast.errors shouldBe emptyList()
                        val fromText = CanonicalYaml.fromTtr(Files.readString(file), uri)
                        CanonicalYaml.write(fromText) shouldBe bytes
                        CanonicalYaml.write(doc) shouldBe bytes
                        astDump(doc.ast) shouldBe astDump(original)
                        // The document projection also covers graph and syntax distinctions absent in the dump.
                        doc.value shouldBe fromText.value
                        val back = CanonicalYaml.fromTtr(CanonicalYaml.toTtr(doc), uri)
                        back.ast.errors shouldBe emptyList()
                        back.value shouldBe fromText.value
                        CanonicalYaml.write(back) shouldBe bytes
                        astDump(back.ast) shouldBe astDump(original)
                        nativeDocs += SemanticsConformanceDump.DocInput(uri, original)
                        yamlDocs += SemanticsConformanceDump.DocInput(uri, doc.ast)
                        roundTripDocs += SemanticsConformanceDump.DocInput(uri, back.ast)
                    }
                }
                SemanticsConformanceDump.dumpDocs(yamlDocs) shouldBe SemanticsConformanceDump.dumpDocs(nativeDocs)
                SemanticsConformanceDump.dumpDocs(roundTripDocs) shouldBe SemanticsConformanceDump.dumpDocs(nativeDocs)
            }
        }
        test("explicit document context graph security repeated-property and authored-order vectors") {
            fun doc(name: String) =
                CanonicalYaml.read(
                    Files.readString(roster.root.resolve("125-yaml-document-order/$name.yaml")),
                )
            val er = doc("a-er")
            er.value.string("package") shouldBe "demo.order"
            er.value["imports"] shouldBe JsonArray(listOf(text("demo.shared.*"), text("demo.extra.Item")))
            er.value.string("schema") shouldBe "retail"
            er.ast.definitions.map { it.name } shouldBe listOf("Zeta", "Alpha")
            val security = er.value.getValue("security").jsonArray
            security.map { it.jsonArray.map { statement -> statement.jsonObject.string("verb") } } shouldBe
                listOf(listOf("own", "grant"), listOf("classify", "mask"))
            val cn =
                doc("b-cn")
                    .value
                    .getValue("defs")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("properties")
                    .jsonArray
            cn.map { it.jsonObject.keys.single() } shouldBe
                listOf("label", "custom", "other", "definition")
            val fields =
                (doc("c-world").ast.definitions.single() as WorldDef)
                    .storages
                    .single()
                    .schemas
                    .single()
                    .fields
            fields.map { it.name } shouldBe listOf("zeta", "alpha")
            (doc("d-md").ast.definitions.single() as MdDomainDef).restrictMembers shouldBe listOf("z", "a")
            val graph = doc("e-graph").value.getValue("graph").jsonObject
            graph.string("id") shouldBe "Overview"
            val props = graph.getValue("properties").jsonObject
            props.keys.toList() shouldBe listOf("model", "description", "tags", "objects", "layout")
            props["objects"] shouldBe JsonArray(listOf(text("er.entity.Zeta"), text("er.entity.Alpha")))
            doc("f-lexicon").ast.modelDirective?.locale shouldBe "cs"
        }
        for (scenario in roster.negative) {
            for (file in scenario.files) {
                test("parser-negative input stays rejected: ${roster.root.relativize(file)}") {
                    val result = TtrLoader.parseFile(file)
                    result.ok shouldBe false
                    try {
                        CanonicalYaml.fromTtr(Files.readString(file), file.toString()).ast.ok shouldBe false
                    } catch (_: CanonicalYamlException) {
                        // Syntax rejection and the typed parser's rejection are both valid here.
                    }
                }
            }
        }
        val cases = YamlFixtureRoster(Path.of("../../../tests/conformance/yaml-cases"))
        test("explicit diagnostic-bearing cases also require committed twins") { cases.requireTwins() }
        test("repeated.ttrm grammar properties and diagnostics survive") {
            val file = cases.root.resolve("repeated.ttrm")
            val original = TtrLoader.parseFile(file)
            original.errors.size shouldBe 2
            val bytes = Files.readString(cases.twin(file))
            val doc = CanonicalYaml.read(bytes)
            astDump(doc.ast) shouldBe astDump(original)
            doc.ast.errors.map { it.code to it.message } shouldBe original.errors.map { it.code to it.message }
            CanonicalYaml.write(doc) shouldBe bytes
            val native = CanonicalYaml.fromTtr(CanonicalYaml.toTtr(doc))
            astDump(native.ast) shouldBe astDump(original)
            val properties =
                doc.value
                    .getValue("defs")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("properties")
                    .jsonArray
            properties.map { it.jsonObject.keys.single() } shouldBe listOf("label", "custom", "custom", "label")
            SemanticsConformanceDump.dump(doc.ast, "repeated.ttrm") shouldBe
                SemanticsConformanceDump.dump(original, "repeated.ttrm")
        }
    })

// The shared dump defines the compared fields. Normalize JSON object keys only; authored lists stay ordered.
// Some retained map nodes in dumpTree preserve insertion order, which is not semantic JSON object order.
private fun astDump(result: ParseResult): String {
    fun sorted(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
            is JsonArray -> JsonArray(value.map(::sorted))
            else -> value
        }
    return sorted(ConformanceDump.dumpTree(result)).toString()
}
