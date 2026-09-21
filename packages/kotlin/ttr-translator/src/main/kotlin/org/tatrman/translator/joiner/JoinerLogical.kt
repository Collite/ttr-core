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
import org.tatrman.translator.joiner.JoinPolicy.SideRef
import org.tatrman.translator.joiner.JoinPolicy.Verdict
import org.tatrman.translator.wire.Expressions

/**
 * Phase 08 B2 — EXPAND_JOINS (logical) — Section C. MJ (model joins): the **wire carrier**.
 *
 * Walks the wire-form `PlanNode` looking for [JoinNode]s with no condition — the comma list / `CROSS JOIN`
 * (Calcite converts both to a `TRUE`-conditioned join, the encoder drops the always-true condition) and the
 * `ON TRUE` the SQL carrier `ModelJoinRewriter` leaves on a bare join it could not resolve. For each such
 * Join whose sides both expose entity Scans (`ScanNode(ER, …)`), asks the shared [JoinPolicy] which declared
 * [org.tatrman.translator.framework.ModelRelation] joins them and inserts the equality condition.
 *
 * ## Operates on proto, not RelNode
 *
 * Same architectural choice as [org.tatrman.translator.schema.Unfold]: working on the wire form
 * sidesteps Calcite cluster-mixing and the protected `LogicalJoin` constructor. The single
 * decode happens at the orchestrator boundary.
 *
 * ## Sides, verdicts, condition (contracts §2–§3)
 *
 * - A side's entity set = the `Scan(ER)` leaves reachable through `Join` and `Filter` nodes only
 *   ([collectEntityScans]). Narrower than the v1.0 first-scan search, which descended `Project` /
 *   `Aggregate` / `Subquery` and could emit a bare-name ref to a column a derived table no longer exposes
 *   (a hard `field not found` at decode instead of a warning). A side that exposes a table instead → the join
 *   is passed through (mixed-schema preservation per §96; `JoinerPhysical` may still condition table ↔ table).
 *   A side that exposes nothing at all (a derived table) → [JoinerWarning.NoRelation] with that side empty
 *   (review-097 R2): the Cartesian product is reported, never silent.
 * - The join is decided against the **whole** set of each side (⚑MJ-2): `((kp ⋈ dm) ⋈ z)` matches `z`
 *   against `{kp, dm}`, so a chain resolves through `dodací_místo → zákazník`; v1.0 matched the first scan
 *   only and warned `NoRelation` for every chain past two entities.
 * - `Resolved` → **collision guard** (F3b): each chosen attribute name must be unique across the entities
 *   of the side it is read from, because the ref is a bare `$L`/`$R` name and the decoder returns the FIRST
 *   holder. A collision leaves the join unconditioned with [JoinerWarning.KeyNameCollision].
 * - Condition: `eq($L.a, $R.b)`; `and(eq, eq, …)` for a composite relation (⚑MJ-7 — v1.0 used the first
 *   pair only). A single pair stays a bare `eq`, byte-identical with the pre-MJ plans.
 *
 * | Verdict | Behaviour |
 * |---|---|
 * | exactly one candidate `(l, r, rel)` | condition inserted (unless a key-name collision) |
 * | zero | Cartesian preserved; [JoinerWarning.NoRelation] |
 * | two or more, or an entity repeated across the join (⚑MJ-6) | Cartesian preserved; [JoinerWarning.AmbiguousRelations] |
 * | one, without join pairs | unconditioned; [JoinerWarning.RelationWithoutJoinPairs] (JoinerPhysical fills from the FK) |
 *
 * Join nodes that already carry a `condition` are passed through unchanged (idempotency; this is also how
 * a join the SQL carrier conditioned crosses this stage). This carrier is the **single source of join
 * warnings** — the SQL carrier records nothing (architecture §2.2). Path inference (gated v2) plugs into
 * [JoinPolicy.resolve], not here.
 */
