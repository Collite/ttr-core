// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AU-core CG (D-142): all four hero bundles retain their pre-cut digests after stripping only CG-2 additions.
 * A bundle keeps its bytes when its wired actions declare no `by`/`due`, and its deployments and their executors
 * declare no `startable_by`/`egress_url`. A `due:` written before CG-2 now adds `recipe.actions.<a>.due` and the
 * catalog's `actions:` line. Only these additions are stripped from release_order and decide_return; every other
 * byte is pinned to the pre-cut toolchain (grammar 0.21, master 0c1e3d74 with the cut fixtures), except for
 * Core#382's boolean detail marker and its catalog/manifest hashes, added to the reviewed baseline.
 * Re-recording writes all four recipes' stripped digests, preserving the complete golden.
 */
class CutUnaffectedBundleSpec :
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
        val golden =
            locate(
                "packages/kotlin/ttrp-cli/src/test/resources/fixtures",
            ).resolve("cut-unaffected-bundles.txt")
        val manifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        "all four hero bundles match the reviewed baseline after stripping only the CG-2 additions" {
            val lines =
                listOf("release_order", "decide_return", "monitor_inbound", "settle").flatMap { name ->
                    val out = Files.createTempDirectory("cut-unaffected-$name")
                    val result =
                        BundleAssembler("1.0.0").build(
                            source = Files.readString(recipes.resolve("$name.ttrp")),
                            fileName = "$name.ttrp",
                            recipeDir = recipes,
                            pipelineManifest = manifest,
                            modelsRoot = fixtures.resolve("model"),
                            outDir = out,
                        )
                    val stripEngineMetadata = name in setOf("release_order", "decide_return")
                    val catalogPath = "catalog/$name.ttrm"
                    val strippedCatalog =
                        Files
                            .readString(result.dir.resolve(catalogPath))
                            .lineSequence()
                            .filterNot { stripEngineMetadata && it.trimStart().startsWith("actions: ") }
                            .joinToString("\n")
                    val catalogHash = sha256(strippedCatalog.toByteArray())
                    val decoded = RunManifest.fromJson(result.manifest.toJson())
                    val strippedManifest =
                        decoded
                            .copy(
                                recipe = decoded.recipe!!.copy(actions = null, deployments = null, executors = null),
                                files = decoded.files + (catalogPath to "sha256:$catalogHash"),
                            ).toJson()
                    Files.walk(result.dir).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it) }
                            .map {
                                val relative =
                                    result.dir
                                        .relativize(it)
                                        .toString()
                                        .replace('\\', '/')
                                val bytes =
                                    when {
                                        !stripEngineMetadata -> Files.readAllBytes(it)
                                        relative == catalogPath -> strippedCatalog.toByteArray()
                                        relative == "manifest.json" -> strippedManifest.toByteArray()
                                        else -> Files.readAllBytes(it)
                                    }
                                "$name/$relative" to sha256(bytes)
                            }.toList()
                    }
                }
            val text = lines.sortedBy { it.first }.joinToString("") { (file, sha) -> "$sha  $file\n" }
            if (System.getProperty("updateGolden") == "true") Files.writeString(golden, text)
            Files.readString(golden) shouldBe text
        }
    })
