// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import java.util.EnumSet
import org.apache.calcite.sql.SqlFunctionCategory
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.SqlOperatorTable
import org.apache.calcite.sql.SqlSyntax
import org.apache.calcite.sql.validate.SqlNameMatcher
import org.apache.calcite.sql.`fun`.SqlLibrary
import org.apache.calcite.sql.`fun`.SqlLibraryOperatorTableFactory
import org.apache.calcite.sql.util.SqlOperatorTables
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle

/**
 * The Calcite [SqlOperatorTable]s the translator loads for validation.
 *
 * Calcite already defines each engine's non-standard functions, correctly typed, in its per-library
 * operator tables ([SqlLibrary.MSSQL], [SqlLibrary.POSTGRESQL], …). Rather than hand-register one
 * function at a time we load the built-in library tables wholesale (decision D2), so the full T-SQL
 * function surface — `CONCAT`, `IIF`, `ISNULL`, `CHOOSE`, `LEFT`/`RIGHT`, `LEN`, `CHARINDEX`,
 * `DATEADD`/`DATEDIFF`/`DATEPART`, `TRY_CAST`, … — resolves and validates. (Ported from ai-platform
 * `query-translator` `CalciteOperatorTables`, trimmed to the operators tatrman actually overrides.)
 *
 * **Dialect scoping.** The design goal is to load only the *source* engine's operators; until the
 * source dialect is threaded into [org.tatrman.translator.framework.TranslatorFramework],
 * [permissiveUnion] loads STANDARD + MSSQL + POSTGRESQL together (POSTGRESQL also covers the
 * Postgres-compatible DuckDB target we emit).
 */
object CalciteOperatorTables {
    /**
     * The permissive parse-time operator set: our custom overrides first, then standard SQL + the
     * MS SQL and PostgreSQL library operators.
     *
     * **Custom-first ordering (the collision policy).** [CustomOperators] (postfix `COLLATE`, the
     * faithful `CONVERT`/`TRY_CONVERT`, and the faithful T-SQL `IIF`/`ISNULL`/`CHOOSE`/`LEN`/… that
     * preserve their spelling over Calcite's rewrites) and [PlatformOperators] (the grounding
     * catalog) chain BEFORE the library tables, so that where a name collides with a Calcite built-in
     * — chiefly our
     * faithful `CONVERT` vs the standard SQL `CONVERT(e USING charset)` and the library's lossy
     * `MSSQL_CONVERT` — validation overload resolution picks ours.
     *
     * **STANDARD is loaded via the factory, not chained separately.** The factory chains
     * [org.apache.calcite.sql.`fun`.SqlStdOperatorTable] exactly once when STANDARD is requested;
     * chaining it a second time would list each standard operator twice, and Calcite's early
     * resolution of builtins (the `overloads.size() == 1` guard) silently stops firing on a
     * duplicated name — which breaks the `COUNT(*)` star special-casing. So we never chain
     * `SqlStdOperatorTable` ourselves.
     */
    val permissiveUnion: SqlOperatorTable by lazy {
        SqlOperatorTables.chain(
            CustomOperators.table,
            PlatformOperators.OPERATOR_TABLE,
            ShadowedOperatorTable(
                libraryTableFor(EnumSet.of(SqlLibrary.STANDARD, SqlLibrary.MSSQL, SqlLibrary.POSTGRESQL)),
                REPLACED_LIBRARY_OPERATORS,
            ),
        )
    }

    /**
     * TF-P5 (contracts §3.6) — the operator set for a framework over [model]: the functions the model
     * declares ([ModelFunctions.tableFor]) chained before [permissiveUnion]. A model without functions
     * resolves exactly as [permissiveUnion].
     */
    fun forModel(
        model: ModelHandle,
        schemaCode: SchemaCode,
        namespace: String,
    ): SqlOperatorTable {
        val declared = ModelFunctions.tableFor(model, schemaCode, namespace, permissiveUnion)
        if (declared.operatorList.isEmpty()) return permissiveUnion
        return SqlOperatorTables.chain(declared, permissiveUnion)
    }

    /**
     * Library operators a [CustomOperators] entry *replaces* with the same name, kind and arity. Chain
     * order alone does not shadow those: with two same-kind candidates of equal arity Calcite's
     * type-precedence pass eliminates both ("No match found for function signature …"). TF-P1.S1:
     * `DATEDIFF` (see [DateOperators.DATEDIFF]).
     */
    private val REPLACED_LIBRARY_OPERATORS: Set<SqlOperator> =
        setOf(org.apache.calcite.sql.`fun`.SqlLibraryOperators.DATEDIFF)

    /** [delegate] with the [hidden] operator instances removed from every lookup and listing. */
    private class ShadowedOperatorTable(
        private val delegate: SqlOperatorTable,
        private val hidden: Set<SqlOperator>,
    ) : SqlOperatorTable {
        override fun lookupOperatorOverloads(
            opName: SqlIdentifier,
            category: SqlFunctionCategory?,
            syntax: SqlSyntax,
            operatorList: MutableList<SqlOperator>,
            nameMatcher: SqlNameMatcher,
        ) {
            val found = mutableListOf<SqlOperator>()
            delegate.lookupOperatorOverloads(opName, category, syntax, found, nameMatcher)
            found.filterTo(operatorList) { op -> hidden.none { it === op } }
        }

        override fun getOperatorList(): List<SqlOperator> =
            delegate.operatorList.filter { op -> hidden.none { it === op } }
    }

    /**
     * Construct the operator table for the given [libraries] via Calcite's caching
     * [SqlLibraryOperatorTableFactory] (repeated calls with the same set are cheap). Include
     * [SqlLibrary.STANDARD] to get standard SQL chained in (exactly once) alongside the library ops.
     */
    fun libraryTableFor(libraries: Set<SqlLibrary>): SqlOperatorTable =
        SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(libraries)
}
