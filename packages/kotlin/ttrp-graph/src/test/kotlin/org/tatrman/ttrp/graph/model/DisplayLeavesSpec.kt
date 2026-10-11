// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.graph.TtrpPipeline
import org.tatrman.ttrp.project.TtrpManifestReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Action displays on the graph (grammar 0.14): the frontend's resolved row schema rides on the [Display]
 * node, and [DisplayLeaves] orders same-named leaves by program position with unique file stems
 * (`notify`, `notify~2`, …) — the manifest order the host concatenates rows in.
 */
class DisplayLeavesSpec :
    StringSpec({
        fun at(
            line: Int,
            column: Int = 0,
        ) = SourceLocation("p.ttrp", line, column, line, column + 1, -1, -1)

        fun display(
            id: String,
            name: String,
            line: Int,
        ) = Display(id, "~$id", at(line), name)

        fun graph(vararg ds: Display) = TtrpGraph(ds.associateBy { it.id }, emptyList(), emptyMap())

        "leaves sort by name, then program position; the k-th of a name gets the stem <name>~k" {
            // insertion order deliberately scrambled
            val g = graph(display("a", "notify", 12), display("b", "evidence", 3), display("c", "notify", 10))
            val leaves = DisplayLeaves.of(g)
            leaves.map { it.display.id } shouldContainExactly listOf("b", "c", "a")
            leaves.map { it.stem } shouldContainExactly listOf("evidence", "notify", "notify~2")
            leaves.map { it.ordinal } shouldContainExactly listOf(1, 1, 2)
            DisplayLeaves.stemOf(g, g.nodes.getValue("a") as Display) shouldBe "notify~2"
        }

        "a program with unique display names keeps the bare stems (byte-identical manifests)" {
            DisplayLeaves.of(graph(display("x", "b", 1), display("y", "a", 2))).map { it.stem } shouldContainExactly
                listOf("a", "b")
        }

        "the pipeline carries the action display's schema onto its Display nodes; an ordinary one has none" {
            val project = locate("packages/kotlin/ttrp-frontend/src/test/resources/display")
            val manifest = TtrpManifestReader.resolve(project).manifest
            val src = Files.readString(project.resolve("programs/notify.ttrp"))
            val plan = TtrpPipeline(manifest, manifest.modelsRoot()).plan(src, "notify.ttrp")
            plan.ok shouldBe true
            val leaves = DisplayLeaves.of(plan.graph!!)
            leaves.map { it.display.name to it.stem } shouldContainExactly
                listOf("notify" to "notify", "notify" to "notify~2", "overdue" to "overdue")
            leaves.map { it.display.schema?.qualifiedName } shouldContainExactly
                listOf("shop.actions.notify", "shop.actions.notify", null)
        }
    }) {
    companion object {
        fun locate(rel: String): Path {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                if (Files.isDirectory(dir.resolve(rel))) return dir.resolve(rel)
                dir = dir.parent
            }
            error("could not locate $rel")
        }
    }
}
