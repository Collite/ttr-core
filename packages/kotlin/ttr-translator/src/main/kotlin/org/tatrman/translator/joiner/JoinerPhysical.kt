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
        val warnings = mutableListOf<JoinerWarning>()
        val rewritten = walk(plan, model, warnings)
        return JoinerResult(plan = rewritten, warnings = warnings)
    }

    private fun walk(
        plan: PlanNode,
        model: ModelHandle,
        warnings: MutableList<JoinerWarning>,
    ): PlanNode {
        val withChildren = JoinerPlanWalker.rewriteChildren(plan) { walk(it, model, warnings) }
        if (withChildren.nodeCase != PlanNode.NodeCase.JOIN) return withChildren

        val join = withChildren.join
        // Don't-double-join.
        if (join.hasCondition()) return withChildren

        val leftTable = JoinerLogical.findFirstScanPublic(join.left, SchemaCode.DB)
        val rightTable = JoinerLogical.findFirstScanPublic(join.right, SchemaCode.DB)
        if (leftTable == null || rightTable == null) return withChildren

        val candidates = matchingForeignKeys(model.foreignKeys(), leftTable, rightTable)
        return when (candidates.size) {
            0 -> {
                warnings += JoinerWarning.NoRelation(leftTable, rightTable)
                withChildren
            }
            1 -> {
                val condition =
                    buildEqualityCondition(
                        fk = candidates.single(),
                        leftTable = leftTable,
                        leftScan = findFirstTableScan(join.left),
                        rightScan = findFirstTableScan(join.right),
                    )
                withConditionSet(withChildren, condition)
            }
            else -> {
                // FK ambiguity is rare but possible (e.g. two FKs between the same two tables —
                // think `customer_id` + `billing_customer_id`). Same Cartesian fallback as
                // JoinerLogical's ambiguous case.
                warnings += JoinerWarning.AmbiguousRelations(leftTable, rightTable, emptyList())
                withChildren
            }
        }
    }

    /**
     * Return every FK whose `(from-table, to-table)` connects [a] and [b] (in either direction),
     * limited to v1.0's single-column FK shape.
     */
    private fun matchingForeignKeys(
        fks: List<ModelForeignKey>,
        a: QualifiedName,
        b: QualifiedName,
    ): List<ModelForeignKey> =
        fks.filter { fk ->
            if (fk.from.size != 1 || fk.to.size != 1) return@filter false
            val fromTable = fk.from.first().tableQname()
            val toTable = fk.to.first().tableQname()
            (fromTable == a && toTable == b) || (fromTable == b && toTable == a)
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

    private fun buildEqualityCondition(
        fk: ModelForeignKey,
        leftTable: QualifiedName,
        leftScan: TableScanNode?,
        rightScan: TableScanNode?,
    ): Expression {
        val fromCol = fk.from.first()
        val toCol = fk.to.first()
        val fromColName = fromCol.name.substringAfterLast('.')
        val toColName = toCol.name.substringAfterLast('.')
        // Orient: which column is on the left vs right input depends on which side of the join
        // the FK's source table happens to be on.
        val (leftColName, rightColName) =
            if (fromCol.tableQname() == leftTable) {
                fromColName to toColName
            } else {
                toColName to fromColName
            }
        val leftRef =
            Expression
                .newBuilder()
                .setColumnRef(
                    ColumnRef
                        .newBuilder()
                        .setName(visibleName(leftScan, leftColName))
                        .setSourceAlias(Expressions.LEFT_INPUT_TAG),
                ).build()
        val rightRef =
            Expression
                .newBuilder()
                .setColumnRef(
                    ColumnRef
                        .newBuilder()
                        .setName(visibleName(rightScan, rightColName))
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

    /**
     * The name [column] is reachable by above [scan]: its alias when the scan aliases it, the column
     * name otherwise. A column the scan does not declare keeps its physical name — the join then fails
     * at unparse naming that column, which is the honest outcome; guessing another column would not be.
     */
    private fun visibleName(
        scan: TableScanNode?,
        column: String,
    ): String =
        scan
            ?.outputColumnsList
            ?.firstOrNull { it.name == column }
            ?.alias
            ?.takeIf { it.isNotEmpty() }
            ?: column

    /**
     * The first `TableScan(DB, …)` NODE under [plan], found along the same path
     * [JoinerLogical.findFirstScanPublic] takes to find its table — so the scan whose aliases are read
     * is the one whose table the FK was matched against.
     */
    private fun findFirstTableScan(plan: PlanNode): TableScanNode? {
        if (plan.nodeCase == PlanNode.NodeCase.TABLE_SCAN && plan.tableScan.table.schemaCode == SchemaCode.DB) {
            return plan.tableScan
        }
        return when (plan.nodeCase) {
            PlanNode.NodeCase.PROJECT -> findFirstTableScan(plan.project.input)
            PlanNode.NodeCase.FILTER -> findFirstTableScan(plan.filter.input)
            PlanNode.NodeCase.JOIN -> findFirstTableScan(plan.join.left) ?: findFirstTableScan(plan.join.right)
            PlanNode.NodeCase.AGGREGATE -> findFirstTableScan(plan.aggregate.input)
            PlanNode.NodeCase.SORT -> findFirstTableScan(plan.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> findFirstTableScan(plan.limitOffset.input)
            PlanNode.NodeCase.SUBQUERY -> findFirstTableScan(plan.subquery.subquery)
            else -> null
        }
    }

    private fun withConditionSet(
        plan: PlanNode,
        condition: Expression,
    ): PlanNode =
        plan
            .toBuilder()
            .setJoin(plan.join.toBuilder().setCondition(condition))
            .build()
}
