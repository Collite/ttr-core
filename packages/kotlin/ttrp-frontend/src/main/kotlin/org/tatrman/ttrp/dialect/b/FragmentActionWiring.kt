// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.FragmentBody
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.PortDecl
import org.tatrman.ttrp.ast.PortKind
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.ast.TtrpDocument
import org.tatrman.ttrp.expr.ColumnRef

/**
 * Routes TTR-B action sentences to their action displays (AG B4). A host reads an action display from
 * a container OUT port (the `sql-text` bundle: one statement per OUT port), so for every action output a
 * fragment reports the checker adds what a hand-written canonical program declares: the container OUT
 * port (unless the header already declares that name) and, right after the container, the program-level
 * wiring `<container>.<port> -> display(<kind>)`. Both are marked `synthesized`; their spans point into
 * the fragment (the action sentence), so TTRP-DSP findings land on the sentence. Applied to the CHECKED
 * document only — the plain parse (formatter, graph edits) keeps the authored text.
 */
object FragmentActionWiring {
    fun apply(doc: TtrpDocument): TtrpDocument {
        if (doc.statements.none { actionsOf(it).isNotEmpty() }) return doc
        val out = ArrayList<Statement>(doc.statements.size + 4)
        for (stmt in doc.statements) {
            val actions = actionsOf(stmt)
            if (stmt !is ContainerDecl || actions.isEmpty()) {
                out += stmt
                continue
            }
            val declared = stmt.ports.map { it.name }.toSet()
            val added =
                actions
                    .filter { it.port !in declared }
                    .distinctBy { it.port }
                    .map { PortDecl(PortKind.OUT, it.port, it.verbLocation, synthesized = true) }
            out += stmt.copy(ports = stmt.ports + added)
            for (a in actions) {
                val ref = DottedRef(listOf(stmt.name, a.port), a.verbLocation)
                val shown = ColumnRef(port = null, column = a.display, location = a.location)
                val display =
                    OpCall("display", listOf(Arg(null, ExprArg(shown, a.location), a.location)), null, a.location)
                out += ChainStmt(Chain(listOf(ref, display), a.location), a.location, synthesized = true)
            }
        }
        return doc.copy(statements = out)
    }

    private fun actionsOf(stmt: Statement) =
        ((stmt as? ContainerDecl)?.body as? FragmentBody)?.decomposition?.actionOutputs.orEmpty()
}
