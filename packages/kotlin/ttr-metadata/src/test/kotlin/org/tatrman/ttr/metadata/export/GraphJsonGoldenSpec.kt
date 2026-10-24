// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

internal fun xmJsonSet(): DefinitionSet =
    loadXmWorld().let {
        DefinitionSet.from(it.files, it.model, "0.18", "fixture-1")
    }

internal fun fixtureConnections(): List<ConnectionDescriptor> {
    val toml = org.tomlj.Toml.parse(Path.of("src/testFixtures/resources/fixtures/xm-world/model/connections.toml"))
    toml.errors() shouldBe emptyList()

    fun table(t: org.tomlj.TomlTable): Map<String, Any> =
        t.keySet().associateWith { key ->
            val value = requireNotNull(t.get(key))
            if (value is org.tomlj.TomlTable) table(value) else value
        }
    val connections: org.tomlj.TomlArray = requireNotNull(toml.getArray("connection"))
    return (0 until connections.size()).map { i ->
        val c = connections.getTable(i)
        val locators: org.tomlj.TomlTable? = c.getTable("locators")
        ConnectionDescriptor(
            requireNotNull(
                c.getString("id"),
            ),
            c.getString("database"),
            c.getString("schema"),
            if (locators == null) emptyMap() else table(locators),
        )
    }
}

internal fun golden(
    format: String,
    name: String,
    actual: ByteArray,
) {
    val path = Path.of("src/test/resources/xm-goldens", format, "$name.json")
    if (name != "contract" &&
        System.getProperty("updateGolden") == "true"
    ) {
        Files.createDirectories(path.parent)
        Files.write(path, actual)
    }
    actual.toList() shouldBe Files.readAllBytes(path).toList()
}

class GraphJsonGoldenSpec :
    StringSpec({
        "hand-authored C-4 sample pins key order, nulls, maps, stubs and three edges" {
            val nodes =
                listOf("c", "a", "b").map {
                    Node(
                        it,
                        kind = "kind",
                        iri = "urn:$it",
                        model = "cn",
                        label = it,
                        pkg = "cn",
                        displayLabel =
                            mapOf(
                                "en" to "E",
                                "cs" to "Č",
                            ),
                        description = emptyMap(),
                        synonyms = emptyMap(),
                        hasDefinition = false,
                    )
                }
            val edges =
                listOf(Edge("b", "c", "is_a"), Edge("a", "c", "grounds"), Edge("a", "b", "involves", label = "role"))
            golden(
                "graph.json",
                "contract",
                GraphJsonWriter.write(DefinitionGraph(nodes, edges), ExportHeader("0.18", "s1", "sample")),
            )
        }
        "fixture graph goldens" {
            val set = xmJsonSet()
            val graph = DefinitionGraph.build(set)
            golden("graph.json", "world", GraphJsonWriter.write(graph, ExportHeader.from(set)))
            val exporter = Exporter()
            for ((name, request) in listOf(
                "cn" to ExportRequest(setOf("cn"), "graph.json"),
                "scoped-er" to ExportRequest(setOf("world"), "graph.json", packages = setOf("er")),
                "since" to
                    ExportRequest(
                        setOf("world"),
                        "graph.json",
                        since = "fixture-0",
                        previousIds = setOf("md.measure.turnover", "er.entity.retired"),
                        renames = listOf(Rename("md.measure.turnover", "md.measure.revenue", "fixture-1")),
                    ),
            )) {
                golden("graph.json", name, (exporter.export(set, null, request) as ExportResult.Single).bytes)
            }
        }
        "scoped external nodes have exactly three fields; JSON bytes repeat" {
            val set = xmJsonSet()
            val graph = ExportScope(packages = setOf("er")).apply(DefinitionGraph.build(set), set)
            val bytes = GraphJsonWriter.write(graph, ExportHeader.from(set, ExportScope(packages = setOf("er"))))
            val doc = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            doc.getValue("nodes").jsonArray.filter { it.jsonObject["external"] == JsonPrimitive(true) }.forEach {
                it.jsonObject.keys shouldBe setOf("id", "kind", "external")
            }
            GraphJsonWriter
                .write(
                    graph.copy(nodes = graph.nodes.reversed(), edges = graph.edges.reversed()),
                    ExportHeader.from(set, ExportScope(packages = setOf("er"))),
                ).toList() shouldBe
                bytes.toList()
        }
        "scope key order follows C-4 and selector values sort bytewise" {
            val bytes =
                GraphJsonWriter.write(
                    DefinitionGraph(emptyList(), emptyList()),
                    ExportHeader("0.18", "s1", "sample", ExportScope(setOf("z", "a"), setOf("retail"))),
                )
            val scope =
                Json
                    .parseToJsonElement(bytes.decodeToString())
                    .jsonObject
                    .getValue("scope")
                    .jsonObject
            scope.keys.toList() shouldBe listOf("packages", "areas")
            scope.getValue("packages").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("a", "z")
        }
    })
