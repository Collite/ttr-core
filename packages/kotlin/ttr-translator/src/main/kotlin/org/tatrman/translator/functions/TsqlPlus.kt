// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.sql.SqlBinaryOperator
import org.apache.calcite.sql.SqlCall
import org.apache.calcite.sql.SqlCallBinding
import org.apache.calcite.sql.SqlCollation
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlOperandCountRange
import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.SqlOperatorBinding
import org.apache.calcite.sql.`fun`.SqlLibraryOperators
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.type.InferTypes
import org.apache.calcite.sql.type.OperandTypes
import org.apache.calcite.sql.type.ReturnTypes
import org.apache.calcite.sql.type.SqlOperandCountRanges
import org.apache.calcite.sql.type.SqlOperandTypeChecker
import org.apache.calcite.sql.type.SqlReturnTypeInference
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.type.SqlTypeUtil
import org.apache.calcite.sql.util.SqlShuttle
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorScope
import org.apache.calcite.sql.validate.SqlValidatorUtil
import java.math.BigDecimal

/**
 * TF-P2.S1 (G A2, C11; contracts §3.4) — T-SQL's overloaded `+` (string concatenation *or* addition)
 * and `<datetime> ± <integer>` (day arithmetic).
 *
 * Why two passes: which `+` is meant depends on the operand types, which are unknown until validation —
 * but Calcite's validator *coerces* `'Skladem (' + CAST(x AS varchar)` to DECIMAL arithmetic before we
 * can look, and rejects `GETDATE() - 30` outright. So:
 *  1. [TsqlArithmeticShuttle] (parsed SqlNode, before validation) swaps every binary `+`/`-` to
 *     [TsqlPlusOperator]/[TsqlMinusOperator]. Their operand checker accepts the T-SQL-only shapes
 *     (any character operand for `+`; DATE/TIMESTAMP ± integer) and delegates every other shape to the
 *     standard `PLUS`/`MINUS` checker, so numeric and interval arithmetic validate exactly as before.
 *  2. [TsqlPlusLowering] (logical RelNode, after `SqlToRel`) knows the operand types and lowers each
 *     call to what the wire already carries: `||` (CONCAT), `DATEADD(DAY, n, d)`, or plain `PLUS`/`MINUS`.
 *
 * The wire never sees these operators; a re-decoded plan carries none, so the lowering is a no-op on
 * REL_NODE re-entry. No operator-table entry is needed: the shuttle binds the objects directly, and
 * [TsqlArithmeticOperator.deriveType] skips the by-name re-resolution that would otherwise look for one.
 *
 * Lenient choices (documented, contracts §3.4): `'x' + a.ID` (text + number) is a conversion error in
 * T-SQL when `'x'` is not numeric; we concatenate with `CAST(<number> AS VARCHAR)`. `<DATE> + <int>` is an
 * operand clash in T-SQL (only DATETIME allows it); we lower it to `DATEADD` like the DATETIME form.
 */
object TsqlPlusOperator : TsqlArithmeticOperator("+", plus = true)

/** The `-` twin of [TsqlPlusOperator]: only `<DATE/TIMESTAMP> - <integer>` is T-SQL-specific. */
object TsqlMinusOperator : TsqlArithmeticOperator("-", plus = false)

/**
 * Shared shape of the two operators: `+`/`-` precedence (40, left-associative), `FIRST_KNOWN` operand
 * inference (so `'%' + {q}` types the parameter), T-SQL-aware return type and operand checking.
 */
abstract class TsqlArithmeticOperator(
    name: String,
    plus: Boolean,
) : SqlBinaryOperator(
        name,
        SqlKind.OTHER,
        40,
        true,
        SqlReturnTypeInference { b -> inferType(b, plus) },
        InferTypes.FIRST_KNOWN,
        TsqlArithmeticOperandChecker(plus),
    ) {
    /**
     * [SqlOperator.deriveType] re-resolves the operator *by name and kind* in the validator's operator
     * table and swaps it onto the call — which finds nothing for `+` with kind OTHER ("No match found for
     * function signature +"), and would drop this operator if it did. Everything else it does is kept:
     * derive the operand types, check them ([TsqlArithmeticOperandChecker]), infer the return type, and
     * apply the binary-operator charset/collation rules.
     */
    override fun deriveType(
        validator: SqlValidator,
        scope: SqlValidatorScope,
        call: SqlCall,
    ): RelDataType {
        call.operandList.forEach { validator.deriveType(scope, it) }
        val validated = validateOperands(validator, scope, call)
        val adjusted = adjustType(validator, call, validated)
        SqlValidatorUtil.checkCharsetAndCollateConsistentIfCharType(adjusted)
        return adjusted
    }
}

