// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexNode
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
 * in the wrong clause: [lift] drops its mark and leaves the plan as it is ([unmarkInto]).
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
     * a marked INNER join its WHERE conditions is left unmarked ([unmarkInto]).
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
                    rel is Filter && rel.input is Join -> unmarkInto(rel)
                    else -> rel
                }
        }.rewrite(rel)

    /**
     * `FROM a JOIN b WHERE a.x = b.y`: the author wrote the join's condition, only in WHERE. A marked INNER join below
     * [filter] that one of its top-level conjuncts [joins] loses its mark, and nothing else changes: the condition stays
     * in WHERE, and the joiners see the join exactly as on a parse not asked to refuse (so an FK-only relation still
     * takes its foreign key). The descent goes through INNER joins only, where a WHERE condition and an ON one mean the
     * same. A marked outer join keeps its mark: a WHERE condition on both of its sides drops exactly the rows the outer
     * join exists to keep, so the author meant ON.
     *
     * The plan is not changed here. Moving the conjunct into the ON instead (0.12.5) left the physical stage a
     * conditioned join, so an FK-only relation lost its foreign key, and a filter that only read both sides became the
     * whole join condition.
     */
    private fun unmarkInto(filter: Filter): RelNode {
        val input = unmark(filter.input, 0, RelOptUtil.conjunctions(filter.condition))
        return if (input === filter.input) filter else filter.copy(filter.traitSet, listOf(input))
    }

    /**
     * [rel] with the mark taken off each of its joins that one of [conjuncts] joins. The conjuncts reference the
     * Filter's row, in which [rel]'s own row starts at [offset].
     */
    private fun unmark(
        rel: RelNode,
        offset: Int,
        conjuncts: List<RexNode>,
    ): RelNode {
        if (rel !is Join || rel.joinType != JoinRelType.INNER) return rel
        val split = offset + rel.left.rowType.fieldCount
        val end = offset + rel.rowType.fieldCount
        val left = unmark(rel.left, offset, conjuncts)
        val right = unmark(rel.right, split, conjuncts)
        val join =
            if (left === rel.left && right === rel.right) {
                rel
            } else {
                rel.copy(rel.traitSet, rel.condition, left, right, rel.joinType, rel.isSemiJoinDone)
            }
        if (!ConditionRequiredHint.isOn(join.hints)) return join
        if (conjuncts.none { joins(it, offset until split, split until end) }) return join
        return join.withHints(join.hints.filterNot { it.hintName == ConditionRequiredHint.NAME })
    }

    /**
     * Whether [conjunct] joins the [left] side to the [right] one: a comparison other than `<>` (a `BETWEEN` arrives as
     * two) with one operand reading one side only and the other the other side only. A disjunction that reads both
     * sides (`a.year = 2000 OR b.qty > 100`) filters the Cartesian product, it does not join it. Only the comparison's
     * own column references count, so a condition inside a sub-query (`EXISTS`, `IN`) never does.
     */
    private fun joins(
        conjunct: RexNode,
        left: IntRange,
        right: IntRange,
    ): Boolean {
        if (conjunct !is RexCall || conjunct.kind !in JOINING || conjunct.operands.size != 2) return false
        val (a, b) = conjunct.operands.map { RelOptUtil.InputFinder.bits(it).toList() }

        fun reads(
            refs: List<Int>,
            side: IntRange,
        ) = refs.isNotEmpty() && refs.all { it in side }
        return (reads(a, left) && reads(b, right)) || (reads(a, right) && reads(b, left))
    }

    private val JOINING =
        setOf(
            SqlKind.EQUALS,
            SqlKind.IS_NOT_DISTINCT_FROM,
            SqlKind.LESS_THAN,
            SqlKind.LESS_THAN_OR_EQUAL,
            SqlKind.GREATER_THAN,
            SqlKind.GREATER_THAN_OR_EQUAL,
        )

    private fun isMarker(condition: RexNode): Boolean = condition is RexCall && condition.operator === BareJoinMarker
}
