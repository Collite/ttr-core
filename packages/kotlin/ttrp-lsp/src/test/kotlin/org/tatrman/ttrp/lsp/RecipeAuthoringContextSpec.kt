// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.Position
import org.tatrman.ttrp.lsp.project.ProjectContext
import org.tatrman.ttrp.lsp.project.ProjectResolver
import org.tatrman.ttrp.lsp.protocol.AuthoringContextParams
import org.tatrman.ttrp.lsp.test.TtrpLspHarness
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * AU-core-P2 · S2.4 T1/T4 — `ttrp/authoringContext` v2 (AU contracts §8) over a recipe: the cursor inside H-1's
 * `price_differences` context gets the recipe (key, parameters, the roles with their classes, the mode verdict), the
 * imports' automation objects, the world's `writable:`, the recipe grammar with its REC rows — and the insertion
 * target is the context's SQL fragment. The bundle validates against the v2 schema (the v1 examples still do,
 * AuthoringContextSchemaSpec), is deterministic, and is the committed example `recipe-at-cursor.json` byte for byte
 * (regenerate with `-DupdateGolden=true` and review the diff).
 */
class RecipeAuthoringContextSpec :
    StringSpec({
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val c = dir.resolve(rel)
                if (Files.exists(c)) return c
                dir = dir.parent
            }
            error("could not locate $rel")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
        val recipes = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes")
        val docs = locate("docs/features/ttr-p/architecture")
        val hartland =
            ProjectResolver {
                ProjectContext(
                    manifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures),
                    modelsRoot = fixtures.resolve("model"),
                    manifestDiagnostics = emptyList(),
                )
            }
        val uri = "file:///release_order.ttrp"
        val text = Files.readString(recipes.resolve("release_order.ttrp"))
        // line 38 of release_order.ttrp, inside `price_differences`'s """sql fragment
        val inPriceDifferences = Position(37, 12)
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

        fun context(position: Position?): JsonObject =
            TtrpLspHarness(hartland).use { h ->
                h.initialize()
                h.open(uri, text)
                h.remote
                    .authoringContext(AuthoringContextParams(uri, position))
                    .get()
                    .bundle
            }

        "the cursor in a recipe: key, parameters, roles with their classes, the mode verdict" {
            val bundle = context(inPriceDifferences)
            bundle.get("version").asInt shouldBe 2
            val recipe = bundle.getAsJsonObject("recipe")
            recipe.getAsJsonArray("key").map { it.asString } shouldContainExactly listOf("order.order_number")
            recipe.getAsJsonArray("parameters").map { it.asString } shouldContainExactly
                listOf("tolerance", "confirmation_threshold")
            recipe.get("mode").asString shouldBe "per-candidate"
            val roles = recipe.getAsJsonArray("roles").map { it.asJsonObject }
            roles.map { it.get("name").asString } shouldContainExactly
                listOf("placed_orders", "shortfall", "price_differences", "overdue", "verdict")
            roles.single { it.get("name").asString == "placed_orders" }.get("role").asString shouldBe "candidates"
            roles.single { it.get("name").asString == "shortfall" }.get("class").asString shouldBe "per-candidate"
            roles.single { it.get("name").asString == "price_differences" }.get("form").asString shouldBe "target"
            roles
                .single {
                    it
                        .get(
                            "name",
                        ).asString == "verdict"
                }.getAsJsonArray("routes")
                .map { it.asString } shouldContain
                "hold_sales"
            // a recipe's role container is a container: the assist inserts SQL into price_differences, on erp
            val target = bundle.getAsJsonObject("scope").getAsJsonObject("insertionTarget")
            target.get("dialect").asString shouldBe "sql"
            target.get("containerName").asString shouldBe "price_differences"
            target.get("targetEngine").asString shouldBe "erp"
        }

        "automationObjects: the imports' parameters with values, actions with columns and sinks, row schemas" {
            val objects = context(inPriceDifferences).getAsJsonArray("automationObjects").map { it.asJsonObject }
            val tolerance =
                objects.single {
                    it.get("kind").asString == "parameter" &&
                        it.get("name").asString == "tolerance"
                }
            tolerance.get("type").asString shouldBe "decimal"
            val hold = objects.single { it.get("kind").asString == "action" && it.get("name").asString == "hold" }
            hold.getAsJsonArray("columns").map { it.asJsonObject.get("name").asString } shouldContain "order"
            hold.getAsJsonObject("sinks").keySet() shouldContain "native"
            objects.map { it.get("kind").asString }.toSet() shouldContainAll listOf("parameter", "action", "schema")
            // sorted by kind, then name
            objects.map { it.get("kind").asString + "/" + it.get("name").asString } shouldBe
                objects
                    .map {
                        it
                            .get(
                                "kind",
                            ).asString + "/" + it.get("name").asString
                    }.sortedBy { it.replace('/', '\u0000') }
        }

        "world.writable and the recipe grammar's REC rows" {
            val bundle = context(inPriceDifferences)
            val writable = bundle.getAsJsonObject("world").getAsJsonArray("writable").map { it.asJsonObject }
            writable.flatMap { w -> w.getAsJsonArray("items").map { it.asString } } shouldContain "obligation"
            val grammar = bundle.getAsJsonObject("recipeGrammar")
            grammar.get("statementSummary").asString shouldContain "switch"
            grammar.getAsJsonArray("diagnostics").map { it.asJsonObject.get("id").asString } shouldContainAll
                listOf("TTRP-REC-000", "TTRP-REC-001", "TTRP-REC-010", "TTRP-REC-050")
        }

        "no cursor, or a cursor outside the recipe: no `recipe` section" {
            context(null).has("recipe") shouldBe false
            context(Position(0, 0)).has("recipe") shouldBe false
        }

        "the bundle validates against the v2 schema, is deterministic, and is the committed example" {
            val first = gson.toJson(context(inPriceDifferences)) + "\n"
            val second = gson.toJson(context(inPriceDifferences)) + "\n"
            first shouldBe second
            val schema =
                JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Files.readString(docs.resolve("authoring-context.schema.json")))
            schema.validate(first, InputFormat.JSON).map { it.message } shouldBe emptyList()
            val example = docs.resolve("examples/authoring-context/recipe-at-cursor.json")
            if (System.getProperty("updateGolden") == "true") Files.writeString(example, first)
            Files.readString(example) shouldBe first
        }
    })
