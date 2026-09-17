// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.tatrman.plan.v1.SchemaCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.calcite.tools.Planner
import org.tatrman.translator.functions.FunctionCatalog

class TranslatorFrameworkSpec :
    StringSpec({

        "newPlanner returns a fresh Planner each call" {
            val fw = TranslatorFramework(FixtureModel.handle())
            val a: Planner = fw.newPlanner()
            val b: Planner = fw.newPlanner()
            (a === b) shouldBe false
        }

        "Calcite enforces single-use Planner: re-parsing throws" {
            val fw = TranslatorFramework(FixtureModel.handle())
            val planner = fw.newPlanner()
            planner.parse("SELECT id FROM customers")
            shouldThrow<Throwable> {
                planner.parse("SELECT name FROM customers")
            }
        }

        "schemaPlusAdapter.db has dbo sub-schema in three-level tree" {
            val fw = TranslatorFramework(FixtureModel.handle())
            fw.schemaPlusAdapter.db
                .getSubSchemaMap()
                .keys
                .shouldContain("dbo")
        }

        "default schema/namespace land on db.dbo" {
            val fw = TranslatorFramework(FixtureModel.handle())
            fw.schemaCode shouldBe SchemaCode.DB
            fw.namespace shouldBe "dbo"
        }

        "rootSchema registers the adapter under schemaCode" {
            val fw = TranslatorFramework(FixtureModel.handle())
            fw.rootSchema.subSchemas().get("db") shouldNotBe null
        }

        // ---- TF-P5 (contracts §3.6) ----

        "a model without functions keeps the shared default catalog" {
            (TranslatorFramework(FixtureModel.handle()).functionCatalog === FunctionCatalog.DEFAULT) shouldBe true
        }

        "the framework catalog decodes a declared function by its qualified wire name, and the library ones still" {
            val catalog = TranslatorFramework(FixtureModel.handleWithFunctions()).functionCatalog
            catalog.lookup("dbo.fn_price")?.name shouldBe "fn_price"
            catalog.lookup("DBO.FN_TODAY")?.name shouldBe "fn_today"
            catalog.lookup("fn_price") shouldBe null
            catalog.lookup("concat") shouldNotBe null
        }

        "an ER framework resolves the DB functions too (they are called from entity-level SQL)" {
            val catalog =
                TranslatorFramework(
                    FixtureModel.handleWithEntitiesAndFunctions(),
                    SchemaCode.ER,
                    "entity",
                ).functionCatalog
            catalog.lookup("dbo.fn_price") shouldNotBe null
        }
    })
