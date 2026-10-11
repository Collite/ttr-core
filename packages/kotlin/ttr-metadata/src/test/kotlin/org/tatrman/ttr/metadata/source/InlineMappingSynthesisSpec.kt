// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.MappingSource
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/**
 * v2.1 — synthesiser + duplicate-mapping validator tests.
 *
 *  - Synthesiser: an entity / attribute / relation with an inline `mapping:`
 *    block contributes Er2Db*Mapping records tagged with `MappingSource.Inline`.
 *  - Validator: when an inline mapping and an explicit `def er2db_*` declaration
 *    target the same qname, `ttr/duplicate-mapping` is emitted.
 */
class InlineMappingSynthesisSpec :
    StringSpec({

        // Minimal in-memory ModelStorage so we can drive FileBasedSource without
        // touching disk. Keys are file paths; values are file contents.
        class InMemoryStorage(
            override val id: String,
            private val files: Map<String, String>,
        ) : ModelStorage {
            override fun fetchVersion(): String = "test"

            override fun listFiles(
                extensions: List<String>,
                prefixes: List<String>,
            ): List<StorageFile> =
                files.keys
                    .filter { p -> extensions.any { p.endsWith(".$it") } }
                    .map { StorageFile(path = it, sizeBytes = 0L, rootPath = Path.of("/")) }

            override fun read(file: StorageFile): String = files[file.path] ?: ""
        }

        fun loadInline(files: Map<String, String>): org.tatrman.ttr.metadata.reconcile.ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "inline-test",
                    priority = 100,
                    storage = InMemoryStorage(id = "inline-test", files = files),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "inline-test", name = "inline-test"))
            return reconciler.reconcile(listOf(source.load()))
        }

        "entity-level inline mapping synthesises Er2DbEntityMapping + per-column Er2DbAttributeMapping" {
            val erTtr =
                """
                model er schema entity
                def entity item {
                    binding: {
                        target: { table: db.dbo.item },
                        columns: {
                            sk: i_item_sk,
                            item_id: { target: i_item_id }
                        }
                    },
                    attributes: [
                        def attribute sk { type: int, isKey: true },
                        def attribute item_id { type: text }
                    ]
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val mappings = result.model.mappings

            val entityMapping = mappings.filterIsInstance<Er2DbEntityMapping>().single()
            entityMapping.mappingSource shouldBe MappingSource.Inline("entity")
            entityMapping.qname.name shouldBe "item"
            entityMapping.target.shouldBeInstanceOf<MappingTarget.Table>()

            val attrMappings = mappings.filterIsInstance<Er2DbAttributeMapping>()
            attrMappings shouldHaveSize 2
            attrMappings.all { it.mappingSource == MappingSource.Inline("entity") } shouldBe true
            attrMappings.map { it.qname.name }.toSet() shouldBe
                setOf("item.sk", "item.item_id")
        }

        "attribute-level inline mapping synthesises Er2DbAttributeMapping tagged Inline(\"attribute\")" {
            val erTtr =
                """
                model er schema entity
                def entity category {
                    attributes: [
                        def attribute sk { type: int, isKey: true, binding: i_category_id },
                        def attribute category_code { type: text, binding: { target: { column: i_category_code } } }
                    ]
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val attrMappings = result.model.mappings.filterIsInstance<Er2DbAttributeMapping>()
            attrMappings shouldHaveSize 2
            attrMappings.all { it.mappingSource == MappingSource.Inline("attribute") } shouldBe true
            val byName = attrMappings.associateBy { it.qname.name }
            byName["category.sk"].shouldNotBeNull()
            byName["category.category_code"].shouldNotBeNull()
        }

        "relation-level inline mapping (bare-fk) synthesises Er2DbRelationMapping tagged Inline" {
            val erTtr =
                """
                model er schema entity
                def entity a {}
                def entity b {}
                def relation item_in_category {
                    from: er.entity.a, to: er.entity.b,
                    cardinality: { from: "0..*", to: "0..1" },
                    join: [{ from: er.entity.a.x, to: er.entity.b.x }],
                    binding: db.dbo.fk_a_b
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val relMapping =
                result.model.mappings
                    .filterIsInstance<Er2DbRelationMapping>()
                    .single()
            relMapping.mappingSource shouldBe MappingSource.Inline("relation")
            relMapping.qname.name shouldBe "item_in_category"
            relMapping.foreignKey.name shouldBe "fk_a_b"
        }

        "relation-level inline mapping (wrapped fk) synthesises Er2DbRelationMapping tagged Inline" {
            val erTtr =
                """
                model er schema entity
                def entity a {}
                def entity b {}
                def relation r {
                    from: er.entity.a, to: er.entity.b,
                    cardinality: { from: "0..*", to: "0..1" },
                    join: [{ from: er.entity.a.x, to: er.entity.b.x }],
                    binding: { fk: db.dbo.fk_a_b }
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            result.model.mappings.filterIsInstance<Er2DbRelationMapping>() shouldHaveSize 1
        }

        "entity without inline mapping contributes no Er2Db mappings" {
            val erTtr =
                """
                model er schema entity
                def entity plain {
                    attributes: [
                        def attribute id { type: int, isKey: true }
                    ]
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            result.model.mappings.filterIsInstance<Er2DbEntityMapping>() shouldHaveSize 0
            result.model.mappings.filterIsInstance<Er2DbAttributeMapping>() shouldHaveSize 0
        }

        "ttr/duplicate-mapping fires when an inline entity mapping and an explicit def er2db_entity share a qname" {
            val erTtr =
                """
                model er schema entity
                def entity item {
                    binding: { target: { table: db.dbo.item } },
                    attributes: [ def attribute id { type: int, isKey: true } ]
                }
                """.trimIndent()
            val mapTtr =
                """
                model binding
                def er2db_entity item { entity: er.entity.item, target: { table: db.dbo.OTHER } }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr, "/map.ttr" to mapTtr))
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 2 // one per offending location
            dup[0].message shouldContain "item"
            dup[0].message shouldContain "inline on entity"
            dup[0].message shouldContain "explicit def er2db_*"
        }

        "ttr/duplicate-mapping fires when inline attribute mapping collides with explicit def er2db_attribute" {
            val erTtr =
                """
                model er schema entity
                def entity category {
                    attributes: [
                        def attribute sk { type: int, isKey: true, binding: i_category_id }
                    ]
                }
                """.trimIndent()
            val mapTtr =
                """
                model binding
                def er2db_attribute category.sk {
                    attribute: er.entity.category.sk,
                    target: { column: db.dbo.OTHER.i_category_id }
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr, "/map.ttr" to mapTtr))
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 2
            dup[0].message shouldContain "category.sk"
        }

        "ttr/duplicate-mapping fires when inline relation mapping collides with explicit def er2db_relation" {
            val erTtr =
                """
                model er schema entity
                def entity a {}
                def entity b {}
                def relation item_in_category {
                    from: er.entity.a, to: er.entity.b,
                    cardinality: { from: "0..*", to: "1" },
                    join: [{ from: er.entity.a.x, to: er.entity.b.x }],
                    binding: db.dbo.fk_one
                }
                """.trimIndent()
            val mapTtr =
                """
                model binding
                def er2db_relation item_in_category {
                    relation: er.entity.item_in_category,
                    fk: db.dbo.fk_two
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr, "/map.ttr" to mapTtr))
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 2
            dup[0].message shouldContain "item_in_category"
            dup[0].message shouldContain "inline on relation"
            dup[0].message shouldContain "explicit def er2db_*"
        }

        "relation-level inline mapping respects host file's ER namespace" {
            val erTtr =
                """
                model er schema foo
                def entity a {}
                def entity b {}
                def relation r {
                    from: er.foo.a, to: er.foo.b,
                    cardinality: { from: "0..*", to: "1" },
                    join: [{ from: er.foo.a.x, to: er.foo.b.x }],
                    binding: db.dbo.fk_a_b
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val rel =
                result.model.mappings
                    .filterIsInstance<Er2DbRelationMapping>()
                    .single()
            rel.mappingSource shouldBe MappingSource.Inline("relation")
            rel.relation.namespace shouldBe "foo"
            rel.relation.name shouldBe "r"
        }

        "no duplicate-mapping when only explicit defs are present (pre-v2.1 behaviour preserved)" {
            val mapTtr =
                """
                model binding
                def er2db_entity item { entity: er.entity.item, target: { table: db.dbo.item } }
                """.trimIndent()
            val result = loadInline(mapOf("/map.ttr" to mapTtr))
            result.errors.filter { it.message.contains("ttr/duplicate-mapping") } shouldHaveSize 0
        }
    })
