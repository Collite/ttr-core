// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.expr.ExpressionTypechecker
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.parser.TtrpParser
import org.tatrman.ttrp.project.TtrpManifest
import org.tatrman.ttrp.resolve.TtrpChecker
import java.nio.file.Files
import java.nio.file.Path

/** Core#382 / R227 L1: a recipe can exclude marker rows from its own detail history. */
class RecipeCatalogSpec :
    StringSpec({
        "a recipe can read its own detail rows and filter out the truncation marker" {
            var root: Path? = Path.of("").toAbsolutePath()
            while (root != null && !Files.isDirectory(root.resolve("packages/kotlin/ttrp-graph"))) root = root.parent
            val repo = checkNotNull(root)
            val fixtures = repo.resolve("packages/kotlin/ttrp-graph/src/test/resources/fixtures/recipes")
            val file = repo.resolve("packages/kotlin/ttrp-frontend/src/test/resources/recipes/monitor_inbound.ttrp")
            val base = Files.readString(file)
            val predicate = "not v.truncated"
            val source =
                base
                    .replace(
                        "LEFT JOIN er.entity.monitor_inbound_verdict v",
                        "LEFT JOIN er.entity.monitor_inbound_verdict_detail v",
                    ).replace(
                        "GROUP  BY q.line_id\n    \"\"\"\n\n    decision",
                        "WHERE $predicate\n        GROUP  BY q.line_id\n    \"\"\"\n\n    decision",
                    )
            (source != base && source.contains("WHERE $predicate")) shouldBe true
            val manifest = TtrpManifest(world = "hartland.worlds.demo", manifestDir = fixtures)
            val report =
                TtrpChecker(manifest, fixtures.resolve("model")).check(source, "monitor_inbound.ttrp")
            report.diagnostics.map { "${it.id.id} ${it.message}" }.shouldBeEmpty()
            // The resolver supplies the actual own-entity schema; recipe predicates are typed by the later
            // recipe pass. Check this WHERE against that resolved schema, so an absent column cannot be deferred.
            val detail =
                report.loadSchemas.values.single { columns ->
                    columns.any { it.name == "row_no" } && columns.any { it.name == "context" }
                }
            val parsed = TtrpParser.parseExpression(predicate)
            parsed.diagnostics.shouldBeEmpty()
            val checked =
                ExpressionTypechecker().check(parsed.expression, mapOf("v" to detail), predicateExpected = true)
            checked.diagnostics.map { "${it.id.id} ${it.message}" }.shouldBeEmpty()
            checked.type shouldBe TtrpType.Bool
        }
    })
