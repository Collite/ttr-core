// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import io.kotest.core.spec.style.StringSpec
import org.tatrman.ttrp.graph.model.Filter
import org.tatrman.ttrp.diagnostics.Severity
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.emit.sql.EmitNode
import org.tatrman.ttrp.emit.sql.SqlGraphEmitter
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.NodeExpressions

/**
 * AU-core-P2 · S2.4e T1 (a) — ttr-server#434: a second `latest(by: d)` after a second join of the same entity reduces
 * by **its own** instance's `d` — the column that join brought (`as_of_date0`), not the first instance's
 * (`as_of_date`). hartland's `stock_at_center` (D-153: `latest` over the candidates' rows) is the shape.
 */
class LatestOwnDateSpec :
    StringSpec({
        /** The candidates and a `stock_at_center` context of [body]; one decision reading it per line. */
        fun recipe(body: String) =
            """
            uses world "hartland.worlds.demo"
            import hartland.*
            import hartland.automation.*
            recipe {
                key: return_request_line.line_id
                candidates received(out lines) target erp ${"\"\"\""}sql
                    SELECT l.line_id, l.item, l.quantity, l.requested_variant, r.customer
                    FROM   er.entity.return_request_line l JOIN er.entity.return_request r ON r.request_number = l.request_number
                    WHERE  l.state = 'received'
                ${"\"\"\""}
                context stock_at_center(in lines, out rows) target erp {
            $body
                }
                decision way(in lines, in stock_at_center) {
                    v = lines -> join(right: stock_at_center, kind: left, on: left.line_id = right.line_id)
                    v -> switch {
                        exchange: variant_on_hand >= quantity  because: "requested variant on stock"
                        else:     refund_after_return           because: "goods back, then the money"
                    }
                }
                actions {
                    way.exchange            -> set_state { line: line_id, to: awaiting_goods, way: exchange }
                    way.refund_after_return -> set_state { line: line_id, to: awaiting_goods, way: refund_after_return }
                }
            }
            """.trimIndent()

        val twice =
            """
                    stock = load(er.entity.inventory)
                    lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)
                          -> join(right: stock, kind: left, on: left.item = right.item and customer.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(item_on_hand = coalesce(stock.quantity_on_hand, 0))
                          -> join(right: stock, kind: left, on: left.requested_variant = right.item and customer.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(variant_on_hand = coalesce(stock.quantity_on_hand, 0))
                          -> select(line_id, item_on_hand, variant_on_hand)
                          -> rows
            """.trimEnd()

        /** [source] planned against the fixture model. */
        fun plan(source: String) = RecipeFixtures.plan(source, "latest_twice.ttrp")

        /** The `stock_at_center.rows` chain the SQL and Polars emitters render. */
        fun chain(source: String): List<EmitNode> {
            val plan = plan(source)
            plan.diagnostics.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            val graph = plan.graph!!
            val c = graph.containers.values.single { it.label == "stock_at_center" }
            return SqlGraphEmitter(graph, plan.bound!!, sqlText = true, fragmentsAsMembers = true, textKeys = true)
                .plansByOutput(c)
                .getValue("rows")
        }

        /**
         * Each `latest`'s reduction, in chain order: the date column its max reads, and the date column its filter keeps
         * at that max (`d = _ttrp_latest_… or _ttrp_latest_… is null`).
         */
        fun reductions(chain: List<EmitNode>): List<Pair<String, String>> =
            chain
                .filter { it.node.id.endsWith("~max") }
                .map { max ->
                    val id = max.node.id.removeSuffix("~max")
                    val agg = max.node as Aggregate
                    val m = agg.aggregations.single().name
                    val maxOf = ((agg.aggregations.single().value as AggregateCall).args.single() as ColumnRef).column
                    val keep = chain.single { it.node.id == id }.node as Filter
                    val kept =
                        NodeExpressions
                            .walk(keep.predicate!!)
                            .filterIsInstance<ColumnRef>()
                            .map { it.column }
                            .filter { it != m }
                            .distinct()
                            .single()
                    maxOf to kept
                }

        /** The first `latest` (hartland's), then [second] over `first`. */
        fun withFirst(second: String) =
            """
                    stock = load(er.entity.inventory)
                    first = lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)
                          -> join(right: stock, kind: left, on: left.item = right.item and customer.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(item_on_hand = coalesce(stock.quantity_on_hand, 0))
            """.trimEnd() + "\n" + second

        "a second latest over the same entity's date reduces by its own instance's alias (#434)" {
            reductions(chain(recipe(twice))) shouldContainExactly
                listOf("as_of_date" to "as_of_date", "as_of_date0" to "as_of_date0")
        }

        "the new instance on the LEFT of the second join: its date, not the first's on the right (review-352 R1)" {
            val swapped =
                withFirst(
                    """
                    stock -> join(right: first, kind: inner, on: left.item = right.requested_variant and left.warehouse = right.center)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(variant_on_hand = coalesce(quantity_on_hand, 0))
                          -> select(line_id, item_on_hand, variant_on_hand)
                          -> rows
                    """.trimEnd(),
                )
            // the left stock keeps `as_of_date`; `first`'s date, on the right, is `as_of_date0` (the PR's first cut read it)
            reductions(chain(recipe(swapped))) shouldContainExactly
                listOf("as_of_date" to "as_of_date", "as_of_date" to "as_of_date")
        }

        "a select that reorders the columns before the second join keeps each latest on its own date (review-352 R1)" {
            val reordered =
                withFirst(
                    """
                    first -> select(as_of_date, quantity_on_hand, item_on_hand, center, requested_variant, quantity, line_id)
                          -> join(right: stock, kind: left, on: left.requested_variant = right.item and left.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(variant_on_hand = coalesce(stock.quantity_on_hand, 0))
                          -> select(line_id, item_on_hand, variant_on_hand)
                          -> rows
                    """.trimEnd(),
                )
            reductions(chain(recipe(reordered))) shouldContainExactly
                listOf("as_of_date" to "as_of_date", "as_of_date0" to "as_of_date0")
        }

        "the checker types the date the SQL reduces by: a newer non-date of the name is refused (review-352 R1)" {
            val newerText =
                withFirst(
                    """
                    texty = load(er.entity.inventory) -> calc(as_of_date = 'x')
                    first -> join(right: texty, kind: left, on: left.requested_variant = right.item and left.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> select(line_id, item_on_hand)
                          -> rows
                    """.trimEnd(),
                )
            plan(recipe(newerText))
                .diagnostics
                .filter { it.severity == Severity.ERROR }
                .map { it.message }
                .single() shouldContain "`latest(by: as_of_date)` orders by a date"
        }

        "the checker types the date the SQL reduces by: an older non-date of the name does not matter (review-352 R1)" {
            val olderText =
                """
                    stock = load(er.entity.inventory)
                    lines -> calc(as_of_date = 'x')
                          -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)
                          -> join(right: stock, kind: left, on: left.item = right.item and customer.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(item_on_hand = coalesce(stock.quantity_on_hand, 0))
                          -> select(line_id, item_on_hand)
                          -> rows
                """.trimEnd()
            reductions(chain(recipe(olderText))) shouldContainExactly listOf("as_of_date0" to "as_of_date0")
        }

        "a single latest still reduces by the one date it reads (regression)" {
            val once =
                """
                    stock = load(er.entity.inventory)
                    lines -> join(right: load(er.entity.customer), kind: inner, on: relation rel_line_customer)
                          -> join(right: stock, kind: left, on: left.requested_variant = right.item and customer.center = right.warehouse)
                          -> latest(by: as_of_date, per: line_id)
                          -> calc(variant_on_hand = coalesce(stock.quantity_on_hand, 0))
                          -> select(line_id, variant_on_hand)
                          -> rows
                """.trimEnd()
            reductions(chain(recipe(once))) shouldContainExactly listOf("as_of_date" to "as_of_date")
        }

        "the date is compared as itself, never cast to text (review-352 R2)" {
            val sql =
                RecipeStatements
                    .plan(plan(recipe(twice)))
                    .single { it.island == "stock_at_center" }
                    .sql
            sql shouldNotContain "CAST(\"as_of_date"
            sql shouldNotContain "_ttrp_d_"
        }
    })
