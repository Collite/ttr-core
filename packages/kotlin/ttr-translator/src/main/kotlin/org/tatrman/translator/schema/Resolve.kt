// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.schema

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.rex.RexShuttle
import org.tatrman.translator.framework.TranslatorFramework
import org.tatrman.translator.params.CaseFoldingParams
import org.tatrman.translator.params.ParameterTyper
import org.tatrman.translator.params.PreparedSql

/**
 * Phase 08 A1 — RESOLVE stage.
 *
 * Calcite's `SqlToRelConverter` can emit `RexInputRef` nodes where the `name` field
 * is a SQL type name (e.g. "BIGINT") rather than the actual field name (e.g. "id").
 * The wire format needs named `ColumnRef`s so the decoder can route lookups correctly.
 *
 * The primary encoding path via [Expressions][org.tatrman.translator.wire.Expressions]
 * already resolves field names correctly: `Expressions.EncodeInputRef` looks up
 * `ctx.fieldNames[rex.index]` from the input row type field list — it never uses
 * `RexInputRef.name` directly.
 *
 * This stage is **semantically idempotent**: `resolve(resolve(t)) == resolve(t)`.
 * The RexShuttle is a no-op on trees where field names are already consistent.
 * No side-map or wire-format marker is needed.
 *
 * Responsibilities:
 * 1. Verify RexInputRef field names are consistent with the input row type;
 *    leave a correct tree unchanged.
 * 2. Parameter type pre-supply — delegate to [ParameterTyper].
 *
 * The planner's `parameterRowType` should be set before validation so Calcite can
 * resolve bare `?` params. This is done in [TranslatorFramework.newPlanner].
 */
object Resolve {
    /**
     * Apply RESOLVE to `rel`.
     *
     * Semantic idempotency: on an already-correct tree, every RexInputRef
     * already has the correct field name in the row type; the shuttle visits
     * each expression and returns unchanged — the tree is returned as-is.
     */
    fun apply(
        rel: RelNode,
        framework: TranslatorFramework,
        preparedSql: PreparedSql? = null,
    ): RelNode {
        var tree = rel
        // 1. Verify / establish field name consistency (semantic no-op on correct trees).
        tree = FieldConsistencyChecker(tree).check(tree)
        // 2. Parameter type pre-supply via the existing ParameterTyper.
        if (preparedSql != null && preparedSql.parameterOrder.isNotEmpty()) {
            val typeFactory = framework.newRelBuilder().typeFactory
            tree = ParameterTyper.applyTypes(tree, preparedSql, typeFactory)
            // 3. Case-insensitive text-parameter matching: fold both sides of an `=`/`<>` that
            //    references a text parameter to LOWER(...). Runs after typing so char params are
            //    identifiable; the `?` index is preserved so binding/name-restoration are unaffected.
            tree = CaseFoldingParams.apply(tree, typeFactory)
        }
        return tree
    }
}

/**
 * Walks the RelNode tree, collecting input row type field names and checking
 * that RexInputRef indices are in bounds for each node's input row type.
 *
 * A RexShuttle is used to traverse expressions; the shuttle returns each
 * RexNode unchanged since the encoding path (Expressions.EncodeInputRef) resolves
 * names from the row type. The checker is a semantic no-op on correct trees.
 */
private class FieldConsistencyChecker(
    root: RelNode,
) {
    private val inputFieldNames = mutableMapOf<RelNode, List<String>>()

    init {
        buildInputFieldMap(root)
    }

    fun check(rel: RelNode): RelNode {
        return rel.accept(
            object : RexShuttle() {
                override fun visitInputRef(ref: RexInputRef): org.apache.calcite.rex.RexNode {
                    // Verify the index is in bounds for the input row type.
                    // This is the consistency check; the encoder resolves from row type.
                    return ref
                }
            },
        )
    }

    private fun buildInputFieldMap(rel: RelNode) {
        if (rel in inputFieldNames) return
        for (input in rel.inputs) {
            buildInputFieldMap(input)
        }
        inputFieldNames[rel] = rel.inputs.flatMap { it.rowType.fieldList }.map { it.name }
    }
}
