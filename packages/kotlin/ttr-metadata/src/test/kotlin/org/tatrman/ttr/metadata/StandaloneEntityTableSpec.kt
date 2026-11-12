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
 *
 * review-245: the gate is "no inline `table`/`view` target", so a `columns:`-only block counts too (✅R245-A, F1);
 * a package-qualified `entity:` ref matches (F2); the first `table`/`view` mapping in file order wins and `query`
 * mappings are skipped (F3).
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

            def table U {
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

        fun standalone(
            name: String,
            entity: String,
            target: String,
        ) = "model binding\n\ndef er2db_entity $name {\n    entity: $entity\n    target: { $target }\n}\n"

        val tTargets = listOf("er.entity.E.id" to "db.dbo.T.ID", "er.entity.E.nazev" to "db.dbo.T.NAZEV")

        fun columnTargets(files: Map<String, String>): List<Pair<String, String>> {
            val root = Files.createTempDirectory("standalone-entity-table")
            for ((rel, content) in files) {
                val path = root.resolve(rel)
                Files.createDirectories(path.parent)
                Files.writeString(path, content)
            }
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
                    .shouldContainExactlyInAnyOrder(tTargets)
            }
        }

        // F1 (✅R245-A): an inline block with only `columns:` has no table either; both the `columns:` entry and the
        // attribute binding take the standalone er2db_entity's table.
        val erColumnsOnly =
            """
            model er

            def entity E {
                binding: { columns: { id: ID } },
                attributes: [
                    def attribute id { type: int },
                    def attribute nazev { type: string, binding: NAZEV }
                ]
            }
            """.trimIndent()
        listOf("a-binding.ttrm" to "b-er.ttrm", "b-binding.ttrm" to "a-er.ttrm").forEach { (bindingFile, erFile) ->
            "a columns-only inline block takes the standalone er2db_entity's table ($bindingFile, $erFile)" {
                columnTargets(mapOf("db.ttrm" to db, erFile to erColumnsOnly, bindingFile to binding))
                    .shouldContainExactlyInAnyOrder(tTargets)
            }
        }

        // F2: S3.3 `link` emits package-qualified refs across packages.
        "a package-qualified entity: ref from another package matches the entity" {
            columnTargets(
                mapOf(
                    "db.ttrm" to db,
                    "demo/er.ttrm" to "package demo\n\n$er",
                    "bind/binding.ttrm" to
                        "package bind\n\nimport demo.*\n\n" + standalone("E", "demo.er.entity.E", "table: db.dbo.T"),
                ),
            ).shouldContainExactlyInAnyOrder(tTargets)
        }

        // F3: the first table/view mapping in file order wins; a query mapping is skipped, not a stop.
        "a query mapping is skipped and a later table mapping anchors" {
            columnTargets(
                mapOf(
                    "db.ttrm" to db,
                    "er.ttrm" to er,
                    "q.ttrm" to "model er\n\ndef query q { sourceText: \"SELECT 1\" }\n",
                    "a-binding.ttrm" to standalone("a_E", "er.entity.E", "query: er.query.q"),
                    "b-binding.ttrm" to binding,
                ),
            ).shouldContainExactlyInAnyOrder(tTargets)
        }

        "with two table mappings the first in file order wins (✅D-MW-70)" {
            columnTargets(
                mapOf(
                    "db.ttrm" to db,
                    "er.ttrm" to er,
                    "a-binding.ttrm" to standalone("E_hist", "er.entity.E", "table: db.dbo.U"),
                    "b-binding.ttrm" to binding,
                ),
            ).shouldContainExactlyInAnyOrder("er.entity.E.id" to "db.dbo.U.ID", "er.entity.E.nazev" to "db.dbo.U.NAZEV")
        }

        "an entity with neither an inline block nor an er2db_entity keeps the bare ref, as before" {
            columnTargets(mapOf("db.ttrm" to db, "er.ttrm" to er))
                .shouldContainExactlyInAnyOrder(
                    "er.entity.E.id" to "db.dbo.ID",
                    "er.entity.E.nazev" to "db.dbo.NAZEV",
                )
        }
    })
