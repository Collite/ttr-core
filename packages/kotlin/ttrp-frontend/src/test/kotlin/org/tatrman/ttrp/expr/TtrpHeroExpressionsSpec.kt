// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.TtrpFrontend
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.parser.Fixtures
import org.tatrman.ttrp.parser.TtrpParser

/**
 * Component test (parser → IR → catalogue → typechecker, no resolution): every hero
 * expression typechecks against hand-declared schemas; `sum(amount)`/`avg(amount)`
 * are [AggregateCall] arms inside the aggregate config block and NOWHERE else; the
 * branch predicate types `bool`; the wired [TtrpFrontend].check is diagnostic-free.
 */
class TtrpHeroExpressionsSpec :
    StringSpec({
        val typechecker = ExpressionTypechecker()

        val accounts =
            listOf(
                Column("account_id", TtrpType.Integer),
                Column("branch_code", TtrpType.Str),
                Column("region", TtrpType.Str),
            )
        val sales =
            listOf(
                Column("customer", TtrpType.Str),
                Column("branch", TtrpType.Str),
                Column("amount", TtrpType.Decimal()),
            )
        val sums =
            listOf(
                Column("region", TtrpType.Str),
                Column("total", TtrpType.Decimal()),
                Column("avg_amt", TtrpType.Decimal()),
            )

        "filter predicate types bool against the sales schema" {
            val filter = opOf(crunchStatements()[1]) // filter(sales, amount > 0 and customer is not null)
            val pred = predicateArg(filter, 1)
            val result =
                typechecker.check(
                    pred,
                    mapOf("" to sales),
                    aggregatesAllowed = false,
                    predicateExpected = true,
                )
            result.diagnostics.shouldBeEmpty()
            result.type?.canonical shouldBe "bool"
        }

        "join on-predicate types bool against the port-qualified left/right schemas" {
            val join = opOf(crunchStatements()[2])
            val on = namedArg(join, "on")
            val schema = mapOf("left" to accounts, "right" to sales)
            val result = typechecker.check(on, schema, aggregatesAllowed = false, predicateExpected = true)
            result.diagnostics.shouldBeEmpty()
            result.type?.canonical shouldBe "bool"
        }

        "aggregate formulas are AggregateCall arms typing to decimal" {
            val aggregate = opOf(crunchStatements()[3])
            val assigns = aggregate.config!!.entries.filterIsInstance<AssignEntry>()
            assigns shouldHaveSize 2
            for (entry in assigns) {
                (entry.value is AggregateCall) shouldBe true
                val result = typechecker.check(entry.value, mapOf("" to sales), aggregatesAllowed = true)
                result.diagnostics.shouldBeEmpty()
                result.type?.canonical shouldBe "decimal"
            }
        }

        "branch predicate `total > 100000` types bool" {
            val branch = opOf(crunchStatements()[4])
            val pred = predicateArg(branch, 1)
            val result = typechecker.check(pred, mapOf("" to sums), aggregatesAllowed = false, predicateExpected = true)
            result.diagnostics.shouldBeEmpty()
            result.type?.canonical shouldBe "bool"
        }

        "AggregateCall arms appear ONLY in the aggregate config block (B-T5)" {
            val doc = TtrpParser.parseString(Fixtures.golden("hero.ttrp"), "hero.ttrp").document
            val aggregates = mutableListOf<AggregateCall>()
            walkDocumentExpressions(doc.statements) { collectAggregates(it, aggregates) }
            aggregates.map { it.function.value }.sorted() shouldBe listOf("agg.avg", "agg.sum")
        }

        "the wired TtrpFrontend.check is diagnostic-free on the hero" {
            val result = TtrpFrontend.check(Fixtures.golden("hero.ttrp"), fileName = "hero.ttrp")
            result.diagnostics.shouldBeEmpty()
        }
    })

private fun crunchStatements(): List<Assignment> {
    val doc = TtrpParser.parseString(Fixtures.golden("hero.ttrp"), "hero.ttrp").document
    val crunch = doc.statements.filterIsInstance<ContainerDecl>().single { it.name == "crunch" }
    return (crunch.body as FlowBody).statements.filterIsInstance<Assignment>()
}

private fun opOf(assignment: Assignment): OpCall =
    assignment.chain.elements
        .filterIsInstance<OpCall>()
        .first()

private fun predicateArg(
    op: OpCall,
    index: Int,
): Expression = (op.args[index].value as ExprArg).expr

private fun namedArg(
    op: OpCall,
    name: String,
): Expression = (op.args.single { it.name == name }.value as ExprArg).expr

/** Walks every expression in the document (op args + config formulas) and applies [visit]. */
private fun walkDocumentExpressions(
    statements: List<Statement>,
    visit: (Expression) -> Unit,
) {
    for (stmt in statements) {
        when (stmt) {
            is Assignment ->
                stmt.chain.elements
                    .filterIsInstance<OpCall>()
                    .forEach { visitOp(it, visit) }
            is ChainStmt ->
                stmt.chain.elements
                    .filterIsInstance<OpCall>()
                    .forEach { visitOp(it, visit) }
            is ContainerDecl -> (stmt.body as? FlowBody)?.let { walkDocumentExpressions(it.statements, visit) }
            else -> Unit
        }
    }
}

private fun visitOp(
    op: OpCall,
    visit: (Expression) -> Unit,
) {
    op.args.mapNotNull { it.value as? ExprArg }.forEach { visit(it.expr) }
    op.config
        ?.entries
        ?.filterIsInstance<AssignEntry>()
        ?.forEach { visit(it.value) }
}

private fun collectAggregates(
    e: Expression,
    into: MutableList<AggregateCall>,
) {
    when (e) {
        is AggregateCall -> {
            into += e
            e.args.forEach { collectAggregates(it, into) }
        }
        is FunctionCall -> e.args.forEach { collectAggregates(it, into) }
        is Cast -> collectAggregates(e.expr, into)
        is IsNull -> collectAggregates(e.expr, into)
        is InList -> {
            collectAggregates(e.expr, into)
            e.items.forEach { collectAggregates(it, into) }
        }
        is CaseWhen -> {
            e.branches.forEach {
                collectAggregates(it.first, into)
                collectAggregates(it.second, into)
            }
            e.elseExpr?.let { collectAggregates(it, into) }
        }
        is ColumnRef, is Literal, is MdPath -> Unit
        is org.tatrman.ttrp.expr.ParamRef, is org.tatrman.ttrp.expr.DurationExpr -> Unit
    }
}
