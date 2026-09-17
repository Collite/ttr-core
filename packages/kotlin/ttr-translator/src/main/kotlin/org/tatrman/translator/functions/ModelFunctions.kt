// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.functions

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.FunctionParameter
import org.apache.calcite.schema.ScalarFunction
import org.apache.calcite.sql.SqlFunctionCategory
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.SqlOperatorTable
import org.apache.calcite.sql.SqlSyntax
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.SqlCallBinding
import org.apache.calcite.sql.SqlOperandCountRange
import org.apache.calcite.sql.type.SqlOperandCountRanges
import org.apache.calcite.sql.type.SqlOperandMetadata
import org.apache.calcite.sql.type.SqlTypeFamily
import org.apache.calcite.sql.type.SqlOperandTypeInference
import org.apache.calcite.sql.type.SqlReturnTypeInference
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.sql.validate.SqlNameMatcher
import org.apache.calcite.sql.validate.SqlUserDefinedFunction
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelFunction
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.params.SurfaceTypeMapping
import org.tatrman.translator.schema.TypeMapping

/**
 * TF-P5 (G C1; contracts §3.1, §3.6) — model-declared scalar functions as Calcite operators.
 *
 * Each [ModelFunction] becomes a [SqlUserDefinedFunction] named by its qualified identifier
 * (`dbo.fn_price`): validation checks the arity and each operand's surface family, the call's type is
 * the declared return (the physical one when the model gave precision/scale), and the MSSQL unparse
 * prints the identifier, so the call keeps the schema T-SQL requires on a scalar UDF.
 */
object ModelFunctions {
    fun toSqlUserDefinedFunction(f: ModelFunction): SqlUserDefinedFunction {
        val paramTypes = { tf: RelDataTypeFactory -> f.parameters.map { TypeMapping.fromSurface(it, tf) } }
        val returnType = { tf: RelDataTypeFactory ->
            tf.createTypeWithNullability(
                f.physicalReturn?.let { TypeMapping.fromPhysical(it, tf) } ?: TypeMapping.fromSurface(f.returns, tf),
                true,
            )
        }
        val metadata = DeclaredOperands(f.parameters.map(SurfaceTypeMapping::familyOf))
        return SqlUserDefinedFunction(
            SqlIdentifier(listOf(f.qname.namespace, f.qname.name), SqlParserPos.ZERO),
            SqlKind.OTHER_FUNCTION,
            SqlReturnTypeInference { binding -> returnType(binding.typeFactory) },
            SqlOperandTypeInference { binding, _, operandTypes ->
                paramTypes(binding.typeFactory).take(operandTypes.size).forEachIndexed { i, t -> operandTypes[i] = t }
            },
            metadata,
            DeclaredScalarFunction(f, paramTypes, returnType),
        )
    }

    /**
     * The functions [model] declares, as an operator table. Every DB namespace is loaded: a qualified
     * call (`xx.fn`) resolves in its own schema. A bare call (`fn_price(…)`) resolves against
     * [defaultNamespace] — the framework's namespace for a DB framework, `dbo` otherwise — unless
     * [builtins] define that name: in T-SQL a bare name is the built-in, so a declared `dbo.len` never
     * captures `LEN(…)`.
     */
    fun tableFor(
        model: ModelHandle,
        schemaCode: SchemaCode,
        namespace: String,
        builtins: SqlOperatorTable,
    ): SqlOperatorTable {
        val isDbFramework = schemaCode == SchemaCode.DB && namespace.isNotEmpty()
        val defaultNamespace = if (isDbFramework) namespace else DEFAULT_NAMESPACE
        val namespaces = model.namespaces(SchemaCode.DB) + defaultNamespace
        val operators = namespaces.flatMap { model.functions(SchemaCode.DB, it) }.map(::toSqlUserDefinedFunction)
        return ModelFunctionOperatorTable(operators, defaultNamespace, builtins)
    }

    private const val DEFAULT_NAMESPACE = "dbo"

