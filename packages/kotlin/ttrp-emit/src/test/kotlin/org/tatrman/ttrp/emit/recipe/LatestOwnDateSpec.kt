// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.recipe

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.emit.sql.EmitNode
import org.tatrman.ttrp.emit.sql.SqlGraphEmitter
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.graph.model.Aggregate
import org.tatrman.ttrp.graph.model.Join
import org.tatrman.ttrp.graph.model.JoinType
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

        /** The `stock_at_center.rows` chain the SQL and Polars emitters render. */
        fun chain(source: String): List<EmitNode> {
            val plan = RecipeFixtures.plan(source, "latest_twice.ttrp")
            plan.ok shouldBe true
            val graph = plan.graph!!
            val c = graph.containers.values.single { it.label == "stock_at_center" }
            return SqlGraphEmitter(graph, plan.bound!!, sqlText = true, fragmentsAsMembers = true, textKeys = true)
                .plansByOutput(c)
                .getValue("rows")
        }

        /**
         * Each `latest`'s reduction: the date column its max reads, and the date column its row's key is made of — the
         * key the semi join meets the max's key on (`left._ttrp_d_… = right._ttrp_dm_…`).
         */
        fun reductions(chain: List<EmitNode>): List<Pair<String, String>> =
            chain
                .filter { (it.node as? Join)?.type == JoinType.SEMI }
                .map { semi ->
                    val id = semi.node.id
                    val agg = chain.single { it.node.id == "$id~max" }.node as Aggregate
                    val maxOf = ((agg.aggregations.single().value as AggregateCall).args.single() as ColumnRef).column
                    val keyed =
                        NodeExpressions
                            .of(chain.single { it.node.id == "$id~group" }.node)
                            .flatMap { NodeExpressions.walk(it) }
                            .filterIsInstance<ColumnRef>()
                            .map { it.column }
                            .filter { it.startsWith("as_of_date") }
                            .distinct()
                            .single()
                    val on = NodeExpressions.walk((semi.node as Join).on!!).filterIsInstance<ColumnRef>()
                    on.map { "${it.port}.${it.column.substringBeforeLast('_')}" } shouldContain
                        "right._ttrp_dm"
                    maxOf to keyed
                }

        "a second latest over the same entity's date reduces by its own instance's alias (#434)" {
            reductions(chain(recipe(twice))) shouldContainExactly
                listOf("as_of_date" to "as_of_date", "as_of_date0" to "as_of_date0")
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
    })
