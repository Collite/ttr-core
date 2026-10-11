// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.lsp.nav

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ContainerDecl
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.expr.ColumnRef

/**
 * One textual occurrence of a variable name in a scope. A TTR-P variable is referenced
 * three ways, all captured here:
 *  - the assignment **target** (`sales = …`) — a definition ([isDefinition]);
 *  - a **chain source / port ref** (`j -> …`, `b.true`) — a [DottedRef] whose head is
 *    the variable ([tail] carries `.true`/`.rejects` so rename rewrites only the head);
 *  - a **bare source arg** (`filter(sales, …)`, `join(left: accounts, …)`) — a top-level
 *    `ColumnRef` argument whose column is the variable (nested predicate columns are
 *    input columns, never variables — C3-a-iv — and are excluded).
 */
data class VarRef(
    val name: String,
    val location: SourceLocation,
    /** Trailing `.port` segments for a `head.port` ref (empty for a bare occurrence). */
    val tail: List<String>,
    val isDefinition: Boolean,
) {
    /** The full replacement text for this occurrence's range under a rename to [newName]. */
    fun replacement(newName: String): String =
        if (tail.isEmpty()) newName else (listOf(newName) + tail).joinToString(".")
}

/** Finds variable occurrences within a single scope (one container body, or the program level). */
object VarRefs {
    /**
     * The variable names declared in [statements] at this scope: assignment targets plus,
     * for a container body, its `in`-port names (a port is a variable at the wiring level).
     */
    fun variableNames(
        statements: List<Statement>,
        inPorts: List<String> = emptyList(),
    ): Set<String> {
        val names = mutableSetOf<String>()
        names += inPorts
        for (s in statements) if (s is Assignment) names += s.target
        return names
    }

    /** All occurrences of any name in [varNames] across [statements] (this scope only). */
    fun occurrences(
        statements: List<Statement>,
        varNames: Set<String>,
    ): List<VarRef> {
        val out = mutableListOf<VarRef>()
        for (s in statements) {
            when (s) {
                is Assignment -> {
                    if (s.target in varNames) {
                        out += VarRef(s.target, s.targetLocation, emptyList(), isDefinition = true)
                    }
                    out += chainOccurrences(s.chain.elements, varNames)
                }
                // A synthesized TTR-B action wiring (AG B4) is not authored text — never an edit target.
                is ChainStmt -> if (!s.synthesized) out += chainOccurrences(s.chain.elements, varNames)
                else -> Unit
            }
        }
        return out
    }

    /** Occurrences of container names / `container.port` refs at program level (wiring). */
    fun containerRefs(
        statements: List<Statement>,
        containerNames: Set<String>,
    ): List<VarRef> {
        val out = mutableListOf<VarRef>()
        for (s in statements) {
            when (s) {
                is Assignment -> out += chainOccurrences(s.chain.elements, containerNames)
                is ChainStmt -> if (!s.synthesized) out += chainOccurrences(s.chain.elements, containerNames)
                is ContainerDecl ->
                    if (s.name in
                        containerNames
                    ) {
                        out += VarRef(s.name, s.location, emptyList(), isDefinition = true)
                    }
                else -> Unit
            }
        }
        return out
    }

    private fun chainOccurrences(
        elems: List<ChainElem>,
        varNames: Set<String>,
    ): List<VarRef> {
        val out = mutableListOf<VarRef>()
        for (e in elems) {
            when (e) {
                is DottedRef ->
                    if (e.parts.first() in varNames) {
                        out += VarRef(e.parts.first(), e.location, e.parts.drop(1), isDefinition = false)
                    }
                is OpCall -> out += sourceArgOccurrences(e.args, varNames)
            }
        }
        return out
    }

    /** Top-level bare-`ColumnRef` args of an op (its inputs) whose column is a variable. */
    private fun sourceArgOccurrences(
        args: List<Arg>,
        varNames: Set<String>,
    ): List<VarRef> {
        val out = mutableListOf<VarRef>()
        for (arg in args) {
            val value = arg.value
            if (value !is ExprArg) continue
            val ref = value.expr as? ColumnRef ?: continue
            if (ref.port != null) continue // `left.x` inside a predicate — an input column, not a variable
            if (ref.column in varNames) {
                out += VarRef(ref.column, ref.location, emptyList(), isDefinition = false)
            }
        }
        return out
    }
}
