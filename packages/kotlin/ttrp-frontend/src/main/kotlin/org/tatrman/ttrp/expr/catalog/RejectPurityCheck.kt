// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr.catalog

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.diagnostics.Severity
import org.tatrman.ttrp.diagnostics.TtrpDiagnostic
import org.tatrman.ttrp.diagnostics.TtrpDiagnosticId
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.DurationExpr
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.ParamRef

/**
 * RJ-P1 / R-C2-b (contracts §9, `TTRP-RJ-104`): a volatile (impure) function may not appear in a
 * reject-capable position. If it could, the synthesized validity guard — evaluated once — might
 * disagree with the guarded op re-evaluating the same volatile call, silently misrouting rows. The
 * v1 catalogue is all-pure, so this is inert today; it guards the invariant against any future
 * volatile entry (e.g. `fn.now`). Reject-capable positions in v1: the operand of a `cast`, the
 * denominator argument of `op.div`, and the argument of a datetime parse (`fn.to_date` /
 * `fn.to_timestamp`).
 */
object RejectPurityCheck {
    private val PARSE_FNS = setOf("fn.to_date", "fn.to_timestamp")

    /** Diagnostics for every impure call sitting in a reject-capable position within [expr]. */
    fun check(
        expr: Expression,
        catalog: FunctionCatalog,
    ): List<TtrpDiagnostic> {
        val out = mutableListOf<TtrpDiagnostic>()
        walk(expr, rejectCapable = false, catalog, out)
        return out
    }

    private fun walk(
        e: Expression,
        rejectCapable: Boolean,
        catalog: FunctionCatalog,
        out: MutableList<TtrpDiagnostic>,
    ) {
        when (e) {
            is Cast -> walk(e.expr, rejectCapable = true, catalog, out)
            is FunctionCall -> {
                if (rejectCapable && isImpure(e.function.value, catalog)) {
                    out += diag(e.location, e.function.value)
                }
                val childCapable = rejectCapable || e.function.value == "op.div" || e.function.value in PARSE_FNS
                e.args.forEach { walk(it, childCapable, catalog, out) }
            }
            is AggregateCall -> e.args.forEach { walk(it, rejectCapable, catalog, out) }
            is CaseWhen -> {
                e.branches.forEach {
                    walk(it.first, rejectCapable, catalog, out)
                    walk(it.second, rejectCapable, catalog, out)
                }
                e.elseExpr?.let { walk(it, rejectCapable, catalog, out) }
            }
            is InList -> {
                walk(e.expr, rejectCapable, catalog, out)
                e.items.forEach { walk(it, rejectCapable, catalog, out) }
            }
            is IsNull -> walk(e.expr, rejectCapable, catalog, out)
            // MdPath is a leaf data-path reference — its components are names/literals/sets/ranges,
            // never nested Expressions, so no volatile function can sit in a reject-capable position
            // inside one. Treat it like ColumnRef/Literal. (Added when md-dotpath introduced MdPath.)
            is ColumnRef, is Literal, is MdPath -> Unit
            // AU-core-P0: a `:name` binding and a duration are leaves too.
            is ParamRef, is DurationExpr -> Unit
        }
    }

    private fun isImpure(
        id: String,
        catalog: FunctionCatalog,
    ): Boolean {
        val name = id.substringAfterLast('.')
        return catalog.resolve(name).any { it.id.value == id && !it.pure }
    }

    private fun diag(
        location: SourceLocation,
        id: String,
    ) = TtrpDiagnostic(
        id = TtrpDiagnosticId.RJ_104,
        severity = Severity.ERROR,
        message = "volatile function `$id` in a reject-capable position",
        location = location,
    )
}
