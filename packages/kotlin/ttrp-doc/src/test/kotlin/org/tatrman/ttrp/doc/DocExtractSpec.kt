// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.doc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class DocExtractSpec :
    FunSpec({
        val md =
            """
            # Release an order

            Consider every placed order.

            ```ttrp preamble
            uses world "hartland.worlds.demo"
            import hartland.*
            ```

            ```ttrp
            recipe { key: order.order_number }
            ```

            Find the orders.

            ```sql candidates placed_orders(out orders) target erp
            SELECT o.order_number FROM er.entity.order o
            ```
            """.trimIndent() + "\n"
        test("source spans, verbatim interiors, stable canonical output and sentence links") {
            val result = DocExtractor.extract(md, "release.md")
            result.diagnostics shouldBe emptyList()
            val recipe = result.recipes.single()
            recipe.path shouldBe "release.ttrp"
            recipe.text shouldContain "SELECT o.order_number FROM er.entity.order o\n"
            recipe.sidecar.nodes.keys shouldBe setOf("placed_orders")
            val node = recipe.sidecar.nodes.getValue("placed_orders")
            md.substring(node.span[0], node.span[1]) shouldContain "```sql candidates"
            md.substring(node.paragraph[0], node.paragraph[1]) shouldBe "Find the orders."
            recipe.sidecar.sentences
                .single { "placed_orders" in it.nodes }
                .span shouldBe node.paragraph
            DocExtractor.extract(md, "release.md").recipes shouldBe result.recipes
        }
        test("astral Unicode in skeleton comments and fragment interiors keeps exact boundaries") {
            val unicode =
                md
                    .replace("recipe { key:", "// 🚀 skeleton\nrecipe { key:")
                    .replace("SELECT o.order_number", "SELECT '🚀' AS note, o.order_number")
            val extracted = DocExtractor.extract(unicode, "release.md")
            extracted.diagnostics shouldBe emptyList()
            val text = extracted.recipes.single().text
            text shouldContain "SELECT '🚀' AS note"
            org.tatrman.ttrp.parser.TtrpParser
                .parseString(text)
                .diagnostics shouldBe emptyList()
        }
        test("check detects program, mapping and prose drift without writing") {
            val dir = Files.createTempDirectory("doc-extract")
            try {
                val result = DocExtractor.extract(md, "release.md")
                result.write(dir)
                result.check(dir) shouldBe emptyList()
                Files.writeString(dir.resolve("release.ttrp"), "edited\n")
                result.check(dir).single().id shouldBe "TTRP-DOC-003"
                result.write(dir)
                DocExtractor
                    .extract(md.replace("Find the orders.", "Find placed orders."), "release.md")
                    .check(dir)
                    .single()
                    .id shouldBe "TTRP-DOC-003"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        test("DOC diagnostics: missing header, duplicate names, unsupported fence and container before skeleton") {
            DocExtractor
                .extract("```sql\nselect 1\n```\n", "x.md")
                .diagnostics
                .first()
                .id shouldBe "TTRP-DOC-001"
            DocExtractor
                .extract(
                    md +
                        "\n```ttrp\nrecipe: same\nrecipe { key: order.order_number }\n```\n```ttrp\nrecipe: same\nrecipe { key: order.order_number }\n```\n",
                    "x.md",
                ).diagnostics
                .any { it.id == "TTRP-DOC-002" } shouldBe true
            DocExtractor
                .extract("```js\n1\n```\n", "x.md")
                .diagnostics
                .first()
                .id shouldBe "TTRP-DOC-004"
            DocExtractor
                .extract("```ttrp context x(out rows) target erp\nload(er.entity.order) -> rows\n```\n", "x.md")
                .diagnostics
                .first()
                .id shouldBe "TTRP-DOC-001"
        }
        test("Czech prose with identical blocks derives identical canonical text") {
            val en = DocExtractor.extract(md, "release.md")
            val cs =
                DocExtractor.extract(
                    md
                        .replace(
                            "# Release an order",
                            "# Uvolnění objednávky",
                        ).replace("Find the orders.", "Najděte objednávky."),
                    "release.cs.md",
                )
            en.recipes.single().text shouldBe cs.recipes.single().text
        }
        test("a tagged TTR-B decision header is validated by the canonical parser") {
            val source =
                md +
                    "\n```ttrb decision verdict(in orders)\nDecide release when order_number > 0. Decide exclusively. Otherwise, nothing.\n```\n"
            val result = DocExtractor.extract(source, "release.md")
            result.diagnostics shouldBe emptyList()
            result.recipes
                .single()
                .sidecar.nodes.keys shouldBe setOf("placed_orders", "verdict")
        }
        test("three named skeletons derive separate nested paths and never mix members") {
            val multi =
                listOf("decide", "monitor", "settle").joinToString("\n") { name ->
                    "```ttrp\nrecipe: $name\nrecipe { key: order.order_number }\n```\n\nFor $name.\n\n```ttrp context ${name}_rows(out rows) target erp\nload(er.entity.order) -> rows\n```\n"
                }
            val result = DocExtractor.extract(multi, "returns.md")
            result.diagnostics shouldBe emptyList()
            result.recipes.map { it.path } shouldBe
                listOf("decide/decide.ttrp", "monitor/monitor.ttrp", "settle/settle.ttrp")
            result.recipes.forEach { it.sidecar.nodes.size shouldBe 1 }
        }
        test("H-1 and H-2 literate fixtures derive stable programs, sidecars and the same statements as 17") {
            val root =
                generateSequence(
                    java.nio.file.Path
                        .of("")
                        .toAbsolutePath(),
                ) { it.parent }
                    .first { Files.isDirectory(it.resolve("packages/kotlin/ttrp-frontend")) }
            val fixture = root.resolve("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
            val pipeline =
                org.tatrman.ttrp.graph.TtrpPipeline(
                    org.tatrman.ttrp.project
                        .TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixture),
                    fixture.resolve("model"),
                )
            for (name in listOf("releasing-a-placed-order", "handling-a-return-request")) {
                val docs =
                    java.nio.file.Path
                        .of("src/test/resources/docs")
                val extracted = DocExtractor.extract(Files.readString(docs.resolve("$name.md")), "$name.md")
                extracted.diagnostics shouldBe emptyList()
                val committed = docs.resolve("derived/$name")
                if (System.getProperty("updateGolden") == "true") extracted.write(committed)
                extracted.check(committed) shouldBe emptyList()
                for (recipe in extracted.recipes) {
                    val canonical =
                        root.resolve(
                            "packages/kotlin/ttrp-frontend/src/test/resources/recipes/${recipe.name}.ttrp",
                        )
                    val derived = pipeline.plan(recipe.text, "${recipe.name}.ttrp")
                    check(derived.ok) { derived.diagnostics.joinToString("\n") }
                    val original = pipeline.plan(Files.readString(canonical), "${recipe.name}.ttrp")
                    org.tatrman.ttrp.emit.recipe.RecipeStatements
                        .plan(derived)
                        .map { it.sql } shouldBe
                        org.tatrman.ttrp.emit.recipe.RecipeStatements
                            .plan(original)
                            .map { it.sql }
                }
            }
        }
    })
