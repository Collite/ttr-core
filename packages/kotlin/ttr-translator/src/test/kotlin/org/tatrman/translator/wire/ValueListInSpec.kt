// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.FixtureModel
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult

/**
 * AU-core-P2 — a value-list `in` on the wire (`state IN ('placed', 'held')`, what a TTR-SQL fragment's IN folds to)
 * decodes: Calcite has no RexCall(IN) — it asserted building one — and RelBuilder.in makes the SEARCH it does have.
 */
class ValueListInSpec :
    StringSpec({
        val translator = Translator(FixtureModel.tfHandle())

        fun col(name: String) = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

        fun text(v: String) =
            Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue(v).setType("text")).build()

        fun filtered(
            op: String,
            vararg operands: Expression,
        ): PlanNode {
            val scan =
                TableScanNode
                    .newBuilder()
                    .setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("A"),
                    ).addOutputColumns(ColumnRef.newBuilder().setName("ID"))
                    .addOutputColumns(ColumnRef.newBuilder().setName("NAME"))
            val call = FunctionCall.newBuilder().setOperation(op).addAllOperands(operands.toList())
            val predicate = Expression.newBuilder().setFunction(call).build()
            return PlanNode
                .newBuilder()
                .setFilter(
                    FilterNode
                        .newBuilder()
                        .setInput(PlanNode.newBuilder().setTableScan(scan))
                        .setCondition(predicate),
                ).build()
        }

        fun postgres(p: PlanNode): String =
            translator
                .unparseFromRelNode(p, Language.SQL, SqlDialect.POSTGRESQL)
                .shouldBeInstanceOf<UnparseResult.Success>()
                .output

        "`NAME in ('x', 'y')` unparses — a value list, no assertion" {
            val sql = postgres(filtered("in", col("NAME"), text("x"), text("y")))
            sql shouldContain "'x'"
            sql shouldContain "'y'"
            sql shouldNotContain "AssertionError"
        }

        "`not (NAME in ('x'))` keeps the negation" {
            val inner =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("in")
                            .addOperands(col("NAME"))
                            .addOperands(text("x")),
                    ).build()
            val sql = postgres(filtered("not", inner))
            sql shouldContain "'x'"
            (sql.contains("<>") || sql.contains("NOT")) shouldBe true
        }
    })
