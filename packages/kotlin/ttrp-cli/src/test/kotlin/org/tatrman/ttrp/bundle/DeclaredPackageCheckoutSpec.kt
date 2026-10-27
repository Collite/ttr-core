// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.cli.TtrpCli
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/** #251: real recipe check/build, including synthesized calendar loads, after checkout relocation. */
class DeclaredPackageCheckoutSpec :
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
        val model = locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes/model/hartland")
        val recipes = locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes")

        fun project(flat: Boolean): Path {
            val root = tempdir("declared-packages").toPath().resolve("private/cache/checkout")
            Files.createDirectories(root.resolve("models"))
            Files.walk(model).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList().forEachIndexed { i, file ->
                    val target =
                        if (flat) {
                            root.resolve("models/metadata-$i.ttrm")
                        } else {
                            root.resolve("models/hartland").resolve(model.relativize(file))
                        }
                    Files.createDirectories(target.parent)
                    Files.copy(file, target)
                }
            }
            Files.writeString(root.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
            return root
        }

        fun build(
            root: Path,
            name: String,
        ): BundleAssembler.BundleResult {
            val source = Files.readString(recipes.resolve(name))
            val file = root.resolve(name)
            Files.writeString(file, source)
            val messages = mutableListOf<String>()
            val code = TtrpCli.runCheck(file, { messages += it })
            withClue("$file: ${messages.joinToString("\n")}") { code shouldBe 0 }
            return BundleAssembler("checkout-test").build(
                source = source,
                fileName = file.toString(),
                recipeDir = root,
                pipelineManifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = root),
                modelsRoot = root.resolve("models"),
                outDir = root.resolve(name.removeSuffix(".ttrp") + "-out"),
            )
        }
        "four shared recipes check and build identically without a package-named directory" {
            val conventional = project(false)
            val relocated = project(true)
            for (name in listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")) {
                val expected = build(conventional, name)
                val actual = build(relocated, name)
                actual.manifest.world shouldBe expected.manifest.world
                actual.manifest.recipe!!.objectsRead shouldBe expected.manifest.recipe!!.objectsRead

                fun statements(result: BundleAssembler.BundleResult): Map<String, String> =
                    Files.walk(result.dir).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it) && it.toString().endsWith(".sql") }
                            .toList()
                            .associate { result.dir.relativize(it).toString() to Files.readString(it) }
                    }
                statements(actual) shouldBe statements(expected)
            }
        }
        "a models symlink to the relocated checkout retains declared package scope" {
            val actual = project(true)
            val linked = tempdir("linked-models").toPath()
            Files.createSymbolicLink(linked.resolve("models"), actual.resolve("models"))
            Files.writeString(linked.resolve("modeler.toml"), "[ttrp]\nworld = \"hartland.worlds.demo\"\n")
            build(linked, "settle.ttrp")
                .manifest.recipe!!
                .objectsRead
                .contains("er.entity.date_dim") shouldBe true
        }
    })
