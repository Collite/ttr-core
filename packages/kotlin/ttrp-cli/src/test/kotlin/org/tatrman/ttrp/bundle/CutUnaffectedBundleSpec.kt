// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AU-core CG (D-142) T1 — the grammar cut leaves an unaffected bundle byte-identical. The four `17` hero recipes
 * are built over the Core fixture model as cut (no removed construct anywhere); every file of every bundle —
 * manifest v3, islands, catalog, README — must hash as it did when the digests were recorded with the toolchain
 * BEFORE the cut (grammar 0.21, `-DupdateGolden=true` on master 0c1e3d74 with the cut fixtures).
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
        val golden = locate("packages/kotlin/ttrp-cli/src/test/resources/fixtures").resolve("cut-unaffected-bundles.txt")
        val manifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        "the hero bundles over a model without removed constructs are byte-identical to the pre-cut toolchain's" {
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
                    Files.walk(result.dir).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it) }
                            .map { "$name/${result.dir.relativize(it).toString().replace('\\', '/')}" to sha256(Files.readAllBytes(it)) }
                            .toList()
                    }
                }
            val text = lines.sortedBy { it.first }.joinToString("") { (file, sha) -> "$sha  $file\n" }
            if (System.getProperty("updateGolden") == "true") Files.writeString(golden, text)
            Files.readString(golden) shouldBe text
        }
    })
