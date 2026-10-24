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
        test("canonical recipe members before a skeleton give one located DOC-001 instead of parser errors") {
            val members =
                listOf(
                    "decision verdict(in orders) { orders -> switch { release: true } }",
                    "actions { verdict.release -> release { order: order_number } }",
                    "context recent_orders(out rows) target erp { load(er.entity.order) -> rows }",
                )
            for (body in members) {
                org.tatrman.ttrp.parser.TtrpParser
                    .parseString("recipe {\n$body\n}")
                    .diagnostics shouldBe emptyList()
                val fence = "```ttrp\n$body\n```\n"
                val source = "# 🚀 Misordered\n\n$fence\n$md"
                val finding = DocExtractor.extract(source, "misordered.md").diagnostics.single()
                finding.id shouldBe "TTRP-DOC-001"
                finding.message shouldBe "a container must follow its recipe skeleton"
                source.substring(finding.span[0], finding.span[1]).trimEnd() shouldBe fence.trimEnd()
            }
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
        test("fence diagnostics retain their parser identity, suggestion and Markdown span") {
            val base = md + "\n```ttrb decision verdict(in orders)\n%s\n```\n"
            for ((body, id, word) in listOf(
                Triple("Decde release when amount > 0.", "TTRP-B-004", "Decde"),
                Triple("Decide release when amount > 0. Keep only rows where amount > 1.", "TTRP-REC-070", "Keep"),
            )) {
                val source = base.format(body).replace("Find the orders.", "Find 🚀 orders.")
                val result = DocExtractor.extract(source, "bad.md")
                val finding = result.diagnostics.first { it.id == id }
                finding.span[0] shouldBe source.indexOf(word)
                check(finding.suggestedAlternative != null)
                result.diagnostics.none { it.id == "TTRP-DOC-001" } shouldBe true
            }
            val source = "```ttrp\nrecipe { key order.order_number }\n```\n"
            val errors = DocExtractor.extract(source, "bad.md").diagnostics
            errors.any { it.id == "TTRP-PRS-001" } shouldBe true
            errors.none { it.message.contains("must follow") } shouldBe true
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
