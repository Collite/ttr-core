// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.tatrman.ttrp.emit.sql.SqlTier
import java.nio.file.Files
import java.nio.file.Path

class RecipeCrossStorageSpec :
    StringSpec({
        for (name in listOf("monitor_inbound.ttrp", "settle.ttrp")) {
            "$name cuts across ERP and automation, sharing content-addressed parts" {
                val plan = RecipeFixtures.sharedRecipe(name)
                plan.ok shouldBe true
                val statements = RecipeStatements.plan(plan)
                val combining = statements.filter { it.parts.isNotEmpty() }
                combining.shouldNotBeEmpty()
                val parts = combining.flatMap { it.parts }
                parts.map { it.storage }.toSet() shouldBe setOf("erp_db", "automation_db")
                (parts.size > parts.distinctBy { it.name }.size) shouldBe true
                parts.groupBy { it.name }.values.forEach { copies -> copies.distinct().size shouldBe 1 }
                combining.forEach { s ->
                    s.file.endsWith(".duckdb.sql") shouldBe true
                    s.parts.forEach { p -> s.sql.contains(p.name) shouldBe true }
                }
            }
        }
        for (name in listOf("release_order.ttrp", "decide_return.ttrp")) {
            "$name remains byte equal to the P2 door goldens" {
                RecipeStatements.plan(RecipeFixtures.sharedRecipe(name)).forEach { s ->
                    s.parts shouldBe emptyList()
                    val golden =
                        Path.of(
                            "src/test/golden/recipes",
                            name.removeSuffix(".ttrp"),
                            "${s.island}.${s.port}.sql",
                        )
                    s.sql.trim() shouldBe Files.readString(golden).trim()
                }
            }
        }
        "the single-database oracle stays byte equal to every pre-cut SQL golden" {
            // `-DupdateGolden=true` rewrites them (RecipeStatementsSpec writes only the split-storage set); review the diff.
            val update = System.getProperty("updateGolden") == "true"
            for (name in RecipeFixtures.SEVENTEEN) {
                RecipeStatements.plan(RecipeFixtures.sharedRecipe(name), splitStorage = false).forEach { s ->
                    val golden =
                        Path.of(
                            "src/test/golden/recipes",
                            name.removeSuffix(".ttrp"),
                            "${s.island}.${s.port}.sql",
                        )
                    if (update) Files.writeString(golden, s.sql.trimEnd('\n') + "\n")
                    s.sql.trim() shouldBe Files.readString(golden).trim()
                }
            }
        }

        fun withWorld(
            customEntity: Boolean = false,
            modelEdit: (Path) -> Unit = {},
            transform: (String) -> String,
        ): org.tatrman.ttrp.graph.TtrpPipeline.PlanResult {
            val root = Files.createTempDirectory("recipe-placement")
            val model = root.resolve("model")
            Files.walk(RecipeFixtures.root.resolve("model")).use { paths ->
                paths.forEach { path ->
                    val target = model.resolve(RecipeFixtures.root.resolve("model").relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
                }
            }
            val world = model.resolve("hartland/worlds/demo.ttrm")
            Files.writeString(world, transform(Files.readString(world)))
            if (customEntity) {
                Files.writeString(
                    model.resolve("hartland/er/unplaced.ttrm"),
                    "package hartland\nmodel er\ndef entity unplaced { attributes: [def attribute value { type: text }] }",
                )
            }
            modelEdit(model)
            val pipeline =
                org.tatrman.ttrp.graph.TtrpPipeline(
                    org.tatrman.ttrp.project
                        .TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                    model,
                )
            val source = Files.readString(RecipeFixtures.shared.resolve("monitor_inbound.ttrp"))
            val program =
                if (customEntity) {
                    source.replace(
                        "    decision inbound",
                        "    context spare(out rows) target erp { load(er.entity.unplaced) -> rows }\n    decision inbound",
                    )
                } else {
                    source
                }
            return pipeline.plan(
                program,
                "monitor_inbound.ttrp",
            )
        }
        "unbound entity without writable placement keeps RES-005" {
            val plan = withWorld { it.replace("[obligation, state_history,", "[state_history,") }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-RES-005" && it.message.contains("obligation") } shouldBe true
        }
        "an ambiguous writable placement is REC-090, never first storage wins" {
            val plan = withWorld { it.replace("[order.state,", "[obligation, order.state,") }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-REC-090" && it.message.contains("obligation") } shouldBe true
        }
        "a custom unbound entity follows writable placement, with no er2db required" {
            val plan =
                withWorld(
                    customEntity = true,
                ) { it.replace("[obligation, state_history,", "[unplaced, obligation, state_history,") }
            io.kotest.assertions.withClue(plan.diagnostics.joinToString("\n")) { plan.ok shouldBe true }
            plan.graph!!
                .loadSources.values
                .single { it.name == "unplaced" }
                .storage shouldBe "automation_db"
        }
        "a custom unbound entity without placement keeps RES-005" {
            val plan = withWorld(customEntity = true) { it }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-RES-005" && it.message.contains("unplaced") } shouldBe true
        }
        "review-121: missing storage engines report a located diagnostic" {
            val plan = withWorld { it.replace("via: automation,", "") }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-REC-090" && it.location.line > 0 } shouldBe true
        }
        "review-121: a writable glob cannot redirect an ERP entity with a binding" {
            val plan = withWorld { it.replace("[obligation, state_history,", "[\"*\", obligation, state_history,") }
            plan.ok shouldBe true
            plan.graph!!
                .loadSources.values
                .filter { it.name == "return_request_line" }
                .forEach { it.storage shouldBe "erp_db" }
        }
        "review-121: parts declare an explicit shape and contain ledger and history filters" {
            val statements = RecipeStatements.plan(RecipeFixtures.sharedRecipe("monitor_inbound.ttrp"))
            val parts = statements.flatMap { it.parts }.distinctBy { it.name }
            parts.forEach { p ->
                p.sql.substringBefore("FROM").contains('*') shouldBe false
                p.columns.forEach { c -> p.sql.substringBefore("FROM").contains("\"${c.name}\"") shouldBe true }
            }
            parts.any { it.sql.contains("monitor_inbound_verdict") && it.sql.contains("'remind'") } shouldBe true
            parts.any {
                it.sql.contains(
                    "obligation",
                ) &&
                    it.sql.contains("'inbound'") &&
                    it.sql.contains("'open'")
            } shouldBe
                true
            parts.any { it.storage == "erp_db" && it.sql.contains("'awaiting_goods'") } shouldBe true
        }
        "review-121: host placement uses model packages, longest segment prefix, and physical sources" {
            val p = RecipeFixtures.sharedRecipe("monitor_inbound.ttrp")
            val snapshot =
                org.tatrman.ttrp.resolve.ModelRepo
                    .snapshotOf(RecipeFixtures.root.resolve("model"))!!
            val index =
                org.tatrman.ttrp.resolve.ModelIndex(snapshot) { obj ->
                    if (obj is org.tatrman.ttr.metadata.model.DbTable) "hartland.erp" else "logical"
                }
            val world = p.bound!!.world
            val erp = world.storages.first { it.qname.name == "erp_db" }
            val automation = world.storages.first { it.qname.name == "automation_db" }
            val source =
                p.graph!!
                    .loadSources.values
                    .first { it.name == "return_request_line" }
            val nested =
                world.copy(
                    storages =
                        listOf(
                            erp.copy(hosts = listOf("hartland")),
                            automation.copy(hosts = listOf("hartland.erp")),
                        ),
                )
            org.tatrman.ttrp.resolve.RecipePlacement
                .storages(source, nested, index) shouldBe listOf("automation_db")
            val misleading = nested.copy(storages = listOf(erp.copy(hosts = listOf("erp"))))
            org.tatrman.ttrp.resolve.RecipePlacement
                .storages(source, misleading, index) shouldBe emptyList()
        }
        "review-121: a display projection preserves the DuckDB combine" {
            val p = RecipeFixtures.sharedRecipe("monitor_inbound.ttrp")
            val graph = p.graph!!
            val container = graph.containers.values.first { it.label == "awaiting" }
            val planner =
                org.tatrman.ttrp.emit.sql.SqlTextPlanner(
                    graph,
                    p.bound!!,
                    org.tatrman.translate.v1.SqlDialect.POSTGRESQL,
                    RecipeStatements.params(p.recipe!!, graph),
                    fragmentsAsMembers = true,
                    tier = SqlTier.ER,
                    cutStorage = true,
                )
            val output =
                planner.emit(
                    container,
                    "lines",
                    org.tatrman.ttrp.resolve.DisplaySchema(
                        "display",
                        "display",
                        listOf(
                            org.tatrman.ttrp.resolve
                                .DisplaySchemaColumn("line_id", "int", false),
                        ),
                    ),
                )
            planner.parts.values.shouldNotBeEmpty()
            planner.storage shouldBe null
            output.columns.map { it.name } shouldBe listOf("line_id")
            planner.parts.keys.forEach { output.sql.contains(it) shouldBe true }
        }
        "review-121: an opaque fragment with unknown storage is a located emit diagnostic" {
            val p = RecipeFixtures.sharedRecipe("monitor_inbound.ttrp")
            val graph = p.graph!!
            val container = graph.containers.values.first { it.label == "awaiting" }
            val adjusted =
                p.copy(
                    graph =
                        graph.copy(
                            containers =
                                graph.containers +
                                    (container.id to container.copy(memberIds = emptyList())),
                        ),
                )
            val failure = RecipeStatements.failures(adjusted).getValue("awaiting")
            failure.detail.contains("no resolved storage") shouldBe true
            failure.location shouldBe container.location
        }
        "review-121: a dangling ERP binding retains RES-005 despite a writable glob" {
            val plan =
                withWorld(modelEdit = { model ->
                    val bindings = model.resolve("hartland/binding/er2db.ttrm")
                    Files.writeString(
                        bindings,
                        Files.readString(bindings).replace(
                            "target: { table: db.dbo.return_request_line }",
                            "target: { table: db.dbo.deleted_line }",
                        ),
                    )
                }) { it.replace("[obligation, state_history,", "[\"return_request*\", obligation, state_history,") }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-RES-005" && it.message.contains("return_request_line") } shouldBe
                true
        }
        "review-121: a synthesized calendar placement error points to its sugar call" {
            val plan =
                withWorld(modelEdit = { model ->
                    val dims = model.resolve("hartland/db/dims.ttrm")
                    Files.writeString(
                        dims,
                        Files.readString(dims).replace("package hartland", "package unhosted_calendar"),
                    )
                }) { it }
            plan.ok shouldBe false
            val diagnostic = plan.diagnostics.first { it.id.id == "TTRP-REC-090" && it.message.contains("date_dim") }
            (diagnostic.location.line > 0) shouldBe true
            val line =
                Files.readAllLines(RecipeFixtures.shared.resolve("monitor_inbound.ttrp"))[
                    diagnostic.location.line -
                        1,
                ]
            line.contains("age(since:") shouldBe true
        }
        "every load including a synthesized calendar has a storage" {
            val plan = RecipeFixtures.sharedRecipe("monitor_inbound.ttrp")
            plan.graph!!
                .loadSources.values
                .forEach { (it.storage != null) shouldBe true }
        }
        "a DB-tier oracle remains one statement per output" {
            RecipeStatements.plan(RecipeFixtures.sharedRecipe("settle.ttrp"), SqlTier.DB).forEach {
                it.parts shouldBe emptyList()
            }
        }
    })
