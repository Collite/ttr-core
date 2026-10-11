// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.export

import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.Relation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.RelationDef
import java.time.Instant

/**
 * Stage 2 (yaml-converter-inline-split) — `ModelToDefinitions.convert` attaches
 * er2db mappings **inline** on the owning entity / attribute / relation def
 * instead of emitting standalone `def er2db_*` blocks. `er2cnc_role` stays
 * standalone (covered elsewhere).
 */
class InlineMappingExportSpec :
    StringSpec({

        fun qn(
            schemaCode: String,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName(
                schemaCode = parseSchemaCode(schemaCode) ?: SchemaCode.UNSPECIFIED,
                namespace = namespace,
                name = name,
            )

        // ----- shared fixture: item (entity-level block) + category (per-attribute) + relation -----

        val itemQn = qn("er", "entity", "item")
        val categoryQn = qn("er", "entity", "category")
        val relQn = qn("er", "relation", "item_in_category")
        val fkQn = qn("db", "dbo", "fk_item_category")

        fun model(): Model {
            val idAttrQn = qn("er", "entity", "item.sk")
            val kodAttrQn = qn("er", "entity", "item.item_id")
            val tableQn = qn("db", "dbo", "item")
            val idColQn = qn("db", "dbo", "item.i_item_sk")

            val idAttr = Attribute(internalId = "a1", qname = idAttrQn, entity = itemQn, type = "int", isKey = true)
            val kodAttr = Attribute(internalId = "a2", qname = kodAttrQn, entity = itemQn, type = "text")
            val item = Entity(internalId = "e1", qname = itemQn, attributes = listOf(idAttr, kodAttr))

            // category: NO entity-level mapping ⇒ its attribute carries the inline mapping itself.
            val pidAttrQn = qn("er", "entity", "category.sk")
            val pidColQn = qn("db", "dbo", "item_category.i_category_id")
            val pidAttr =
                Attribute(internalId = "a3", qname = pidAttrQn, entity = categoryQn, type = "int", isKey = true)
            val category = Entity(internalId = "e2", qname = categoryQn, attributes = listOf(pidAttr))

            val rel =
                Relation(
                    internalId = "r1",
                    qname = relQn,
                    fromEntity = itemQn,
                    toEntity = categoryQn,
                    cardinality = Cardinality(0, -1, 0, 1),
                )

            val entityMapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "item"),
                    entity = itemQn,
                    target = MappingTarget.Table(tableQn),
                )
            // plain column ⇒ short bare-id in the columns map
            val idAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m2",
                    qname = qn("map", "er2db_attribute", "item.sk"),
                    attribute = idAttrQn,
                    target = AttributeMappingTarget.Column(idColQn),
                )
            // expression ⇒ object/block form in the columns map
            val kodAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m3",
                    qname = qn("map", "er2db_attribute", "item.item_id"),
                    attribute = kodAttrQn,
                    target = AttributeMappingTarget.Expression("UPPER(i_item_id)"),
                )
            val pidAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m4",
                    qname = qn("map", "er2db_attribute", "category.sk"),
                    attribute = pidAttrQn,
                    target = AttributeMappingTarget.Column(pidColQn),
                )
            val relMapping =
                Er2DbRelationMapping(
                    internalId = "m5",
                    qname = qn("map", "er2db_relation", "item_in_category"),
                    relation = relQn,
                    foreignKey = fkQn,
                )

            return Model(
                descriptor = ModelDescriptor(id = "t", name = "T", description = ""),
                version = ModelVersion(value = "v", swappedAt = Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to
                            ErSchema(
                                entities = mapOf(itemQn to item, categoryQn to category),
                                relations = mapOf(relQn to rel),
                            ),
                    ),
                mappings = listOf(entityMapping, idAttrMapping, kodAttrMapping, pidAttrMapping, relMapping),
                queries = emptyMap(),
            )
        }

        // ----- 2.1 — mappings are inline; no standalone er2db defs; no map.ttr -----

        "2.1 no standalone er2db_* defs are emitted" {
            val bundle = ModelToDefinitions.convert(model())
            val defs = bundle.files.flatMap { it.definitions }
            defs.none { it is Er2DbEntityDef } shouldBe true
            defs.none { it is Er2DbAttributeDef } shouldBe true
            defs.none { it is Er2DbRelationDef } shouldBe true
            // With only er2db mappings (no er2cnc), the `map` bucket is now empty.
            bundle.files.none { it.filename == "map.ttr" } shouldBe true
        }

        "2.1 entity/attribute/relation defs carry the inline mapping" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val item = defs.filterIsInstance<EntityDef>().first { it.name == "item" }
            item.binding.shouldBeInstanceOf<BindingPropertyBlock>()
            val rel = defs.filterIsInstance<RelationDef>().first { it.name == "item_in_category" }
            rel.binding.shouldBeInstanceOf<BindingPropertyBareId>()
        }

        // ----- 2.2 — short vs block selection -----

        "2.2 entity-level block carries only target, no columns map" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val item = defs.filterIsInstance<EntityDef>().first { it.name == "item" }
            val block = item.binding as BindingPropertyBlock
            block.columns.shouldBeEmpty()
        }

        "2.2 per-attribute mapping even under an entity-level block: plain ⇒ bare-id, expression ⇒ block" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val item = defs.filterIsInstance<EntityDef>().first { it.name == "item" }
            val byName = item.attributes.associateBy { it.name }
            // plain column ⇒ short bare-id
            byName.getValue("sk").binding.shouldBeInstanceOf<BindingPropertyBareId>()
            (byName.getValue("sk").binding as BindingPropertyBareId).id.path shouldBe "i_item_sk"
            // expression ⇒ block form
            byName.getValue("item_id").binding.shouldBeInstanceOf<BindingPropertyBlock>()
        }

        "2.2 no entity-level block ⇒ per-attribute short mapping (category case)" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val category = defs.filterIsInstance<EntityDef>().first { it.name == "category" }
            category.binding.shouldBeNull()
            val pid = category.attributes.first { it.name == "sk" }
            pid.binding.shouldBeInstanceOf<BindingPropertyBareId>()
            (pid.binding as BindingPropertyBareId).id.path shouldBe "i_category_id"
        }

        "2.2 relation ⇒ bare-id fk reference (fully-qualified)" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val rel = defs.filterIsInstance<RelationDef>().first { it.name == "item_in_category" }
            (rel.binding as BindingPropertyBareId).id.path shouldBe "db.dbo.fk_item_category"
        }
    })