    /**
     * Calcite's list table matches simple names only, so a qualified `dbo.fn_price` would never be found.
     * This one matches the last two parts of the call name against `(namespace, name)`, or a single part
     * against a function of [defaultNamespace] that no built-in shadows, with the validator's name matcher
     * (case-insensitive under the framework's `Lex.MYSQL_ANSI`).
     */
    private class ModelFunctionOperatorTable(
        private val operators: List<SqlUserDefinedFunction>,
        private val defaultNamespace: String,
        private val builtins: SqlOperatorTable,
    ) : SqlOperatorTable {
        override fun lookupOperatorOverloads(
            opName: SqlIdentifier,
            category: SqlFunctionCategory?,
            syntax: SqlSyntax,
            operatorList: MutableList<SqlOperator>,
            nameMatcher: SqlNameMatcher,
        ) {
            if (syntax.family != SqlSyntax.FUNCTION) return
            val names = opName.names
            if (names.size == 1 && isBuiltin(opName, category, syntax, nameMatcher)) return
            val (namespace, name) =
                when (names.size) {
                    1 -> defaultNamespace to names[0]
                    else -> names[names.size - 2] to names[names.size - 1]
                }
            operators.filterTo(operatorList) { op ->
                val id = op.sqlIdentifier ?: return@filterTo false
                nameMatcher.matches(id.names[0], namespace) && nameMatcher.matches(id.names[1], name)
            }
        }

        override fun getOperatorList(): List<SqlOperator> = operators

        private fun isBuiltin(
            opName: SqlIdentifier,
            category: SqlFunctionCategory?,
            syntax: SqlSyntax,
            nameMatcher: SqlNameMatcher,
        ): Boolean {
            val found = mutableListOf<SqlOperator>()
            builtins.lookupOperatorOverloads(opName, category, syntax, found, nameMatcher)
            return found.isNotEmpty()
        }
    }

    /**
     * The operands of a declared function: the arity is exact and each operand must already belong to its
     * parameter's family (a NULL literal fits any). Nothing is coerced, so the call keeps the text its
     * author wrote. The stock family checker casts a mismatched operand (a text column into a numeric
     * parameter became `CAST([NAME] AS DECIMAL(19, 9))`); here a mismatch is a validation error.
     *
     * [paramTypes] are `ANY` on purpose: with concrete types Calcite's UDF coercion wraps every operand
     * that is not exactly that type in a CAST (`CONVERT(date, GETDATE())` → `CAST(… AS DATETIME2(0))`).
     * SQL Server converts the arguments of a scalar UDF itself.
     */
    private class DeclaredOperands(
        private val families: List<SqlTypeFamily>,
    ) : SqlOperandMetadata {
        override fun paramTypes(typeFactory: RelDataTypeFactory): List<RelDataType> =
            families.map { typeFactory.createSqlType(SqlTypeName.ANY) }

        override fun paramNames(): List<String> = families.indices.map { "p$it" }

        override fun checkOperandTypes(
            callBinding: SqlCallBinding,
            throwOnFailure: Boolean,
        ): Boolean {
            val ok =
                families.indices.all { i ->
                    val type = callBinding.getOperandType(i)
                    type.sqlTypeName == SqlTypeName.NULL || families[i].contains(type)
                }
            if (!ok && throwOnFailure) throw callBinding.newValidationSignatureError()
            return ok
        }

        override fun getOperandCountRange(): SqlOperandCountRange = SqlOperandCountRanges.of(families.size)

        override fun getAllowedSignatures(
            op: SqlOperator,
            opName: String,
        ): String = families.joinToString(", ", "$opName(", ")") { "<$it>" }

        override fun isFixedParameters(): Boolean = true
    }

    /** The [org.apache.calcite.schema.Function] behind a declared operator: parameter names and types. */
    private class DeclaredScalarFunction(
        private val f: ModelFunction,
        private val paramTypes: (RelDataTypeFactory) -> List<RelDataType>,
        private val returnType: (RelDataTypeFactory) -> RelDataType,
    ) : ScalarFunction {
        override fun getReturnType(typeFactory: RelDataTypeFactory): RelDataType = returnType(typeFactory)

        override fun getParameters(): List<FunctionParameter> =
            f.parameters.indices.map { i ->
                object : FunctionParameter {
                    override fun getOrdinal(): Int = i

                    override fun getName(): String = "p$i"

                    override fun getType(typeFactory: RelDataTypeFactory): RelDataType = paramTypes(typeFactory)[i]

                    override fun isOptional(): Boolean = false
                }
            }
    }
}
