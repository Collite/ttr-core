// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.tatrman.plan.v1.ColumnValue
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWriteOp

/** The row-write wire vocabulary, checked before lowering and before SQL generation. */
internal object RowWriteShape {
    fun check(row: RowWrite) {
        if (row.op == RowWriteOp.ROW_WRITE_OP_UNSPECIFIED ||
            row.op == RowWriteOp.UNRECOGNIZED
        ) {
            fail("row_writes_op", "Unspecified row write operation")
        }
        if (row.op != RowWriteOp.INSERT &&
            !row.hasWhere()
        ) {
            fail("row_writes_where", "UPDATE/DELETE requires a where predicate")
        }
        if (row.op == RowWriteOp.INSERT &&
            (row.hasWhere() || row.readBeforeCount > 0)
        ) {
            fail("row_writes_insert", "INSERT takes neither where nor read_before")
        }
        if (row.op != RowWriteOp.INSERT && row.ifAbsent) fail("row_writes_if_absent", "if_absent is INSERT only")
        if ((row.op == RowWriteOp.DELETE) !=
            (row.valuesCount == 0)
        ) {
            fail("row_writes_values", "INSERT/UPDATE needs values; DELETE takes none")
        }
        val names = row.valuesList.map(ColumnValue::getName)
        if (names.size != names.toSet().size ||
            names.any(String::isEmpty)
        ) {
            fail("row_writes_values", "Duplicate or empty target column")
        }
        if (row.priorKeysCount > 0 &&
            (
                row.priorKeysCount != row.readBeforeCount ||
                    row.priorKeysList.any(String::isEmpty) ||
                    row.priorKeysCount != row.priorKeysList.toSet().size
            )
        ) {
            fail("row_writes_prior", "prior_keys must contain one non-empty, distinct key per read_before column")
        }
        if (row.readBeforeList.size !=
            row.readBeforeList.toSet().size
        ) {
            fail("row_writes_prior", "Duplicate read_before column")
        }

        row.valuesList.forEach { value(it.value) }
        if (row.hasWhere()) predicate(row.where)
    }

    private fun value(expr: Expression) {
        when (expr.exprCase) {
            Expression.ExprCase.PARAMETER -> Unit
            Expression.ExprCase.LITERAL ->
                if (!expr.literal.isNull &&
                    expr.literal.valueCase == Literal.ValueCase.VALUE_NOT_SET
                ) {
                    fail("row_writes_value", "Unset literal")
                }
            else -> fail("row_writes_value", "A row value must be a literal or parameter, got ${expr.exprCase}")
        }
    }

    private fun predicate(expr: Expression) {
        when (expr.exprCase) {
            Expression.ExprCase.COLUMN_REF -> Unit
            Expression.ExprCase.LITERAL, Expression.ExprCase.PARAMETER -> value(expr)
            Expression.ExprCase.FUNCTION -> {
                val op = expr.function.operation.lowercase()
                val args = expr.function.operandsList
                when (op) {
                    "and", "or" -> if (args.size < 2) fail("row_writes_expression", "$op needs at least two operands")
                    "eq", "ne", "lt", "le", "gt", "ge" ->
                        if (args.size != 2) fail("row_writes_expression", "$op needs two operands")
                    "in" -> {
                        if (args.size < 2) fail("row_writes_expression", "$op needs a column and values")
                        args.drop(1).forEach(::value)
                    }
                    "is_null", "is_not_null" ->
                        if (args.size !=
                            1
                        ) {
                            fail("row_writes_expression", "$op needs one operand")
                        }
                    else -> fail("row_writes_expression", "Unsupported row predicate operator $op")
                }
                args.forEach(::predicate)
            }
            else -> fail("row_writes_expression", "Unsupported row predicate ${expr.exprCase}")
        }
    }

    private fun fail(
        code: String,
        message: String,
    ): Nothing = throw RowWriteUnparseException(code, message)
}
