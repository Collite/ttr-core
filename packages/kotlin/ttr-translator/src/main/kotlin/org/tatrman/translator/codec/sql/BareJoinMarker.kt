// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexUtil
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlInternalOperator
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlOperator.MDX_PRECEDENCE
import org.apache.calcite.sql.SqlWriter
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.tatrman.translator.framework.RelTreeRexShuttle
import org.tatrman.translator.wire.ConditionRequiredHint

/**
 * ttr-server#232 — the SQL carrier's mark on a bare join it could not condition, on a parse asked to refuse such a
 * join should it stay a Cartesian product (`Translator.parseToRelNode(refuseUnconditionedBareJoins = true)`).
 *
 * [ModelJoinRewriter] writes `ON <marker>` where it otherwise writes `ON TRUE`. To the validator and SqlToRel the
 * marker is a niladic `BOOLEAN NOT NULL` call, carried as the join's condition; it renders as `TRUE`, so the
 * `model_joins` SQL reads as before. [lift] then, before any rel pass, puts the plain `TRUE` back and moves the mark
 * onto the `LogicalJoin` as a [ConditionRequiredHint]: from there on the join is the same TRUE-conditioned join it
 * always was, and the encoder writes the hint as `JoinNode.condition_required`. No operator-table entry: the rewriter
 * binds this object directly, and [SqlInternalOperator.deriveType] does not re-resolve it by name.
 *
 * A marked INNER join that its WHERE already conditions (`FROM a JOIN b WHERE a.x = b.y`) is the author's join, written
 * in the wrong clause: [lift] moves that condition into its ON and drops the mark ([absorb]).
 */
object BareJoinMarker : SqlInternalOperator(
    "TTR_CONDITION_REQUIRED",
    SqlKind.OTHER,
    // A primary's precedence, so the writer never parenthesises it: `ON TRUE`, not `ON (TRUE)`.
    MDX_PRECEDENCE,
    false,
    ReturnTypes.BOOLEAN_NOT_NULL,
    null,
    OperandTypes.NILADIC,
) {
    fun call(pos: SqlParserPos): SqlNode = createCall(pos)

    override fun unparse(
        writer: SqlWriter,
        call: SqlCall,
        leftPrec: Int,
        rightPrec: Int,
    ) {
        writer.keyword("TRUE")
    }

    /**
     * [rel] with every marked join's condition back to `TRUE` and the mark on the join as a hint, sub-queries included;
     * a marked INNER join its WHERE conditions takes that condition as its ON, unmarked ([absorbInto]).
     */
    fun lift(rel: RelNode): RelNode =
        object : RelTreeRexShuttle() {
            override fun visitRel(rel: RelNode): RelNode =
                when {
                    rel is Join && isMarker(rel.condition) ->
                        rel
                            .copy(
                                rel.traitSet,
                                rel.cluster.rexBuilder.makeLiteral(true),
                                rel.left,
                                rel.right,
                                rel.joinType,
                                rel.isSemiJoinDone,
                            ).attachHints(listOf(ConditionRequiredHint.of()))
                    // Post-order: the joins below this Filter carry their marks already.
                    rel is Filter && rel.input is Join -> absorbInto(rel)
                    else -> rel
                }
        }.rewrite(rel)

    /**
     * `FROM a JOIN b WHERE a.x = b.y`: the author wrote the join's condition, only in WHERE. Each top-level conjunct of
     * [filter] that reads both sides of a marked INNER join below it, and nothing outside that join, moves into the ON
     * of the lowest such join, which loses its mark: it reaches the joiners conditioned, as `ON a.x = b.y` would. The
     * descent goes through INNER joins only, where a WHERE condition and an ON one mean the same. A marked outer join
     * keeps its mark: a WHERE condition on both of its sides drops exactly the rows the outer join exists to keep, so
     * the author meant ON. A conjunct with a sub-query stays in WHERE: one correlated to the Filter's row is bound by the
     * Filter, and would lose that binding in an ON.
     */
    private fun absorbInto(filter: Filter): RelNode {
        val conjuncts = RelOptUtil.conjunctions(filter.condition).toMutableList()
        val input = absorb(filter.input, 0, conjuncts, filter.cluster.rexBuilder)
        if (input === filter.input) return filter
        if (conjuncts.isEmpty()) return input
        return filter.copy(filter.traitSet, input, RexUtil.composeConjunction(filter.cluster.rexBuilder, conjuncts))
    }

    /**
     * [rel] with the [conjuncts] it takes moved into its marked joins (each removed from [conjuncts]). The conjuncts
     * reference the Filter's row, in which [rel]'s own row starts at [offset].
     */
    private fun absorb(
        rel: RelNode,
        offset: Int,
        conjuncts: MutableList<RexNode>,
        rexBuilder: RexBuilder,
    ): RelNode {
        if (rel !is Join || rel.joinType != JoinRelType.INNER) return rel
        val split = offset + rel.left.rowType.fieldCount
        val end = offset + rel.rowType.fieldCount
        // The inputs first: a conjunct belongs to the lowest join it spans.
        val left = absorb(rel.left, offset, conjuncts, rexBuilder)
        val right = absorb(rel.right, split, conjuncts, rexBuilder)
        val join =
            if (left === rel.left && right === rel.right) {
                rel
            } else {
                rel.copy(rel.traitSet, rel.condition, left, right, rel.joinType, rel.isSemiJoinDone)
            }
        if (!ConditionRequiredHint.isOn(join.hints)) return join
        val taken =
            conjuncts.filter { c ->
                val refs = RelOptUtil.InputFinder.bits(c).toList()
                refs.all { it in offset until end } &&
                    refs.any { it < split } &&
                    refs.any { it >= split } &&
                    RexUtil.SubQueryFinder.find(c) == null
            }
        if (taken.isEmpty()) return join
        conjuncts.removeAll { c -> taken.any { it === c } }
        return join
            .copy(
                join.traitSet,
                RexUtil.composeConjunction(rexBuilder, taken.map { RexUtil.shift(it, -offset) }),
                join.left,
                join.right,
                join.joinType,
                join.isSemiJoinDone,
            ).withHints(join.hints.filterNot { it.hintName == ConditionRequiredHint.NAME })
    }

    private fun isMarker(condition: RexNode): Boolean = condition is RexCall && condition.operator === BareJoinMarker
}
