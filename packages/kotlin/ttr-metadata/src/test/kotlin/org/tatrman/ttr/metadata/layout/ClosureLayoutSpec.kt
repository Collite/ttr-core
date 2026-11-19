// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.layout

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.YamlFileSource
import org.tatrman.ttr.parser.model.LayoutPoint
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** MW S4.3 T1/T4 and D-MW-108: projection of an invented mixed-schema ResolveTags result. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ClosureLayoutSpec :
    StringSpec({
        fun snapshot(firstPackage: String = "demo.alpha"): RegistrySnapshot {
            val root = Files.createTempDirectory("mw-closure")
            try {
                val files =
                    listOf(
                        firstPackage to
                            """
                            model er
                            def entity widget { attributes: [def attribute code { type: int }] }
                            def entity crate { attributes: [def attribute code { type: int }] }
                            def entity decoy { tags: ["other"] }
                            def relation packed {
                                tags: ["selected"], from: er.entity.widget, to: er.entity.crate,
                                cardinality: { from: "0..*", to: "1" }
                            }
                            """.trimIndent(),
                        "demo.omega" to
                            """
                            model er
                            def entity rack { tags: ["selected"] }
                            def entity neighbour { }
                            def relation adjacent {
                                from: er.entity.rack, to: er.entity.neighbour,
                                cardinality: { from: "0..*", to: "1" }
                            }
                            """.trimIndent(),
                        firstPackage to
                            """
                            model db schema dbo
                            def table widget_row {
                                tags: ["selected"], columns: [def column code { type: int }]
                            }
                            def table spare { columns: [def column code { type: int }] }
                            def view widget_view {
                                tags: ["selected"], columns: [def column code { type: int }]
                            }
                            """.trimIndent(),
                    )
                files.forEachIndexed { i, (pkg, ttr) ->
                    val file = root.resolve(pkg.replace('.', '/')).resolve("model$i.yaml")
                    file.parent.createDirectories()
                    file.writeText(CanonicalYaml.write(CanonicalYaml.fromTtr("package $pkg\n$ttr\n")))
                }
                val source = YamlFileSource("t", 100, LocalFsStorage("t", root)).load()
                source.errors.shouldBeEmpty()
                val loaded = ModelReconciler(ModelDescriptor("t", "t")).reconcile(listOf(source))
                loaded.errors.shouldBeEmpty()
                val registry = MetadataRegistry()
                registry.swap(loaded.model, ModelGraph.build(loaded.model), files = loaded.files)
                return registry.readOrError()
            } finally {
                root.deleteRecursively()
            }
        }
        val captured = snapshot()

        // The caller owns ResolveTags. Supply its structural result: tagged cards and relation endpoints,
        // plus contained parts. In particular, widget/crate have no tag and must still be in the picture.
        fun closure(snap: RegistrySnapshot = captured): List<ModelObject> {
            val objects = snap.model.objectByQname()
            val tagged = objects.values.filter { "selected" in it.tags }
            val endpoints = tagged.filterIsInstance<Relation>().flatMap { listOf(it.fromEntity, it.toEntity) }.toSet()
            return objects.values.filter {
                it in tagged || it.qname in endpoints || it.kind in setOf("attribute", "column")
            }
        }
        "default ER projects exactly the closure cards across packages, with no neighbours or DB cards" {
            val layout = closureLayout(captured, closure())
            layout.name shouldBe "closure_er"
            layout.schema shouldBe "er"
            layout.objects shouldBe
                listOf(
                    "demo.alpha.er.entity.crate",
                    "demo.alpha.er.entity.widget",
                    "demo.omega.er.entity.rack",
                )
            layout.positions shouldBe
                mapOf(
                    "demo.alpha.er.entity.crate" to LayoutPoint(40.0, 40.0),
                    "demo.alpha.er.entity.widget" to LayoutPoint(360.0, 40.0),
                    "demo.omega.er.entity.rack" to LayoutPoint(40.0, 480.0),
                )
            layout.positionsFrom shouldBe null
            layout.source shouldBe "computed:tags"
            layout.missing.shouldBeEmpty()
            layout.ambiguous.shouldBeEmpty()
            layout.ignored.shouldBeEmpty()
        }
        "DB projects both tables and views, without columns or ER cards" {
            val layout = closureLayout(captured, closure(), "db")
            layout.name shouldBe "closure_db"
            layout.schema shouldBe "db"
            layout.objects shouldBe listOf("demo.alpha.db.dbo.widget_row", "demo.alpha.db.dbo.widget_view")
            layout.positions.keys.toList() shouldBe layout.objects
            layout.positions.values
                .toSet()
                .size shouldBe 2
        }
        "placement is identical for reversed and duplicated closure and model insertion orders" {
            val expected = closureLayout(captured, closure())
            val reversed =
                captured
                    .copy(
                        model =
                            captured.model.copy(
                                schemas =
                                    captured.model.schemas.entries.reversed().associate {
                                        it.toPair()
                                    },
                            ),
                    ).also { it.layoutPackageOf = captured.layoutPackageOf }
            closureLayout(reversed, closure().reversed() + closure()) shouldBe expected
            expected.positions.values
                .toSet()
                .size shouldBe expected.objects.size
        }
        "package provenance stays with the captured snapshot after a registry swap and file deletion" {
            val fresh = snapshot("demo.beta")
            val registry = MetadataRegistry()
            registry.swap(fresh.model, fresh.graph)
            closureLayout(captured, closure()).objects shouldBe
                listOf(
                    "demo.alpha.er.entity.crate",
                    "demo.alpha.er.entity.widget",
                    "demo.omega.er.entity.rack",
                )
            closureLayout(fresh, closure(fresh)).objects shouldBe
                listOf(
                    "demo.beta.er.entity.crate",
                    "demo.beta.er.entity.widget",
                    "demo.omega.er.entity.rack",
                )
        }
        "empty closure or a schema without selected cards returns an empty layout" {
            val empty = closureLayout(captured, emptyList())
            empty.objects.shouldBeEmpty()
            empty.positions shouldBe emptyMap()
            empty.mode shouldBe "auto"
            empty.positionsFrom shouldBe null
            val onlyEr = closure().filter { it.kind == "entity" }
            closureLayout(captured, onlyEr, "db").objects.shouldBeEmpty()
        }
        "unsupported schemas fail rather than silently selecting ER" {
            listOf("md", "cnc", "ER", "").forEach { schema ->
                shouldThrow<IllegalArgumentException> { closureLayout(captured, closure(), schema) }
            }
        }
    })
