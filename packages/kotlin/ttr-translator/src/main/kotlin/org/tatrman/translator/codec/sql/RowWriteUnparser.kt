// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.codec.sql

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWriteOp
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.translator.dialects.Dialects
import org.tatrman.translate.v1.SqlDialect

/**
 * AU D-163 row DML. Each call returns one statement and its positional bindings, with exactly
 * one answer row (rows_affected bigint, prior text). The executor owns the ordered transaction.
 * Unlike StoreDmlUnparser this never deletes/reinserts an UPDATE target.
 *
 * A prior read is materialized and locked by key alone; the CAS predicate is evaluated on that
 * locked row, so a miss still reports prior and retains the lock. The modifying CTE depends on
 * prior through FROM/USING, rather than relying on textual CTE order. JSON contains only the
 * requested read_before columns, aliased to prior_keys (or read_before when empty), never the extra lock/key fields.
 */
object RowWriteUnparser {
    data class Statement(
        val sql: String,
        val parameters: List<ParameterBinding>,
    )

    fun unparse(
        row: RowWrite,
        dialect: SqlDialect,
        parameters: List<ParameterBinding> = emptyList(),
        keyColumns: List<String> = emptyList(),
    ): Statement {
        Dialects.requireRowWrites(dialect)
        checkRow(row)
        val bindings = parameters.associateBy { it.name }
        val positional = mutableListOf<ParameterBinding>()

        fun value(expr: Expression): String {
            val binding =
                when (expr.exprCase) {
                    Expression.ExprCase.PARAMETER ->
                        bindings[expr.parameter.name]
                            ?: fail("row_writes_parameter", "Missing parameter ${expr.parameter.name}")
                    Expression.ExprCase.LITERAL -> literal(expr.literal)
                    else -> fail("row_writes_value", "A row value must be a literal or parameter, got ${expr.exprCase}")
                }
            if (!binding.hasValue()) fail("row_writes_parameter", "Unbound parameter ${binding.name}")
            positional += binding
            return "?"
        }

        fun expression(
            expr: Expression,
            alias: String,
        ): String =
            when (expr.exprCase) {
                Expression.ExprCase.COLUMN_REF -> "$alias.${quote(expr.columnRef.name)}"
                Expression.ExprCase.PARAMETER, Expression.ExprCase.LITERAL -> value(expr)
                Expression.ExprCase.FUNCTION -> {
                    val fn = expr.function
                    val args = fn.operandsList
                    when (val op = fn.operation.lowercase()) {
                        "and", "or" -> {
                            if (args.size < 2) fail("row_writes_expression", "$op needs at least two operands")
                            args.joinToString(if (op == "and") " AND " else " OR ", "(", ")") { expression(it, alias) }
                        }
                        "eq", "ne", "neq", "gt", "ge", "gte", "lt", "le", "lte" -> {
                            if (args.size != 2) fail("row_writes_expression", "$op needs two operands")
                            val operator =
                                mapOf(
                                    "eq" to "=",
                                    "ne" to "<>",
                                    "neq" to "<>",
                                    "gt" to ">",
                                    "ge" to ">=",
                                    "gte" to ">=",
                                    "lt" to "<",
                                    "le" to "<=",
                                    "lte" to "<=",
                                ).getValue(op)
                            "(${expression(args[0], alias)} $operator ${expression(args[1], alias)})"
                        }
                        "in", "not_in" -> {
                            if (args.size < 2) fail("row_writes_expression", "$op needs a column and values")
                            val left = expression(args[0], alias)
                            val values = args.drop(1).joinToString(", ") { value(it) }
                            "($left ${if (op == "in") "IN" else "NOT IN"} ($values))"
                        }
                        "is_null", "is_not_null" -> {
                            if (args.size != 1) fail("row_writes_expression", "$op needs one operand")
                            "(${expression(args[0], alias)} IS ${if (op == "is_null") "" else "NOT "}NULL)"
                        }
                        else -> fail("row_writes_expression", "Unsupported row predicate operator $op")
                    }
                }
                else -> fail("row_writes_expression", "Unsupported row predicate ${expr.exprCase}")
            }
        val target = quote(row.target.name) // PG search_path, as on the read path (dbo is a logical namespace).
        val locked = row.readBeforeCount > 0
        val prefix: String
        val prior: String
        val join: String
        val predicates =
            if (row.op == RowWriteOp.INSERT) {
                emptyList()
            } else {
                if (keyColumns.isEmpty()) {
                    fail("row_writes_key", "Table ${row.target.name} has no primary key; a row write needs one")
                }
                val equalities = conjuncts(row.where).filter { keyEquality(it) != null }
                keyColumns.map { key ->
                    equalities.firstOrNull { keyEquality(it) == key }
                        ?: fail("row_writes_key", "Missing equality for primary-key column $key of ${row.target.name}")
                }
            }
        if (locked) {
            val keySql = predicates.joinToString(" AND ") { expression(it, "_rw_target") }
            prefix =
                "WITH prior AS MATERIALIZED (SELECT _rw_target.* FROM $target AS _rw_target WHERE $keySql FOR UPDATE), "
            // Project only read_before; row_to_json retains identifier spelling and null values.
            val selected =
                row.readBeforeList
                    .mapIndexed { index, column ->
                        val key = if (row.priorKeysCount == 0) column else row.getPriorKeys(index)
                        "_rw_prior.${quote(column)} AS ${quote(key)}"
                    }.joinToString(", ")
            prior =
                "(SELECT row_to_json(_rw_before)::text FROM (SELECT $selected FROM prior AS _rw_prior) AS _rw_before)"
            join = keyColumns.joinToString(" AND ") { "_rw_target.${quote(it)} = _rw_prior.${quote(it)}" }
        } else {
            prefix = "WITH "
            prior = "NULL::text"
            join = ""
        }
        val dml =
            when (row.op) {
                RowWriteOp.INSERT -> {
                    val columns = row.valuesList.joinToString(", ") { quote(it.name) }
                    val values = row.valuesList.joinToString(", ") { value(it.value) }
                    "INSERT INTO $target ($columns) VALUES ($values)${if (row.ifAbsent) " ON CONFLICT DO NOTHING" else ""}"
                }
                RowWriteOp.UPDATE -> {
                    val set = row.valuesList.joinToString(", ") { "${quote(it.name)} = ${value(it.value)}" }
                    if (locked) {
                        "UPDATE $target AS _rw_target SET $set FROM prior AS _rw_prior WHERE $join AND ${expression(
                            row.where,
                            "_rw_prior",
                        )}"
                    } else {
                        "UPDATE $target AS _rw_target SET $set WHERE ${expression(row.where, "_rw_target")}"
                    }
                }
                RowWriteOp.DELETE ->
                    if (locked) {
                        "DELETE FROM $target AS _rw_target USING prior AS _rw_prior WHERE $join AND ${expression(
                            row.where,
                            "_rw_prior",
                        )}"
                    } else {
                        "DELETE FROM $target AS _rw_target WHERE ${expression(row.where, "_rw_target")}"
                    }
                else -> fail("row_writes_op", "Unspecified row write operation")
            }
        return Statement(
            "${prefix}w AS ($dml RETURNING 1) SELECT (SELECT count(*) FROM w) AS rows_affected, $prior AS prior",
            positional,
        )
    }

    private fun checkRow(row: RowWrite) {
        if (row.target.schemaCode != SchemaCode.DB ||
            row.target.name.isEmpty()
        ) {
            fail("row_writes_target_schema", "Row writes must be mapped to a DB table before SQL unparse")
        }
        RowWriteShape.check(row)
    }

    private fun conjuncts(expr: Expression): List<Expression> =
        if (expr.hasFunction() &&
            expr.function.operation.equals("and", true)
        ) {
            expr.function.operandsList.flatMap(::conjuncts)
        } else {
            listOf(expr)
        }

    private fun keyEquality(expr: Expression): String? {
        if (!expr.hasFunction() ||
            !expr.function.operation.equals("eq", true) ||
            expr.function.operandsCount != 2
        ) {
            return null
        }
        val (left, right) = expr.function.operandsList
        return when {
            left.hasColumnRef() && (right.hasLiteral() || right.hasParameter()) -> left.columnRef.name
            right.hasColumnRef() && (left.hasLiteral() || left.hasParameter()) -> right.columnRef.name
            else -> null
        }
    }

    private fun quote(name: String): String {
        if (name.isEmpty() || '\u0000' in name) fail("row_writes_identifier", "Invalid empty/NUL identifier")
        return "\"${name.replace("\"", "\"\"")}\""
    }

    private fun literal(lit: Literal): ParameterBinding {
        val value = Value.newBuilder()
        when {
            lit.isNull -> value.isNull = true
            lit.valueCase == Literal.ValueCase.STRING_VALUE -> value.stringValue = lit.stringValue
            lit.valueCase == Literal.ValueCase.INT_VALUE -> value.intValue = lit.intValue
            lit.valueCase == Literal.ValueCase.FLOAT_VALUE -> value.floatValue = lit.floatValue
            lit.valueCase == Literal.ValueCase.BOOL_VALUE -> value.boolValue = lit.boolValue
            lit.valueCase == Literal.ValueCase.DATETIME_VALUE -> value.datetimeValue = lit.datetimeValue
            else -> fail("row_writes_value", "Unset literal")
        }
        return ParameterBinding
            .newBuilder()
            .setType(lit.type)
            .setValue(value)
            .build()
    }

    private fun fail(
        code: String,
        message: String,
    ): Nothing = throw RowWriteUnparseException(code, message)
}

class RowWriteUnparseException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)
