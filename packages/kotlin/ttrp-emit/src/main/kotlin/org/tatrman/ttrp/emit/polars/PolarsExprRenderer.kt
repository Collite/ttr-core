// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.polars

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CaseWhen
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.MdPath
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.graph.model.MdStageRef

/**
 * Renders the TTR-P [Expression] IR to a Polars-expression Python string (`pl.col(...)`,
 * operators, `.is_null()`, `pl.when(...)`, …). Polars comparison/boolean ops propagate nulls
 * SQL-style natively, so the 3VL rules need no wrapping here (documented in [needsThreeValued]);
 * decimal/datetime enforcement is a Load/Store-boundary concern handled by
 * [PreludeGenerator]/[TransferScriptEmitter], not per-expression.
 *
 * [mdStaging] (S4-B4) carries where a Polars-placed MD read's value was staged — the
 * [org.tatrman.ttrp.graph.movement.MdReadHoist] stratum moved each MD read into its own db island and
 * staged the 1-row result into a container IN port. An `mdPath` whose location is in the map renders
 * as `pl.lit(<port>.item(0, "<column>"))` (a scalar read of the staged frame); one that is not (the
 * S4-A binding context was never wired for this placement) still raises `UNSUPPORTED_NODE`.
 */
class PolarsExprRenderer(
    private val mdStaging: Map<SourceLocation, MdStageRef> = emptyMap(),
    private val column: (ColumnRef) -> String = { it.column },
) {
    fun render(e: Expression): String =
        when (e) {
            is ColumnRef -> "pl.col(${quote(column(e))})"
            is Literal -> "pl.lit(${literal(e.value)})"
            is IsNull -> "${render(e.expr)}.${if (e.negated) "is_not_null" else "is_null"}()"
            is InList -> {
                val items = e.items.joinToString(", ") { literalOrRender(it) }
                val base = "${render(e.expr)}.is_in([$items])"
                if (e.negated) "(~$base)" else base
            }
            is Cast -> "${render(e.expr)}.cast(${polarsType(e.target)})"
            is CaseWhen -> caseWhen(e)
            is FunctionCall -> functionCall(e)
            is AggregateCall -> aggregate(e)
            is MdPath ->
                mdStaging[e.location]?.let { ref ->
                    // The read was hoisted to a db island (S4-B4); its 1-row result is the staged
                    // frame `ref.port`. Pull the scalar as a Python value and splice it as a literal.
                    "pl.lit(${ref.port}.item(0, ${quote(ref.column)}))"
                } ?: throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "MD dot-path reached Polars emit unstaged (no md-source hoist for this placement)",
                    location = e.location,
                )
            is org.tatrman.ttrp.expr.ParamRef -> "pl.lit(bindings[${quote(e.name)}])"
            is org.tatrman.ttrp.expr.DurationExpr ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "an unlowered duration reached Polars emit",
                    location = e.location,
                )
            // TTRP 0.5: a cn definition form — refused at check (TTRP-CN-001); a program never emits one.
            is org.tatrman.ttrp.expr.ConceptForm ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "a conceptual-model form belongs in a cn `definition:` (TTRP-CN-001)",
                    location = e.location,
                )
        }

    /** Aggregate expression body, e.g. `pl.col("amount").sum().alias("total")` (alias added by caller). */
    fun aggregate(e: AggregateCall): String {
        val arg = e.args.singleOrNull() ?: error("aggregate expects one arg")
        val base = render(arg)
        return when (e.function.name) {
            "sum" -> "pl.when($base.count() > 0).then($base.sum()).otherwise(None)"
            "avg" -> "$base.mean()"
            "min" -> "$base.min()"
            "max" -> "$base.max()"
            "count" -> {
                if (e.distinct) "$base.drop_nulls().n_unique().cast(pl.Int64)" else "$base.count().cast(pl.Int64)"
            }
            else ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "aggregate '${e.function.name}' has no Polars rendering",
                    location = e.location,
                )
        }
    }

    private fun functionCall(e: FunctionCall): String {
        val name = e.function.name
        val a = e.args.map { render(it) }
        if (name == "div") return "(${a[0]}.cast(pl.Float64) / ${a[1]}.cast(pl.Float64))"
        BINARY[name]?.let { op -> return "(${a[0]} $op ${a[1]})" }
        return when (name) {
            "not" -> "(~${a[0]})"
            "neg" -> "(-${a[0]})"
            "nullif" -> "pl.when(${a[0]} == ${a[1]}).then(None).otherwise(${a[0]})"
            "coalesce" -> "pl.coalesce([${a.joinToString(", ")}])"
            "concat" -> "pl.concat_str([${a.joinToString(", ")}])"
            "ceil" -> "${a[0]}.ceil()"
            "abs" -> "${a[0]}.abs()"
            "lower" -> "${a[0]}.str.to_lowercase()"
            "upper" -> "${a[0]}.str.to_uppercase()"
            "length" -> "${a[0]}.str.len_chars()"
            "round" -> if (a.size > 1) "${a[0]}.round(${a[1]})" else "${a[0]}.round()"
            else ->
                throw TtrpEmitException(
                    EmitDiagnosticId.UNSUPPORTED_NODE,
                    detail = "function '$name' has no Polars rendering",
                    location = e.location,
                )
        }
    }

    private fun caseWhen(e: CaseWhen): String {
        val sb = StringBuilder()
        e.branches.forEachIndexed { i, (cond, value) ->
            sb.append(if (i == 0) "pl.when(" else ".when(")
            sb
                .append(render(cond))
                .append(").then(")
                .append(render(value))
                .append(")")
        }
        sb.append(".otherwise(").append(e.elseExpr?.let { render(it) } ?: "None").append(")")
        return sb.toString()
    }

    private fun literalOrRender(e: Expression): String = if (e is Literal) literal(e.value) else render(e)

    private fun literal(v: LiteralValue): String =
        when (v) {
            is LiteralValue.Str -> quote(v.value)
            is LiteralValue.Bool -> if (v.value) "True" else "False"
            is LiteralValue.Num -> v.raw
            LiteralValue.Null -> "None"
        }

    private fun polarsType(t: TtrpType): String =
        when (t) {
            TtrpType.Integer -> "pl.Int64"
            TtrpType.Float, TtrpType.Double, TtrpType.Number -> "pl.Float64"
            TtrpType.Bool -> "pl.Boolean"
            TtrpType.Str -> "pl.String"
            TtrpType.Date -> "pl.Date"
            TtrpType.Timestamp, TtrpType.Datetime -> "pl.Datetime(\"us\", \"UTC\")"
            is TtrpType.Decimal -> "pl.Decimal(${t.precision ?: 38}, ${t.scale ?: 0})"
            else -> "pl.String"
        }

    private fun quote(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** Operators known to diverge from SQL 3VL and thus needing a prelude helper (near-empty for Polars). */
    fun needsThreeValued(): Set<String> = emptySet()

    companion object {
        private val BINARY =
            mapOf(
                "and" to "&",
                "or" to "|",
                "eq" to "==",
                "neq" to "!=",
                "lt" to "<",
                "lte" to "<=",
                "gt" to ">",
                "gte" to ">=",
                "add" to "+",
                "sub" to "-",
                "mul" to "*",
                "div" to "/",
            )
    }
}