/** Post-parse, pre-validate: binary `+` → [TsqlPlusOperator], binary `-` → [TsqlMinusOperator]; unary signs untouched. */
class TsqlArithmeticShuttle : SqlShuttle() {
    override fun visit(call: SqlCall): SqlNode? {
        val replacement =
            when {
                call.operandCount() != 2 -> null
                call.operator === SqlStdOperatorTable.PLUS -> TsqlPlusOperator
                call.operator === SqlStdOperatorTable.MINUS -> TsqlMinusOperator
                else -> null
            } ?: return super.visit(call)
        val operands = call.operandList.map { it?.accept(this) ?: it }
        return replacement.createCall(call.parserPosition, operands)
    }

    companion object {
        /** Fresh shuttle per call (SqlShuttle is cheap and not thread-safe to share). */
        fun rewriter(): TsqlArithmeticShuttle = TsqlArithmeticShuttle()
    }
}

/** Post-`SqlToRel`: lower every [TsqlPlusOperator]/[TsqlMinusOperator] call by its now-known operand types. */
object TsqlPlusLowering {
    fun apply(rel: RelNode): RelNode {
        val rexBuilder = rel.cluster.rexBuilder
        lateinit var rewrite: (RelNode) -> RelNode
        val shuttle =
            object : RexShuttle() {
                override fun visitCall(call: RexCall): RexNode {
                    val visited = super.visitCall(call)
                    if (visited !is RexCall) return visited
                    return when (visited.operator) {
                        TsqlPlusOperator -> lowerPlus(rexBuilder, visited)
                        TsqlMinusOperator -> lowerMinus(rexBuilder, visited)
                        else -> visited
                    }
                }

                // A sub-query's body is a RelNode the RexShuttle does not enter by itself.
                override fun visitSubQuery(subQuery: RexSubQuery): RexNode {
                    val visited = super.visitSubQuery(subQuery) as RexSubQuery
                    val body = rewrite(visited.rel)
                    return if (body === visited.rel) visited else visited.clone(body)
                }
            }

        // `RelNode.accept(RexShuttle)` rewrites only a node's own expressions — descend the whole tree
        // (same skeleton as SearchExpander).
        rewrite = { node ->
            val newInputs = node.inputs.map { rewrite(it) }
            val withInputs = if (newInputs == node.inputs) node else node.copy(node.traitSet, newInputs)
            withInputs.accept(shuttle)
        }
        return rewrite(rel)
    }

    private fun lowerPlus(
        rexBuilder: RexBuilder,
        call: RexCall,
    ): RexNode {
        val (left, right) = call.operands
        return when {
            SqlTypeUtil.inCharFamily(left.type) || SqlTypeUtil.inCharFamily(right.type) ->
                // Keep the validated type: parent nodes' input refs were typed against it.
                rexBuilder.makeCall(
                    call.type,
                    SqlStdOperatorTable.CONCAT,
                    listOf(asText(rexBuilder, left), asText(rexBuilder, right)),
                )
            isDayArithmetic(left.type, right.type) -> dateAdd(rexBuilder, days = right, date = left)
            isDayArithmetic(right.type, left.type) -> dateAdd(rexBuilder, days = left, date = right)
            // datetime + interval: the special operator StandardConvertletTable.convertPlus would have
            // chosen, interval second.
            SqlTypeUtil.isDatetime(call.type) ->
                rexBuilder.makeCall(
                    call.type,
                    SqlStdOperatorTable.DATETIME_PLUS,
                    if (SqlTypeUtil.isInterval(left.type)) listOf(right, left) else listOf(left, right),
                )
            else -> rexBuilder.makeCall(call.type, SqlStdOperatorTable.PLUS, call.operands)
        }
    }

    private fun lowerMinus(
        rexBuilder: RexBuilder,
        call: RexCall,
    ): RexNode {
        val (left, right) = call.operands
        return when {
            isDayArithmetic(left.type, right.type) -> dateAdd(rexBuilder, days = negate(rexBuilder, right), date = left)
            // datetime - interval: what StandardConvertletTable's MINUS convertlet would have chosen.
            SqlTypeUtil.isDatetime(left.type) ->
                rexBuilder.makeCall(call.type, SqlStdOperatorTable.MINUS_DATE, call.operands)
            else -> rexBuilder.makeCall(call.type, SqlStdOperatorTable.MINUS, call.operands)
        }
    }

