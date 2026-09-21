// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.joiner

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.joiner.JoinPolicy.SideRef
import org.tatrman.translator.wire.Expressions

/**
 * Phase 08 B5 — EXPAND_JOINS (physical) — Section E.
 *
 * Mirrors [JoinerLogical] but operates on `TableScan(DB, ...)` pairs using
 * [ModelHandle.foreignKeys] instead of ER relations.
 *
 * Runs only when `targetSchema = DB`. By that point the tree has been through MAP_TO_PHYSICAL,
 * so ER scans are already rewritten to TableScans. JoinerPhysical fills in conditions for any
 * remaining unconditioned joins between two DB tables — which includes every entity join the
 * logical Joiner could not condition: no relation in the model, or a relation bound to its FK
 * with no attribute join pairs ([JoinerWarning.RelationWithoutJoinPairs]).
 *
 * ## The condition names what each scan EXPOSES, not what the table stores
 *
 * Wherever the model renames an attribute, MAP_TO_PHYSICAL rebuilds the scan's output columns as
 * `name = DB column, alias = ER attribute` (DF-T05, alias-at-boundary), and the decoder wraps that
 * scan in a Project producing the aliases — so above the scan only the alias exists. An FK names
 * physical columns. Each operand is therefore mapped through ITS OWN side's scan: the alias when
 * that scan aliases the column, the column name otherwise (a bare TableScan, or a column whose
 * attribute name already equals it — MAP_TO_PHYSICAL leaves the alias empty then).
 *
 * Emitting the FK's physical names unmapped failed at unparse with `field [d_date_sk] not found;
 * input fields are: [sk, …]` — on the first estate whose join keys were renamed.
 *
 * ## Sides (MJ-P2·S2)
 *
 * The same rule as [JoinerLogical]: a side's table set = the `TableScan(DB)` leaves reachable through
 * `Join` and `Filter` only, and the join is decided against the **whole** set of each side — a comma chain
 * `FROM a, b, c` with FKs `a→b`, `b→c` conditions the second join on `b ↔ c` (v1.0 matched the first scan
 * of the left side and warned). Exactly one matching FK over all `(l, r)` pairs → condition; zero →
 * [JoinerWarning.NoRelation]; two or more, or a table repeated across the join → [JoinerWarning.AmbiguousRelations]
 * (`candidateRelations` empty — FKs are not relations). A multi-column FK ANDs its columns (v1.0 skipped it
 * as "not the single-column shape" and warned NoRelation). The F3b collision guard applies here too, on the
 * names each scan EXPOSES (`output_columns` aliases, else the model's column list).
 *
 * ## Don't-double-join
 *
 * Per master plan §138, JoinerPhysical must not re-insert a condition that JoinerLogical
 * already supplied. The check is structural: a Join already carrying any condition is left alone.
 * JoinerLogical's condition names ER attributes, and MAP_TO_PHYSICAL does not rewrite it — per that
 * stage's own contract, upstream references (join conditions included) keep their attribute names
 * and resolve against the same scan aliases this Joiner targets.
 *
 * Idempotency: an already-conditioned Join is passed through unchanged.
 */
