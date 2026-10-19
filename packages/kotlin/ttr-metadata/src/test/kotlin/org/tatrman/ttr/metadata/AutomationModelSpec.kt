// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationDuration
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.DeploymentTrigger
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.GrantWho
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ProgramDelivery
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.RoleMembership
import org.tatrman.ttr.metadata.model.RunAsRef
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.query.MetadataQuery
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.reconcile.ReconciliationResult
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.parser.model.AutoValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Grammar 0.16 — the automation tier in the metadata model (AU-core-P0 S0.2 T5): design `17` §1–§2 (as written
 * for 0.16) load into typed objects — what ttr-server's bundle loader, `RunAs` and `GrantMap` read — plus
 * `writable:` on storages, `states:`/`transitions:` on attributes, and the implicit automation stdlib.
 */
class AutomationModelSpec :
    StringSpec({
        fun copy(
            root: Path,
            rel: String,
            from: Path,
        ) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, Files.readString(from))
        }

        fun reconcile(root: Path): ReconciliationResult =
            ModelReconciler(ModelDescriptor(id = "d", name = "d"))
                .reconcile(listOf(FileBasedSource("au", 100, LocalFsStorage(id = "au", rootPath = root)).load()))

        fun hartland(): ReconciliationResult {
            val fixtures = locate("packages/kotlin/ttr-parser/src/test/resources/automation")
            val root = Files.createTempDirectory("au-model")
            copy(root, "hartland/automation/automation.ttrm", fixtures.resolve("automation.ttrm"))
            copy(root, "hartland/automation/programs.ttrm", fixtures.resolve("programs.ttrm"))
            copy(root, "hartland/worlds/demo-world.ttrm", fixtures.resolve("demo-world.ttrm"))
            copy(
                root,
                "hartland/er.ttrm",
                locate("packages/kotlin/ttr-semantics/src/test/resources/automation").resolve("hartland-er-stub.ttrm"),
            )
            return reconcile(root)
        }

        "design 17 §1–§2 load clean into the automation tier" {
            val r = hartland()
            r.errors shouldBe emptyList()
            val au = r.model.schemas.getValue("automation") as AutomationSchema
            au.parameters.size shouldBe 9
            au.actions.size shouldBe 10
            au.roles.size shouldBe 8
            au.approvals.size shouldBe 4
            au.programs.size shouldBe 8
            // the automation roles are not cnc roles
            (r.model.schemas.getValue("cnc") as org.tatrman.ttr.metadata.model.CncSchema)
                .roles.keys
                .none { it.name == "sales" } shouldBe true
        }

        "GetObject returns a def program with its properties" {
            val r = hartland()
            val q = MetadataQuery(RegistrySnapshot(r.model, ModelGraph.build(r.model), Instant.EPOCH, emptyList()))
            val program =
                q.getObject(QualifiedName(SchemaCode.AUTOMATION, "program", "morning_release", "hartland.automation"))
            program.shouldBeInstanceOf<AutomationProgram>()
            program.recipe shouldBe "release_order"
            program.port shouldBe "none"
            program.mode shouldBe "batch"
            program.asOf shouldBe "run"
            program.deliver shouldBe ProgramDelivery.Digest(by = "who", per = "day")
            program.schedule shouldBe "06:00 Europe/Prague"
            program.qname.dotted() shouldBe "automation.program.morning_release"
            val decide =
                q.getObject(
                    QualifiedName(SchemaCode.AUTOMATION, "program", "decide_on_request", "hartland.automation"),
                )
            (decide as AutomationProgram).event shouldBe "line_received"
            decide.port shouldBe "event"
            decide.mode shouldBe "on_event"
        }

        "parameters, actions and roles carry their typed contract" {
            val au = hartland().model.schemas.getValue("automation") as AutomationSchema
            val tolerance = au.byName("parameter", "tolerance") as AutomationParameter
            tolerance.type shouldBe "decimal"
            (tolerance.value as AutoValue.Num).raw shouldBe 0.01
            tolerance.label.byLanguage["cs"] shouldBe "Cenová tolerance"
            val hold = au.byName("action", "hold") as AutomationAction
            hold.columns.map { it.name to it.type } shouldContainExactly
                listOf("order" to "order.order_number", "department" to "text")
            hold.sinks shouldBe mapOf("native" to "transition", "n8n" to "door", "camunda" to "door")
            hold.transition?.to shouldBe "held"
            hold.transition?.from shouldContainExactly listOf("placed")
            hold.set?.column shouldBe "department"
            hold.approval shouldBe "none"
            (au.byName("action", "ask") as AutomationAction).due shouldBe AutomationDuration(2.0, "working_days")
            (au.byName("action", "set_state") as AutomationAction).transition?.toColumn shouldBe "to"
            val sales = au.byName("role", "sales") as AutomationRole
            sales.membership shouldBe RoleMembership.KeycloakGroup("hartland-sales")
            sales.delivery shouldBe mapOf("email" to "sales@hartland.example")
        }

        "a grant round-trips its triple; a deployment its run_as; an identity its credential" {
            val world = (hartland().model.schemas.getValue("world") as WorldSchema).worlds.values.single()
            world.identity("automation_decider")?.credential shouldBe "TTR_CONN_AUTOMATION_DECIDER"
            world.identity("automation_decider")?.principal shouldBe "svc-hartland-automation"
            val first = world.grants.first()
            first.who shouldBe GrantWho.Role("ops")
            first.anyProgram shouldBe true
            first.runAs shouldBe RunAsRef.Identity("automation_decider")
            world.grants[1].program shouldBe "release_on_placement"
            world.grants[1].who shouldBe GrantWho.Role("sales_manager")
            val morning = world.deployment("release_native_morning")!!
            morning.program shouldBe "morning_release"
            morning.target shouldBe "native"
            morning.runAs shouldBe RunAsRef.Identity("automation_decider")
            morning.trigger shouldBe DeploymentTrigger.Schedule("06:00 Europe/Prague")
            morning.enabled shouldBe true
            world.deployment("release_n8n")!!.granularity shouldBe "parts"
            world.deployment("returns_n8n")!!.process shouldBe "return_line_flow"
            world.deployment("returns_n8n")!!.program.shouldBeNull()
            val flow = world.process("return_line_flow")!!
            flow.instance shouldBe "return_request_line.line_id"
            flow.steps.map { it.name } shouldContainExactly listOf("decide", "inbound", "settle")
            flow.steps[2].after?.obligations shouldContainExactly listOf("credit_note", "payment", "replacement")
            flow.steps[2].after?.until shouldContainExactly listOf("fulfilled", "due", "absent")
        }

        "writable on a storage, states and transitions on an attribute" {
            val root = Files.createTempDirectory("au-writable")
            Files.createDirectories(root.resolve("acme/worlds"))
            Files.writeString(
                root.resolve("acme/worlds/w.ttrm"),
                """
                package acme.worlds
                model world
                def world dev {
                    def engine pg { type: postgres }
                    def storage erp_db { type: postgres, via: pg, writable: [order.state, return_request_line, "acme_*"] }
                }
                """.trimIndent(),
            )
            Files.writeString(
                root.resolve("acme/er.ttrm"),
                """
                package acme
                model er
                def entity order { attributes: [ def attribute order_number { type: text, isKey: true },
                    def attribute state { type: text, states: [placed, held], transitions: { placed: [held] } } ] }
                """.trimIndent(),
            )
            val r = reconcile(root)
            r.errors shouldBe emptyList()
            val storage =
                (r.model.schemas.getValue("world") as WorldSchema)
                    .worlds.values
                    .single()
                    .storages
                    .single()
            storage.writable shouldContainExactly listOf("order.state", "return_request_line", "acme_*")
            val state =
                (r.model.schemas.getValue("er") as ErSchema).entities.values.single().attributes.single {
                    it.qname.name ==
                        "order.state"
                }
            state.states shouldContainExactly listOf("placed", "held")
            state.transitions shouldBe mapOf("placed" to listOf("held"))
            // no automation in this project → no stdlib
            (r.model.schemas.getValue("er") as ErSchema).entities.keys.none { it.name == "obligation" } shouldBe true
        }

        "the automation stdlib joins a project that declares model automation" {
            val entities = (hartland().model.schemas.getValue("er") as ErSchema).entities
            val obligation = entities.values.single { it.qname.name == "obligation" }
            obligation.attributes.single { it.qname.name == "obligation.state" }.states shouldContainExactly
                listOf("open", "fulfilled", "overdue", "cancelled", "reopened")
            entities.values.map { it.qname.name }.containsAll(listOf("state_history", "approvals")) shouldBe true
        }
    })

private fun locate(rel: String): Path {
    var dir: Path? = Paths.get("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve(rel)
        if (Files.isDirectory(candidate)) return candidate
        dir = dir.parent
    }
    error("could not locate $rel")
}
