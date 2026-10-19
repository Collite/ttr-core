// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.dialect.sql

import org.tatrman.ttrp.ast.Arg
import org.tatrman.ttrp.ast.Assignment
import org.tatrman.ttrp.ast.Chain
import org.tatrman.ttrp.ast.ChainElem
import org.tatrman.ttrp.ast.ChainStmt
import org.tatrman.ttrp.ast.ConfigBlock
import org.tatrman.ttrp.ast.DottedRef
import org.tatrman.ttrp.ast.ExprArg
import org.tatrman.ttrp.ast.AssignEntry
import org.tatrman.ttrp.ast.GroupByEntry
import org.tatrman.ttrp.ast.OpCall
import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Statement
import org.tatrman.ttrp.expr.AggregateCall
import org.tatrman.ttrp.expr.CatalogId
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.catalog.FunctionCatalog
import org.tatrman.ttrp.parser.generated.TTRSqlParser as P

/**
 * Clause→node decomposition (C2-a-β): lowers a TTR-SQL parse tree into canonical TTR-P
 * statements (the SAME AST canonical authoring emits), so downstream resolution + graph
 * construction are shared and bare ≡ embedded ≡ canonical graphs hold by construction.
 *
 * The C2-b α clause map: each `WITH` CTE → an SSA-labelled sub-chain (CTE name = SSA
 * label, E-b's inverse); `FROM a JOIN b ON` → `join(left:a, right:b, on:…, type:…)` with
 * `left`/`right` port qualifiers (C2-b-ii); `WHERE` → `filter`; `GROUP BY`/`HAVING` →
 * `aggregate {…}` + trailing `filter`; the SELECT list → `project`/`calc`; `DISTINCT` →
 * `distinct`; set-ops → `union`/`intersect`/`except`; `ORDER BY` → `sort`; `LIMIT` →
 * `limit`; `VALUES` → `values`. `SELECT *` expands against [schemas] when known (T6.1.6).
 */
