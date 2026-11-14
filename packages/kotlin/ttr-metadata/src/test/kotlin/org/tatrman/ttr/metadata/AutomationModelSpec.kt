// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationDuration
import org.tatrman.ttr.metadata.model.AutomationParameter
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.AutomationRole
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.GrantWho
import org.tatrman.ttr.metadata.model.ModelDescriptor
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
 * `writable:` on storages, `states:`/`transitions:` on attributes, and the implicit automation stdlib. Since grammar
 * 0.22 (D-142) its orchestration constructs are load errors and leave the typed tier.
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

        "design 17 §1–§2 load into the automation tier; D-142's removed constructs are load errors" {
            val r = hartland()
            r.errors
                .map { it.message.substringBefore(':') }
                .groupingBy { it }
                .eachCount() shouldBe
                mapOf(
                    "ttr/automation-removed" to 33,
                    // `returns_n8n { process: … }` has no program: it does not load
                    "ttr/automation-deployment-dropped" to 1,
                )
            // … and the drop names the cut, not a missing program (the flow moves to the engine)
            r.errors.single { it.message.startsWith("ttr/automation-deployment-dropped") }.message.let {
                it shouldContain "deployment 'returns_n8n' runs the removed `process:` (CUT-002"
                it shouldNotContain "program:`"
            }
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
            // D-142: `deliver:` and `schedule:` are no typed fields; they ride as authored, for the checkers to refuse
            program.properties.map { it.first } shouldContainExactly
                listOf("recipe", "port", "mode", "as_of", "deliver", "schedule")
            program.qname.dotted() shouldBe "automation.program.morning_release"
            val decide =
                q.getObject(
                    QualifiedName(SchemaCode.AUTOMATION, "program", "decide_on_request", "hartland.automation"),
                )
            (decide as AutomationProgram).port shouldBe "event" // refused (TTRP-CUT-009), read as written
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
            morning.target shouldBe "native" // refused (TTRP-CUT-004), read as written
            morning.runAs shouldBe RunAsRef.Identity("automation_decider")
            morning.enabled shouldBe true
            world.deployment("release_n8n")!!.granularity shouldBe "parts"
            // D-142: `def process` is recognised only to be refused; a deployment of one does not load, but its
            // removed entries are still the world's — one record each, at its entry
            world.deployment("returns_n8n").shouldBeNull()
            world.removed.groupingBy { "${it.keyword}.${it.key}" }.eachCount() shouldBe
                mapOf(
                    "process.process" to 1,
                    "deployment.process" to 1,
                    "deployment.target" to 8,
                    "deployment.trigger" to 4,
                )
            world.removed.single { it.keyword == "process" }.name shouldBe "return_line_flow"
            world.removed.single { it.keyword == "deployment" && it.key == "process" }.name shouldBe "returns_n8n"
            val trigger = world.removed.first { it.key == "trigger" }
            trigger.name shouldBe "release_native_morning"
            trigger.sourceLocation!!.column shouldBe
                Files
                    .readAllLines(
                        locate("packages/kotlin/ttr-parser/src/test/resources/automation").resolve("demo-world.ttrm"),
                    )[trigger.sourceLocation!!.line - 1]
                    .indexOf("trigger:")
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
                def entity return_request_line { attributes: [ def attribute line_id { type: text, isKey: true } ] }
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
                (
                    r.model.schemas.getValue(
                        "er",
                    ) as ErSchema
                ).entities.values.single { it.qname.name == "order" }.attributes.single {
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

        // ----- review-114 -----

        fun project(vararg files: Pair<String, String>): ReconciliationResult {
            val root = Files.createTempDirectory("au-review")
            for ((rel, content) in files) {
                val p = root.resolve(rel)
                Files.createDirectories(p.parent)
                Files.writeString(p, content.trimIndent())
            }
            return reconcile(root)
        }

        val automationFile =
            "acme/automation/a.ttrm" to
                """
                package acme.automation
                model automation
                def program p { recipe: r, port: none, mode: batch }
                def role ops { resolve: keycloak_group "ops" }
                """

        "M4: a malformed grant is dropped with a load error — it never widens" {
            val r =
                project(
                    automationFile,
                    "acme/worlds/w.ttrm" to
                        """
                        package acme.worlds
                        model world
                        def world w {
                            def grant { who: role.ops, program: "release", as: author }
                            def grant { who: role.ops, as: author }
                            def grant { who: user.jan.novak, program: *, as: author }
                            def grant { who: *, program: "*", as: author }
                            def grant { who: user "jan-novak@x", program: p, as: author }
                        }
                        """,
                )
            val world = (r.model.schemas.getValue("world") as WorldSchema).worlds.values.single()
            world.grants.map { it.who } shouldContainExactly
                listOf(GrantWho.User("jan.novak"), GrantWho.Anyone, GrantWho.User("jan-novak@x"))
            world.grants.map { it.anyProgram } shouldContainExactly listOf(true, true, false)
            r.errors.count { it.message.startsWith("ttr/automation-grant-dropped") } shouldBe 2
        }

        "M4: a deployment with a non-boolean flag is dropped with a load error" {
            val r =
                project(
                    automationFile,
                    "acme/worlds/w.ttrm" to
                        """
                        package acme.worlds
                        model world
                        def world w {
                            def executor x { extends: n8n_l1 }
                            def deployment off  { program: p, target: x, run_as: caller, enabled: "false" }
                            def deployment dry  { program: p, target: x, run_as: caller, dry_run: "true" }
                            def deployment obo  { program: p, target: x, run_as: caller, obo_only: "true" }
                            def deployment good { program: p, target: x, run_as: caller }
                        }
                        """,
                )
            val world = (r.model.schemas.getValue("world") as WorldSchema).worlds.values.single()
            world.deployments.map { it.qname.name } shouldContainExactly listOf("good")
            world.deployment("good")!!.enabled shouldBe true
            r.errors.count { it.message.startsWith("ttr/automation-deployment-dropped") } shouldBe 3
            // the validator's reasons are on the load path too
            r.errors.count {
                it.sourceId == "automation-validator" &&
                    it.message.startsWith("ttr/automation-invalid-value")
            } shouldBe
                3
        }

        "review-269 N3: a world member's drop and a validator error carry 1-based columns" {
            val world =
                project(
                    automationFile,
                    "acme/worlds/w.ttrm" to
                        """
                        package acme.worlds
                        model world
                        def world w {
                            def executor x { extends: n8n_l1 }
                            def deployment off  { program: p, target: x, run_as: caller, enabled: "false" }
                        }
                        """,
                )
            // Source.kt's world site: `def deployment` is indented four, so it starts in column 5.
            world.errors.single { it.message.startsWith("ttr/automation-deployment-dropped") }.column shouldBe 5
            val validated =
                project(
                    automationFile,
                    "acme/automation/b.ttrm" to
                        """
                        package acme.automation
                        model automation
                        def schema s { columns: [def column a { type: text }] }
                        def service q { request: nope, response: s }
                        """,
                )
            // ModelReconciler's validator site: the reference `nope`.
            validated.errors.single().column shouldBe "def service q { request: nope".indexOf("nope") + 1
        }

        "M4: automation validation errors are load errors" {
            val r =
                project(
                    automationFile,
                    "acme/automation/b.ttrm" to
                        """
                        package acme.automation
                        model automation
                        def schema s { columns: [def column a { type: text }] }
                        def service q { request: nope, response: s }
                        """,
                )
            r.errors.map { it.message.substringBefore(':') } shouldContainExactly
                listOf("ttr/automation-event-not-schema")
        }

        "M6: two sources with `model automation` get the stdlib once; an ER package named `plant.automation` none" {
            fun source(
                id: String,
                pkg: String,
            ): org.tatrman.ttr.metadata.source.SourceSnapshot {
                val root = Files.createTempDirectory("au-src")
                val dir = root.resolve(pkg.replace('.', '/'))
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve("a.ttrm"),
                    "package $pkg\nmodel automation\ndef parameter t_$id { type: int, value: 1 }",
                )
                return FileBasedSource(id, 100, LocalFsStorage(id = id, rootPath = root)).load()
            }
            val two =
                ModelReconciler(ModelDescriptor(id = "d", name = "d"))
                    .reconcile(listOf(source("a", "x.automation"), source("b", "y.automation")))
            two.errors shouldBe emptyList()
            (two.model.schemas.getValue("er") as ErSchema).entities.keys.count { it.name == "obligation" } shouldBe 1

            val er =
                project(
                    "plant/automation/m.ttrm" to
                        """
                        package plant.automation
                        model er
                        def entity machine { attributes: [ def attribute id { type: text, isKey: true } ] }
                        """,
                )
            (er.model.schemas.getValue("er") as ErSchema).entities.keys.none { it.name == "obligation" } shouldBe true
        }

        "B1: a package segment named `automation` stays a package" {
            org.tatrman.ttr.metadata.source.Reference
                .toQname("plant.automation.order.state", "db", "dbo") shouldBe
                FileBasedSource.qname("plant", "automation", "order.state")
            val r =
                project(
                    "hartland/automation/events.ttrm" to
                        """
                        package hartland.automation
                        model automation
                        def schema line_received { columns: [def column line_id { type: text }] }
                        def program on_line { recipe: r, port: key, mode: on_event }
                        """,
                    "hartland/x/e.ttrm" to
                        """
                        package hartland.x
                        import hartland.automation.*
                        model er
                        def entity e { attributes: [ def attribute id { type: text, isKey: true } ] }
                        """,
                )
            r.errors shouldBe emptyList()
            r.warnings.none { it.message.contains("unimported") || it.message.contains("unresolved") } shouldBe true
            r.model.rowSchemas.keys shouldContainExactly listOf("hartland.automation.line_received")
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
