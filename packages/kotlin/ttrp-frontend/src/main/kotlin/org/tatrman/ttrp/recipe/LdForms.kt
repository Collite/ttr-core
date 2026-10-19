// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.recipe

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ConfigBlock
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.DecisionDecl
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FlowBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.RecipeDecl
import org.tatrman.ttrp.ast.RoleContainer
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.FunctionCall

/**
 * The language design's argument forms (D-108), read into the canonical shapes the resolver and the graph
 * builder already know — so `TtrpChecker`, `GraphBuilder` and everything after them see one spelling
 * (AU-core-P1; the forms were parsed and silently dropped before):
 *
 * - `join(…, kind: left)` → `join(…, type: left)`;
 * - an op written as an argument — `join(right: load(er.entity.sales_line), …)` — is hoisted to a variable
 *   `_ldN = load(…)` ahead of its statement, and the argument names the variable;
 * - `calc(a = e, …)` → `calc { a = e, … }`;
 * - `select(c, …, x = e)` → `calc { x = e } -> select(c, …, x)`.
 *
 * Locations are the authored ones (a hoisted load keeps the call's span), so diagnostics point into the source.
 * The parse itself is untouched (the formatter and the LSP read the authored AST); this runs where a program
 * is resolved.
 */
object LdForms {
    /** The hoisted variables' prefix; a name the document already uses is skipped. */
    const val HOIST_PREFIX = "_ld"

    fun desugar(doc: TtrpDocument): TtrpDocument {
        val run = Run(names(doc.statements))
        return doc.copy(statements = run.statements(doc.statements))
    }

    /** Every name the document binds — a hoisted variable must not take one. */
    private fun names(stmts: List<Statement>): Set<String> =
        stmts
            .flatMap { s ->
                when (s) {
                    is Assignment -> listOf(s.target)
                    is ContainerDecl ->
                        listOf(s.name) +
                            ((s.body as? FlowBody)?.let { names(it.statements) } ?: emptySet())
                    is RecipeDecl ->
                        s.members.flatMap { m ->
                            when (m) {
                                is RoleContainer ->
                                    listOf(m.name) +
                                        ((m.body as? FlowBody)?.let { names(it.statements) } ?: emptySet())
                                is DecisionDecl -> listOf(m.name) + names(m.statements)
                                else -> emptyList()
                            }
                        }
                    else -> emptyList()
                }
            }.toSet()

    private class Run(
        private val taken: Set<String>,
    ) {
        private var counter = 0

        private fun fresh(): String {
            var n: String
            do {
                n = "$HOIST_PREFIX${counter++}"
            } while (n in taken)
            return n
        }

        fun statements(stmts: List<Statement>): List<Statement> = stmts.flatMap { statement(it) }

        private fun statement(stmt: Statement): List<Statement> =
            when (stmt) {
                is Assignment -> {
                    val hoisted = mutableListOf<Statement>()
                    val rewritten = stmt.copy(chain = chain(stmt.chain, hoisted))
                    hoisted + rewritten
                }
                is ChainStmt -> {
                    val hoisted = mutableListOf<Statement>()
                    val rewritten = stmt.copy(chain = chain(stmt.chain, hoisted))
                    hoisted + rewritten
                }
                is ContainerDecl ->
                    listOf(
                        (stmt.body as? FlowBody)?.let {
                            stmt.copy(
                                body = it.copy(statements = statements(it.statements)),
                            )
                        }
                            ?: stmt,
                    )
                is RecipeDecl -> listOf(stmt.copy(members = stmt.members.map { member(it) }))
                else -> listOf(stmt)
            }

        private fun member(m: org.tatrman.ttrp.ast.RecipeMember): org.tatrman.ttrp.ast.RecipeMember =
            when (m) {
                is RoleContainer ->
                    (m.body as? FlowBody)?.let { m.copy(body = it.copy(statements = statements(it.statements))) } ?: m
                is DecisionDecl -> m.copy(statements = statements(m.statements))
                else -> m
            }

        private fun chain(
            c: Chain,
            hoisted: MutableList<Statement>,
        ): Chain {
            val out = mutableListOf<ChainElem>()
            for (e in c.elements) {
                if (e !is OpCall) {
                    out += e
                    continue
                }
                val op = e.copy(args = e.args.map { hoistOpArg(it, hoisted) })
                when (op.name) {
                    "join" -> out += kindToType(op)
                    "calc" -> out += calcArgs(op)
                    "select" -> out += selectArgs(op)
                    else -> out += op
                }
            }
            return c.copy(elements = out)
        }

        /** `right: load(x)` → `right: _ldN` with `_ldN = load(x)` hoisted ahead of the statement. */
        private fun hoistOpArg(
            arg: Arg,
            hoisted: MutableList<Statement>,
        ): Arg {
            val call = (arg.value as? ExprArg)?.expr as? FunctionCall ?: return arg
            if (call.function.value != "load") return arg
            val name = fresh()
            val load =
                OpCall(
                    name = "load",
                    args = call.args.map { Arg(null, ExprArg(it, it.location), it.location) },
                    config = null,
                    location = call.location,
                )
            hoisted += Assignment(name, call.location, Chain(listOf(load), call.location), call.location)
            return arg.copy(value = ExprArg(ColumnRef(null, name, call.location), call.location))
        }

        private fun kindToType(op: OpCall): OpCall =
            if (op.args.none { it.name == "type" }) {
                op.copy(args = op.args.map { if (it.name == "kind") it.copy(name = "type") else it })
            } else {
                op
            }

        /** A positional `name = expr` argument (an `op.eq` whose left side is a bare column name). */
        private fun assignment(arg: Arg): AssignEntry? {
            if (arg.name != null) return null
            val eq = (arg.value as? ExprArg)?.expr as? FunctionCall ?: return null
            if (eq.function != CatalogId.EQ || eq.args.size != 2) return null
            val lhs = eq.args[0] as? ColumnRef ?: return null
            if (lhs.port != null) return null
            return AssignEntry(lhs.column, eq.args[1], arg.location)
        }

        private fun calcArgs(op: OpCall): OpCall {
            val entries = op.args.map { assignment(it) }
            if (entries.isEmpty() || entries.any { it == null }) return op
            val existing = op.config?.entries.orEmpty()
            return op.copy(
                args = emptyList(),
                config = ConfigBlock(existing + entries.filterNotNull(), op.config?.location ?: op.location),
            )
        }

        private fun selectArgs(op: OpCall): List<OpCall> {
            val computed = op.args.mapNotNull { assignment(it) }
            if (computed.isEmpty()) return listOf(op)
            val calc = OpCall("calc", emptyList(), ConfigBlock(computed, op.location), op.location)
            val kept =
                op.args.map { a ->
                    assignment(
                        a,
                    )?.let { Arg(null, ExprArg(ColumnRef(null, it.name, it.location), it.location), a.location) }
                        ?: a
                }
            return listOf(calc, op.copy(args = kept))
        }
    }
}