object JoinerLogical {
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): JoinerResult {
        val outcomes = mutableListOf<JoinOutcome>()
        val rewritten = walk(plan, emptyList(), model, outcomes)
        return JoinerResult(plan = rewritten, warnings = outcomes.mapNotNull { it.warning }, outcomes = outcomes)
    }

    private fun walk(
        plan: PlanNode,
        path: List<Int>,
        model: ModelHandle,
        outcomes: MutableList<JoinOutcome>,
    ): PlanNode {
        var child = 0
        val withChildren = JoinerPlanWalker.rewriteChildren(plan) { walk(it, path + child++, model, outcomes) }
        if (withChildren.nodeCase != PlanNode.NodeCase.JOIN) return withChildren
        val warnings = mutableListOf<JoinerWarning>()
        val decided = decide(withChildren, model, warnings)
        if (decided !== withChildren) {
            outcomes += JoinOutcome(path, null)
        } else {
            warnings.forEach { outcomes += JoinOutcome(path, it) }
        }
        return decided
    }

    /** Decide one join node whose children are already rewritten; records at most one warning. */
    private fun decide(
        withChildren: PlanNode,
        model: ModelHandle,
        warnings: MutableList<JoinerWarning>,
    ): PlanNode {
        val join = withChildren.join
        // Idempotency: a Join already carrying a condition is left alone.
        if (join.hasCondition()) return withChildren

        val left = collectEntityScans(join.left)
        val right = collectEntityScans(join.right)
        if (left.isEmpty() && right.isEmpty()) return withChildren
        if (left.isEmpty() || right.isEmpty()) {
            // Mixed-schema preservation: only act on entity ↔ entity pairs. Section E handles
            // table ↔ table; entity ↔ table is left for the user to express explicitly.
            val opaque = if (left.isEmpty()) join.left else join.right
            if (hasScan(opaque)) return withChildren
            // review-097 R2 (contracts §1 row 5): entity ↔ derived table (a Project / Aggregate / Subquery
            // the narrowed descent does not look into) is a Cartesian product nobody declared — say so;
            // the empty side is rendered as "a derived table" and carries no QualifiedName.
            val entities = left.ifEmpty { right }.map { it.entity }
            warnings +=
                JoinerWarning.NoRelation(
                    sideA = if (left.isEmpty()) QualifiedName.getDefaultInstance() else entities.first(),
                    sideB = if (right.isEmpty()) QualifiedName.getDefaultInstance() else entities.first(),
                    leftEntities = left.map { it.entity },
                    rightEntities = right.map { it.entity },
                )
            return withChildren
        }

        return when (val verdict = JoinPolicy.resolve(model, left, right)) {
            is Verdict.NoRelation -> {
                warnings +=
                    JoinerWarning.NoRelation(verdict.left.first(), verdict.right.first(), verdict.left, verdict.right)
                withChildren
            }
            is Verdict.Ambiguous -> {
                warnings +=
                    JoinerWarning.AmbiguousRelations(
                        sideA = verdict.left.first(),
                        sideB = verdict.right.first(),
                        candidateRelations = verdict.candidates.map { it.relation },
                        repeated = verdict.repeated,
                        leftEntities = verdict.left,
                        rightEntities = verdict.right,
                    )
                withChildren
            }
            is Verdict.WithoutPairs -> {
                // A relation bound only to its FK carries no attribute pairs to join on. Step aside rather
                // than guess: JoinerPhysical fills this join from that FK after MAP_TO_PHYSICAL.
                warnings +=
                    JoinerWarning.RelationWithoutJoinPairs(verdict.left.entity, verdict.right.entity, verdict.relation)
                withChildren
            }
            is Verdict.Resolved -> {
                val collision = keyNameCollision(model, verdict, left, right)
                if (collision != null) {
                    warnings += collision
                    withChildren
                } else {
                    withConditionSet(withChildren, buildCondition(verdict))
                }
            }
        }
    }

    /**
     * The entity `Scan(ER)` leaves of a join side, in tree order, reachable through `Join` and `Filter`
     * nodes only (design §3). The handle is the scan node itself.
     */
    internal fun collectEntityScans(plan: PlanNode): List<SideRef<PlanNode>> =
        when (plan.nodeCase) {
            PlanNode.NodeCase.SCAN ->
                if (plan.scan.getObject().schemaCode ==
                    SchemaCode.ER
                ) {
                    listOf(SideRef(plan.scan.getObject(), plan))
                } else {
                    emptyList()
                }
            PlanNode.NodeCase.JOIN -> collectEntityScans(plan.join.left) + collectEntityScans(plan.join.right)
            PlanNode.NodeCase.FILTER -> collectEntityScans(plan.filter.input)
            else -> emptyList()
        }

    /** Is any scan (entity or table) reachable through `Join` / `Filter`? False for an opaque (derived) side. */
    private fun hasScan(plan: PlanNode): Boolean =
        when (plan.nodeCase) {
            PlanNode.NodeCase.SCAN, PlanNode.NodeCase.TABLE_SCAN -> true
            PlanNode.NodeCase.JOIN -> hasScan(plan.join.left) || hasScan(plan.join.right)
            PlanNode.NodeCase.FILTER -> hasScan(plan.filter.input)
            else -> false
        }

    /**
     * F3b — the first pair whose attribute name is carried by more than one entity of its side, or null.
     * Checked against the model's attribute lists (the scans' `output_columns` may be empty on the wire).
     */
    private fun keyNameCollision(
        model: ModelHandle,
        verdict: Verdict.Resolved<PlanNode>,
        left: List<SideRef<PlanNode>>,
        right: List<SideRef<PlanNode>>,
    ): JoinerWarning.KeyNameCollision? {
        fun holders(
            side: List<SideRef<PlanNode>>,
            attribute: String,
        ): Int = side.count { ref -> model.attributes(ref.entity).any { it.name == attribute } }
        for ((leftAttr, rightAttr) in verdict.pairs) {
            val side =
                when {
                    holders(left, leftAttr) > 1 -> JoinerWarning.Side.LEFT
                    holders(right, rightAttr) > 1 -> JoinerWarning.Side.RIGHT
                    else -> continue
                }
            return JoinerWarning.KeyNameCollision(
                sideA = verdict.left.entity,
                sideB = verdict.right.entity,
                relation = verdict.relation,
                attribute = if (side == JoinerWarning.Side.LEFT) leftAttr else rightAttr,
                side = side,
            )
        }
        return null
    }

    /**
     * `eq(ColumnRef($L, leftAttr), ColumnRef($R, rightAttr))` per pair — `source_alias = $L` / `$R` so the
     * decoder routes each ref into the correct join input via [Expressions.LEFT_INPUT_TAG] /
     * [Expressions.RIGHT_INPUT_TAG] — wrapped in `and(…)` when the relation has more than one pair. The
     * bare attribute name stays valid after MAP_TO_PHYSICAL even when the column is renamed: that stage
     * aliases each renamed column back to its attribute name (DF-T05), so the condition resolves by
     * attribute name on both sides of the ER/DB boundary.
     */
    private fun buildCondition(verdict: Verdict.Resolved<PlanNode>): Expression {
        val equalities =
            verdict.pairs.map { (leftAttr, rightAttr) ->
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(ref(leftAttr, Expressions.LEFT_INPUT_TAG))
                            .addOperands(ref(rightAttr, Expressions.RIGHT_INPUT_TAG)),
                    ).build()
            }
        return if (equalities.size == 1) {
            equalities.single()
        } else {
            Expression
                .newBuilder()
                .setFunction(FunctionCall.newBuilder().setOperation("and").addAllOperands(equalities))
                .build()
        }
    }

    private fun ref(
        name: String,
        sourceAlias: String,
    ): Expression =
        Expression
            .newBuilder()
            .setColumnRef(ColumnRef.newBuilder().setName(name).setSourceAlias(sourceAlias))
            .build()

    private fun withConditionSet(
        plan: PlanNode,
        condition: Expression,
    ): PlanNode =
        plan
            .toBuilder()
            .setJoin(plan.join.toBuilder().setCondition(condition))
            .build()
}
