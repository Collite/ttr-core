// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * AU-core-P1 · S1.2 — key lineage (06 A-5 + D-103): one tiny recipe per row of the op table, a context classified,
 * and the `17` traces reproduced exactly.
 */
class KeyLineageSpec :
    StringSpec({
        /** The class of a context `c(<ports>)` with [body] (canonical, unless [form] says otherwise). */
        fun classOf(
            body: String,
            ports: String = "in orders, out rows",
            form: String = "target erp",
        ): ContextInfo {
            val r =
                RecipeFixtures.plan(
                    """
                    uses world "hartland.worlds.demo"
                    import hartland.*
                    import hartland.automation.*
                    recipe {
                        key: order.order_number
                        candidates placed(out orders) target erp { load(er.entity.order) -> orders }
                        context c($ports) $form ${if (body.isEmpty()) "" else "{\n$body\n}"}
                        decision v(in orders) { orders -> switch { go: value > 0  because: "x" } }
                        actions { v.go -> release { order: order_number } }
                    }
                    """.trimIndent(),
                    "lineage.ttrp",
                )
            return r.recipe!!.contexts.single { it.name == "c" }
        }

        val keyed = ContextClass.PER_CANDIDATE
        val set = ContextClass.SET_LEVEL

        "filter / calc / sort keep the key" {
            classOf("orders -> filter(value > 0) -> sort(value) -> rows").contextClass shouldBe keyed
            classOf("orders -> calc(double = value * 2) -> rows").contextClass shouldBe keyed
        }

        "a limit over the candidates loses the key (D-133)" {
            val c = classOf("orders -> filter(value > 0) -> sort(value) -> limit(10) -> rows")
            c.contextClass shouldBe set
            c.lostKeyAt!! shouldContain "`limit(10)` keeps the first rows of all the candidates taken together"
        }

        "a calc that overwrites the key column loses it" {
            val c = classOf("orders -> calc(order_number = customer) -> rows")
            c.contextClass shouldBe set
            c.lostKeyAt!! shouldContain "calc overwrites the key column"
        }

        "select keeps the key only when it keeps the key column" {
            classOf("orders -> select(order_number, value) -> rows").contextClass shouldBe keyed
            val lost = classOf("orders -> select(customer, value) -> rows")
            lost.contextClass shouldBe set
            lost.lostKeyAt!! shouldContain "`select` selects without the key"
        }

        "an inner join with one keyed side is keyed" {
            classOf(
                "orders -> join(right: load(er.entity.invoice), kind: inner, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe keyed
        }

        "a left join keyed on the right is not keyed — unless the left side equates to the key (D-103)" {
            classOf(
                "load(er.entity.invoice) -> join(right: orders, kind: left, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe set
            classOf(
                "load(er.entity.invoice) -> join(right: orders, kind: left, on: left.order_number = right.order_number) -> rows",
            ).contextClass shouldBe keyed
        }

        "a semi join keyed on the right: keyed when it equates to the key, or by a relation from the key's entity" {
            classOf(
                "load(er.entity.sales_line) -> join(right: orders, kind: semi, on: left.order_number = right.order_number) -> rows",
            ).contextClass shouldBe keyed
            classOf(
                "load(er.entity.sales_line) -> join(right: orders, kind: semi, on: relation rel_order_line) -> rows",
            ).contextClass shouldBe keyed
            // the customer side of a many-to-one relation: a customer row can match several candidates
            classOf(
                "load(er.entity.invoice) -> join(right: orders, kind: semi, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe set
        }

        "both sides keyed: on the key the same candidate, otherwise one candidate meets another" {
            classOf(
                """
                a = orders -> filter(value > 0)
                orders -> join(right: a, kind: inner, on: left.order_number = right.order_number) -> rows
                """,
            ).contextClass shouldBe keyed
            classOf(
                """
                a = orders -> filter(value > 0)
                orders -> join(right: a, kind: inner, on: left.customer = right.customer) -> rows
                """,
            ).contextClass shouldBe set
        }

        "full and cross joins are not keyed" {
            classOf(
                "orders -> join(right: load(er.entity.invoice), kind: full, on: left.customer = right.customer) -> rows",
            ).contextClass shouldBe set
        }

        "aggregate: keyed iff the key is in group_by" {
            classOf("orders -> aggregate { group_by: order_number  n = count(customer) } -> rows").contextClass shouldBe
                keyed
            val lost = classOf("orders -> aggregate { group_by: customer  n = count(order_number) } -> rows")
            lost.contextClass shouldBe set
            lost.lostKeyAt!! shouldContain "aggregates the candidates without the key"
        }

        "union: keyed iff every input is keyed" {
            classOf(
                """
                a = orders -> filter(value > 100)
                b = orders -> filter(value < 10)
                union(a, b) -> rows
                """,
            ).contextClass shouldBe keyed
            classOf(
                """
                a = orders -> filter(value > 100)
                b = orders -> select(customer)
                union(a, b) -> rows
                """,
            ).contextClass shouldBe set
        }

        "a context that reads no candidates port is a lookup" {
            classOf("load(er.entity.price_list) -> rows", ports = "out rows").contextClass shouldBe ContextClass.LOOKUP
        }

        "a foreign island: per candidate with the key in its schema is keyed; for the set it is not" {
            classOf("", form = "foreign python per candidate schema care_answer").contextClass shouldBe keyed
            classOf("", form = "foreign python set schema care_answer").contextClass shouldBe set
        }

        "the `17` traces" {
            fun classes(f: String) =
                RecipeFixtures.sharedRecipe(f).recipe!!.contexts.associate {
                    it.name to
                        it.contextClass
                }
            classes("release_order.ttrp") shouldBe
                mapOf("shortfall" to keyed, "price_differences" to keyed, "overdue" to keyed)
            classes("decide_return.ttrp") shouldBe
                mapOf(
                    "bought_here" to keyed,
                    "return_share" to keyed,
                    "request_value" to ContextClass.LOOKUP,
                    "stock_at_center" to keyed,
                )
            classes("monitor_inbound.ttrp") shouldBe mapOf("receipts" to keyed, "age" to keyed, "reminded" to keyed)
            classes("settle.ttrp") shouldBe
                mapOf("open_obligations" to keyed, "documents" to keyed, "overdue_days" to keyed)
        }
    })
