// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.SqlOperatorTable
import org.apache.calcite.sql.SqlSyntax
import org.apache.calcite.sql.validate.SqlUserDefinedFunction
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle

/**
 * Single source of wire identity for function-syntax operators (master-plan §5.2, decision D4).
 *
 * The v1 wire format keys a [org.tatrman.plan.v1.FunctionCall] by the operator's lowercased name
 * (the encoder's `operator.name.lowercase()` fallback). On decode we must map that name back to a
 * Calcite [SqlOperator]. Hand-maintaining a `when` with hundreds of functions does not scale and
 * drifts from the operator table, so the catalog is built by enumerating the loaded operator
 * table's [SqlOperatorTable.getOperatorList] — one source of truth shared with the parse-time
 * operator set ([CalciteOperatorTables]).
 *
 * Scope of the catalog (what it owns vs. what stays explicit in
 * [org.tatrman.translator.wire.Expressions.operatorFor]):
 *  - **Owned here:** function-syntax operators (`SqlSyntax.FUNCTION` / `FUNCTION_ID` /
 *    `FUNCTION_STAR`) — `CONCAT`, `SUBSTRING`, `LEFT`, … These are emitted by the encoder's name
 *    fallback and decoded by catalog lookup.
 *  - **NOT owned here:** the structural/contract operators (`and`, `eq`, `add`, `||`, unary `-`, …).
 *    Those are *wire-contract* names, not Calcite names, and remain explicitly mapped in
 *    `operatorFor` so the contract can never silently change. The catalog is only consulted as a
 *    fallback after those explicit entries.
 *
 * **Collision policy.** Several libraries can define the same name (e.g. `CONCAT` resolves to
 * different operators across MySQL / MSSQL / Oracle). Within a single dialect-scoped set collisions
 * are rare; when one occurs we keep the first operator enumerated and ignore the rest (deterministic
 * given the operator table's stable ordering). This is the main reason the catalog is meant to be
 * dialect-scoped — see the lossiness note in master-plan §5.2. The wire format keys by name only,
 * so it cannot distinguish two same-named overloads; that is accepted for v1.
 */
class FunctionCatalog private constructor(
    private val byName: Map<String, SqlOperator>,
) {
    /** Look up the operator a wire `operation` string decodes to, or null if not catalogued. */
    fun lookup(operation: String): SqlOperator? = byName[operation.lowercase()]

    /** All catalogued (lowercased) function names — for coverage tests / diagnostics. */
    val names: Set<String> get() = byName.keys

    companion object {
        private val FUNCTION_SYNTAXES =
            setOf(SqlSyntax.FUNCTION, SqlSyntax.FUNCTION_ID, SqlSyntax.FUNCTION_STAR)

        /**
         * Build a catalog from the function-syntax operators in [table]. On a name collision the
         * first operator enumerated wins (see the class KDoc collision policy).
         */
        fun fromOperatorTable(table: SqlOperatorTable): FunctionCatalog {
            val byName: Map<String, SqlOperator> =
                table.operatorList
                    .filter { it.syntax in FUNCTION_SYNTAXES }
                    .groupBy(::wireName)
                    .mapValues { (_, ops) -> ops.first() }
            return FunctionCatalog(byName)
        }

        /**
         * The wire `operation` of a function-syntax [operator]: its lower-cased name, or for a
         * model-declared function (TF-P5) its lower-cased qualified identifier (`dbo.fn_price`), so a
         * decoded call finds the function of the right schema.
         */
        fun wireName(operator: SqlOperator): String =
            if (operator is SqlUserDefinedFunction) {
                operator.nameAsId.names
                    .joinToString(".")
                    .lowercase()
            } else {
                operator.name.lowercase()
            }

        /** TF-P5 (contracts §3.6) — the catalog of [CalciteOperatorTables.forModel]. */
        fun forModel(
            model: ModelHandle,
            schemaCode: SchemaCode,
            namespace: String,
        ): FunctionCatalog = forTable(CalciteOperatorTables.forModel(model, schemaCode, namespace))

        /** The catalog of a framework operator [table]: the shared [DEFAULT] for the permissive union. */
        fun forTable(table: SqlOperatorTable): FunctionCatalog =
            if (table === CalciteOperatorTables.permissiveUnion) DEFAULT else fromOperatorTable(table)

        /**
         * The default catalog for the Phase 0 interim permissive union (STANDARD + MSSQL +
         * POSTGRESQL). Built once. Replace with a dialect-scoped catalog when the source dialect is
         * threaded through (master-plan §10 Q2).
         */
        val DEFAULT: FunctionCatalog by lazy { fromOperatorTable(CalciteOperatorTables.permissiveUnion) }
    }
}
