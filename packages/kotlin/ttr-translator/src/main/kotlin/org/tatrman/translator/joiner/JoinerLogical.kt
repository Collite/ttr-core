// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.wire.Expressions

/**
 * Phase 08 B2 — EXPAND_JOINS (logical) — Section C.
 *
 * Walks the wire-form `PlanNode` looking for [JoinNode]s with no condition (the "Cartesian
 * implied by FROM-list" case). For each such Join whose subtrees both contain an entity Scan
 * (`ScanNode(ER, ...)`), looks up a matching [ModelRelation] in [ModelHandle.relations] and
 * inserts the equivalent equality condition.
 *
 * ## Operates on proto, not RelNode
 *
 * Same architectural choice as [org.tatrman.translator.schema.Unfold]: working on the wire form
 * sidesteps Calcite cluster-mixing and the protected `LogicalJoin` constructor. The single
 * decode happens at the orchestrator boundary.
 *
 * ## Cardinality decision (v1.0)
 *
 * Per master plan §218 ("Resolved decisions"), v1.0 implements **direct relations only** — at
 * most one [ModelRelation] linking two entities. Transitive chaining (A → B → C inserts both
 * joins) is a v1.1 extension; the [RelationMatcher] interface below is the localised seam.
 *
 * ## Outcomes
 *
 * | Candidates between left and right entity | Behaviour |
 * |---|---|
 * | exactly one direct relation | join condition inserted |
 * | zero | Cartesian preserved; [JoinerWarning.NoRelation] |
 * | two or more | Cartesian preserved; [JoinerWarning.AmbiguousRelations] |
 *
 * Join nodes that already carry a `condition` are passed through unchanged (idempotency).
 * Subtrees with no ER scan on one or both sides are passed through (mixed-schema preservation
 * per §96).
 */
