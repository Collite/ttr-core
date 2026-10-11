// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.params

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexDynamicParam
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.translator.codec.sql.SqlValidator
import org.tatrman.translator.codec.sql.ValidateResult
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.framework.TranslatorFramework

class ParameterTyperSpec :
    StringSpec({

        fun preparedFor(
            sql: String,
            params: List<SqlParam>,
        ): Pair<String, PreparedSql> {
            val prepared = ParameterBridge.prepareSqlForCalcite(sql, params)
            return prepared.sql to prepared
        }

        fun parse(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        /** Collect every [RexDynamicParam] in the tree, breadth-first across rels, sub-query bodies included. */
        fun collectParams(rel: org.apache.calcite.rel.RelNode): List<RexDynamicParam> {
            val out = mutableListOf<RexDynamicParam>()

            // Walk each rel in the tree; RelNode.accept(RexShuttle) only visits the rel's own
            // exprs, so descend manually — into inputs and into every sub-query's body.
            fun visit(n: org.apache.calcite.rel.RelNode) {
                n.accept(
                    object : RexShuttle() {
                        override fun visitDynamicParam(d: RexDynamicParam): RexNode {
                            out.add(d)
                            return d
                        }

                        override fun visitSubQuery(subQuery: RexSubQuery): RexNode {
                            visit(subQuery.rel)
                            return super.visitSubQuery(subQuery)
                        }
                    },
                )
                n.inputs.forEach(::visit)
            }
            visit(rel)
            return out
        }

        "applyTypes re-types a RexDynamicParam to the declared type (BIGINT)" {
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT id FROM customers WHERE id = {cid}",
                    listOf(SqlParam("cid", "int", 7)),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val params = collectParams(rewritten)
            params.size shouldBe 1
            params[0].index shouldBe 0
            params[0].type.sqlTypeName shouldBe SqlTypeName.BIGINT
        }

        "applyTypes is a no-op when the SqlParam list is empty" {
            val rel = parse("SELECT id FROM customers WHERE id = 5")
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten =
                ParameterTyper.applyTypes(
                    rel = rel,
                    parameterOrder = emptyList(),
                    valuesByName = emptyMap(),
                    typeFactory = typeFactory,
                )
            rewritten shouldBe rel // identity when no params
        }

        "applyTypes re-types a string parameter to VARCHAR" {
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT id FROM customers WHERE name = {n}",
                    listOf(SqlParam("n", "text", "Alice")),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val params = collectParams(rewritten)
            params.size shouldBe 1
            params[0].type.sqlTypeName shouldBe SqlTypeName.VARCHAR
        }

        "applyTypes leaves params alone when the index isn't in parameterOrder" {
            // A prepared SQL with one param but the rel has two ? — the second isn't covered;
            // it should keep whatever type Calcite inferred (not be rewritten or crash).
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT id FROM customers WHERE id = {a}",
                    listOf(SqlParam("a", "int", 1)),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            // Shrink parameterOrder to empty: applyTypes returns the rel verbatim by contract.
            val rewritten =
                ParameterTyper.applyTypes(
                    rel = rel,
                    parameterOrder = emptyList(),
                    valuesByName = prepared.values,
                    typeFactory = typeFactory,
                )
            rewritten shouldBe rel
        }

        // A sub-query's body is a RelNode of its own: a `?` in it is retyped and cast-unwrapped like any other.
        "applyTypes re-types a parameter inside an IN sub-query's body" {
            // The validator types the `?` from `total` (DECIMAL); the declared `int` must win (BIGINT).
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT name FROM customers WHERE id IN (SELECT customer_id FROM orders WHERE total > {t})",
                    listOf(SqlParam("t", "int", 7)),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val params = collectParams(rewritten)
            params.size shouldBe 1
            params[0].type.sqlTypeName shouldBe SqlTypeName.BIGINT
        }

        "applyTypes unwraps the typed CAST(? AS T) inside an IN sub-query's body" {
            val prepared =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE name LIKE {q} || '%')",
                    listOf(SqlParam("q", "text", "able")),
                    typed = true,
                )
            val rel = parse(prepared.sql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val params = collectParams(rewritten)
            params.size shouldBe 1
            params[0].type.sqlTypeName shouldBe SqlTypeName.VARCHAR
            castsOverParams(rewritten) shouldBe 0
        }
    })

/** How many `CAST(? AS T)` calls in [rel] (sub-query bodies included) have a bare `?` as their operand. */
private fun castsOverParams(rel: org.apache.calcite.rel.RelNode): Int {
    var count = 0

    fun visit(n: org.apache.calcite.rel.RelNode) {
        n.accept(
            object : RexShuttle() {
                override fun visitCall(call: RexCall): RexNode {
                    if (call.kind == SqlKind.CAST && call.operands.singleOrNull() is RexDynamicParam) count++
                    return super.visitCall(call)
                }

                override fun visitSubQuery(subQuery: RexSubQuery): RexNode {
                    visit(subQuery.rel)
                    return super.visitSubQuery(subQuery)
                }
            },
        )
        n.inputs.forEach(::visit)
    }
    visit(rel)
    return count
}