    /** `DATEADD(DAY, n, d)` — the T-SQL meaning of `<datetime> + n` (whole days). */
    private fun dateAdd(
        rexBuilder: RexBuilder,
        days: RexNode,
        date: RexNode,
    ): RexNode = rexBuilder.makeCall(SqlLibraryOperators.DATEADD, rexBuilder.makeFlag(TimeUnit.DAY), days, date)

    private fun negate(
        rexBuilder: RexBuilder,
        n: RexNode,
    ): RexNode {
        val value = (n as? RexLiteral)?.getValueAs(BigDecimal::class.java)
        return if (value != null) {
            rexBuilder.makeExactLiteral(value.negate(), n.type)
        } else {
            rexBuilder.makeCall(SqlStdOperatorTable.UNARY_MINUS, n)
        }
    }

    private fun asText(
        rexBuilder: RexBuilder,
        operand: RexNode,
    ): RexNode =
        if (SqlTypeUtil.inCharFamily(operand.type)) {
            operand
        } else {
            rexBuilder.makeCast(varchar(rexBuilder.typeFactory, operand.type.isNullable), operand)
        }
}

/** DATE/TIMESTAMP on one side, an integer type on the other: T-SQL day arithmetic. */
internal fun isDayArithmetic(
    date: RelDataType,
    days: RelDataType,
): Boolean = date.sqlTypeName in DAY_ARITHMETIC_DATES && SqlTypeUtil.isIntType(days)

private val DAY_ARITHMETIC_DATES =
    setOf(SqlTypeName.DATE, SqlTypeName.TIMESTAMP, SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE)

/** Unbounded VARCHAR with the default charset and an implicit collation — what a validated `CAST(x AS VARCHAR)` gets. */
private fun varchar(
    typeFactory: RelDataTypeFactory,
    nullable: Boolean,
): RelDataType {
    val bare = typeFactory.createSqlType(SqlTypeName.VARCHAR)
    val withCharset =
        typeFactory.createTypeWithCharsetAndCollation(
            bare,
            typeFactory.defaultCharset,
            SqlCollation.IMPLICIT,
        )
    return typeFactory.createTypeWithNullability(withCharset, nullable)
}

internal fun inferType(
    b: SqlOperatorBinding,
    plus: Boolean,
): RelDataType {
    val left = b.getOperandType(0)
    val right = b.getOperandType(1)
    val nullable = left.isNullable || right.isNullable
    val factory = b.typeFactory
    return when {
        plus && SqlTypeUtil.inCharFamily(left) && SqlTypeUtil.inCharFamily(right) ->
            ReturnTypes.DYADIC_STRING_SUM_PRECISION_NULLABLE.inferReturnType(b)!!
        plus && (SqlTypeUtil.inCharFamily(left) || SqlTypeUtil.inCharFamily(right)) -> varchar(factory, nullable)
        isDayArithmetic(left, right) -> factory.createTypeWithNullability(left, nullable)
        plus && isDayArithmetic(right, left) -> factory.createTypeWithNullability(right, nullable)
        else -> ReturnTypes.NULLABLE_SUM.inferReturnType(b)!!
    }
}

/**
 * Accepts the T-SQL-only operand shapes and hands every other shape to the standard checker, so
 * `a.ID + 1`, `x - y` and `d + INTERVAL '1' DAY` validate (and fail) exactly as they did with PLUS/MINUS.
 */
internal class TsqlArithmeticOperandChecker(
    private val plus: Boolean,
) : SqlOperandTypeChecker {
    private val standard: SqlOperandTypeChecker = if (plus) OperandTypes.PLUS_OPERATOR else OperandTypes.MINUS_OPERATOR

    override fun checkOperandTypes(
        callBinding: SqlCallBinding,
        throwOnFailure: Boolean,
    ): Boolean {
        val left = callBinding.getOperandType(0)
        val right = callBinding.getOperandType(1)
        val tsqlOnly =
            if (plus) {
                SqlTypeUtil.inCharFamily(left) ||
                    SqlTypeUtil.inCharFamily(right) ||
                    isDayArithmetic(left, right) ||
                    isDayArithmetic(right, left)
            } else {
                isDayArithmetic(left, right)
            }
        return tsqlOnly || standard.checkOperandTypes(callBinding, throwOnFailure)
    }

    override fun getOperandCountRange(): SqlOperandCountRange = SqlOperandCountRanges.of(2)

    override fun getAllowedSignatures(
        op: SqlOperator,
        opName: String,
    ): String = standard.getAllowedSignatures(op, opName)
}
