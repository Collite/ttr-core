// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel

/**
 * NX-A.S2 — correlated `[NOT] EXISTS` sub-queries decorrelate before encode so they no longer
 * crash the encoder (ai-models#27), and the resulting plan round-trips SQL → plan → MSSQL.
 *
 * **What Calcite 1.41 actually produces (verified empirically, incl. `expand=true`):** correlated
 * `NOT EXISTS` lowers to `LEFT JOIN + Aggregate + IS NULL` (anti) and `EXISTS` to
 * `INNER JOIN + distinct Aggregate` (semi) — NOT a native `JoinRelType.SEMI/ANTI`. So the
 * NX-A.S1 `SEMI`/`ANTI` wire is not exercised by this path and the unparse is semantically
 * equivalent MSSQL, not a literal `[NOT] EXISTS`. This is the tracked NX-A.S2 deviation from the
 * plan's original "native semi/anti → renders NOT EXISTS" DoD. The concrete bug — the encoder
 * throwing `RexNode kind 'RexFieldAccess' is not in the v1 wire format` — is what is fixed here.
 *
 * The **hero fixture** is the reported shape (a document correlated with its history table on the header
 * key) expressed against the fixture model (`customers`/`orders` on `customer_id = id`).
 */
class SubqueryTranslationSpec :
    StringSpec({
        val translator = Translator(FixtureModel.handle())

        fun translateMssql(sql: String) =
            translator.translate(
                source = sql,
                sourceLanguage = Language.SQL,
                targetLanguage = Language.SQL,
                targetDialect = SqlDialect.MSSQL,
            )

        // All join types anywhere in the plan tree (relational nodes only).
        fun joinTypes(plan: PlanNode): List<JoinType> =
            buildList {
                fun rec(n: PlanNode) {
                    when (n.nodeCase) {
                        PlanNode.NodeCase.JOIN -> {
                            add(n.join.joinType)
                            rec(n.join.left)
                            rec(n.join.right)
                        }
                        PlanNode.NodeCase.PROJECT -> rec(n.project.input)
                        PlanNode.NodeCase.FILTER -> rec(n.filter.input)
                        PlanNode.NodeCase.AGGREGATE -> rec(n.aggregate.input)
                        PlanNode.NodeCase.SORT -> rec(n.sort.input)
                        PlanNode.NodeCase.LIMIT_OFFSET -> rec(n.limitOffset.input)
                        PlanNode.NodeCase.UNION -> n.union.inputsList.forEach { rec(it) }
                        else -> {}
                    }
                }
                rec(plan)
            }

        fun hasAggregate(plan: PlanNode): Boolean {
            var found = false

            fun rec(n: PlanNode) {
                when (n.nodeCase) {
                    PlanNode.NodeCase.AGGREGATE -> {
                        found = true
                        rec(n.aggregate.input)
                    }
                    PlanNode.NodeCase.PROJECT -> rec(n.project.input)
                    PlanNode.NodeCase.FILTER -> rec(n.filter.input)
                    PlanNode.NodeCase.JOIN -> {
                        rec(n.join.left)
                        rec(n.join.right)
                    }
                    PlanNode.NodeCase.SORT -> rec(n.sort.input)
                    PlanNode.NodeCase.LIMIT_OFFSET -> rec(n.limitOffset.input)
                    PlanNode.NodeCase.UNION -> n.union.inputsList.forEach { rec(it) }
                    else -> {}
                }
            }
            rec(plan)
            return found
        }

        // ---- S2.1 hero: the exact ai-models#27 shape (correlated NOT EXISTS) ----

        "hero: correlated NOT EXISTS translates SQL->plan->MSSQL without crashing (ai-models#27)" {
            // Was: encoder threw `RexNode kind 'RexFieldAccess' is not in the v1 wire format`.
            val r =
                translateMssql(
                    "SELECT z.id, z.name FROM customers z " +
                        "WHERE NOT EXISTS (SELECT 1 FROM orders td WHERE td.customer_id = z.id)",
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            // Anti-join lowering: a LEFT join, an aggregate, and no residual sub-query expression.
            joinTypes(r.plan) shouldContain JoinType.LEFT
            hasAggregate(r.plan).shouldBeTrue()
            r.plan.toString().contains("subquery") shouldBe false
            // MSSQL renders the classic anti pattern.
            r.output.shouldContainIgnoringCase("LEFT JOIN")
            r.output.shouldContainIgnoringCase("IS NULL")
        }

        // ---- S2.2 companion: correlated EXISTS (semi side) ----

        "correlated EXISTS translates to an inner-join + aggregate (semi) plan" {
            val r =
                translateMssql(
                    "SELECT z.id FROM customers z " +
                        "WHERE EXISTS (SELECT 1 FROM orders td WHERE td.customer_id = z.id)",
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            joinTypes(r.plan) shouldContain JoinType.INNER
            hasAggregate(r.plan).shouldBeTrue()
            r.plan.toString().contains("subquery") shouldBe false
            r.output.shouldContainIgnoringCase("INNER JOIN")
        }

        // ---- S2.2 regression guard: the correlation gate leaves uncorrelated sub-queries alone ----
        // These must keep their `SubqueryExpression` encoding (RoundTripSpec Q9/Q10 depend on it),
        // so the normalizer must NOT decorrelate them.

        "uncorrelated IN sub-query is preserved as a SubqueryExpression (gate stays off)" {
            val r = translateMssql("SELECT id FROM customers WHERE id IN (SELECT customer_id FROM orders)")
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.plan.toString().shouldContainIgnoringCase("subquery")
            joinTypes(r.plan).contains(JoinType.LEFT) shouldBe false
        }

        "scalar WHERE sub-query is preserved as a SubqueryExpression (gate stays off)" {
            val r =
                translateMssql(
                    "SELECT id FROM customers WHERE id = (SELECT customer_id FROM orders WHERE total > 100)",
                )
            r.shouldBeInstanceOf<TranslateResult.Success>()
            r.plan.toString().shouldContainIgnoringCase("subquery")
        }
    })
