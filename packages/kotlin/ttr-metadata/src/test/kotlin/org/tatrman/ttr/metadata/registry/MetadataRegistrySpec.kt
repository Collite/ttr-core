// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.export.DefinitionGraph
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

class MetadataRegistrySpec :
    StringSpec({
        "loading identical source versions gives a stable export snapshot" {
            val loader =
                MetadataLoader(
                    FileBasedSource(
                        "xm",
                        100,
                        LocalFsStorage("xm", Path.of("src/testFixtures/resources/fixtures/xm-world/model")),
                    ),
                )
            loader
                .load()
                .model!!
                .version.value shouldBe
                loader
                    .load()
                    .model!!
                    .version.value
        }

        "sidecar-only changes refresh the snapshot and preserve the old captured sidecars" {
            val root =
                java.nio.file.Files
                    .createTempDirectory("xm-sidecars")
            try {
                java.nio.file.Files
                    .writeString(root.resolve("items.ttrm"), "model er\ndef entity Item {}")
                val sidecar = root.resolve("connections.toml")
                java.nio.file.Files
                    .writeString(sidecar, "# first")
                val source = FileBasedSource("xm", 100, LocalFsStorage("xm", root))
                val registry = MetadataRegistry()
                val refresher =
                    org.tatrman.ttr.metadata.refresh.MetadataRefresher(
                        listOf(source),
                        listOf("xm"),
                        org.tatrman.ttr.metadata.reconcile.ModelReconciler(
                            org.tatrman.ttr.metadata.model
                                .ModelDescriptor("xm", "xm"),
                        ),
                        registry,
                    )
                kotlinx.coroutines.runBlocking { refresher.refresh("", true) }
                val first = registry.readOrError()
                java.nio.file.Files
                    .writeString(sidecar, "# second")
                java.nio.file.Files.setLastModifiedTime(
                    sidecar,
                    java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() + 5000,
                    ),
                )
                kotlinx.coroutines
                    .runBlocking { refresher.refresh("", true) }
                    .single()
                    .snapshotSwapped shouldBe true
                first.exportSidecars.getValue("connections.toml") shouldBe "# first"
                registry.readOrError().exportSidecars.getValue("connections.toml") shouldBe "# second"
                registry
                    .readOrError()
                    .definitions.entries
                    .any { it.qname == "er.entity.Item" } shouldBe true
            } finally {
                java.nio.file.Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete)
                }
            }
        }
        "nine publishes evict oldest id set, keep in-flight snapshot and refresh definitions" {
            val loaded =
                MetadataLoader(
                    FileBasedSource(
                        "xm",
                        100,
                        LocalFsStorage("xm", Path.of("src/testFixtures/resources/fixtures/xm-world/model")),
                    ),
                ).load()
            val original = requireNotNull(loaded.model)
            val registry = MetadataRegistry()

            fun publish(n: Int) {
                val model = original.copy(version = original.version.copy(value = "v$n"))
                registry.swap(
                    model,
                    ModelGraph.build(model),
                    files = loaded.files,
                    exportSidecars = loaded.exportSidecars,
                )
            }
            publish(0)
            val first = registry.readOrError()
            for (n in 1..8) publish(n)
            val current = registry.readOrError()
            current.recentIdSets.keys.toList() shouldBe (1..8).map { "v$it" }
            current.recentIdSets["v0"] shouldBe null
            first.recentIdSets.keys shouldBe setOf("v0")
            current.definitions.snapshot shouldBe "v8"
            current.definitions.entries.any { it.qname == "md.measure.revenue" } shouldBe true
            val graph = DefinitionGraph.build(current.definitions)
            current.recentIdSets.getValue("v8") shouldBe
                (graph.nodes.map { it.id } + graph.edges.mapNotNull { it.id }).toSet()
            current.exportSidecars.keys shouldBe setOf("connections.toml", "renames.yaml")
            publish(8)
            registry.readOrError().recentIdSets.size shouldBe 8
        }
    })
