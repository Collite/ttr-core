// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.Er2DbRelationMapping
import org.tatrman.ttr.metadata.model.MappingSource
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * v2.1 — cross-loader round-trip against the canonical `samples/2.1/` corpus (a
 * small slice of the Hartland demo database). The same three files (`db.ttr`,
 * `er.ttr`, `map.ttr`) are checked in here under `resources/v2-1-samples/` and
 * loaded through the `FileBasedSource` → `ModelReconciler` pipeline.
 *
 * This locks the inline-mapping contract between the two repos: any drift in
 * the grammar / walker / synthesizer / validator that changes the synthesised
 * `Er2Db*Mapping` inventory will fail this spec before it lands.
 *
 * Source of truth: `samples/2.1/{db,er,map}.ttrm` (repo root). The copies here use
 * the older reference spelling (`db.dbo.<name>` without the `table.` / `fk.`
 * segment, `model er schema entity`); if the sample is intentionally changed,
 * re-copy it in that spelling and update the expected inventory below.
 */
class V21SamplesSpec :
    StringSpec({
        val samplesRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("v2-1-samples")).toURI())

        fun load(): org.tatrman.ttr.metadata.reconcile.ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "v2-1-samples",
                    priority = 100,
                    storage = LocalFsStorage(id = "v2-1-samples", rootPath = samplesRoot),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "v2-1-samples", name = "v2-1-samples"))
            return reconciler.reconcile(listOf(source.load()))
        }

        "samples/2.1 — all three files parse without errors" {
            val result = load()
            val parseErrors = result.errors.filter { it.message.contains("ttr/parse-error") }
            parseErrors shouldHaveSize 0
        }

        "samples/2.1 — canonical (non-broken) corpus emits zero ttr/duplicate-mapping" {
            val result = load()
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 0
        }

        "samples/2.1 — entity-level inline mapping (item) synthesises Er2DbEntityMapping + 5 columns" {
            val result = load()
            val entityMappings = result.model.mappings.filterIsInstance<Er2DbEntityMapping>()

            val item = entityMappings.single { it.qname.name == "item" }
            item.mappingSource shouldBe MappingSource.Inline("entity")

            // 5 inline-entity attribute mappings for item's columns map
            val itemAttrs =
                result.model.mappings
                    .filterIsInstance<Er2DbAttributeMapping>()
                    .filter { it.qname.name.startsWith("item.") }
            itemAttrs shouldHaveSize 5
            itemAttrs.all { it.mappingSource == MappingSource.Inline("entity") } shouldBe true
            itemAttrs.map { it.qname.name }.toSet() shouldBe
                setOf(
                    "item.sk",
                    "item.item_id",
                    "item.product_name",
                    "item.category_id",
                    "item.class_id",
                )
        }

        "samples/2.1 — attribute-level inline mapping (category) synthesises 3 Er2DbAttributeMapping records" {
            val result = load()
            val categoryAttrs =
                result.model.mappings
                    .filterIsInstance<Er2DbAttributeMapping>()
                    .filter { it.qname.name.startsWith("category.") }
            categoryAttrs shouldHaveSize 3
            categoryAttrs.all { it.mappingSource == MappingSource.Inline("attribute") } shouldBe true
            categoryAttrs.map { it.qname.name }.toSet() shouldBe
                setOf("category.sk", "category.category_code", "category.category_name")
        }

        "samples/2.1 — relation-level inline mappings (item_in_category + item_in_class) both Inline" {
            val result = load()
            val relMappings = result.model.mappings.filterIsInstance<Er2DbRelationMapping>()
            val inline = relMappings.filter { it.mappingSource is MappingSource.Inline }
            inline.map { it.qname.name }.toSet() shouldBe
                setOf("item_in_category", "item_in_class")
        }

        "samples/2.1 — explicit map.ttr contributes 4 explicit entity mappings + 3 explicit relation mappings" {
            val result = load()
            val explicitEntities =
                result.model.mappings
                    .filterIsInstance<Er2DbEntityMapping>()
                    .filter { it.mappingSource is MappingSource.Explicit }
            explicitEntities.map { it.qname.name }.toSet() shouldBe
                setOf("item_class", "market", "division", "store")

            val explicitRelations =
                result.model.mappings
                    .filterIsInstance<Er2DbRelationMapping>()
                    .filter { it.mappingSource is MappingSource.Explicit }
            explicitRelations.map { it.qname.name }.toSet() shouldBe
                setOf("class_in_category", "market_in_division", "store_in_market")
        }

        // MH — the authored `cardinality:` now reaches the model. It used to be dropped on the
        // floor (`Cardinality(0, -1, 0, -1)`, hardcoded in `Source.kt`), so every relation on
        // every loaded estate claimed both sides were optional. Nothing read the field until MH's
        // `reachedFrom.mandatory`, which is precisely the `to`-side lower bound. This corpus
        // happens to carry all four authored shapes, so it is where the parse is pinned.
        "samples/2.1 — a relation's authored cardinality reaches the model (MH)" {
            val relations =
                load()
                    .model.schemas.values
                    .filterIsInstance<org.tatrman.ttr.metadata.model.ErSchema>()
                    .flatMap { it.relations.values }
                    .associateBy { it.qname.name }

            // `to: "0..1"` — the optional side. This is the shape that makes two readings of one
            // word DIFFER, so reading it as mandatory would be the expensive mistake.
            relations.getValue("item_in_category").cardinality.toMin shouldBe 0
            relations.getValue("item_in_category").cardinality.toMax shouldBe 1
            // `to: "1"` — exactly one.
            relations.getValue("item_in_class").cardinality.toMin shouldBe 1
            relations.getValue("item_in_class").cardinality.toMax shouldBe 1
            // `from: "0..*"` — unbounded is -1, the value the hardcoded default used.
            relations.getValue("item_in_class").cardinality.fromMin shouldBe 0
            relations.getValue("item_in_class").cardinality.fromMax shouldBe -1
            // `from: "1..*"`.
            relations.getValue("market_in_division").cardinality.fromMin shouldBe 1
            relations.getValue("market_in_division").cardinality.fromMax shouldBe -1
            // `from: "*"` (bare many) and `to: "1..*"`.
            relations.getValue("store_in_market").cardinality.fromMin shouldBe 0
            relations.getValue("store_in_market").cardinality.fromMax shouldBe -1
            relations.getValue("store_in_market").cardinality.toMin shouldBe 1
            relations.getValue("store_in_market").cardinality.toMax shouldBe -1
        }

        "samples/2.1 — full mapping inventory: 5 entity + 15 attribute + 5 relation" {
            val result = load()
            result.model.mappings.filterIsInstance<Er2DbEntityMapping>() shouldHaveSize 5
            result.model.mappings.filterIsInstance<Er2DbAttributeMapping>() shouldHaveSize 15
            result.model.mappings.filterIsInstance<Er2DbRelationMapping>() shouldHaveSize 5
        }
    })
