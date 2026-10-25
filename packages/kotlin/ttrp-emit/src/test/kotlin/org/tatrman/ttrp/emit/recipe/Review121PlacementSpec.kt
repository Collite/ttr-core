// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttrp.resolve.LoadSourceResolver
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.ModelRepo
import org.tatrman.ttrp.resolve.RecipePlacement

class Review121PlacementSpec :
    StringSpec({
        val original = ModelRepo.indexOf(RecipeFixtures.root.resolve("model"))!!
        val entity =
            original.objectsByQname.values.filterIsInstance<Entity>().first {
                it.qname.name ==
                    "return_request_line"
            }
        val world = RecipeFixtures.sharedRecipe("monitor_inbound.ttrp").bound!!.world
        val mapping =
            original.snapshot.model.mappings.filterIsInstance<Er2DbEntityMapping>().first {
                it.entity ==
                    entity.qname
            }

        "query-bound entities use the query's package even when its qname uses a schema instead of query" {
            val target = QualifiedName(SchemaCode.DB, "query", "line_filter")
            val query =
                Query(
                    "filtered-lines",
                    target.copy(namespace = "dbo"),
                    sourceFile = "/unrelated/model.ttrm",
                    sourceLanguage = "sql",
                    sourceText = "SELECT * FROM return_request_line",
                )
            val model =
                original.snapshot.model.copy(
                    mappings =
                        original.snapshot.model.mappings.map {
                            if (it == mapping) mapping.copy(target = MappingTarget.SqlQuery(target)) else it
                        },
                    queries = original.snapshot.model.queries + (query.qname to query),
                )
            val index =
                ModelIndex(original.snapshot.copy(model = model, graph = ModelGraph.build(model))) {
                    if (it is Query) "physical.db" else "logical.er"
                }
            val source = LoadSourceResolver.resolve(entity, index)!!
            source.sql shouldBe query.sourceText
            val erp = world.storages.first { it.qname.name == "erp_db" }.copy(hosts = listOf("physical.db"))
            RecipePlacement.storages(source, world.copy(storages = listOf(erp)), index) shouldBe listOf("erp_db")
        }

        "a writable match never repairs a dangling or unsupported binding" {
            val missing = QualifiedName(SchemaCode.DB, "dbo", "deleted_table")
            for (target in listOf(missing, entity.qname)) {
                val model =
                    original.snapshot.model.copy(
                        mappings =
                            original.snapshot.model.mappings.map {
                                if (it == mapping) mapping.copy(target = MappingTarget.Table(target)) else it
                            },
                    )
                val index =
                    ModelIndex(
                        original.snapshot.copy(model = model, graph = ModelGraph.build(model)),
                        original::packageOf,
                    )
                LoadSourceResolver.resolve(entity, index, allowUnbound = true) shouldBe null
            }
        }
    })
