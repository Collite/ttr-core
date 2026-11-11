// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files

/**
 * MW S3.2b T1b (✅D-MW-70): an entity with no inline `binding:` block takes its table from the standalone
 * `def er2db_entity` whose `entity:` is that entity, so its bare attribute bindings canonicalise to the
 * table-embedded column qname instead of dangling as `db.dbo.<COL>`.
 */
class StandaloneEntityTableSpec :
    StringSpec({
        val db =
            """
            model db schema dbo

            def table T {
                columns: [
                    def column ID { type: int },
                    def column NAZEV { type: varchar }
                ]
            }
            """.trimIndent()
        val er =
            """
            model er

            def entity E {
                attributes: [
                    def attribute id { type: int, binding: ID },
                    def attribute nazev { type: string, binding: NAZEV }
                ]
            }
            """.trimIndent()
        val binding =
            """
            model binding

            def er2db_entity E {
                entity: er.entity.E
                target: { table: db.dbo.T }
            }
            """.trimIndent()

        fun columnTargets(files: Map<String, String>): List<Pair<String, String>> {
            val root = Files.createTempDirectory("standalone-entity-table")
            for ((rel, content) in files) Files.writeString(root.resolve(rel), content)
            val source = FileBasedSource(sourceId = "s", priority = 100, storage = LocalFsStorage("s", root))
            val result = MetadataLoader(source, ModelDescriptor(id = "s", name = "s")).load()
            withClue(result.issues.toString()) { result.errors.shouldBeEmpty() }
            return result.model
                .shouldNotBeNull()
                .mappings
                .filterIsInstance<Er2DbAttributeMapping>()
                .map { it.attribute.dotted() to (it.target as AttributeMappingTarget.Column).qname.dotted() }
        }

        // Both listing orders: the er2db_entity read before the entity, and after it.
        listOf("a-binding.ttrm" to "b-er.ttrm", "b-binding.ttrm" to "a-er.ttrm").forEach { (bindingFile, erFile) ->
            "bare attribute bindings take the standalone er2db_entity's table ($bindingFile, $erFile)" {
                columnTargets(mapOf("db.ttrm" to db, erFile to er, bindingFile to binding))
                    .shouldContainExactlyInAnyOrder(
                        "er.entity.E.id" to "db.dbo.T.ID",
                        "er.entity.E.nazev" to "db.dbo.T.NAZEV",
                    )
            }
        }

        "an entity with neither an inline block nor an er2db_entity keeps the bare ref, as before" {
            columnTargets(mapOf("db.ttrm" to db, "er.ttrm" to er))
                .shouldContainExactlyInAnyOrder(
                    "er.entity.E.id" to "db.dbo.ID",
                    "er.entity.E.nazev" to "db.dbo.NAZEV",
                )
        }
    })