object JoinerLogical {
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): JoinerResult {
        val warnings = mutableListOf<JoinerWarning>()
        val rewritten = walk(plan, DirectRelationMatcher(model), warnings)
        return JoinerResult(plan = rewritten, warnings = warnings)
    }

    /**
     * The seam the master plan §218 calls out — v1.0 only resolves direct one-hop paths;
     * v1.1's transitive walker plugs in here as a drop-in replacement.
     */
    interface RelationMatcher {
        /**
         * Find the relation(s) between [from] and [to]. Returns:
         *   - empty list if no relation exists,
         *   - singleton list if exactly one direct relation matches (the success case),
         *   - multi-element list if more than one matches (ambiguity → Cartesian + warning).
         *
         * The matcher is symmetric: `findCandidates(A, B)` and `findCandidates(B, A)` return
         * the same set.
         */
        fun findCandidates(
            from: QualifiedName,
            to: QualifiedName,
        ): List<ModelRelation>
    }

    private class DirectRelationMatcher(
        private val model: ModelHandle,
    ) : RelationMatcher {
        override fun findCandidates(
            from: QualifiedName,
            to: QualifiedName,
        ): List<ModelRelation> =
            model.relations().filter { rel ->
                (rel.fromEntity == from && rel.toEntity == to) ||
                    (rel.fromEntity == to && rel.toEntity == from)
            }
    }

    private fun walk(
        plan: PlanNode,
        matcher: RelationMatcher,
        warnings: MutableList<JoinerWarning>,
    ): PlanNode {
        val withChildren = JoinerPlanWalker.rewriteChildren(plan) { walk(it, matcher, warnings) }
        if (withChildren.nodeCase != PlanNode.NodeCase.JOIN) return withChildren

        val join = withChildren.join
        // Idempotency: a Join already carrying a condition is left alone.
        if (join.hasCondition()) return withChildren

        val leftEntity = findFirstScan(join.left, SchemaCode.ER)
        val rightEntity = findFirstScan(join.right, SchemaCode.ER)
        if (leftEntity == null || rightEntity == null) {
            // Mixed-schema preservation: only act on entity ↔ entity pairs. Section E handles
            // table ↔ table; entity ↔ table is left for the user to express explicitly.
            return withChildren
        }

        val candidates = matcher.findCandidates(leftEntity, rightEntity)
        return when (candidates.size) {
            0 -> {
                warnings += JoinerWarning.NoRelation(leftEntity, rightEntity)
                withChildren
            }
            1 -> {
                val relation = candidates.single()
                if (relation.joinPairs.isEmpty()) {
                    // A relation bound only to its FK carries no attribute pairs to join on, and
                    // `buildEqualityCondition` would call `.first()` on none. Step aside rather than
                    // guess: JoinerPhysical fills this join from that FK after MAP_TO_PHYSICAL.
                    warnings += JoinerWarning.RelationWithoutJoinPairs(leftEntity, rightEntity, relation)
                    return withChildren
                }
                val condition = buildEqualityCondition(relation, leftEntity)
                withConditionSet(withChildren, condition)
            }
            else -> {
                warnings += JoinerWarning.AmbiguousRelations(leftEntity, rightEntity, candidates)
                withChildren
            }
        }
    }

    /**
     * Build an equality `Expression` that joins on the relation's first attribute pair, with
     * `source_alias = $L` / `$R` so the decoder routes each ColumnRef into the correct join input
     * via [Expressions.LEFT_INPUT_TAG] / [Expressions.RIGHT_INPUT_TAG]. v1.0 only handles a single
     * attribute pair; composite relations (multi-pair) wait for v1.1.
     */
    private fun buildEqualityCondition(
        relation: ModelRelation,
        leftEntity: QualifiedName,
    ): Expression {
        val (fromAttr, toAttr) = relation.joinPairs.first()
        // Attribute qnames carry `<entity>.<attribute>` in the `name` field — strip the entity
        // prefix to recover the bare attribute name that lives in the corresponding Scan's
        // row type. It stays valid after MAP_TO_PHYSICAL even when the column is renamed: that
        // stage aliases each renamed column back to its attribute name (DF-T05), so the condition
        // resolves by attribute name on both sides of the ER/DB boundary.
        val fromAttrName = fromAttr.name.substringAfterLast('.')
        val toAttrName = toAttr.name.substringAfterLast('.')
        val (leftAttrName, rightAttrName) =
            if (relation.fromEntity == leftEntity) {
                fromAttrName to toAttrName
            } else {
                toAttrName to fromAttrName
            }
        val leftRef =
            Expression
                .newBuilder()
                .setColumnRef(
                    ColumnRef
                        .newBuilder()
                        .setName(leftAttrName)
                        .setSourceAlias(Expressions.LEFT_INPUT_TAG),
                ).build()
        val rightRef =
            Expression
                .newBuilder()
                .setColumnRef(
                    ColumnRef
                        .newBuilder()
                        .setName(rightAttrName)
                        .setSourceAlias(Expressions.RIGHT_INPUT_TAG),
                ).build()
        return Expression
            .newBuilder()
            .setFunction(
                FunctionCall
                    .newBuilder()
                    .setOperation("eq")
                    .addOperands(leftRef)
                    .addOperands(rightRef),
            ).build()
    }

    private fun withConditionSet(
        plan: PlanNode,
        condition: Expression,
    ): PlanNode =
        plan
            .toBuilder()
            .setJoin(plan.join.toBuilder().setCondition(condition))
            .build()

    /**
     * Walk the subtree under [plan] and return the qname of the first `Scan` whose schema_code
     * matches [schemaCode], or `null` if none found. Used by both JoinerLogical (with `ER`) and
     * JoinerPhysical (with `DB` — see [findFirstScanPublic]).
     */
    private fun findFirstScan(
        plan: PlanNode,
        schemaCode: SchemaCode,
    ): QualifiedName? {
        if (plan.nodeCase == PlanNode.NodeCase.SCAN && plan.scan.getObject().schemaCode == schemaCode) {
            return plan.scan.getObject()
        }
        if (plan.nodeCase == PlanNode.NodeCase.TABLE_SCAN && plan.tableScan.table.schemaCode == schemaCode) {
            return plan.tableScan.table
        }
        return when (plan.nodeCase) {
            PlanNode.NodeCase.PROJECT -> findFirstScan(plan.project.input, schemaCode)
            PlanNode.NodeCase.FILTER -> findFirstScan(plan.filter.input, schemaCode)
            PlanNode.NodeCase.JOIN ->
                findFirstScan(plan.join.left, schemaCode) ?: findFirstScan(plan.join.right, schemaCode)
            PlanNode.NodeCase.AGGREGATE -> findFirstScan(plan.aggregate.input, schemaCode)
            PlanNode.NodeCase.SORT -> findFirstScan(plan.sort.input, schemaCode)
            PlanNode.NodeCase.LIMIT_OFFSET -> findFirstScan(plan.limitOffset.input, schemaCode)
            PlanNode.NodeCase.SUBQUERY -> findFirstScan(plan.subquery.subquery, schemaCode)
            else -> null
        }
    }

    /** Sibling accessor for [JoinerPhysical] — same logic, exposed by package contract. */
    internal fun findFirstScanPublic(
        plan: PlanNode,
        schemaCode: SchemaCode,
    ): QualifiedName? = findFirstScan(plan, schemaCode)
}
