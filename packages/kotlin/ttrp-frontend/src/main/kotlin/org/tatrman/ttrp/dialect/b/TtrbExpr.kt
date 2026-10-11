// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.b

import org.tatrman.ttrp.dialect.sql.TtrSqlLoc
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.InList
import org.tatrman.ttrp.expr.IsNull
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.catalog.FunctionCatalog
import org.tatrman.ttrp.expr.catalog.FunctionKind
import org.tatrman.ttrp.parser.generated.TTRBParser as P

/**
 * Folds TTR-B expression trees into the ONE PL expression IR (S16 / T5-e) — the SAME
 * [CatalogId]s canonical TTR-P produces. The verbose comparators ("is more than", "is not
 * empty", "is one of") are closed grammar alternatives (C4-c = β); each folds to the same
 * id as its canonical operator, pinned by the synonym table + drift spec. Canonical
 * spellings may mix with verbose forms in one predicate. Columns are port-qualified
 * (`left`/`right`) inside a join `on`, unqualified elsewhere (the C2-b-ii analogue).
 */
class TtrbExpr(
    private val loc: TtrSqlLoc,
    private val catalog: FunctionCatalog,
    private val skin: TtrbSkin = TtrbSkin.EN,
) {
    fun foldBool(
        ctx: P.BoolExprContext,
        aliasPort: Map<String, String> = emptyMap(),
    ): Expression = orExpr(ctx.orExpr(), aliasPort)

    fun foldExpr(
        ctx: P.ExprContext,
        aliasPort: Map<String, String> = emptyMap(),
    ): Expression = addExpr(ctx.addExpr(), aliasPort)

    private fun orExpr(
        ctx: P.OrExprContext,
        ap: Map<String, String>,
    ): Expression = foldChain(ctx.andExpr().map { andExpr(it, ap) }, CatalogId.OR, loc.of(ctx))

    private fun andExpr(
        ctx: P.AndExprContext,
        ap: Map<String, String>,
    ): Expression = foldChain(ctx.notExpr().map { notExpr(it, ap) }, CatalogId.AND, loc.of(ctx))

    private fun notExpr(
        ctx: P.NotExprContext,
        ap: Map<String, String>,
    ): Expression =
        if (ctx.NOT() != null) {
            FunctionCall(CatalogId.NOT, listOf(notExpr(ctx.notExpr(), ap)), loc.of(ctx))
        } else {
            predicate(ctx.predicate(), ap)
        }

    private fun predicate(
        ctx: P.PredicateContext,
        ap: Map<String, String>,
    ): Expression =
        when (ctx) {
            is P.EmptyPredicateContext ->
                IsNull(addExpr(ctx.addExpr(), ap), negated = ctx.NOT() != null, location = loc.of(ctx))
            is P.OneOfPredicateContext ->
                InList(
                    addExpr(ctx.addExpr(), ap),
                    ctx.expr().map { foldExpr(it, ap) },
                    negated = false,
                    location = loc.of(ctx),
                )
            is P.BetweenPredicateContext -> {
                val x = addExpr(ctx.addExpr(0), ap)
                val lo = addExpr(ctx.addExpr(1), ap)
                val hi = addExpr(ctx.addExpr(2), ap)
                val range =
                    FunctionCall(
                        CatalogId.AND,
                        listOf(
                            FunctionCall(CatalogId.GTE, listOf(x, lo), loc.of(ctx)),
                            FunctionCall(CatalogId.LTE, listOf(x, hi), loc.of(ctx)),
                        ),
                        loc.of(ctx),
                    )
                if (ctx.NOT() != null) FunctionCall(CatalogId.NOT, listOf(range), loc.of(ctx)) else range
            }
            is P.ComparePredicateContext -> {
                val l = addExpr(ctx.addExpr(0), ap)
                val r = addExpr(ctx.addExpr(1), ap)
                FunctionCall(compareOp(ctx.comparator()), listOf(l, r), loc.of(ctx))
            }
            is P.BarePredicateContext -> addExpr(ctx.addExpr(), ap)
            else -> error("unhandled predicate: ${ctx::class.simpleName}")
        }

    /** Verbose OR canonical comparator → the one [CatalogId] (C4-c: same tree as canonical). */
    private fun compareOp(ctx: P.ComparatorContext): CatalogId =
        when {
            ctx.symbolOp() != null -> symbolOp(ctx.symbolOp())
            ctx.verboseGt() != null -> CatalogId.GT
            ctx.verboseLt() != null -> CatalogId.LT
            ctx.verboseLe() != null -> CatalogId.LTE
            ctx.verboseGe() != null -> CatalogId.GTE
            ctx.verboseNe() != null -> CatalogId.NEQ
            else -> CatalogId.EQ // verboseEq
        }

    private fun symbolOp(ctx: P.SymbolOpContext): CatalogId =
        when {
            ctx.EQ() != null -> CatalogId.EQ
            ctx.NEQ() != null || ctx.NEQ2() != null -> CatalogId.NEQ
            ctx.LT() != null -> CatalogId.LT
            ctx.LTE() != null -> CatalogId.LTE
            ctx.GT() != null -> CatalogId.GT
            else -> CatalogId.GTE
        }

    private fun addExpr(
        ctx: P.AddExprContext,
        ap: Map<String, String>,
    ): Expression {
        var acc = mulExpr(ctx.mulExpr(0), ap)
        val ops = symbolTokens(ctx, setOf("+", "-"))
        for (i in 1 until ctx.mulExpr().size) {
            val id = if (ops[i - 1] == "+") CatalogId.ADD else CatalogId.SUB
            acc = FunctionCall(id, listOf(acc, mulExpr(ctx.mulExpr(i), ap)), loc.of(ctx))
        }
        return acc
    }

    private fun mulExpr(
        ctx: P.MulExprContext,
        ap: Map<String, String>,
    ): Expression {
        var acc = unaryExpr(ctx.unaryExpr(0), ap)
        val ops = symbolTokens(ctx, setOf("*", "/"))
        for (i in 1 until ctx.unaryExpr().size) {
            val id = if (ops[i - 1] == "*") CatalogId.MUL else CatalogId.DIV
            acc = FunctionCall(id, listOf(acc, unaryExpr(ctx.unaryExpr(i), ap)), loc.of(ctx))
        }
        return acc
    }

    private fun unaryExpr(
        ctx: P.UnaryExprContext,
        ap: Map<String, String>,
    ): Expression =
        if (ctx.MINUS() != null) {
            FunctionCall(CatalogId.NEG, listOf(unaryExpr(ctx.unaryExpr(), ap)), loc.of(ctx))
        } else {
            primary(ctx.primary(), ap)
        }

    private fun primary(
        ctx: P.PrimaryContext,
        ap: Map<String, String>,
    ): Expression =
        when (ctx) {
            is P.LitPrimaryContext -> literal(ctx.literal())
            is P.CallPrimaryContext -> functionCall(ctx.funcCall(), ap)
            is P.ColPrimaryContext -> columnRef(ctx.dottedRef(), ap)
            is P.ParenPrimaryContext -> foldBool(ctx.boolExpr(), ap)
            else -> error("unhandled primary: ${ctx::class.simpleName}")
        }

    private fun functionCall(
        ctx: P.FuncCallContext,
        ap: Map<String, String>,
    ): Expression {
        val name = skin.function(ctx.name.text).lowercase()
        val args = ctx.expr().map { foldExpr(it, ap) }
        val entries = catalog.resolve(name)
        val aggEntry = entries.firstOrNull { it.kind == FunctionKind.AGGREGATE }
        val scalarEntry = entries.firstOrNull { it.kind == FunctionKind.SCALAR }
        return when {
            aggEntry != null -> AggregateCall(aggEntry.id, args, distinct = false, location = loc.of(ctx))
            scalarEntry != null -> FunctionCall(scalarEntry.id, args, loc.of(ctx))
            else -> FunctionCall(CatalogId(name), args, loc.of(ctx))
        }
    }

    /** A dotted column reference outside any join (`email_zástupce`, `t.col`) — the recipient column of an action. */
    fun foldRef(ctx: P.DottedRefContext): ColumnRef = columnRef(ctx, emptyMap())

    private fun columnRef(
        ctx: P.DottedRefContext,
        ap: Map<String, String>,
    ): ColumnRef {
        val parts = ctx.ident().map { it.text }
        return if (parts.size >= 2) {
            ColumnRef(port = ap[parts[parts.size - 2]], column = parts.last(), location = loc.of(ctx))
        } else {
            ColumnRef(port = null, column = parts.last(), location = loc.of(ctx))
        }
    }

    private fun literal(ctx: P.LiteralContext): Literal {
        val value: LiteralValue =
            when {
                ctx.str() != null -> LiteralValue.Str(unquote(ctx.str().text))
                ctx.NUMBER() != null -> LiteralValue.Num(ctx.NUMBER().text)
                ctx.TRUE() != null -> LiteralValue.Bool(true)
                else -> LiteralValue.Bool(false)
            }
        return Literal(value, loc.of(ctx))
    }

    private fun foldChain(
        operands: List<Expression>,
        op: CatalogId,
        at: org.tatrman.ttrp.ast.SourceLocation,
    ): Expression {
        var acc = operands.first()
        for (i in 1 until operands.size) acc = FunctionCall(op, listOf(acc, operands[i]), at)
        return acc
    }

    private fun symbolTokens(
        ctx: org.antlr.v4.runtime.ParserRuleContext,
        wanted: Set<String>,
    ): List<String> =
        ctx.children
            .orEmpty()
            .mapNotNull { (it as? org.antlr.v4.runtime.tree.TerminalNode)?.text }
            .filter { it in wanted }

    /** Strip the one enclosing quote pair (both `"…"` and `'…'` are string literals in TTR-B). */
    private fun unquote(raw: String): String = raw.substring(1, raw.length - 1)
}
