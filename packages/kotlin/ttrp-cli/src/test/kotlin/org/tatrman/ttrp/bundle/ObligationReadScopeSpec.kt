// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.project.TtrpManifest
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

        fun build(
            text: String = source,
            name: String = "implicit_obligation.ttrp",
            transform: (String) -> String = { it },
        ): BundleAssembler.BundleResult {
            val root = tempdir("obligation-read-scope").toPath().resolve("cache/private-checkout")
            val models = root.resolve("models")
            Files.createDirectories(models)
            Files.walk(fixtures).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList().forEachIndexed { index, file ->
                    val contents = Files.readString(file)
                    // Flat files deliberately have no hartland directory; declared packages own scope.
                    Files.writeString(models.resolve("metadata-$index.ttrm"), transform(contents))
                }
            }
            return BundleAssembler("read-scope-test").build(
                source = text,
                fileName = name,
                pipelineManifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                modelsRoot = models,
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

        "unaffected bundles retain every pre-fix artifact byte" {
            val controls =
                mapOf(
                    "insert-only" to build(transform = { it.replace("transition: obligation.state -> {to}", "") }),
                    "other-executor" to
                        build(
                            transform = {
                                it.replace(
                                    "sinks:      { native: obligation }",
                                    "sinks: { n8n: obligation }",
                                )
                            },
                        ),
                    "unwired" to
                        build(
                            text =
                                source.replace(
                                    "fulfil { line: line_id, kind: payment, to: fulfilled }",
                                    "set_state { line: line_id, to: closed }",
                                ),
                        ),
                ) +
                    listOf("release_order.ttrp", "settle.ttrp").associateWith { name ->
                        build(
                            text =
                                Files.readString(
                                    locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/$name"),
                                ),
                            name = name,
                        )
                    }
            val hashes =
                controls.toSortedMap().map { (name, result) ->
                    val artifacts =
                        result.manifest.files +
                            ("manifest.json" to sha256(Files.readString(result.dir.resolve("manifest.json"))))
                    val digest =
                        sha256(artifacts.toSortedMap().entries.joinToString("") { (path, hash) -> "$path\t$hash\n" })
                    "$name $digest"
                }
            val expected =
                javaClass
                    .getResourceAsStream("/obligation-read-scope/unaffected.sha256")!!
                    .bufferedReader()
                    .useLines { lines ->
                        lines.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
                    }
            hashes shouldContainExactly expected
        }
    })
