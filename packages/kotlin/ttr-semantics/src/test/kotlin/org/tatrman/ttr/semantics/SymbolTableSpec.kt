// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Mirrors `packages/semantics/src/__tests__/symbol-table.test.ts`. The Kotlin
 * `SymbolTable` is the project-level table (TS `ProjectSymbolTable`): it builds
 * qnames from each document's schema/namespace/package the same way TS
 * `DocumentSymbolTable.makeQname` does.
 */
class SymbolTableSpec :
    StringSpec({
        "entity + 3 attributes register as 4 entries with qualified qname" {
            val t =
                Fixtures.symbolTable(
                    "file:///test.ttr" to
                        """
                        model er schema myns
                        def entity Order {
                          attributes: [
                            def attribute id { type: integer },
                            def attribute customer_id { type: integer },
                            def attribute total_amount { type: decimal }
                          ]
                        }
                        """.trimIndent(),
                )
            t.all() shouldHaveSize 4
            val order = t.get("er.entity.Order")
            order.shouldNotBeNull()
            order.name shouldBe "Order"
            order.kind shouldBe "entity"
            t.all().count { it.parent?.contains("Order") == true } shouldBe 3
        }

        "SymbolEntry.schema carries the file namespace ('' when none declared)" {
            val withNs =
                Fixtures.symbolTable(
                    "file:///test.ttr" to
                        """
                        model er schema entity
                        def entity Order { attributes: [ def attribute id { type: integer } ] }
                        """.trimIndent(),
                )
            val order = withNs.get("er.entity.Order")
            order.shouldNotBeNull()
            order.namespace shouldBe "entity"
            // nested children share the file namespace verbatim
            withNs.get("er.entity.Order.id")!!.namespace shouldBe "entity"

            val noNs =
                Fixtures.symbolTable(
                    "file:///t2.ttr" to
                        "model db\ndef table orders { columns: [ def column id { type: integer } ] }",
                )
            // qname uses def.kind in the ns slot, but SymbolEntry.namespace is ""
            val orders = noNs.get("db.dbo.table.orders")
            orders.shouldNotBeNull()
            orders.namespace shouldBe ""
            noNs.get("db.dbo.table.orders.id")!!.namespace shouldBe ""
        }

        "table + columns register as 3 entries" {
            val t =
                Fixtures.symbolTable(
                    "file:///test.ttr" to
                        """
                        model db schema dbo
                        def table orders {
                          columns: [
                            def column id { type: integer },
                            def column created_at { type: timestamp }
                          ]
                        }
                        """.trimIndent(),
                )
            t.all() shouldHaveSize 3
            t.get("db.dbo.table.orders").shouldNotBeNull()
        }

        "empty document yields no entries" {
            val t = Fixtures.symbolTable("file:///test.ttr" to "model db\nmodel test {}")
            t.all() shouldHaveSize 0
        }

        "duplicates detects the same qname across two documents" {
            val src =
                """
                model db
                def table users { columns: [ def column id { type: integer } ] }
                """.trimIndent()
            val t = Fixtures.symbolTable("file:///file1.ttr" to src, "file:///file2.ttr" to src)
            t.duplicates().any { it.qname == "db.dbo.table.users" } shouldBe true
        }

        "removeDocument removes all entries for a URI" {
            val t = SymbolTable()
            Fixtures.upsert(
                t,
                "file:///test.ttr",
                "model db\ndef table users { columns: [ def column id { type: integer } ] }",
            )
            t.all() shouldHaveSize 2
            t.removeDocument("file:///test.ttr")
            t.all() shouldHaveSize 0
        }

        "findByName returns entries across documents" {
            val t =
                Fixtures.symbolTable(
                    "file:///f1.ttr" to "model db\ndef table users { columns: [ def column id { type: integer } ] }",
                    "file:///f2.ttr" to
                        "model er\ndef entity users { attributes: [ def attribute id { type: integer } ] }",
                )
            t.findByName("users").size shouldBe 2
        }

        "stock cnc symbols get the doubled cnc.role.* qname" {
            val t = SymbolTable()
            Fixtures.upsert(
                t,
                "stock://cnc-roles.ttr",
                "model cnc schema role\ndef role fact { description: \"Fact role\" }",
            )
            val fact = t.get("cnc.role.fact")
            fact.shouldNotBeNull()
            fact.documentUri shouldBe "stock://cnc-roles.ttr"
        }

        "getByPackage returns only that package's entries" {
            val t =
                Fixtures.symbolTable(
                    "billing/a.ttr" to
                        "package billing\nmodel er schema entity\ndef entity artikl { attributes: [] }",
                    "other/b.ttr" to
                        "package other\nmodel er schema entity\ndef entity produkt { attributes: [] }",
                )
            t.getByPackage("billing").all { it.packageName == "billing" } shouldBe true
            t.getByPackage("billing").any { it.name == "artikl" } shouldBe true
            t.getByPackage("billing").any { it.name == "produkt" } shouldBe false
        }

        "the declared package prefixes the qname (caller must pass ParseResult.packageName)" {
            // Locks the upsertDocument packageName contract: the declared `package`
            // flows into the qname prefix. A caller passing a different value here
            // (e.g. inferred-from-path) would diverge from TS.
            val t =
                Fixtures.symbolTable(
                    "billing/a.ttr" to
                        "package billing\nmodel er schema entity\ndef entity artikl { attributes: [ def attribute id { type: int } ] }",
                )
            t.get("billing.er.entity.artikl").shouldNotBeNull()
            t.get("billing.er.entity.artikl.id").shouldNotBeNull() // child carries the prefix too
            t.get("er.entity.artikl") shouldBe null // un-prefixed form must NOT exist
        }

        "getBySuffix matches qnames ending in the suffix" {
            val t =
                Fixtures.symbolTable(
                    "db.ttr" to
                        "model db schema dbo\ndef table CUSTOMER { columns: [ def column C_CUSTOMER_SK { type: int } ] }",
                )
            t.getBySuffix("CUSTOMER").any { it.qname == "db.dbo.table.CUSTOMER" } shouldBe true
        }
    })
