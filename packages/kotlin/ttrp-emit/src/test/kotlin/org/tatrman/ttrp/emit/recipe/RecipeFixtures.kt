// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The recipe-compile specs' driver (AU-core-P2): the hartland model of ttrp-graph's `fixtures/recipes/model` (the
 * recipe checker's — ttr-demo's public model, 0.16's world members, `states:`, design 17's automation package) and
 * the shared `17` recipes of ttrp-frontend's test resources.
 */
object RecipeFixtures {
    private fun locate(rel: String): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val c = dir.resolve(rel)
            if (Files.isDirectory(c)) return c
            dir = dir.parent
        }
        error("could not locate $rel from ${Path.of("").toAbsolutePath()}")
    }

    val root: Path by lazy { locate("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes") }
    val shared: Path by lazy { locate("packages/kotlin/ttrp-frontend/src/test/resources/recipes") }

    val SEVENTEEN = listOf("release_order.ttrp", "decide_return.ttrp", "monitor_inbound.ttrp", "settle.ttrp")

    private val pipeline by lazy {
        TtrpPipeline(TtrpManifest(world = "hartland.worlds.demo", manifestDir = root), root.resolve("model"))
    }

    fun plan(
        source: String,
        fileName: String,
    ): TtrpPipeline.PlanResult = pipeline.plan(source, fileName)

    fun sharedRecipe(name: String): TtrpPipeline.PlanResult = plan(Files.readString(shared.resolve(name)), name)
}
