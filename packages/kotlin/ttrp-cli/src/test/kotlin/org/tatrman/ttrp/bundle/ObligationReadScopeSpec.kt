// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.project.TtrpManifest
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import org.tatrman.ttrp.graph.TtrpPipeline
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** #319: implicit action reads through the real checker/model/bundle pipeline. */
class ObligationReadScopeSpec :
    StringSpec({
        fun locate(relative: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(relative)
                if (Files.exists(candidate)) return candidate
                dir = dir.parent
            }
            error("could not locate $relative")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model/hartland")
        val sqlQuote = "\"\"\""
        val source =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: return_request_line.line_id
                candidates lines(out rows) target erp ${sqlQuote}sql
                    SELECT line_id FROM er.entity.return_request_line
                $sqlQuote
                decision verdict(in rows) {
                    rows -> switch { done: true }
                }
                actions {
                    verdict.done -> fulfil { line: line_id, kind: payment, to: fulfilled }
                }
            }
            """.trimIndent()

        fun project(transform: (String) -> String = { it }): Path {
            val root = tempdir("obligation-read-scope").toPath().resolve("cache/private-checkout")
            val models = root.resolve("models")
            Files.createDirectories(models)
            Files.walk(fixtures).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().toList().forEachIndexed { index, file ->
                    val contents = Files.readString(file)
                    // Flat files deliberately have no hartland directory; declared packages own scope.
                    Files.writeString(models.resolve("metadata-$index.ttrm"), transform(contents))
                }
            }
            return root
        }

        fun build(
            text: String = source,
            name: String = "implicit_obligation.ttrp",
            transform: (String) -> String = { it },
        ): BundleAssembler.BundleResult {
            val root = project(transform)
            return BundleAssembler("read-scope-test").build(
                source = text,
                fileName = name,
                pipelineManifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                modelsRoot = root.resolve("models"),
                outDir = root.resolve("out"),
                recipeDir = root,
            )
        }

        fun sha256(text: String): String =
            "sha256:" +
                MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        "a wired native obligation transition declares its lookup without an authored load" {
            val result = build()
            result.manifest.recipe!!.objectsRead shouldContainExactly
                listOf("er.entity.obligation", "er.entity.return_request_line")
            val readme = Files.readString(result.dir.resolve("README.md"))
            readme shouldContain "er.entity.obligation"
            result.manifest.files.getValue("README.md") shouldBe sha256(readme)
            val sql =
                result.manifest.files.keys.filter { it.endsWith(".sql") }.joinToString("\n") {
                    Files.readString(result.dir.resolve(it))
                }
            sql.contains("obligation", ignoreCase = true) shouldBe false
        }

        "an obligation insert without a transition does not add a lookup" {
            val result = build(transform = { it.replace("transition: obligation.state -> {to}", "") })
            result.manifest.recipe!!.objectsRead shouldContainExactly listOf("er.entity.return_request_line")
        }

        "a visible but unwired obligation transition does not add a lookup" {
            val result =
                build(
                    text =
                        source.replace(
                            "fulfil { line: line_id, kind: payment, to: fulfilled }",
                            "set_state { line: line_id, to: closed }",
                        ),
                )
            result.manifest.recipe!!.objectsRead shouldContainExactly listOf("er.entity.return_request_line")
        }

        "an obligation sink for another executor does not add a native lookup" {
            val result =
                build(transform = { it.replace("sinks:      { native: obligation }", "sinks: { n8n: obligation }") })
            result.manifest.recipe!!.objectsRead shouldContainExactly listOf("er.entity.return_request_line")
        }

        "a set-only obligation action does not add a transition lookup" {
            val result =
                build(transform = { it.replace("transition: obligation.state -> {to}", "set: obligation.state = to") })
            result.manifest.recipe!!.objectsRead shouldContainExactly listOf("er.entity.return_request_line")
        }

        "repeated obligation wiring produces one sorted scope entry" {
            val result =
                build(
                    text =
                        source
                            .replace("done: true", "mode: overlapping done: true second: false")
                            .replace(
                                "verdict.done -> fulfil { line: line_id, kind: payment, to: fulfilled }",
                                """
                                verdict.done -> fulfil { line: line_id, kind: payment, to: fulfilled }
                                verdict.second -> fulfil { line: line_id, kind: credit_note, to: fulfilled }
                                """.trimIndent(),
                            ),
                )
            withClue(result.manifest.recipe!!.objectsRead) {
                result.manifest.recipe!!.objectsRead shouldContainExactly
                    listOf("er.entity.obligation", "er.entity.return_request_line")
            }
        }

        "the sink and transition determine the lookup independently of the action name" {
            val result =
                build(
                    text = source.replace("-> fulfil", "-> complete"),
                    transform = { it.replace("def action fulfil", "def action complete") },
                )
            result.manifest.recipe!!.objectsRead shouldContainExactly
                listOf("er.entity.obligation", "er.entity.return_request_line")
        }

        "existing action approval evidence covers the native sink and transition that imply the read" {
            val text =
                source.replace(
                    "verdict.done -> fulfil { line: line_id, kind: payment, to: fulfilled }",
                    """
                    verdict.done -> fulfil { line: line_id, kind: payment, to: fulfilled }
                    verdict.done -> stamp { line: line_id }
                    """.trimIndent(),
                )
            val stamp =
                """
                def action stamp {
                    columns: [def column line { type: return_request_line.line_id },
                              def column way { type: return_request_line.way, optional: true }]
                    sinks: { native: writer }
                    writer_defaults: { way: { expression: "'refund_after_return'" } }
                    approval: none
                }
                """.trimIndent()

            fun metadata(contents: String): String =
                if (contents.contains("def action fulfil")) contents + "\n" + stamp else contents
            val withLookup = build(text = text, transform = ::metadata)
            val insertOnly =
                build(text = text, transform = { metadata(it).replace("transition: obligation.state -> {to}", "") })
            val before = withLookup.manifest.recipe!!
            val after = insertOnly.manifest.recipe!!
            before.objectsRead shouldContainExactly listOf("er.entity.obligation", "er.entity.return_request_line")
            after.objectsRead shouldContainExactly listOf("er.entity.return_request_line")
            before.approvalDependencies!!
                .actions.keys
                .sorted() shouldContainExactly listOf("fulfil", "stamp")
            before.approvalDependencies!!.actions.getValue("fulfil") shouldNotBe
                after.approvalDependencies!!.actions.getValue("fulfil")
            before.fingerprints.text shouldNotBe after.fingerprints.text
            before.fingerprints.values shouldBe after.fingerprints.values
            before.fingerprints.labels shouldBe after.fingerprints.labels
            withLookup.manifest.files.getValue("README.md") shouldNotBe insertOnly.manifest.files.getValue("README.md")

            fun statements(result: BundleAssembler.BundleResult): Map<String, String> =
                result.manifest.files.filterKeys { it.endsWith(".sql") }
            statements(withLookup) shouldBe statements(insertOnly)
        }

        "wired action resolution validates every name and names the recipe on failure" {
            val root = project()
            val plan =
                TtrpPipeline(
                    TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                    root.resolve("models"),
                ).plan(source, "implicit_obligation.ttrp")
            plan.ok shouldBe true
            val model = plan.recipe!!
            val wiring = model.wirings.single()
            val repeated = model.copy(wirings = listOf(wiring.copy(action = "set_state"), wiring, wiring))
            RecipeActionDependencies.wiredActions(plan, repeated)!!.keys.toList() shouldContainExactly
                listOf("fulfil", "set_state")
            val missing = model.copy(wirings = listOf(wiring, wiring.copy(action = "zz_missing")))
            val failure = shouldThrow<IllegalStateException> { RecipeActionDependencies.wiredActions(plan, missing) }
            failure.message.orEmpty() shouldContain "recipe `${model.name}`"
            failure.message.orEmpty() shouldContain "wired action `zz_missing`"
            RecipeActionDependencies.wiredActions(plan.copy(frontendReport = null), model) shouldBe null
            RecipeActionDependencies.of(plan.copy(frontendReport = null), model, "unused") shouldBe null
        }

        "controls retain exact read scopes README disclosure and unchanged statement SQL" {
            fun statements(result: BundleAssembler.BundleResult): Map<String, String> =
                result.manifest.files.keys.filter { it.endsWith(".sql") }.associateWith { path ->
                    Files.readString(result.dir.resolve(path))
                }

            fun checkReads(
                name: String,
                expected: List<String>,
                text: String = source,
                transform: (String) -> String = { it },
            ): BundleAssembler.BundleResult {
                val result = build(text, name, transform)
                val withoutLookup =
                    build(text, name) {
                        transform(it).replace("transition: obligation.state -> {to}", "")
                    }
                withClue(name) {
                    result.manifest.recipe!!.objectsRead shouldContainExactly expected
                    val readme = Files.readString(result.dir.resolve("README.md"))
                    val readsSection = readme.substringAfter("## What it reads\n\n").substringBefore("\n## ")
                    Regex("`([^`]+)`").findAll(readsSection).map { it.groupValues[1] }.toList() shouldContainExactly
                        expected
                    statements(result) shouldBe statements(withoutLookup)
                }
                return result
            }
            val lineOnly = listOf("er.entity.return_request_line")
            checkReads("insert-only.ttrp", lineOnly) { it.replace("transition: obligation.state -> {to}", "") }
            checkReads("other-executor.ttrp", lineOnly) {
                it.replace("sinks:      { native: obligation }", "sinks: { n8n: obligation }")
            }
            checkReads(
                "unwired.ttrp",
                lineOnly,
                source.replace(
                    "fulfil { line: line_id, kind: payment, to: fulfilled }",
                    "set_state { line: line_id, to: closed }",
                ),
            )
            val recipes = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes")
            checkReads(
                "release_order.ttrp",
                listOf(
                    "er.entity.customer_price",
                    "er.entity.inventory",
                    "er.entity.invoice",
                    "er.entity.order",
                    "er.entity.price_list",
                    "er.entity.sales_line",
                    "er.entity.warehouse_outage",
                ),
                Files.readString(recipes.resolve("release_order.ttrp")),
            )
            val settle =
                checkReads(
                    "settle.ttrp",
                    listOf(
                        "er.entity.credit_note",
                        "er.entity.date_dim",
                        "er.entity.obligation",
                        "er.entity.refund_payment",
                        "er.entity.replacement_shipment",
                        "er.entity.return_request",
                        "er.entity.return_request_line",
                    ),
                    Files.readString(recipes.resolve("settle.ttrp")),
                )
            settle.manifest.recipe!!
                .objectsRead
                .count { it == "er.entity.obligation" } shouldBe 1
        }
    })
