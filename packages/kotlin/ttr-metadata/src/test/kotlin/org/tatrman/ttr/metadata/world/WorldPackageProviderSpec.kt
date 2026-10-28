// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.world

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.metadata.fixtures.MetadataFixtures
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.layout.LayoutResolver
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.time.Instant

class WorldPackageProviderSpec :
    StringSpec({
        val root = tempdir("world-packages").toPath().resolve("private/cache/checkout")
        Files.createDirectories(root)
        Files.walk(MetadataFixtures.erpModelsRoot()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().forEachIndexed { i, file ->
                Files.copy(file, root.resolve("metadata-$i.ttrm"))
            }
        }
        val loaded =
            ModelReconciler(ModelDescriptor("test", "test")).reconcile(
                listOf(FileBasedSource("test", 100, LocalFsStorage("test", root)).load()),
            )
        val snapshot = RegistrySnapshot(loaded.model, ModelGraph.build(loaded.model), Instant.EPOCH, emptyList())
        val packages = LayoutResolver.packagesFromFiles(loaded.files)
        val world = QualifiedName(SchemaCode.WORLD, "", "dev", "acme.worlds")

        "loader packages resolve a relocated world with overlays and unchanged host lists" {
            val expected =
                WorldResolver(MetadataFixtures.loadErpSnapshot())
                    .resolve(world)
                    .shouldBeInstanceOf<WorldResolution.Ok>()
                    .world
            val actual =
                WorldResolver(snapshot, packages)
                    .resolve(world)
                    .shouldBeInstanceOf<WorldResolution.Ok>()
                    .world
            actual.fingerprint shouldBe expected.fingerprint
            actual.storages.map { Triple(it.qname, it.hosts, it.via) } shouldBe
                expected.storages.map { Triple(it.qname, it.hosts, it.via) }
            actual.engines.map { Triple(it.type, it.version, it.extendsRef) } shouldBe
                expected.engines.map { Triple(it.type, it.version, it.extendsRef) }
        }
        "provider package segments match descendants but never partial prefixes or source paths" {
            WorldResolver(snapshot) { "erp.sales" }.resolve(world).shouldBeInstanceOf<WorldResolution.Ok>()
            for (pkg in listOf("er", "erpish", "erp_sales", "")) {
                WorldResolver(snapshot) { pkg }.resolve(world).shouldBeInstanceOf<WorldResolution.HostsUnknownPackage>()
            }
            // A supplied but incomplete map is authoritative, even where the original path contains /erp/.
            val original = MetadataFixtures.loadErpSnapshot()
            WorldResolver(original) { "" }.resolve(world).shouldBeInstanceOf<WorldResolution.HostsUnknownPackage>()
        }
        "the snapshot-only constructor reads dotted hosts as whole directory segments" {
            for ((host, matches) in listOf("acme.sales" to true, "acme.sale" to false)) {
                val legacy = tempdir("legacy-dotted-host").toPath()
                val table = legacy.resolve("x/acme/sales/table.ttrm")
                Files.createDirectories(table.parent)
                Files.writeString(table, "package acme.sales\nmodel db\ndef table orders { columns: [] }")
                Files.writeString(
                    legacy.resolve("world.ttrm"),
                    """
                    package worlds
                    model world
                    def world dev { def storage data { type: postgres, hosts: [$host] } }
                    """.trimIndent(),
                )
                val reconciled =
                    ModelReconciler(ModelDescriptor("test", "test")).reconcile(
                        listOf(FileBasedSource("test", 100, LocalFsStorage("test", legacy)).load()),
                    )
                val snap =
                    RegistrySnapshot(reconciled.model, ModelGraph.build(reconciled.model), Instant.EPOCH, emptyList())
                val result = WorldResolver(snap).resolve(QualifiedName(SchemaCode.WORLD, "", "dev", "worlds"))
                if (matches) {
                    result.shouldBeInstanceOf<WorldResolution.Ok>()
                } else {
                    result.shouldBeInstanceOf<WorldResolution.HostsUnknownPackage>()
                }
            }
        }
        "the snapshot-only JVM constructor remains available for existing binaries" {
            val original = MetadataFixtures.loadErpSnapshot()
            val constructor = WorldResolver::class.java.getConstructor(RegistrySnapshot::class.java)
            constructor.newInstance(original).resolve(world).shouldBeInstanceOf<WorldResolution.Ok>()
            WorldResolver(snapshot).resolve(world).shouldBeInstanceOf<WorldResolution.HostsUnknownPackage>()
        }
    })