class TtrSqlDecomposer(
    private val loc: TtrSqlLoc,
    catalog: FunctionCatalog,
    /** Table/port name → ordered column names, for `SELECT *` expansion (empty ⇒ star kept as a sentinel). */
    private val schemas: Map<String, List<String>> = emptyMap(),
) {
    private val exprFolder = TtrSqlExpr(loc, catalog)
    private val pre = mutableListOf<Statement>()
    private val derived = LinkedHashSet<String>()
    private var cteNames = mutableSetOf<String>()
    private var synth = 0

    data class Result(
        val statements: List<Statement>,
        val derivedInPorts: List<String>,
    )

    fun decompose(
        program: P.FragmentProgramContext,
        outPort: String?,
    ): Result {
        val out = mutableListOf<Statement>()
        program.withClause()?.cte()?.forEach { cte ->
            val name = sqlName(cte.name.text)
            val chain = decomposeQuery(cte.queryExpression())
            out += drainPre()
            out += assign(name, chain, loc.of(cte))
            cteNames += name
        }
        val finalChain = decomposeQuery(program.queryExpression())
        out += drainPre()
        out +=
            if (outPort != null) {
                assign(outPort, finalChain, loc.of(program.queryExpression()))
            } else {
                ChainStmt(finalChain, loc.of(program.queryExpression()))
            }
        return Result(out, derived.toList())
    }

    // ---- query / core ----

    private fun decomposeQuery(qe: P.QueryExpressionContext): Chain {
        val cores = qe.selectCore()
        var elems = decomposeCore(cores[0])
        // Set-ops: left-fold. `union` collapses consecutive UNIONs; intersect/except are binary.
        val ops = qe.setOp()
        for (i in ops.indices) {
            val left = materialize(Chain(elems, loc.of(qe)), loc.of(qe))
            val right = materialize(Chain(decomposeCore(cores[i + 1]), loc.of(qe)), loc.of(qe))
            val opName =
                when {
                    ops[i].UNION() != null -> "union"
                    ops[i].INTERSECT() != null -> "intersect"
                    else -> "except"
                }
            elems =
                listOf(
                    opCall(
                        opName,
                        listOf(refArg(null, left, loc.of(qe)), refArg(null, right, loc.of(qe))),
                        null,
                        loc.of(qe),
                    ),
                )
        }
        qe.orderByClause()?.let { elems = elems + sortElem(it) }
        qe.limitClause()?.let { elems = elems + limitElem(it) }
        return Chain(elems, loc.of(qe))
    }

    private fun decomposeCore(core: P.SelectCoreContext): List<ChainElem> =
        when (core) {
            is P.ValuesQueryContext -> listOf(opCall("values", emptyList(), null, loc.of(core)))
            is P.SelectQueryContext -> decomposeSelect(core)
            else -> error("unhandled selectCore: ${core::class.simpleName}")
        }

    private fun decomposeSelect(core: P.SelectQueryContext): List<ChainElem> {
        val elems = mutableListOf<ChainElem>()
        val aliasPort = mutableMapOf<String, String>()

        // FROM / JOIN.
        val from = core.fromClause()
        if (from != null) {
            val base = fromItemName(from.fromItem())
            noteExternal(base.first)
            val joins = from.joinClause()
            if (joins.isEmpty()) {
                elems += baseHead(base.first, loc.of(from.fromItem()))
                base.second?.let { aliasPort[it] = "left" }
                aliasPort[base.first] = "left"
            } else {
                // Left-deep join chain. For a single join (the hero) no intermediate name is needed.
                var leftName = operandRef(base.first, loc.of(from.fromItem()))
                // Every table (and alias) joined so far is a column source of the NEXT join's left input — so a
                // qualifier naming any of them reads `left` (AU-core-P1: only the first table's did, and a later
                // `q.line_id` came out unqualified, ambiguous against a right side with the same column).
                val joinedSoFar = (listOf(base.first) + listOfNotNull(base.second)).toMutableList()
                for ((idx, jc) in joins.withIndex()) {
                    val rightItem = fromItemName(jc.fromItem())
                    noteExternal(rightItem.first)
                    val rightName = operandRef(rightItem.first, loc.of(jc.fromItem()))
                    val ap = mutableMapOf<String, String>()
                    // qualifiers for THIS join's ON: first operand → left, joined → right (C2-b-ii).
                    joinedSoFar.forEach { ap[it] = "left" }
                    ap[leftName] = "left"
                    ap[rightItem.first] = "right"
                    rightItem.second?.let { ap[it] = "right" }
                    joinedSoFar.addAll(listOfNotNull(rightItem.first, rightItem.second))
                    val onExpr = exprFolder.fold(jc.expr(), ap)
                    val joinCall =
                        opCall(
                            "join",
                            listOf(
                                refArg("left", leftName, loc.of(jc)),
                                refArg("right", rightName, loc.of(jc)),
                                namedArg("on", onExpr, loc.of(jc.expr())),
                                refArg("type", joinKind(jc), loc.of(jc)),
                            ),
                            null,
                            loc.of(jc),
                        )
                    if (idx == joins.lastIndex) {
                        elems += joinCall
                        aliasPort.putAll(ap)
                    } else {
                        leftName = synthName()
                        pre += assign(leftName, Chain(listOf(joinCall), loc.of(jc)), loc.of(jc))
                    }
                }
            }
        }

        // WHERE — EXISTS/IN-subquery desugar to semi/anti Join (T6.1.6), else Filter.
        core.whereClause()?.let { w ->
            val semiAnti = trySemiAntiDesugar(w.expr(), elems, aliasPort)
            if (semiAnti != null) {
                elems.clear()
                elems += semiAnti
            } else {
                elems +=
                    opCall(
                        "filter",
                        listOf(namedArg(null, exprFolder.fold(w.expr(), aliasPort), loc.of(w.expr()))),
                        null,
                        loc.of(w),
                    )
            }
        }

        // GROUP BY (+ HAVING) → aggregate + trailing filter.
        val group = core.groupByClause()
        var projectedByAggregate = false
        if (group != null || selectHasAggregate(core.selectList())) {
            elems += aggregateElem(core.selectList(), group, aliasPort)
            group?.having?.let {
                elems +=
                    opCall(
                        "filter",
                        listOf(namedArg(null, exprFolder.fold(it, aliasPort), loc.of(it))),
                        null,
                        loc.of(it),
                    )
            }
            projectedByAggregate = true
        }

        // SELECT list → project/calc (unless the aggregate produced the output columns).
        if (!projectedByAggregate) {
            elems += projectElem(core.selectList(), aliasPort)
        }

        // DISTINCT → distinct.
        if (core.DISTINCT() != null) elems += opCall("distinct", emptyList(), null, loc.of(core))

        return elems
    }

    // ---- clause elements ----

    private fun projectElem(
        list: P.SelectListContext,
        ap: Map<String, String>,
    ): ChainElem {
        // A select item's `AS alias` names the projected column (AU-core-P1: it was dropped, so a computed column
        // reached the graph unnamed); it rides as the argument's name, which the graph builder reads as the alias.
        val cols = mutableListOf<Pair<String?, Expression>>()
        for (item in list.selectItem()) {
            when (item) {
                is P.StarAllContext -> cols += expandStar(null, item).map { null to it }
                is P.StarQualifiedContext -> cols += expandStar(sqlName(item.qualifier.text), item).map { null to it }
                is P.ExprItemContext -> {
                    val e = exprFolder.fold(item.expr(), ap)
                    cols += item.alias?.text?.let { sqlName(it) } to e
                }
            }
        }
        return opCall("project", cols.map { (alias, e) -> namedArg(alias, e, e.location) }, null, loc.of(list))
    }

    private fun expandStar(
        qualifier: String?,
        at: org.antlr.v4.runtime.ParserRuleContext,
    ): List<Expression> {
        val key = qualifier ?: schemas.keys.firstOrNull()
        val cols = key?.let { schemas[it] }
        return if (cols != null) {
            cols.map { ColumnRef(port = null, column = it, location = loc.of(at)) }
        } else {
            // Schema not known at this stage — keep a `*` sentinel (expansion is internal, C2-b-iii β).
            listOf(ColumnRef(port = qualifier, column = "*", location = loc.of(at)))
        }
    }

    private fun aggregateElem(
        list: P.SelectListContext,
        group: P.GroupByClauseContext?,
        ap: Map<String, String>,
    ): ChainElem {
        val entries = mutableListOf<org.tatrman.ttrp.ast.ConfigEntry>()
        val groupKeys =
            group?.groupItem()?.mapNotNull { (exprFolder.fold(it.expr(), ap) as? ColumnRef)?.column }.orEmpty()
        if (groupKeys.isNotEmpty()) entries += GroupByEntry(groupKeys, loc.of(group!!))
        for (item in list.selectItem()) {
            if (item is P.ExprItemContext) {
                val e = exprFolder.fold(item.expr(), ap)
                if (containsAggregate(e)) {
                    val name =
                        item.alias?.text?.let { sqlName(it) } ?: (e as? AggregateCall)?.function?.name
                            ?: "agg${entries.size}"
                    entries += AssignEntry(name, e, loc.of(item))
                }
            }
        }
        return OpCall("aggregate", emptyList(), ConfigBlock(entries, loc.of(list)), loc.of(list))
    }

    private fun sortElem(ob: P.OrderByClauseContext): ChainElem {
        val keys =
            ob.sortKey().mapNotNull { (exprFolder.fold(it.expr(), emptyMap()) as? ColumnRef)?.column }
        return opCall(
            "sort",
            keys.map { namedArg(null, ColumnRef(null, it, loc.of(ob)), loc.of(ob)) },
            null,
            loc.of(ob),
        )
    }

    private fun limitElem(lc: P.LimitClauseContext): ChainElem {
        val n = lc.count.text
        return opCall(
            "limit",
            listOf(namedArg(null, Literal(LiteralValue.Num(n), loc.of(lc)), loc.of(lc))),
            null,
            loc.of(lc),
        )
    }

    // ---- semi/anti desugar (T6.1.6) ----

    private fun trySemiAntiDesugar(
        where: P.ExprContext,
        baseElems: List<ChainElem>,
        ap: Map<String, String>,
    ): List<ChainElem>? {
        // Only a WHERE that is EXACTLY an EXISTS / NOT EXISTS / x IN (subquery) / x NOT IN (subquery).
        val unwrapped = unwrapPredicate(where) ?: return null
        val leftName = baseRefName(baseElems) ?: return null
        val pred = unwrapped.pred
        return when (pred) {
            is P.ExistsPredicateContext -> {
                // A leading `NOT EXISTS` is an ANTI join; a bare `EXISTS` is SEMI. (The EXISTS
                // correlation lives inside the subquery's own WHERE — outside the structural cut —
                // so `on` stays null: an unconditioned existence match.)
                val sub = materialize(decomposeQuery(pred.queryExpression()), loc.of(pred))
                val type = if (unwrapped.negated) "anti" else "semi"
                listOf(semiAntiJoin(leftName, sub, type, null, loc.of(pred)))
            }
            is P.InPredicateContext -> {
                val subQ = pred.queryExpression() ?: return null
                val sub = materialize(decomposeQuery(subQ), loc.of(pred))
                // `x NOT IN` and an outer `NOT (x IN …)` both negate; either one alone ⇒ ANTI (XOR).
                val type = if (unwrapped.negated != (pred.NOT() != null)) "anti" else "semi"
                // `x IN (SELECT y …)` carries a real correlation `left.x = right.y` (unlike EXISTS).
                val on = inCorrelation(pred, subQ, ap)
                listOf(semiAntiJoin(leftName, sub, type, on, loc.of(pred)))
            }
            else -> null
        }
    }

    /** A single-level-unwrapped WHERE predicate plus whether a leading `NOT` negated it. */
    private data class Unwrapped(
        val pred: P.PredicateContext,
        val negated: Boolean,
    )

    private fun unwrapPredicate(where: P.ExprContext): Unwrapped? {
        val or = where.orExpr()
        if (or.andExpr().size != 1) return null
        val and = or.andExpr(0)
        if (and.notExpr().size != 1) return null
        val notE = and.notExpr(0)
        val neg = notE.NOT() != null
        // One clean level: `NOT <pred>` unwraps to the inner predicate; a bare predicate is itself.
        val pred = if (neg) notE.notExpr()?.predicate() else notE.predicate()
        return pred?.let { Unwrapped(it, neg) }
    }

    /**
     * The correlation for `x IN (SELECT y …)`: `left.x = right.y`, where `y` is the subquery's
     * single output column. Null (unconditioned) when either side isn't a plain single column.
     */
    private fun inCorrelation(
        pred: P.InPredicateContext,
        subQ: P.QueryExpressionContext,
        ap: Map<String, String>,
    ): Expression? {
        val lhs = exprFolder.foldAdd(pred.addExpr(), ap) as? ColumnRef ?: return null
        val rightCol = singleOutputColumn(subQ) ?: return null
        return FunctionCall(
            CatalogId.EQ,
            listOf(
                ColumnRef(port = "left", column = lhs.column, location = lhs.location),
                ColumnRef(port = "right", column = rightCol, location = lhs.location),
            ),
            lhs.location,
        )
    }

    /** The single projected column of a `SELECT col FROM …` subquery, or null if it isn't one. */
    private fun singleOutputColumn(qe: P.QueryExpressionContext): String? {
        val core = qe.selectCore().singleOrNull() as? P.SelectQueryContext ?: return null
        val item = core.selectList().selectItem().singleOrNull() as? P.ExprItemContext ?: return null
        return (exprFolder.fold(item.expr(), emptyMap()) as? ColumnRef)?.column
    }

    private fun semiAntiJoin(
        leftName: String,
        rightName: String,
        type: String,
        on: Expression?,
        at: SourceLocation,
    ): ChainElem =
        opCall(
            "join",
            listOfNotNull(
                refArg("left", leftName, at),
                refArg("right", rightName, at),
                on?.let { namedArg("on", it, at) },
                refArg("type", type, at),
            ),
            null,
            at,
        )

    private fun baseRefName(elems: List<ChainElem>): String? =
        (elems.singleOrNull() as? DottedRef)?.parts?.firstOrNull()

    // ---- helpers ----

    private fun materialize(
        chain: Chain,
        at: SourceLocation,
    ): String {
        (chain.elements.singleOrNull() as? DottedRef)?.let { return it.parts.first() }
        val name = synthName()
        pre += assign(name, chain, at)
        return name
    }

    private fun selectHasAggregate(list: P.SelectListContext): Boolean =
        list.selectItem().any { it is P.ExprItemContext && containsAggregate(exprFolder.fold(it.expr(), emptyMap())) }

    private fun containsAggregate(e: Expression): Boolean =
        when (e) {
            is AggregateCall -> true
            is org.tatrman.ttrp.expr.FunctionCall -> e.args.any { containsAggregate(it) }
            is org.tatrman.ttrp.expr.Cast -> containsAggregate(e.expr)
            else -> false
        }

    private fun fromItemName(fi: P.FromItemContext): Pair<String, String?> {
        val ref = fi.tableRef()
        val name = (listOf(sqlName(ref.identifier().text)) + ref.namePart().map { sqlName(it.text) }).joinToString(".")
        val alias = fi.alias?.text?.let { sqlName(it) }
        return name to alias
    }

    /** A table only counts as a derived in-port when it is a bare name (not a CTE, not a qname). */
    private fun noteExternal(name: String) {
        if (!isCte(name) && !name.contains(".")) derived += name
    }

    /** The chain head for a FROM-single-table: a qname → `load(qname)`; a bare name (in-port/CTE) → a ref. */
    private fun baseHead(
        name: String,
        at: SourceLocation,
    ): ChainElem = if (name.contains(".") && !isCte(name)) loadCall(name, at) else dref(name, at)

    /** A join operand: a bare in-port/CTE name is referenced directly; a qname is loaded to a synth name. */
    private fun operandRef(
        name: String,
        at: SourceLocation,
    ): String {
        if (!name.contains(".") || isCte(name)) return name
        val n = synthName()
        pre += assign(n, Chain(listOf(loadCall(name, at)), at), at)
        return n
    }

    private fun loadCall(
        qname: String,
        at: SourceLocation,
    ): OpCall {
        val parts = qname.split(".")
        val ref =
            ColumnRef(port = parts.dropLast(1).joinToString(".").ifEmpty { null }, column = parts.last(), location = at)
        return opCall("load", listOf(Arg(null, ExprArg(ref, at), at)), null, at)
    }

    private fun joinKind(jc: P.JoinClauseContext): String {
        val k = jc.joinKind() ?: return "inner"
        return when {
            k.LEFT() != null -> "left"
            k.RIGHT() != null -> "right"
            k.FULL() != null -> "full"
            k.CROSS() != null -> "cross"
            else -> "inner"
        }
    }

    private fun isCte(name: String): Boolean = name in cteNames

    private fun synthName(): String = "_sq${synth++}"

    private fun drainPre(): List<Statement> {
        val p = pre.toList()
        pre.clear()
        return p
    }

    // ---- canonical AST builders ----

    private fun assign(
        target: String,
        chain: Chain,
        at: SourceLocation,
    ): Assignment = Assignment(target = target, targetLocation = at, chain = chain, location = at)

    private fun opCall(
        name: String,
        args: List<Arg>,
        config: ConfigBlock?,
        at: SourceLocation,
    ): OpCall = OpCall(name, args, config, at)

    private fun dref(
        name: String,
        at: SourceLocation,
    ): DottedRef = DottedRef(name.split("."), at)

    private fun namedArg(
        name: String?,
        expr: Expression,
        at: SourceLocation,
    ): Arg = Arg(name, ExprArg(expr, at), at)

    private fun refArg(
        name: String?,
        ref: String,
        at: SourceLocation,
    ): Arg = Arg(name, ExprArg(ColumnRef(port = null, column = ref, location = at), at), at)
}
