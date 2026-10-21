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
            for (name in RecipeFixtures.SEVENTEEN) {
                RecipeStatements.plan(RecipeFixtures.sharedRecipe(name), splitStorage = false).forEach { s ->
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

        fun withWorld(
            customEntity: Boolean = false,
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
        "unbound entity without writable placement is REC-090, before emission" {
            val plan = withWorld { it.replace("[obligation, state_history,", "[state_history,") }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-REC-090" && it.message.contains("obligation") } shouldBe true
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
        "a custom unbound entity without placement gets REC-090" {
            val plan = withWorld(customEntity = true) { it }
            plan.ok shouldBe false
            plan.diagnostics.any { it.id.id == "TTRP-REC-090" && it.message.contains("unplaced") } shouldBe true
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
