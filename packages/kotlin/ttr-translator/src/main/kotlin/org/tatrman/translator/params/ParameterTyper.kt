// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.params

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexDynamicParam
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.translator.framework.RelTreeRexShuttle

/**
 * Phase 08 A2 — parameter type pre-supply via [RelTreeRexShuttle].
 *
 * Calcite's validator infers `RexDynamicParam` types from surrounding context. When the context
 * is ambiguous (`? > ?`, `WHERE id = ?` where `id` is itself ANY-typed, or a bare select-list
 * `SELECT ?`) the param ends up ANY-typed; that survives encoding (the wire format's
 * `result_type` becomes `"unknown:ANY"`) and the decoder re-emits an ANY-typed param which
 * mis-validates downstream when the rel is rebuilt.
 *
 * [applyTypes] walks the rel tree (sub-query bodies included) once and replaces every `RexDynamicParam`
 * with a fresh one whose `RelDataType` matches the type declared in the matching [SqlParam].
 * Indices that don't have a matching `SqlParam` are left alone (handled by Calcite's inference).
 *
 * It also **unwraps** any `CAST(? AS T)` whose sole operand is a parameter back to a typed bare
 * `?`. The orchestrator wraps placeholders in `CAST(? AS T)` (via
 * [ParameterBridge.prepareSqlForCalcite] `typed = true`) to satisfy validation in contexts Calcite
 * can't infer — chiefly inside `||` / `CONCAT`. Those casts are a validation-only crutch: keeping
 * them in the rel would leak a `CAST(? AS VARCHAR)` into the executed SQL (and, on dialects whose
 * default `VARCHAR` length is short, truncate the search term). Unwrapping leaves the param typed
 * but cast-free, so the regenerated SQL is identical to the bare-`?` form.
 *
 * Sequencing: this is intended to run **after** the planner produces the validated rel and
 * **before** [org.tatrman.translator.wire.PlanNodeEncoder.encode], so the wire-format param picks up
 * the declared type. The Translator orchestrator (Section J, v1.1) is the natural caller; until
 * the orchestrator lands, downstream pipelines invoke this directly.
 */
object ParameterTyper {
    /**
     * Walk [rel], re-typing every [RexDynamicParam] whose positional index resolves to a known
     * [SqlParam]. Returns the rewritten rel — structurally equivalent to the input when no
     * params needed re-typing.
     *
     * @param rel the validated rel produced by Calcite
     * @param parameterOrder positional → parameter name (see [PreparedSql.parameterOrder])
     * @param valuesByName name → [SqlParam] (see [PreparedSql.values])
     * @param typeFactory the Calcite type factory (the same one the planner used; usually
     *   obtained via the framework's RelBuilder)
     */
    fun applyTypes(
        rel: RelNode,
        parameterOrder: List<String>,
        valuesByName: Map<String, SqlParam>,
        typeFactory: RelDataTypeFactory,
    ): RelNode {
        if (parameterOrder.isEmpty()) return rel
        val rexBuilder = RexBuilder(typeFactory)
        // Every `?` in the tree, sub-query bodies included: Calcite numbers them across the whole
        // statement, so a sub-query's `?` maps through [parameterOrder] like any other.
        return object : RelTreeRexShuttle() {
            override fun visitDynamicParam(dynamicParam: RexDynamicParam): RexNode = retype(dynamicParam)

            override fun visitCall(call: RexCall): RexNode {
                // Unwrap a validation-only `CAST(? AS T)` whose sole operand is a known
                // parameter back to a typed bare `?`. Real casts (over any non-param operand)
                // and casts over an unmapped param index fall through to the default recursion.
                if (call.kind == SqlKind.CAST && call.operands.size == 1) {
                    val operand = call.operands[0]
                    if (operand is RexDynamicParam &&
                        operand.index in parameterOrder.indices &&
                        valuesByName.containsKey(parameterOrder[operand.index])
                    ) {
                        return retype(operand)
                    }
                }
                return super.visitCall(call)
            }

            private fun retype(dynamicParam: RexDynamicParam): RexNode {
                val idx = dynamicParam.index
                if (idx !in parameterOrder.indices) return dynamicParam
                val name = parameterOrder[idx]
                val declared = valuesByName[name] ?: return dynamicParam
                val targetType = toRelDataType(declared.type, typeFactory)
                if (dynamicParam.type.sqlTypeName == targetType.sqlTypeName) return dynamicParam
                return rexBuilder.makeDynamicParam(targetType, idx)
            }
        }.rewrite(rel)
    }

    /**
     * Convenience overload — takes a [PreparedSql] directly. Equivalent to calling [applyTypes]
     * with `prepared.parameterOrder` and `prepared.values`.
     */
    fun applyTypes(
        rel: RelNode,
        prepared: PreparedSql,
        typeFactory: RelDataTypeFactory,
    ): RelNode = applyTypes(rel, prepared.parameterOrder, prepared.values, typeFactory)

    /**
     * Surface parameter type → [RelDataType]. Resolves through the shared [SurfaceTypeMapping]
     * table (N9) so this and [ParameterBridge.calciteSqlType] cannot drift; unknown surface types
     * fall back to [SurfaceTypeMapping.UNKNOWN_REL_TYPE] (`ANY` — Calcite infers it). DECIMAL carries
     * an explicit precision/scale (M3) so a decimal param isn't truncated to scale 0.
     */
    private fun toRelDataType(
        surfaceType: String,
        factory: RelDataTypeFactory,
    ): RelDataType {
        val sqlTypeName =
            SurfaceTypeMapping.sqlTypeNameOrNull(surfaceType) ?: SurfaceTypeMapping.UNKNOWN_REL_TYPE
        return if (sqlTypeName == SqlTypeName.DECIMAL) {
            factory.createSqlType(
                SqlTypeName.DECIMAL,
                SurfaceTypeMapping.DECIMAL_PRECISION,
                SurfaceTypeMapping.DECIMAL_SCALE,
            )
        } else {
            factory.createSqlType(sqlTypeName)
        }
    }
}