object JoinerPhysical {
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): JoinerResult {
        val outcomes = mutableListOf<JoinOutcome>()
        val rewritten = walk(plan, emptyList(), model, outcomes)
        return JoinerResult(plan = rewritten, warnings = outcomes.mapNotNull { it.warning }, outcomes = outcomes)
    }

    /**
     * A `TableScan(DB)` on a join side together with the alias-only projections between it and the join
     * ([projections], innermost first): MAP_TO_PHYSICAL wraps a query-backed entity's body in an
     * alias-at-boundary `Project` (`id_obchodního_kanálu := IDCENSKUP`), and the body itself usually
     * carries one. A column is visible above the join only under the name the outermost projection
     * gives it — [visibleName] maps it through; a column no projection exposes is not usable.
     */
    data class ScanRef(
        val scan: TableScanNode,
        val projections: List<Map<String, String>>,
    )

    /** One FK that could join a left scan to a right scan. */
    private data class Candidate(
        val left: SideRef<ScanRef>,
        val right: SideRef<ScanRef>,
        val fk: ModelForeignKey,
    )

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
        // Don't-double-join.
        if (join.hasCondition()) return withChildren

        val left = collectTableScans(join.left)
        val right = collectTableScans(join.right)
        if (left.isEmpty() || right.isEmpty()) return withChildren
        val leftTables = left.map { it.entity }
        val rightTables = right.map { it.entity }

        val repeated =
            (leftTables + rightTables)
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val fks = model.foreignKeys().filter { it.from.isNotEmpty() && it.from.size == it.to.size }
        val all =
            left.flatMap { l ->
                right.flatMap { r ->
                    fks.filter { fk -> connects(fk, l.entity, r.entity) }.map { fk -> Candidate(l, r, fk) }
                }
            }
        // MJ-P4·S2.6 — an FK whose column a projection does not expose cannot be used: drop it here so the
        // verdict is a warning, not a `field not found` at decode.
        val candidates =
            all
                .filter { it.left.entity !in repeated && it.right.entity !in repeated }
                .filter { c -> orientedVisiblePairs(c) != null }
        return when {
            repeated.isNotEmpty() || candidates.size > 1 -> {
                // FK ambiguity is rare but possible (two FKs between the same two tables — think
                // `customer_id` + `billing_customer_id`), or the same table on both sides. Same Cartesian
                // fallback as JoinerLogical's ambiguous case.
                warnings +=
                    JoinerWarning.AmbiguousRelations(
                        sideA = leftTables.first(),
                        sideB = rightTables.first(),
                        candidateRelations = emptyList(),
                        repeated = repeated,
                        leftEntities = leftTables,
                        rightEntities = rightTables,
                    )
                withChildren
            }
            candidates.isEmpty() -> {
                warnings += JoinerWarning.NoRelation(leftTables.first(), rightTables.first(), leftTables, rightTables)
                withChildren
            }
            else -> {
                val c = candidates.single()
                val pairs = checkNotNull(orientedVisiblePairs(c))
                val collision = keyNameCollision(c, pairs, left, right, model)
                if (collision != null) {
                    warnings += collision
                    withChildren
                } else {
                    withConditionSet(withChildren, buildCondition(pairs))
                }
            }
        }
    }

    /**
     * The `TableScan(DB)` leaves of a join side, in tree order, reachable through `Join`, `Filter` and
     * `Project` (MJ-P4·S2.6 — the [JoinerLogical.collectEntityScans] rule plus the projections
     * MAP_TO_PHYSICAL itself puts above a query-backed entity's body; the df-test `*__filter` entities).
     * A `Project` is recorded as a name map on the [ScanRef] — only its bare column-ref expressions expose
     * a name; a computed expression, an aggregate or a subquery hide the column, as the narrowed descent
     * intends. The handle is the scan node plus that map chain, which the condition resolves through.
     */
    internal fun collectTableScans(
        plan: PlanNode,
        projections: List<Map<String, String>> = emptyList(),
    ): List<SideRef<ScanRef>> =
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN ->
                if (plan.tableScan.table.schemaCode == SchemaCode.DB) {
                    listOf(SideRef(plan.tableScan.table, ScanRef(plan.tableScan, projections)))
                } else {
                    emptyList()
                }
            PlanNode.NodeCase.JOIN ->
                collectTableScans(plan.join.left, projections) + collectTableScans(plan.join.right, projections)
            PlanNode.NodeCase.FILTER -> collectTableScans(plan.filter.input, projections)
            PlanNode.NodeCase.PROJECT ->
                collectTableScans(
                    plan.project.input,
                    listOf(aliasMap(plan.project)) + projections,
                )
            else -> emptyList()
        }

    /** `input name → exposed name` for every bare column-ref expression of a Project; other expressions expose nothing. */
    private fun aliasMap(project: org.tatrman.plan.v1.ProjectNode): Map<String, String> =
        project.expressionsList
            .filter {
                it.expression.exprCase == Expression.ExprCase.COLUMN_REF &&
                    it.expression.columnRef.sourceAlias
                        .isEmpty()
            }.associate { ne ->
                ne.expression.columnRef.name to ne.alias.ifEmpty { ne.expression.columnRef.name }
            }

    /** Does [fk] connect tables [a] and [b] (either direction)? Every column of an FK belongs to one table. */
    private fun connects(
        fk: ModelForeignKey,
        a: QualifiedName,
        b: QualifiedName,
    ): Boolean {
        val fromTable = fk.from.first().tableQname()
        val toTable = fk.to.first().tableQname()
        return (fromTable == a && toTable == b) || (fromTable == b && toTable == a)
    }

    /**
     * v1 convention: column qnames are stored table-qualified as `<schema>.<namespace>.<table.column>`
     * — the `name` field contains `<table>.<column>`. Strip the column suffix to recover the
     * containing table's qname.
     */
    private fun QualifiedName.tableQname(): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(schemaCode)
            .setNamespace(namespace)
            .setName(name.substringBeforeLast('.'))
            .build()

    /**
     * The FK's column pairs as `(leftVisible, rightVisible)`: oriented to the join's sides (which side
     * holds the FK's source table) and mapped through each side's own scan and projections
     * ([visibleName]); null when a column is not exposed above the join.
     */
    private fun orientedVisiblePairs(c: Candidate): List<Pair<String, String>>? {
        val fromOnLeft =
            c.fk.from
                .first()
                .tableQname() == c.left.entity
        return c.fk.from.zip(c.fk.to).map { (fromCol, toCol) ->
            val fromName = fromCol.name.substringAfterLast('.')
            val toName = toCol.name.substringAfterLast('.')
            val (leftCol, rightCol) = if (fromOnLeft) fromName to toName else toName to fromName
            val l = visibleName(c.left.handle, leftCol) ?: return null
            val r = visibleName(c.right.handle, rightCol) ?: return null
            l to r
        }
    }

    /**
     * The name [column] is reachable by above the join: the scan's alias when it aliases the column (the
     * column name otherwise — a column the scan does not declare keeps its physical name, the honest
     * outcome; guessing another column would not be), then mapped through every projection on the way up.
     * Null when a projection does not expose it.
     */
    private fun visibleName(
        ref: ScanRef,
        column: String,
    ): String? {
        val atScan =
            ref.scan.outputColumnsList
                .firstOrNull { it.name == column }
                ?.alias
                ?.takeIf { it.isNotEmpty() }
                ?: column
        return ref.projections.fold(atScan, ::exposedAs)
    }

    /** The name [name] has above one projection ([map]: input name → exposed name), or null once it is hidden. */
    private fun exposedAs(
        name: String?,
        map: Map<String, String>,
    ): String? = name?.let { map[it] }

    /**
     * Every name a scan exposes above the join: its `output_columns` (alias, else name) or the model's
     * columns, mapped through the projections.
     */
    private fun visibleNames(
        ref: ScanRef,
        model: ModelHandle,
    ): Set<String> {
        val atScan =
            if (ref.scan.outputColumnsCount > 0) {
                ref.scan.outputColumnsList.map { it.alias.ifEmpty { it.name } }
            } else {
                model.columns(ref.scan.table).map { it.name }
            }
        return atScan
            .mapNotNull { name -> ref.projections.fold(name, ::exposedAs) }
            .toSet()
    }

    /** F3b on the physical side — see [JoinerLogical]: a bare `$L`/`$R` name must be unique on its side. */
    private fun keyNameCollision(
        c: Candidate,
        pairs: List<Pair<String, String>>,
        left: List<SideRef<ScanRef>>,
        right: List<SideRef<ScanRef>>,
        model: ModelHandle,
    ): JoinerWarning.KeyNameCollision? {
        fun holders(
            side: List<SideRef<ScanRef>>,
            name: String,
        ): Int = side.count { name in visibleNames(it.handle, model) }
        for ((leftName, rightName) in pairs) {
            val side =
                when {
                    holders(left, leftName) > 1 -> JoinerWarning.Side.LEFT
                    holders(right, rightName) > 1 -> JoinerWarning.Side.RIGHT
                    else -> continue
                }
            return JoinerWarning.KeyNameCollision(
                sideA = c.left.entity,
                sideB = c.right.entity,
                relation =
                    ModelRelation(
                        c.fk.from
                            .first()
                            .tableQname(),
                        c.fk.to
                            .first()
                            .tableQname(),
                        emptyList(),
                    ),
                attribute = if (side == JoinerWarning.Side.LEFT) leftName else rightName,
                side = side,
            )
        }
        return null
    }

    /** `eq($L.l, $R.r)` per pair, `and(…)` for a multi-column FK; a single column stays a bare `eq`. */
    private fun buildCondition(pairs: List<Pair<String, String>>): Expression {
        val equalities =
            pairs.map { (leftName, rightName) ->
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(ref(leftName, Expressions.LEFT_INPUT_TAG))
                            .addOperands(ref(rightName, Expressions.RIGHT_INPUT_TAG)),
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
