// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The recipe-checker specs' driver (AU-core-P1): the hartland model of `fixtures/recipes/model` (ttr-demo's public
 * model, with 0.16's world members, `states:` and the automation package of design 17), the shared `17` recipes of
 * ttrp-frontend's test resources, and this module's negatives.
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

    /** ttrp-frontend's shared `17` recipes (AU-core-P0 S0.1). */
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

    fun fixture(name: String): TtrpPipeline.PlanResult = plan(Files.readString(root.resolve(name)), name)

    fun negative(name: String): TtrpPipeline.PlanResult = fixture("negative/$name")

    /** Everything the checker said, but the P2 guard rail (a recipe does not compile to a bundle until AU-core-P2). */
    fun findings(r: TtrpPipeline.PlanResult): List<TtrpDiagnostic> =
        r.diagnostics.filter {
            it.id !=
                TtrpDiagnosticId.REC_099
        }

    /** A recipe written inline: the imports + the world of the fixture, then [body] inside `recipe { }`. */
    fun inline(
        body: String,
        name: String = "inline.ttrp",
    ): TtrpPipeline.PlanResult =
        plan(
            """
            |uses world "hartland.worlds.demo"
            |import hartland.*
            |import hartland.automation.*
            |
            |recipe {
            |${body.trimIndent()}
            |}
            |
            """.trimMargin(),
            name,
        )
}
