// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import org.tatrman.translator.framework.RelTreeRexShuttle

/**
 * Drops the VARCHAR → VARCHAR casts Calcite wraps around a VARCHAR operand when it unifies types, before
 * the SQL Server unparse: a cast that is an operand of another call, whose target is a VARCHAR with no
 * length or with the operand's own length.
 *
 * The CASE that `COALESCE` expands to casts a column branch to the CASE's derived type, and implicit
 * coercion does the same. With the type name and the length unchanged (only nullability, charset or
 * collation differ), or the length widened to unbounded, the value never changes, and SQL Server derives
 * the same result from the bare operand (it types a CASE by its widest branch). Spelled for SQL Server,
 * though, it was a bug: T-SQL reads `CAST(x AS VARCHAR)` without a length as `VARCHAR(30)` and cut every
 * longer value without an error. The dialect now spells an unbounded VARCHAR `VARCHAR(MAX)`
 * ([org.tatrman.translator.dialects.MssqlSqlDialectWithFloatCast.getCastSpec]); this pass keeps the cast
 * out of the SQL altogether, and with it the LOB type `VARCHAR(MAX)` would give the CASE and the
 * down-conversion either cast applies to an `nvarchar` column (Calcite types `nvarchar(n)` as VARCHAR(n),
 * so `CAST([email] AS VARCHAR(320))` turned Unicode text outside the code page into `?`).
 *
 * Deliberately narrow:
 * * Only a cast that is an OPERAND of another call (a CASE branch, a comparison side, a function
 *   argument). A top-level Project expression keeps its cast — the Project's row type was derived from
 *   it, and Calcite asserts that the two agree; that cast renders as `VARCHAR(MAX)`.
 * * Only a target with no length or the operand's own length. Any other length — narrowing, widening,
 *   `VARCHAR(MAX)` (load-bearing in T-SQL: `CONCAT` of non-MAX strings stops at 8000 characters) — is kept as
 *   written. Calcite already drops an authored cast to the operand's own type at parse time, so what this
 *   pass removes is the validator's, never the author's.
 * * Only a VARCHAR operand. A CHAR → VARCHAR cast changes the trailing-space semantics on some engines,
 *   and a number → VARCHAR cast is a real conversion.
 * * `TRY_CAST` (SAFE_CAST) and sub-query operands are left alone.
 */
internal object VarcharCastElision {
    fun apply(rel: RelNode): RelNode = Shuttle().rewrite(rel)

    private class Shuttle : RelTreeRexShuttle() {
        override fun visitCall(call: RexCall): RexNode {
            val visited = super.visitCall(call)
            if (visited !is RexCall || visited.operands.none(::isRedundantVarcharCast)) return visited
            val operands = visited.operands.map { if (isRedundantVarcharCast(it)) (it as RexCall).operands[0] else it }
            return visited.clone(visited.type, operands)
        }
    }

    /** A CAST of a VARCHAR operand to a VARCHAR with no length, or with the operand's own length. */
    internal fun isRedundantVarcharCast(node: RexNode): Boolean {
        if (node !is RexCall || node.kind != SqlKind.CAST) return false
        val operand = node.operands[0].type
        val target = node.type
        return operand.sqlTypeName == SqlTypeName.VARCHAR &&
            target.sqlTypeName == SqlTypeName.VARCHAR &&
            (target.precision == RelDataType.PRECISION_NOT_SPECIFIED || target.precision == operand.precision)
    }
}
