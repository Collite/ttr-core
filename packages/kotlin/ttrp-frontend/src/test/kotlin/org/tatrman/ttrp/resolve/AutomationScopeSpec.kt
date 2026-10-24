// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.layout.LayoutResolver
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import java.time.Instant
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode

class AutomationScopeSpec :
    StringSpec({
        val qname = QualifiedName(SchemaCode.ER, "entity", "order")
        val attr = Attribute("a", QualifiedName(SchemaCode.ER, "attribute", "order.id"), entity = qname, type = "int")
        val entity = Entity("e", qname, attributes = listOf(attr))
        val action =
            AutomationAction("a", QualifiedName(SchemaCode.AUTOMATION, "action", "release", "hartland.automation"))

        fun scope(
            entities: List<Entity> = listOf(entity),
            actions: List<AutomationAction> = listOf(action),
            stdlib: List<Entity> = emptyList(),
        ) = AutomationScope(emptyList(), actions, entities, stdlib) { _, prefix -> prefix == "hartland" }

        "bare package and canonical target refs resolve the same entity and attribute" {
            val expected = TargetResolution.Resolved(entity, attr)
            for (ref in listOf("order.id", "hartland.order.id", "er.entity.order.id")) {
                scope().target(ref) shouldBe
                    expected
            }
            for (ref in listOf("other.order.id", "order.missing", "hartland.order", "id", "order..id")) {
                scope().target(ref) shouldBe TargetResolution.Unresolved(ref)
            }
        }

        "ambiguous entities attributes and actions are distinct from unresolved names" {
            val other = entity.copy(qname = qname.copy(`package` = "other"))
            scope(entities = listOf(entity, other)).target("order.id") shouldBe TargetResolution.Ambiguous("order.id")
            scope(entities = listOf(entity.copy(attributes = listOf(attr, attr.copy(internalId = "second")))))
                .target("order.id") shouldBe TargetResolution.Ambiguous("order.id")
            scope(actions = listOf(action, action.copy(qname = action.qname.copy(`package` = "other"))))
                .action("release") shouldBe ActionResolution.Ambiguous("release")
            scope().action("missing") shouldBe ActionResolution.Unresolved("missing")
            scope().action("release") shouldBe ActionResolution.Resolved(action)
        }

        "imported entities take precedence over the stdlib and only absent imported names fall back" {
            val standard = entity.copy(internalId = "stdlib", attributes = listOf(attr.copy(type = "text")))
            scope(stdlib = listOf(standard)).target("order.id") shouldBe TargetResolution.Resolved(entity, attr)
            scope(entities = emptyList(), stdlib = listOf(standard)).target("order.id") shouldBe
                TargetResolution.Resolved(standard, standard.attributes.single())
        }

        "rereview R1 approval imports use declared packages independently of checkout directories" {
            val root = tempdir("approval-scope").toPath().resolve("private/cache")
            Files.createDirectories(root)
            Files.writeString(
                root.resolve("entity.ttrm"),
                "package hartland\nmodel er\ndef entity order { attributes: [def attribute id { type: int }] }",
            )
            Files.writeString(
                root.resolve("action.ttrm"),
                "package hartland.automation\nmodel automation\ndef action release { columns: [def column id { type: order.id }] }",
            )
            val index = ModelRepo.indexOf(root)!!
            val imports = listOf(ModelIndex.importScope(listOf("hartland"), "hartland"))
            val resolved = index.automationScope(imports)
            (resolved.action("release") is ActionResolution.Resolved) shouldBe true
            for (ref in listOf("order.id", "hartland.order.id", "er.entity.order.id")) {
                (resolved.target(ref) is TargetResolution.Resolved) shouldBe true
            }
            resolved.target("hartland.er.order.id") shouldBe TargetResolution.Unresolved("hartland.er.order.id")
            (resolved.target("automation.obligation.state") is TargetResolution.Resolved) shouldBe true
            val loaded =
                ModelReconciler(ModelDescriptor("manual", "manual")).reconcile(
                    listOf(
                        FileBasedSource("manual", 0, LocalFsStorage("manual", root.toRealPath())).load(),
                    ),
                )
            val manual =
                ModelIndex(
                    RegistrySnapshot(loaded.model, ModelGraph.build(loaded.model), Instant.EPOCH, emptyList()),
                    LayoutResolver.packagesFromFiles(loaded.files),
                ).automationScope(imports)
            for (ref in listOf(
                "hartland.er.order.id",
                "er.order.id",
                "private.order.id",
                "er.entity.order.id",
                "hartland.order.id",
            )) {
                manual.target(ref) shouldBe resolved.target(ref)
            }
        }

        "rereview R1 a checkout directory cannot import unrelated actions or targets" {
            val root = tempdir("approval-scope").toPath().resolve("private/cache")
            Files.createDirectories(root)
            Files.writeString(
                root.resolve("objects.ttrm"),
                "package hartland\nmodel er\ndef entity order { attributes: [def attribute id { type: int }] }",
            )
            Files.writeString(
                root.resolve("actions.ttrm"),
                "package hartland.automation\nmodel automation\ndef action release { sinks: { native: writer } }",
            )
            val resolved =
                ModelRepo
                    .indexOf(
                        root,
                    )!!
                    .automationScope(listOf(ModelIndex.importScope(listOf("private"), "private")))
            resolved.action("release") shouldBe ActionResolution.Unresolved("release")
            resolved.target("order.id") shouldBe TargetResolution.Unresolved("order.id")
        }

        "rereview R2 an explicit stdlib qualifier bypasses project shadowing" {
            val standard = entity.copy(internalId = "stdlib", attributes = listOf(attr.copy(type = "text")))
            val resolved =
                AutomationScope(emptyList(), emptyList(), listOf(entity), listOf(standard)) { e, prefix ->
                    prefix == if (e.internalId == "stdlib") "automation" else "hartland"
                }
            resolved.target("order.id") shouldBe TargetResolution.Resolved(entity, attr)
            resolved.target("automation.order.id") shouldBe
                TargetResolution.Resolved(standard, standard.attributes.single())
        }

        "rereview n5 target conflicts are reported by the shared collector" {
            val other = entity.copy(qname = qname.copy(`package` = "other"))
            val mapped =
                action.copy(
                    columns =
                        listOf(
                            org.tatrman.ttr.metadata.model
                                .AutomationColumn("one", "first.order.id"),
                            org.tatrman.ttr.metadata.model
                                .AutomationColumn("two", "second.order.id"),
                        ),
                )
            val targets =
                AutomationScope.targetsOf(mapped) { ref ->
                    TargetResolution.Resolved(if (ref.startsWith("first.")) entity else other, attr)
                }
            targets.getValue("first.order.id") shouldBe TargetResolution.Resolved(entity, attr)
            targets.getValue("second.order.id") shouldBe TargetResolution.Ambiguous("second.order.id")
        }
    })
