// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttrp.cli.EmitCommand
import org.tatrman.ttrp.cli.TtrpCommand
import org.tatrman.ttrp.cli.BuildCommand
import org.tatrman.ttrp.cli.CheckCommand
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import org.tatrman.ttrp.project.TtrpManifest
import org.tatrman.ttrp.resolve.ModelRepo
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.ActionResolution
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
            sourceText: String = recipe,
            checkoutSubdir: String = "",
            transform: (String) -> String = { it },
        ): BundleAssembler.BundleResult {
            val root = tempdir("writer-fingerprints").toPath().resolve(checkoutSubdir)
            Files.createDirectories(root)
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
                    source = sourceText,
                    fileName = "release_order.ttrp",
                    pipelineManifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                    modelsRoot = root.resolve("model"),
                    outDir = root.resolve("out"),
                )
        }

        fun build(transform: (String) -> String = { it }): RecipeManifest =
            bundle(transform = transform).manifest.recipe!!

        fun exportProject(
            result: BundleAssembler.BundleResult,
            packageName: String = "hartland",
        ): Path {
            val root = result.dir.parent.parent
            val packagePath = packageName.replace('.', '/')
            Files.move(root.resolve("model"), root.resolve("models"))
            // The compiler's old shared fixture uses pre-package-parity directory layout and role hints.
            // Normalize this private copy for the export CLI's strict metadata reader.
            for (folder in listOf("binding", "db", "er")) {
                val from = root.resolve("models/$packagePath/$folder")
                Files.list(from).use { paths ->
                    paths.forEach { file ->
                        val text = Files.readString(file).replace(Regex("roles: \\[.*?\\]"), "")
                        Files.writeString(root.resolve("models/$packagePath/${folder}_${file.fileName}"), text)
                        Files.delete(file)
                    }
                }
                Files.delete(from)
            }
            Files.deleteIfExists(root.resolve("models/$packagePath/automation/checker-probes.ttrm"))
            Files.writeString(root.resolve("modeler.toml"), "[ttrp]\nworld = \"$packageName.worlds.demo\"\n")
            return root
        }

        "native export validates dotted host packages using the loader provider" {
            val result =
                bundle(
                    sourceText = recipe.replace("hartland", "acme.sales"),
                    editModel = { model ->
                        val oldPackage = model.resolve("hartland")
                        Files.walk(oldPackage).use { paths ->
                            paths.filter { Files.isRegularFile(it) }.toList().forEach { file ->
                                Files.writeString(file, Files.readString(file).replace("hartland", "acme.sales"))
                            }
                        }
                        Files.createDirectories(model.resolve("acme"))
                        Files.move(oldPackage, model.resolve("acme/sales"))
                    },
                )
            val root = exportProject(result, "acme.sales")
            val emitted =
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
                        root.resolve("export").toString(),
                        "--json",
                    ),
                )
            withClue(emitted.output) { emitted.statusCode shouldBe 0 }
            Files.exists(root.resolve("export/release_native_morning.native.export/manifest.json")) shouldBe true
        }

        "rereview R1 default approval verifies after moving metadata outside all package directories" {
            val result = bundle()
            val recipe = result.manifest.recipe!!
            val evidence = recipe.approvalDependencies!!
            val moved = tempdir("moved-approval").toPath().resolve("private/cache/model")
            Files.createDirectories(moved)
            Files
                .walk(
                    result.dir.parent.parent
                        .resolve("model"),
                ).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.toList().forEachIndexed { i, file ->
                        Files.copy(file, moved.resolve("metadata-$i.ttrm"))
                    }
                }
            val scope =
                ModelRepo.indexOf(moved)!!.automationScope(
                    evidence.imports.map { ModelIndex.importScope(it.split('.'), it) },
                )
            val actions =
                evidence.actions.keys.associateWith { name ->
                    (scope.action(name) as ActionResolution.Resolved).action
                }
            RecipeActionDependencies.verify(evidence, recipe.fingerprints.text, actions, scope::target) shouldBe
                RecipeActionDependencies.Verification.Ok
        }

        "a path only import becomes a located RES006 refusal" {
            val source =
                recipe
                    .replace("import hartland.*", "import private.*")
                    .replace("import hartland.automation.*", "")
            val failed =
                shouldThrow<BundleAssembler.BundleRefused> {
                    bundle(sourceText = source, checkoutSubdir = "private/cache")
                }
            failed.diagnostics.any {
                it.id.name == "RES_006" && it.location.line == 6 && "private" in it.message
            } shouldBe
                true
        }

        "rereview R3 an evidence free export identifies model ambiguity without inventing a recorded scope" {
            val result = bundle { it.replace("writer_defaults: { state: { expression: \"'released'\" } }", "") }
            val root = exportProject(result)
            Files.createDirectories(root.resolve("models/other"))
            Files.writeString(
                root.resolve("models/other/action.ttrm"),
                "package other\nmodel automation\ndef action release { " +
                    "columns: [def column id { type: text, optional: true }], sinks: { native: writer } }",
            )
            val out = root.resolve("ambiguous-export")
            val refused =
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
                    ),
                )
            refused.statusCode shouldBe 2
            refused.output shouldContain "release"
            refused.output shouldContain "ambiguous"
            refused.output.contains("recorded scope") shouldBe false
            Files.exists(out.resolve("release_native_morning.native.export")) shouldBe false
        }

        "rereview n4 an unknown recorded import is named before any export is written" {
            val result = bundle()
            val root = exportProject(result)
            val recipe = result.manifest.recipe!!
            val evidence =
                recipe.approvalDependencies!!.copy(
                    imports =
                        (
                            recipe.approvalDependencies!!.imports +
                                "absent"
                        ).sorted(),
                )
            val changed =
                recipe.copy(
                    approvalDependencies = evidence,
                    fingerprints = recipe.fingerprints.copy(text = RecipeActionDependencies.text(evidence)),
                )
            val badBundle = root.resolve("missing-import-bundle")
            Files.walk(result.dir).use { paths ->
                paths.forEach { path ->
                    val to = badBundle.resolve(result.dir.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(to) else Files.copy(path, to)
                }
            }
            Files.writeString(badBundle.resolve("manifest.json"), result.manifest.copy(recipe = changed).toJson())
            val out = root.resolve("missing-import-export")
            val refused =
                TtrpCommand().subcommands(EmitCommand()).test(
                    listOf(
                        "emit",
                        "--deployment",
                        "release_native_morning",
                        "--project",
                        root.toString(),
                        "--bundle",
                        badBundle.toString(),
                        "--out",
                        out.toString(),
                    ),
                )
            refused.statusCode shouldBe 2
            refused.output shouldContain "recorded import `absent.*`"
            refused.output.contains("rebuild") shouldBe false
            Files.exists(out.resolve("release_native_morning.native.export")) shouldBe false
        }

        "rereview n3 an ambiguous action typed value has the actionable REC007 diagnostic" {
            val declaration =
                "def action value_action { columns: [def column id { type: text, optional: true }], " +
                    "sinks: { native: writer } }"
            val failed =
                shouldThrow<BundleAssembler.BundleRefused> {
                    bundle(sourceText = recipe.replace("action: release", "action: value_action"), editModel = { root ->
                        val dir = root.resolve("hartland/automation/legacy")
                        Files.createDirectories(dir)
                        Files.writeString(
                            dir.resolve("action.ttrm"),
                            "package hartland.automation.legacy\nmodel automation\n$declaration",
                        )
                    }) { it + "\n$declaration\n" }
                }
            failed.diagnostics.any { it.id.name == "REC_007" && it.location.line > 0 } shouldBe true
            failed.diagnostics.none { it.id.name == "REC_002" } shouldBe true
        }

        for (prefix in listOf("hartland", "er.entity")) {
            "review145 B1 $prefix qualified targets cover the real entity even beside a package-named entity" {
                fun qualified(text: String) = text.replace("type: order.", "type: $prefix.order.")

                fun edit(
                    root: Path,
                    bounds: Boolean,
                ) {
                    Files.writeString(
                        root.resolve("hartland/er/package_name.ttrm"),
                        "package hartland\nmodel er\ndef entity hartland { " +
                            "attributes: [def attribute id { type: int, isKey: true }] }",
                    )
                    if (bounds) {
                        val file = root.resolve("hartland/er/orders.ttrm")
                        Files.writeString(
                            file,
                            Files.readString(file).replace(
                                "def attribute placed_by            { type: text",
                                "def attribute placed_by            { type: { type: text, length: 40 }",
                            ),
                        )
                    }
                }
                bundle(
                    transform = ::qualified,
                ).manifest.recipe!!.approvalDependencies!!.actions.containsKey("release") shouldBe
                    true
                val before = bundle(editModel = { edit(it, false) }, transform = ::qualified).manifest.recipe!!
                val after = bundle(editModel = { edit(it, true) }, transform = ::qualified).manifest.recipe!!
                before.approvalDependencies!!.actions.getValue("release") shouldNotBe
                    after.approvalDependencies!!.actions.getValue("release")
            }
        }

        "review145 B1 qualified action references are type checked like bare references" {
            val failure =
                shouldThrow<BundleAssembler.BundleRefused> {
                    bundle(
                        sourceText = recipe.replace("release { order: order_number", "release { order: 'wrong-type'"),
                    ) {
                        it
                            .replace("type: order.order_number", "type: hartland.order.order_number")
                            .replace("writer_defaults: { state: { expression: \"'released'\" } }", "")
                    }
                }
            failure.diagnostics.any { it.id.name == "REC_002" && it.location.line > 0 } shouldBe true
        }

        "review145 M1 an unimported wired target refuses with a located REC diagnostic" {
            var invalidRoot: Path? = null

            fun widget(root: Path) {
                invalidRoot = root.parent
                val dir = root.resolve("other/er")
                Files.createDirectories(dir)
                Files.writeString(
                    dir.resolve("widget.ttrm"),
                    "package other\nmodel er\ndef entity widget { attributes: [def attribute name { type: text }] }",
                )
            }
            val failure =
                shouldThrow<BundleAssembler.BundleRefused> {
                    bundle(editModel = ::widget) {
                        it.replace(
                            "def column department { type: text }",
                            "def column department { type: widget.name }",
                        )
                    }
                }
            failure.diagnostics.any { it.id.name == "REC_006" && it.location.line > 0 } shouldBe true
            val root = requireNotNull(invalidRoot)
            Files.move(root.resolve("model"), root.resolve("models"))
            Files.writeString(root.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
            val file = root.resolve("release_order.ttrp")
            Files.writeString(file, recipe)
            val check = TtrpCommand().subcommands(CheckCommand()).test(listOf("check", file.toString()))
            check.statusCode shouldBe 1
            check.output shouldContain "TTRP-REC-006"
            val built =
                TtrpCommand().subcommands(BuildCommand()).test(
                    listOf("build", file.toString(), "--json", "--out", root.resolve("refused").toString()),
                )
            built.statusCode shouldBe 1
            val findings =
                Json
                    .parseToJsonElement(built.stdout)
                    .jsonObject
                    .getValue("diagnostics")
                    .jsonArray
            val diagnostics =
                findings.filter {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "TTRP-REC-006"
                }
            diagnostics.isNotEmpty() shouldBe true
            diagnostics.forEach { finding ->
                val diagnostic = finding.jsonObject
                diagnostic.getValue("file").jsonPrimitive.content shouldBe file.toString()
                diagnostic
                    .getValue("line")
                    .jsonPrimitive.content
                    .toInt()
                    .let { it > 0 } shouldBe true
            }
            Files.exists(root.resolve("refused/release_order.bundle")) shouldBe false
            bundle(editModel = ::widget) {
                it
                    .replace("writer_defaults: { state: { expression: \"'released'\" } }", "")
                    .replace("def column department { type: text }", "def column department { type: widget.name }")
            }.manifest.recipe!!.approvalDependencies shouldBe null
        }

        "review145 M1 set and transition target refs receive the same located coverage diagnostic" {
            for ((before, after) in listOf(
                "order.held_for = department" to "missing.widget.name = department",
                "transition: order.state" to "transition: missing.widget.state",
            )) {
                val failed = shouldThrow<BundleAssembler.BundleRefused> { bundle { it.replace(before, after) } }
                failed.diagnostics.any { it.id.name == "REC_006" && it.location.line > 0 } shouldBe true
            }
        }

        "review145 M2 visible same-named actions refuse with a located REC diagnostic" {
            for (defaults in listOf(true, false)) {
                val failure =
                    shouldThrow<BundleAssembler.BundleRefused> {
                        bundle(editModel = { root ->
                            val dir = root.resolve("hartland/automation/legacy")
                            Files.createDirectories(dir)
                            Files.writeString(
                                dir.resolve("legacy.ttrm"),
                                "package hartland.automation.legacy\nmodel automation\ndef action release { " +
                                    "columns: [def column order { type: order.order_number }], sinks: { native: writer } }",
                            )
                        }) {
                            if (defaults) {
                                it
                            } else {
                                it.replace(
                                    "writer_defaults: { state: { expression: \"'released'\" } }",
                                    "",
                                )
                            }
                        }
                    }
                failure.diagnostics.any { it.id.name == "REC_007" && it.location.line > 0 } shouldBe true
            }
        }

        "review145 M2 a default on an out of scope same-named action cannot activate evidence" {
            val result =
                bundle(editModel = { root ->
                    val dir = root.resolve("other/automation")
                    Files.createDirectories(dir)
                    Files.writeString(
                        dir.resolve("legacy.ttrm"),
                        "package other.automation\nmodel automation\ndef action release { writer_defaults: {} }",
                    )
                }) { it.replace("writer_defaults: { state: { expression: \"'released'\" } }", "") }
            result.manifest.recipe!!.approvalDependencies shouldBe null
        }

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
            val root = exportProject(result)

            fun emit(
                out: Path,
                bundleDir: Path = result.dir,
            ) = TtrpCommand().subcommands(EmitCommand()).test(
                listOf(
                    "emit",
                    "--deployment",
                    "release_native_morning",
                    "--project",
                    root.toString(),
                    "--bundle",
                    bundleDir.toString(),
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
            stale.output shouldContain "rebuild the recipe bundle"
            Files.exists(root.resolve("stale-export/release_native_morning.native.export")) shouldBe false
            Files.writeString(metadata, original)
            val recipe = result.manifest.recipe!!
            val evidence = recipe.approvalDependencies!!

            fun invalidBundle(
                name: String,
                json: String,
            ): Path {
                val copy = root.resolve(name)
                Files.walk(result.dir).use { paths ->
                    paths.forEach { path ->
                        val to = copy.resolve(result.dir.relativize(path))
                        if (Files.isDirectory(path)) Files.createDirectories(to) else Files.copy(path, to)
                    }
                }
                Files.writeString(copy.resolve("manifest.json"), json)
                return copy
            }
            listOf(
                recipe.copy(approvalDependencies = null) to "approval evidence is missing",
                recipe.copy(approvalDependencies = evidence.copy(version = 2)) to "unsupported approval evidence",
                recipe.copy(fingerprints = recipe.fingerprints.copy(text = evidence.textBase)) to
                    "not bound to recipe text/2",
            ).forEachIndexed { i, (invalid, reason) ->
                val dir = invalidBundle("invalid-bundle-$i", result.manifest.copy(recipe = invalid).toJson())
                val out = root.resolve("invalid-export-$i")
                val refused = emit(out, dir)
                withClue(refused.output) { refused.statusCode shouldBe 2 }
                refused.output shouldContain reason
                refused.output.contains("rebuild") shouldBe false
                Files.exists(out.resolve("release_native_morning.native.export")) shouldBe false
            }
            val missingVersion = result.manifest.toJson().replace(Regex("\"version\":\\s*1,\\s*"), "")
            val missingDir = invalidBundle("missing-version-bundle", missingVersion)
            val missingOut = root.resolve("missing-version")
            val missing = emit(missingOut, missingDir)
            missing.statusCode shouldBe 2
            missing.output shouldContain "version"
            Files.exists(missingOut.resolve("release_native_morning.native.export")) shouldBe false

            // #294: after reconcile a different package can replace an ER entity with the same name.
            // Scoped verification must name the unresolved ref, not recommend a rebuild of the same model.
            Files.createDirectories(root.resolve("models/other"))
            val source = Files.readString(root.resolve("models/hartland/er_orders.ttrm"))
            val order =
                source.substring(
                    source.indexOf("def entity order {"),
                    source.indexOf("def entity sales_line {"),
                )
            Files.writeString(root.resolve("models/other/order.ttrm"), "package other\nmodel er\n$order")
            val unresolved = emit(root.resolve("unresolved-export"))
            unresolved.statusCode shouldBe 2
            unresolved.output shouldContain "unresolved"
            unresolved.output shouldContain "order."
            unresolved.output.contains("rebuild") shouldBe false
        }
    })
