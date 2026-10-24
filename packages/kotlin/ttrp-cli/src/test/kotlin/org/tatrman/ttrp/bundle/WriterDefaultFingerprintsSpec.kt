// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttrp.cli.EmitCommand
import org.tatrman.ttrp.cli.TtrpCommand
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/** Approval regressions through the real filesystem metadata/checker/bundle pipeline. */
class WriterDefaultFingerprintsSpec :
    StringSpec({
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(rel)
                if (Files.exists(candidate)) return candidate
                dir = dir.parent
            }
            error("could not locate $rel")
        }

        val fixtures = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model")
        val recipe =
            Files.readString(locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes/release_order.ttrp"))
        val writer =
            """
            def action release {
                columns: [def column order { type: order.order_number },
                          def column state { type: order.state, optional: true }]
                sinks: { native: writer }
                writer_defaults: { state: { expression: "'released'" } }
                approval: none
                dry_run: { en: "would release order {order}" }
            }
            """.trimIndent()

        fun bundle(
            editModel: (Path) -> Unit = {},
            transform: (String) -> String = { it },
        ): BundleAssembler.BundleResult {
            val root = Files.createTempDirectory("writer-fingerprints")
            Files.walk(fixtures).use { paths ->
                paths.forEach { path ->
                    val target = root.resolve("model").resolve(fixtures.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
                }
            }
            val actions = root.resolve("model/hartland/automation/automation.ttrm")
            val original = Files.readString(actions)
            Files.writeString(
                actions,
                transform(
                    original.replaceRange(
                        original.indexOf("def action release"),
                        original.indexOf("def action hold"),
                        writer + "\n",
                    ),
                ),
            )
            editModel(root.resolve("model"))
            return BundleAssembler("test")
                .build(
                    source = recipe,
                    fileName = "release_order.ttrp",
                    pipelineManifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                    modelsRoot = root.resolve("model"),
                    outDir = root.resolve("out"),
                )
        }

        fun build(transform: (String) -> String = { it }): RecipeManifest =
            bundle(transform = transform).manifest.recipe!!

        "a metadata-only default edit requires a new recipe logic approval" {
            val before = build()
            val after = build { it.replace("expression: \"'released'\"", "expression: \"'held'\"") }
            before.fingerprints.text shouldNotBe after.fingerprints.text
            before.fingerprints.values shouldBe after.fingerprints.values
            before.fingerprints.labels shouldBe after.fingerprints.labels
            before.approvalDependencies!!.textBase shouldBe after.approvalDependencies!!.textBase
        }

        "default declarations activate evidence even when empty or malformed and parameters retain value approvals" {
            val before = build()
            for (value in listOf("{}", "null")) {
                val changed =
                    build {
                        it.replace(
                            "writer_defaults: { state: { expression: \"'released'\" } }",
                            "writer_defaults: $value",
                        )
                    }
                changed.approvalDependencies shouldNotBe null
                changed.fingerprints.text shouldNotBe before.fingerprints.text
                changed.approvalDependencies!!.textBase shouldBe before.approvalDependencies!!.textBase
            }
            val parameter = build { it.replace("value: 0.01", "value: 0.02") }
            parameter.fingerprints.text shouldBe before.fingerprints.text
            parameter.fingerprints.values shouldNotBe before.fingerprints.values
            parameter.fingerprints.labels shouldBe before.fingerprints.labels
        }

        "context sources and target mappings change logic even with unchanged recipe SQL" {
            val before = build()
            val edits: List<(String) -> String> =
                listOf(
                    { it.replace("expression: \"'released'\"", "context: run_id") },
                    { it.replace("expression: \"'released'\"", "context: started_at") },
                    { it.replace("type: order.state, optional: true", "type: order.held_for, optional: true") },
                    { it.replace("sinks: { native: writer }", "sinks: { native: transition }") },
                    { it.replace("approval: none", "approval: required") },
                    // Every directly wired action is covered once a recipe uses defaults, not only the writer.
                    { it.replace("order.held_for = department", "order.state = department") },
                )
            edits.forEachIndexed { i, edit ->
                withClue("edit $i") {
                    val after = build(edit)
                    after.fingerprints.text shouldNotBe before.fingerprints.text
                    after.approvalDependencies!!.textBase shouldBe before.approvalDependencies!!.textBase
                    after.fingerprints.values shouldBe before.fingerprints.values
                    after.fingerprints.labels shouldBe before.fingerprints.labels
                }
            }
        }

        "logical target declarations participate even when the changed attribute is not read" {
            val before = build()
            val after =
                bundle(editModel = { root ->
                    val file = root.resolve("hartland/er/orders.ttrm")
                    val text = Files.readString(file)
                    Files.writeString(
                        file,
                        text.replace(
                            "def attribute placed_by            { type: text",
                            "def attribute placed_by            { type: { type: text, length: 40 }",
                        ),
                    )
                }).manifest.recipe!!
            after.fingerprints.text shouldNotBe before.fingerprints.text
            after.approvalDependencies!!.textBase shouldBe before.approvalDependencies!!.textBase
        }

        "comments layout property order and unwired actions do not change approval dependencies" {
            val before = build()
            val after =
                build {
                    it
                        .replace(
                            "sinks: { native: writer }",
                            "// moved without changing behavior\n sinks: { native: writer }",
                        ).replace("approval: none\n", "")
                        .replace("writer_defaults:", "approval: none\n writer_defaults:") +
                        "\ndef action unrelated { sinks: { native: writer }, writer_defaults: {} }\n"
                }
            after.fingerprints shouldBe before.fingerprints
            after.approvalDependencies shouldBe before.approvalDependencies
        }

        "explanation edits move labels only and label edits never move text" {
            val before = build()
            val after = build { it.replace("would release order {order}", "would prepare order {order}") }
            after.fingerprints.text shouldBe before.fingerprints.text
            after.fingerprints.values shouldBe before.fingerprints.values
            after.fingerprints.labels shouldNotBe before.fingerprints.labels
            after.approvalDependencies shouldBe before.approvalDependencies
            build { it.replace("def action release {", "def action release {\n label: { en: \"Release\" }") }
                .fingerprints.text shouldBe before.fingerprints.text
        }

        "the manifest schema and strict reader retain verifiable coverage evidence" {
            val result = bundle()
            val json = result.manifest.toJson()
            val schema =
                JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(javaClass.getResourceAsStream("/schemas/bundle-manifest-v3.schema.json")!!)
            schema.validate(ObjectMapper().readTree(json)).map { it.message }.shouldBeEmpty()
            RunManifest.fromJson(json) shouldBe result.manifest
            val r = result.manifest.recipe!!
            val evidence = r.approvalDependencies!!
            evidence.actions.keys shouldBe r.wiring.map { it.action }.toSet()
            RecipeActionDependencies.text(evidence) shouldBe r.fingerprints.text
            // Binding cannot be attached to the old text fingerprint as an unsigned extra field.
            evidence.textBase shouldNotBe r.fingerprints.text
        }

        "removing the last default restores the frozen text1 base and omits evidence" {
            val before = build()
            val after = build { it.replace("writer_defaults: { state: { expression: \"'released'\" } }", "") }
            after.approvalDependencies shouldBe null
            after.fingerprints.text shouldBe before.approvalDependencies!!.textBase
            after.fingerprints.text shouldNotBe before.fingerprints.text
        }

        "native export propagates text2 and refuses stale missing or unbound dependency evidence" {
            val result = bundle()
            val root = result.dir.parent.parent
            Files.move(root.resolve("model"), root.resolve("models"))
            // The compiler's old shared fixture uses pre-package-parity directory layout and role hints.
            // Normalize this private copy for the export CLI's strict metadata reader.
            for (folder in listOf("binding", "db", "er")) {
                val from = root.resolve("models/hartland/$folder")
                Files.list(from).use { paths ->
                    paths.forEach { file ->
                        val text = Files.readString(file).replace(Regex("roles: \\[.*?\\]"), "")
                        Files.writeString(root.resolve("models/hartland/${folder}_${file.fileName}"), text)
                        Files.delete(file)
                    }
                }
                Files.delete(from)
            }
            Files.deleteIfExists(root.resolve("models/hartland/automation/checker-probes.ttrm"))
            Files.writeString(root.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")

            fun emit(out: Path) =
                TtrpCommand().subcommands(EmitCommand()).test(
                    listOf(
                        "emit",
                        "--deployment",
                        "release_native_morning",
                        "--project",
                        root.toString(),
                        "--bundle",
                        result.dir.toString(),
                        "--out",
                        out.toString(),
                        "--json",
                    ),
                )
            val good = root.resolve("good-export")
            val goodResult = emit(good)
            withClue(goodResult.output) { goodResult.statusCode shouldBe 0 }
            val exported =
                Json
                    .parseToJsonElement(
                        Files.readString(good.resolve("release_native_morning.native.export/manifest.json")),
                    ).jsonObject
            exported
                .getValue("fingerprints")
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content shouldBe
                result.manifest.recipe!!
                    .fingerprints.text
            val metadata = root.resolve("models/hartland/automation/automation.ttrm")
            val original = Files.readString(metadata)
            Files.writeString(metadata, original.replace("expression: \"'released'\"", "expression: \"'held'\""))
            val stale = emit(root.resolve("stale-export"))
            stale.statusCode shouldBe 2
            stale.output shouldContain "action approval dependencies"
            Files.exists(root.resolve("stale-export/release_native_morning.native.export")) shouldBe false
            Files.writeString(metadata, original)
            val recipe = result.manifest.recipe!!
            val evidence = recipe.approvalDependencies!!
            listOf(
                recipe.copy(approvalDependencies = null),
                recipe.copy(approvalDependencies = evidence.copy(version = 2)),
                recipe.copy(fingerprints = recipe.fingerprints.copy(text = evidence.textBase)),
            ).forEachIndexed { i, invalid ->
                Files.writeString(result.dir.resolve("manifest.json"), result.manifest.copy(recipe = invalid).toJson())
                val out = root.resolve("invalid-export-$i")
                val refused = emit(out)
                withClue(refused.output) { refused.statusCode shouldBe 2 }
                refused.output shouldContain "action approval dependencies"
                Files.exists(out.resolve("release_native_morning.native.export")) shouldBe false
            }
        }
    })
