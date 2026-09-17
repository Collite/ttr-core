// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.wire

import com.google.common.collect.ImmutableList
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FrameBound
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.OverExpression
import org.tatrman.plan.v1.OverOrderKey
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.SubqueryExpression
import org.tatrman.plan.v1.WindowFrame
import org.apache.calcite.rel.RelFieldCollation
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexDynamicParam
import org.apache.calcite.rex.RexFieldCollation
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexOver
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.rex.RexWindowBound
import org.apache.calcite.rex.RexWindowBounds
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.RelBuilder
import org.tatrman.translator.functions.FunctionCatalog

/**
 * RexNode ↔ Expression encoders / decoders for the v1 wire format.
 *
 * Conversion is opinionated: only the operators in the v1 RelOp subset's
 * standardised expression enum (see v1-architecture.md §3.2) are handled.
 * Anything else throws [UnsupportedOperationException] with the offending
 * operator named. The Translator's TO_AST stage is responsible for catching
 * those cases earlier; this encoder is the last-line check that the wire
 * format stays clean.
 *
 * Phase 08 A1 / DF-T01 — RESOLVE stage. `RexInputRef`s are encoded as named
 * `ColumnRef`s (instead of positional `$idx`) so the wire shape survives
 * round-trips, including across joins. For join conditions the encoder also
 * sets `source_alias = "$L"`/`"$R"` so the decoder can route the lookup
 * into the correct join input via `RelBuilder.field(inputCount, ord, name)`.
 * Bare positional `$idx` names remain accepted on decode for backward
 * compatibility with plans produced before RESOLVE landed.
 */
object Expressions {
    /** Synthetic source-alias markers used to route a [ColumnRef] into a join input on decode. */
    const val LEFT_INPUT_TAG: String = "\$L"
    const val RIGHT_INPUT_TAG: String = "\$R"

    /**
     * Surface-type tag prefix for a SYMBOL [Literal] (an enum-flag operand carried as its constant
     * name). Distinct from the `text` tag so decode rebuilds a SYMBOL RexLiteral, not a VARCHAR.
     * The concrete tag is `symbol:<EnumSimpleName>` (e.g. `symbol:TimeUnit`) so the enum kind is
     * recorded on the wire — only `TimeUnit`/`TimeUnitRange` (the DATEADD/DATEDIFF/DATEPART datepart)
     * is supported; any other symbol enum fails decode with a clear error rather than being
     * mis-rebuilt. The bare prefix (`symbol`) is still accepted on decode for back-compat.
     * CalciteExtParser (CEP-P1, datetime).
     */
    const val SYMBOL_TYPE_TAG: String = "symbol"

    /**
     * Resolution context threaded through [encode]. `fieldNames` is the field-name list of the
     * RelDataType the expression is interpreted against (the input row type for Filter / Project,
     * the combined row type for Join.condition). `joinSplit` is non-null only inside a Join
     * condition; it carries the field-count of the left input so the encoder can decide whether
     * a `RexInputRef.index` belongs to the left (`< joinSplit`) or right (`>= joinSplit`) input.
     *
     * Phase 08 A2 — `parameterNames` is an optional positional-index → original parameter name
     * map (built by [org.tatrman.translator.params.ParameterBridge] and carried by the Translator
     * orchestrator). When supplied, the encoder restores the original `{name}` on
     * `RexDynamicParam`s; otherwise the legacy `?N` positional shape is emitted.
     */
    data class ResolveContext(
        val fieldNames: List<String>,
        val joinSplit: Int? = null,
        val parameterNames: Map<Int, String> = emptyMap(),
        // Per-input field names for a Join condition. Calcite uniquifies duplicate
        // names in the COMBINED join row type (a right-side `IDSTRED` colliding with
        // a left-side `IDSTRED` becomes `IDSTRED0`), but on decode a `$L`/`$R` ref is
        // resolved against the individual input — where the name is still the
        // original. So join-condition refs must be encoded with the per-input name,
        // not the combined one. Empty → fall back to `fieldNames` (no per-input info).
        val leftFieldNames: List<String> = emptyList(),
        val rightFieldNames: List<String> = emptyList(),
    ) {
        companion object {
            /** Marker for places that haven't been migrated yet — falls back to positional encoding. */
            val NONE: ResolveContext = ResolveContext(emptyList(), null)
        }
    }

    fun encode(
        rex: RexNode,
        ctx: ResolveContext = ResolveContext.NONE,
    ): Expression =
        when (rex) {
            is RexLiteral ->
                Expression
                    .newBuilder()
                    .setLiteral(encodeLiteral(rex))
                    .setResultType(surfaceTypeOf(rex.type))
                    .build()
            is RexInputRef -> encodeInputRef(rex, ctx)
            // `RexSubQuery` extends `RexCall`, so it MUST be matched before the generic
            // `is RexCall` branch — otherwise its inner subquery RelNode (held in `.rel`,
            // not in `.operands`) is silently dropped and the operator name leaks out as a
            // bare `$scalar_query` function the decoder can't reconstruct.
            is RexSubQuery -> encodeSubquery(rex, ctx)
            // `RexOver` (windowed aggregate) extends `RexCall`, so it MUST be matched before
            // the generic `is RexCall` branch — otherwise its window spec (partition/order/frame,
            // held in `.window`, not `.operands`) is silently dropped.
            is RexOver -> encodeOver(rex, ctx)
            is RexCall ->
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation(operationCode(rex))
                            .addAllOperands(rex.operands.map { encode(it, ctx) }),
                    ).setResultType(
                        if (rex.kind == SqlKind.CAST || rex.kind == SqlKind.SAFE_CAST) {
                            physicalCodeOf(rex.type)
                        } else {
                            surfaceTypeOf(rex.type)
                        },
                    ).build()
            is RexDynamicParam -> {
                // Phase 08 A2 — name restoration. When the orchestrator threaded the prepared
                // `parameterNames` map in, emit the original `{name}` here; otherwise fall back
                // to the positional `?N` shape so callers without the map stay back-compat.
                val restoredName = ctx.parameterNames[rex.index] ?: "?${rex.index}"
                Expression
                    .newBuilder()
                    .setParameter(
                        ParameterRef
                            .newBuilder()
                            .setName(restoredName)
                            .setPositionalIndex(rex.index),
                    ).setResultType(surfaceTypeOf(rex.type))
                    .build()
            }
            else -> throw UnsupportedOperationException(
                "RexNode kind '${rex.javaClass.simpleName}' is not in the v1 wire format",
            )
        }

    private fun encodeOver(
        rex: RexOver,
        ctx: ResolveContext,
    ): Expression {
        val w = rex.window
        val over =
            OverExpression
                .newBuilder()
                .setAggregate(aggCode(rex.aggOperator))
                .setDistinct(rex.isDistinct)
        rex.operands.forEach { over.addOperands(encode(it, ctx)) }
        w.partitionKeys.forEach { over.addPartitionKeys(encode(it, ctx)) }
        w.orderKeys.forEach { fc ->
            over.addOrderKeys(
                OverOrderKey
                    .newBuilder()
                    .setExpr(encode(fc.left, ctx))
                    .setDescending(fc.direction.isDescending)
                    .setNullsFirst(fc.nullDirection == RelFieldCollation.NullDirection.FIRST),
            )
        }
        val (lower, lowerOffset) = frameBoundCode(w.lowerBound)
        val (upper, upperOffset) = frameBoundCode(w.upperBound)
        over.setFrame(
            WindowFrame
                .newBuilder()
                .setIsRows(w.isRows)
                .setLower(lower)
                .setLowerOffset(lowerOffset)
                .setUpper(upper)
                .setUpperOffset(upperOffset),
        )
        return Expression
            .newBuilder()
            .setOver(over)
            .setResultType(surfaceTypeOf(rex.type))
            .build()
    }

    private fun aggCode(op: SqlAggFunction): String =
        when (op.kind) {
            SqlKind.SUM, SqlKind.SUM0 -> "sum"
            SqlKind.COUNT -> "count"
            SqlKind.AVG -> "avg"
            SqlKind.MIN -> "min"
            SqlKind.MAX -> "max"
            SqlKind.ROW_NUMBER -> "row_number"
            SqlKind.RANK -> "rank"
            SqlKind.DENSE_RANK -> "dense_rank"
            SqlKind.NTILE -> "ntile"
            SqlKind.LAG -> "lag"
            SqlKind.LEAD -> "lead"
            SqlKind.FIRST_VALUE -> "first_value"
            SqlKind.LAST_VALUE -> "last_value"
            else -> throw UnsupportedOperationException(
                "Window aggregate '${op.name}' is not in the v1 wire format",
            )
        }

    /** The wire bound and its offset (`0` unless the bound is `n PRECEDING` / `n FOLLOWING`). */
    private fun frameBoundCode(b: RexWindowBound): Pair<FrameBound, Long> =
        when {
            b.isUnbounded && b.isPreceding -> FrameBound.UNBOUNDED_PRECEDING to 0L
            b.isCurrentRow -> FrameBound.CURRENT_ROW to 0L
            b.isUnbounded && b.isFollowing -> FrameBound.UNBOUNDED_FOLLOWING to 0L
            b.isPreceding -> FrameBound.PRECEDING to frameOffset(b)
            b.isFollowing -> FrameBound.FOLLOWING to frameOffset(b)
            else -> throw UnsupportedOperationException(
                "Window frame bound '$b' is not in the v1 wire format",
            )
        }

    private fun frameOffset(b: RexWindowBound): Long {
        val offset = b.offset
        if (offset is RexLiteral && SqlTypeName.EXACT_TYPES.contains(offset.type.sqlTypeName)) {
            val exact = offset.getValueAs(java.math.BigDecimal::class.java)
            runCatching { exact?.longValueExact() }.getOrNull()?.let { return it }
        }
        throw UnsupportedOperationException(
            "Window frame bound '$b' is not in the v1 wire format (offset must be an integer literal)",
        )
    }

    private fun encodeInputRef(
        rex: RexInputRef,
        ctx: ResolveContext,
    ): Expression {
        val builder =
            ColumnRef
                .newBuilder()
                .setType(surfaceTypeOf(rex.type))
        // No resolution context (legacy callers, or a hop where we don't know the row type) →
        // fall back to the v1.0 positional shape so behaviour is unchanged.
        if (ctx.fieldNames.isEmpty() || rex.index !in ctx.fieldNames.indices) {
            builder.setName("\$${rex.index}")
        } else {
            val split = ctx.joinSplit
            if (split != null) {
                val isLeft = rex.index < split
                builder.setSourceAlias(if (isLeft) LEFT_INPUT_TAG else RIGHT_INPUT_TAG)
                // Emit the name as it appears in the TARGET INPUT, not the combined
                // (uniquified) join row type — the decoder resolves `$L`/`$R` against
                // the input. Falls back to the combined name when per-input names
                // aren't supplied (preserves the prior positional/combined behaviour).
                val perInput =
                    if (isLeft) {
                        ctx.leftFieldNames.getOrNull(rex.index)
                    } else {
                        ctx.rightFieldNames.getOrNull(rex.index - split)
                    }
                builder.setName(perInput ?: ctx.fieldNames[rex.index])
            } else {
                builder.setName(ctx.fieldNames[rex.index])
            }
        }
        return Expression
            .newBuilder()
            .setColumnRef(builder)
            .setResultType(surfaceTypeOf(rex.type))
            .build()
    }

    /**
     * Encode a Calcite [RexSubQuery] (an expression-level scalar / EXISTS / IN subquery) into a
     * [SubqueryExpression]. The nested subquery RelNode rides in `RexSubQuery.rel`; we recurse
     * into [PlanNodeEncoder] for it. IN-subquery left-hand-side expressions live in
     * `RexSubQuery.operands` (empty for scalar / EXISTS) and are encoded inline.
     *
     * Correlated subqueries (whose `rel` references the outer row via a `RexCorrelVariable`) are
     * out of the v1 subset — they surface as an [UnsupportedOperationException] from the nested
     * [PlanNodeEncoder.encode] when it meets the correlation reference, not silently.
     */
    private fun encodeSubquery(
        rex: RexSubQuery,
        ctx: ResolveContext,
    ): Expression {
        val kind =
            when (rex.kind) {
                SqlKind.SCALAR_QUERY -> "scalar"
                SqlKind.EXISTS -> "exists"
                SqlKind.IN -> "in"
                else -> throw UnsupportedOperationException(
                    "Subquery kind '${rex.kind}' is not in the v1 wire format",
                )
            }
        val sub =
            SubqueryExpression
                .newBuilder()
                .setSubquery(PlanNodeEncoder.encode(rex.rel, ctx.parameterNames))
                .setKind(kind)
                .addAllOperands(rex.operands.map { encode(it, ctx) })
        return Expression
            .newBuilder()
            .setSubquery(sub)
            .setResultType(surfaceTypeOf(rex.type))
            .build()
    }

    private fun encodeLiteral(lit: RexLiteral): Literal {
        val builder = Literal.newBuilder().setType(surfaceTypeOf(lit.type))
        if (lit.value == null) {
            return builder.setIsNull(true).build()
        }
        return when (lit.type.sqlTypeName) {
            SqlTypeName.VARCHAR, SqlTypeName.CHAR ->
                builder.setStringValue(lit.value2.toString()).build()
            SqlTypeName.BOOLEAN ->
                builder.setBoolValue(lit.value2 as Boolean).build()
            SqlTypeName.INTEGER, SqlTypeName.BIGINT, SqlTypeName.SMALLINT, SqlTypeName.TINYINT ->
                builder.setIntValue((lit.value2 as Number).toLong()).build()
            // TF-P1.S1 (G A1) — the numeric value, not `value2`: for a DECIMAL literal `value2` is the
            // *unscaled* long (`1.5` → 15, `100.0` → 1000), which silently multiplied every fractional
            // constant by 10^scale. `getValueAs(BigDecimal)` is the scaled value for every exact and
            // approximate numeric literal.
            SqlTypeName.DECIMAL, SqlTypeName.DOUBLE, SqlTypeName.FLOAT, SqlTypeName.REAL ->
                builder.setFloatValue(lit.getValueAs(java.math.BigDecimal::class.java)!!.toDouble()).build()
            // ISO-8601, as plan.proto defines `datetime_value`. `value2` is not that: for a TIMESTAMP
            // it is epoch milliseconds, so the wire carried "1735689600000" — a value no caller writing
            // the contract's own form produces, and one decode could not read back as a date.
            SqlTypeName.DATE, SqlTypeName.TIME, SqlTypeName.TIMESTAMP ->
                builder.setDatetimeValue(isoDatetimeOf(lit)).build()
            // CalciteExtParser (CEP-P1) — datepart SYMBOL operand. DATEADD/DATEDIFF/DATEPART carry
            // their time unit as a SYMBOL RexLiteral (e.g. `FLAG(DAY)`, an avatica TimeUnit enum).
            // The wire format has no symbol slot, so carry the enum constant's name as a string under
            // a `symbol:<EnumSimpleName>` type tag; decode rebuilds it via RexBuilder.makeFlag.
            SqlTypeName.SYMBOL -> {
                val enumValue =
                    lit.value as? Enum<*>
                        ?: throw UnsupportedOperationException(
                            "SYMBOL literal value '${lit.value}' is not an enum; not in the v1 wire format",
                        )
                builder
                    .setType("$SYMBOL_TYPE_TAG:${enumValue.javaClass.simpleName}")
                    .setStringValue(enumValue.name)
                    .build()
            }
            else -> throw UnsupportedOperationException(
                "Literal of SqlTypeName '${lit.type.sqlTypeName}' is not in the v1 wire format",
            )
        }
    }

    private fun operationCode(call: RexCall): String =
        when (call.kind) {
            SqlKind.AND -> "and"
            SqlKind.OR -> "or"
            SqlKind.NOT -> "not"
            SqlKind.EQUALS -> "eq"
            SqlKind.NOT_EQUALS -> "ne"
            SqlKind.LESS_THAN -> "lt"
            SqlKind.LESS_THAN_OR_EQUAL -> "le"
            SqlKind.GREATER_THAN -> "gt"
            SqlKind.GREATER_THAN_OR_EQUAL -> "ge"
            SqlKind.PLUS -> "add"
            SqlKind.MINUS -> "sub"
            SqlKind.TIMES -> "mul"
            SqlKind.DIVIDE -> "div"
            SqlKind.IS_NULL -> "is_null"
            SqlKind.IS_NOT_NULL -> "is_not_null"
            // Phase 08 B4 / DF-S05 + DF-DSL04 — first-class set/pattern membership.
            SqlKind.IN -> "in"
            SqlKind.LIKE -> "like"
            // TF-P1.S3 (G C8) — T-SQL TRY_CAST; Calcite builds it as SAFE_CAST (or TRY_CAST, same kind).
            SqlKind.SAFE_CAST -> "safe_cast"
            // TF-P5 — a model-declared function rides under its qualified name (`dbo.fn_price`).
            else -> FunctionCatalog.wireName(call.operator)
        }

    /**
     * Decode an [Expression] back into a [RexNode] using the supplied
     * [RelBuilder]. Inverse of [encode].
     *
     * Column references are resolved against the builder's current peek's
     * row type (so `decode` MUST be called after the input has been pushed
     * onto the builder's stack).
     *
     * [catalog] resolves function-syntax operators by wire name; TF-P5 — pass the framework's
     * [org.tatrman.translator.framework.TranslatorFramework.functionCatalog] so model-declared functions
     * decode.
     */
    fun decode(
        builder: RelBuilder,
        expr: Expression,
        catalog: FunctionCatalog = FunctionCatalog.DEFAULT,
    ): RexNode =
        when (expr.exprCase) {
            Expression.ExprCase.LITERAL -> decodeLiteral(builder, expr.literal)
            Expression.ExprCase.COLUMN_REF -> decodeColumnRef(builder, expr.columnRef)
            Expression.ExprCase.FUNCTION ->
                // CAST is encoded as a FunctionCall (operation="cast") whose target type
                // rides on the *Expression's* result_type, not on the call — so it can't
                // go through the generic operator path (which has no type to cast to).
                if (expr.function.operation.equals("cast", ignoreCase = true)) {
                    decodeCast(builder, expr.function, expr.resultType, safe = false, catalog)
                } else if (expr.function.operation.equals("safe_cast", ignoreCase = true)) {
                    decodeCast(builder, expr.function, expr.resultType, safe = true, catalog)
                } else {
                    decodeFunctionCall(builder, expr.function, catalog)
                }
            Expression.ExprCase.PARAMETER ->
                decodeParameter(builder, expr.parameter, expr.resultType)
            Expression.ExprCase.SUBQUERY ->
                decodeSubquery(builder, expr.subquery, catalog)
            Expression.ExprCase.OVER ->
                decodeOver(builder, expr.over, expr.resultType, catalog)
            Expression.ExprCase.CAST ->
                throw UnsupportedOperationException(
                    "CastExpression decoding is TODO; v1 codecs preserve casts via Expression.cast",
                )
            else -> throw UnsupportedOperationException(
                "Expression case '${expr.exprCase}' is not in the v1 wire format",
            )
        }

    private fun decodeLiteral(
        builder: RelBuilder,
        lit: Literal,
    ): RexNode {
        if (lit.isNull) return builder.literal(null)
        // CalciteExtParser (CEP-P1) — datepart SYMBOL operand. A `symbol:<EnumSimpleName>`-tagged
        // string is an enum-flag constant name; rebuild the SYMBOL RexLiteral via makeFlag so the
        // datetime operator (DATEADD/DATEDIFF/DATEPART) re-validates and unparses the datepart. Only
        // TimeUnit/TimeUnitRange are supported; any other symbol enum is rejected with a clear error
        // (not silently mis-rebuilt). The bare `symbol` tag (no enum suffix) is accepted for back-compat.
        if (lit.type == SYMBOL_TYPE_TAG || lit.type.startsWith("$SYMBOL_TYPE_TAG:")) {
            val enumKind = lit.type.substringAfter(':', missingDelimiterValue = "TimeUnit")
            if (enumKind != "TimeUnit" && enumKind != "TimeUnitRange") {
                throw UnsupportedOperationException(
                    "Symbol literal of enum '$enumKind' is not supported in the v1 wire format",
                )
            }
            return builder.rexBuilder.makeFlag(
                org.apache.calcite.avatica.util.TimeUnit
                    .valueOf(lit.stringValue),
            )
        }
        return when (lit.valueCase) {
            Literal.ValueCase.STRING_VALUE -> builder.literal(lit.stringValue)
            Literal.ValueCase.INT_VALUE -> builder.literal(lit.intValue)
            // TF-P1.S1 (G A1) — an exact literal: `RelBuilder.literal(Double)` builds an approximate one,
            // which MSSQL renders in E-notation (`2.5E-1`) — a T-SQL FLOAT constant, turning decimal
            // arithmetic into float arithmetic. `BigDecimal.valueOf` keeps the written digits (`0.25`,
            // `100.0`) and re-encodes to the same double.
            Literal.ValueCase.FLOAT_VALUE ->
                if (lit.floatValue.isFinite()) {
                    builder.rexBuilder.makeExactLiteral(java.math.BigDecimal.valueOf(lit.floatValue))
                } else {
                    builder.literal(lit.floatValue)
                }
            Literal.ValueCase.BOOL_VALUE -> builder.literal(lit.boolValue)
            // A TYPED temporal literal. `builder.literal(String)` built a CHARACTER literal, so a date
            // bound came back typed `text` and was compared to a date column as a string.
            Literal.ValueCase.DATETIME_VALUE ->
                temporalLiteral(builder.rexBuilder, datetimeLiteralValue(lit.datetimeValue))
            else -> builder.literal(null)
        }
    }

    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val ISO_TIME = Regex("""\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?""")

    /**
     * `datetime_value` (ISO-8601) → the Calcite temporal value [RelBuilder.literal] types correctly:
     * a date-only value becomes a DATE ([org.apache.calcite.util.DateString]), a time-only value a
     * TIME ([org.apache.calcite.util.TimeString]), and a date with a time a TIMESTAMP
     * ([org.apache.calcite.util.TimestampString]). An instant with an offset (`…Z`, `…+02:00`) is
     * normalised to UTC — the zone [isoDatetimeOf] writes back, so encode → decode → encode is stable;
     * a zone-less local date-time is taken as written.
     *
     * Anything else throws. The previous fallback, a string literal, is exactly how a malformed bound
     * became a silent text comparison; a clear failure is the better outcome.
     */
    private fun datetimeLiteralValue(value: String): Any =
        runCatching {
            when {
                ISO_DATE.matches(value) ->
                    org.apache.calcite.util
                        .DateString(value)
                ISO_TIME.matches(value) ->
                    org.apache.calcite.util
                        .TimeString(value)
                else -> {
                    val millis =
                        runCatching {
                            java.time.OffsetDateTime
                                .parse(value)
                                .toInstant()
                                .toEpochMilli()
                        }.getOrElse {
                            java.time.LocalDateTime
                                .parse(value.replace(' ', 'T'))
                                .toInstant(java.time.ZoneOffset.UTC)
                                .toEpochMilli()
                        }
                    org.apache.calcite.util.TimestampString
                        .fromMillisSinceEpoch(millis)
                }
            }
        }.getOrElse { cause ->
            throw UnsupportedOperationException(
                "datetime_value '$value' is not ISO-8601 (plan.v1 Literal.datetime_value) — expected a " +
                    "date (2025-01-01), a time (12:30:00) or a date-time (2025-01-01T00:00:00Z)",
                cause,
            )
        }

    /**
     * The typed [RexLiteral] for a value [datetimeLiteralValue] produced. Built through the
     * [org.apache.calcite.rex.RexBuilder] directly: `RelBuilder.literal(Object)` does not accept Calcite's
     * temporal value types ("cannot convert … to a constant"). Precision is 0 unless the value carries
     * milliseconds, so a whole-second bound unparses as `TIMESTAMP '2025-01-01 00:00:00'`.
     */
    private fun temporalLiteral(
        rex: org.apache.calcite.rex.RexBuilder,
        value: Any,
    ): RexNode =
        when (value) {
            is org.apache.calcite.util.DateString -> rex.makeDateLiteral(value)
            is org.apache.calcite.util.TimeString ->
                rex.makeTimeLiteral(value, if (Math.floorMod(value.millisOfDay, 1000) == 0) 0 else 3)
            is org.apache.calcite.util.TimestampString ->
                rex.makeTimestampLiteral(value, if (Math.floorMod(value.millisSinceEpoch, 1000L) == 0L) 0 else 3)
            else -> throw IllegalStateException("not a temporal value: ${value::class.java.name}")
        }

    /** A DATE / TIME / TIMESTAMP [RexLiteral] as the ISO-8601 text `datetime_value` carries — see [datetimeLiteralValue]. */
    private fun isoDatetimeOf(lit: RexLiteral): String =
        when (lit.type.sqlTypeName) {
            SqlTypeName.DATE ->
                lit.getValueAs(org.apache.calcite.util.DateString::class.java)!!.toString()
            SqlTypeName.TIME ->
                lit.getValueAs(org.apache.calcite.util.TimeString::class.java)!!.toString()
            else ->
                java.time.Instant
                    .ofEpochMilli(
                        lit.getValueAs(org.apache.calcite.util.TimestampString::class.java)!!.millisSinceEpoch,
                    ).toString()
        }

    private fun decodeColumnRef(
        builder: RelBuilder,
        ref: ColumnRef,
    ): RexNode {
        val name = ref.name
        // Phase 08 A1 — RESOLVE: a source_alias hint of `$L` / `$R` routes the lookup into the
        // correct join input. RelBuilder's three-arg `field(inputCount, ord, name)` consults the
        // specified input even when both join inputs are on the builder stack — without the hint
        // the bare `field(name)` only sees the top-of-stack rel and a join's right-side reference
        // mis-resolves into the right input by index, going out of range.
        when (ref.sourceAlias) {
            LEFT_INPUT_TAG -> return fieldByName(builder, 2, 0, name)
            RIGHT_INPUT_TAG -> return fieldByName(builder, 2, 1, name)
        }
        // A `$`-prefix means a positional ref ONLY when the remainder is an integer — the encoder's
        // sole positional fallback shape is `$<index>` (see [encodeInputRef]). Calcite also mints
        // synthetic column *names* that begin with `$` (e.g. `$f0`/`$f1`, the decorrelator's
        // existence-marker columns; `$EXPR$0`); those are genuine field names, not positions, so
        // they must resolve by name rather than be mis-parsed as a malformed index. NX-A: without
        // this, a decorrelated `NOT EXISTS` whose `IS NULL($f1)` references such a marker failed to
        // decode with "Malformed positional column ref '$f1'".
        if (name.startsWith("\$")) {
            val idx = name.drop(1).toIntOrNull()
            if (idx != null) return builder.field(idx)
        }
        return fieldByName(builder, name)
    }

    /**
     * TF-P1.S2 (G A3, contracts §3.2) — resolve [name] against the top of the builder stack, falling back
     * to the row type's ordinal.
     *
     * Above a join whose inputs share a column name, the encoder names a ref from the `LogicalJoin` row
     * type, which Calcite uniquifies (`[ID, NAME, B_ID, ID0, NAME0]`). `RelBuilder.join` keeps the
     * per-input names in its *frame* (`[ID, NAME, B_ID, ID, NAME]`), and `field(String)` looks there, so
     * `field("NAME0")` threw `field [NAME0] not found`. Frame and row type share positions, so the row
     * type's ordinal is the exact field: `field(ordinal)` is a plain `RexInputRef`, so the decoded plan —
     * and its re-encoding — is unchanged. When neither lookup matches, the original error is rethrown.
     */
    internal fun fieldByName(
        builder: RelBuilder,
        name: String,
    ): RexNode = fieldByName(builder, 1, 0, name)

    /**
     * [fieldByName] for input [inputOrdinal] of [inputCount] — a join condition's `$L`/`$R` lookup. The
     * same frame-vs-row-type gap applies when that input is itself a join: in `A JOIN B … JOIN C ON c.x =
     * b.ID` the second condition names `ID0` from the first join's row type (legacy
     * `podprodukty_pro_firmu`).
     */
    internal fun fieldByName(
        builder: RelBuilder,
        inputCount: Int,
        inputOrdinal: Int,
        name: String,
    ): RexNode =
        try {
            builder.field(inputCount, inputOrdinal, name)
        } catch (e: IllegalArgumentException) {
            val ordinal =
                builder
                    .peek(inputCount, inputOrdinal)
                    .rowType.fieldNames
                    .indexOf(name)
            if (ordinal >= 0) builder.field(inputCount, inputOrdinal, ordinal) else throw e
        }

    private fun decodeFunctionCall(
        builder: RelBuilder,
        fn: FunctionCall,
        catalog: FunctionCatalog,
    ): RexNode {
        val operator = operatorFor(fn.operation, catalog)
        val operands = fn.operandsList.map { decode(builder, it, catalog) }
        return builder.call(operator, operands)
    }

    /**
     * Decode a CAST (encoded as `FunctionCall(operation="cast")` with the single value operand;
     * the target type is the wrapping Expression's [resultType] — a physical type code, see
     * [castTargetType]). Rebuilt as a Calcite `RexCall(CAST)` via
     * [org.apache.calcite.rex.RexBuilder.makeCast] so RelToSql renders the dialect-appropriate
     * `CAST(... AS ...)`.
     */
    private fun decodeCast(
        builder: RelBuilder,
        fn: FunctionCall,
        resultType: String,
        safe: Boolean,
        catalog: FunctionCatalog,
    ): RexNode {
        require(fn.operandsCount == 1) { "${fn.operation} expects exactly 1 operand, got ${fn.operandsCount}" }
        val operand = decode(builder, fn.operandsList[0], catalog)
        val targetType = castTargetType(builder.typeFactory, resultType)
        // TF-P1.S3 (G C8) — `safe_cast` (T-SQL TRY_CAST): the same target-type code, a SAFE_CAST call.
        return if (safe) {
            builder.rexBuilder.makeAbstractCast(SqlParserPos.ZERO, targetType, operand, true)
        } else {
            builder.rexBuilder.makeCast(targetType, operand)
        }
    }

    /**
     * TF-P1.S1 (G A9, contracts §3.3) — the physical type code a CAST's target type rides as:
     * `<kind>[:<precision>[,<scale>]]` (`varchar:20`, `decimal:18,2`, `int`, `date`, `datetime`,
     * `datetime2:0`, `varchar:max`). The surface tags (`text`, `float`, …) lost length, precision and
     * scale, and widened a `date` cast to a timestamp that the optimizer then folded away. A type
     * outside the table falls back to its surface tag.
     *
     * Deliberate: `int` is INTEGER and `datetime` is TIMESTAMP(3) here, where the surface tags meant
     * BIGINT / TIMESTAMP. Only casts read these codes ([castTargetType]); parameters keep the surface
     * mapping ([sqlTypeNameFor]).
     */
    internal fun physicalCodeOf(t: RelDataType): String {
        val precision = t.precision
        return when (t.sqlTypeName) {
            SqlTypeName.VARCHAR ->
                when {
                    precision == RelDataType.PRECISION_NOT_SPECIFIED -> "varchar"
                    precision >= VARCHAR_MAX_PRECISION -> "varchar:max"
                    else -> "varchar:$precision"
                }
            SqlTypeName.CHAR -> "char:$precision"
            SqlTypeName.DECIMAL -> "decimal:$precision,${t.scale}"
            SqlTypeName.INTEGER -> "int"
            SqlTypeName.BIGINT -> "bigint"
            SqlTypeName.SMALLINT -> "smallint"
            SqlTypeName.TINYINT -> "tinyint"
            SqlTypeName.BOOLEAN -> "bit"
            SqlTypeName.DOUBLE, SqlTypeName.FLOAT -> "float"
            SqlTypeName.REAL -> "real"
            SqlTypeName.DATE -> "date"
            SqlTypeName.TIME -> if (precision > 0) "time:$precision" else "time"
            SqlTypeName.TIMESTAMP -> if (precision == DATETIME_PRECISION) "datetime" else "datetime2:$precision"
            else -> surfaceTypeOf(t)
        }
    }

    /**
     * Inverse of [physicalCodeOf]. Also accepts the T-SQL-only kinds (contracts §3.3 — `nvarchar`,
     * `money`, `uniqueidentifier`, …) and the pre-TF surface tags `text`, `float`, `bool`, `decimal`
     * that older plans and the TTR-P lowerings still send. Anything else is refused rather than
     * rebuilt as `ANY`.
     */
    private fun castTargetType(
        typeFactory: RelDataTypeFactory,
        code: String,
    ): RelDataType {
        val kind = code.substringBefore(':').lowercase()
        val args =
            code
                .substringAfter(':', "")
                .split(',')
                .filter { it.isNotBlank() }
                .map { it.trim() }

        fun unsupported(): Nothing =
            throw UnsupportedOperationException("Cast target type '$code' is not in the v1 wire format")

        fun number(i: Int): Int? = args.getOrNull(i)?.let { it.toIntOrNull() ?: unsupported() }

        fun sized(
            name: SqlTypeName,
            default: Int? = null,
        ): RelDataType {
            val p = if (args.firstOrNull()?.lowercase() == "max") VARCHAR_MAX_PRECISION else number(0) ?: default
            return if (p == null) typeFactory.createSqlType(name) else typeFactory.createSqlType(name, p)
        }

        fun decimal(
            p: Int?,
            s: Int?,
        ): RelDataType =
            when {
                p == null -> typeFactory.createSqlType(SqlTypeName.DECIMAL)
                s == null -> typeFactory.createSqlType(SqlTypeName.DECIMAL, p)
                else -> typeFactory.createSqlType(SqlTypeName.DECIMAL, p, s)
            }
        return when (kind) {
            "varchar", "nvarchar", "text", "ntext" -> sized(SqlTypeName.VARCHAR)
            "char", "nchar" -> sized(SqlTypeName.CHAR)
            "decimal", "numeric" -> decimal(number(0), number(1))
            "money" -> decimal(19, 4)
            "smallmoney" -> decimal(10, 4)
            "int", "integer" -> typeFactory.createSqlType(SqlTypeName.INTEGER)
            "bigint" -> typeFactory.createSqlType(SqlTypeName.BIGINT)
            "smallint" -> typeFactory.createSqlType(SqlTypeName.SMALLINT)
            "tinyint" -> typeFactory.createSqlType(SqlTypeName.TINYINT)
            "bit", "bool", "boolean" -> typeFactory.createSqlType(SqlTypeName.BOOLEAN)
            "float", "double" -> typeFactory.createSqlType(SqlTypeName.DOUBLE)
            "real" -> typeFactory.createSqlType(SqlTypeName.REAL)
            "date" -> typeFactory.createSqlType(SqlTypeName.DATE)
            "time" -> sized(SqlTypeName.TIME)
            "datetime" -> typeFactory.createSqlType(SqlTypeName.TIMESTAMP, number(0) ?: DATETIME_PRECISION)
            "smalldatetime" -> typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 0)
            "datetime2" -> sized(SqlTypeName.TIMESTAMP, default = DATETIME2_DEFAULT_PRECISION)
            "uniqueidentifier" -> typeFactory.createSqlType(SqlTypeName.CHAR, 36)
            else -> unsupported()
        }
    }

    /** T-SQL `datetime` is millisecond-ish (3.33 ms) — the TIMESTAMP precision it maps to. */
    private const val DATETIME_PRECISION = 3

    /** T-SQL `datetime2` without a precision is `datetime2(7)`. */
    private const val DATETIME2_DEFAULT_PRECISION = 7

    /** `varchar(max)` — Calcite's default type-system ceiling for VARCHAR. */
    private val VARCHAR_MAX_PRECISION: Int =
        org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT
            .getMaxPrecision(SqlTypeName.VARCHAR)

    /** Surface-type tag → the [SqlTypeName] the encoder used. Shared by parameter + cast decoding. */
    private fun sqlTypeNameFor(resultType: String): SqlTypeName =
        when (resultType) {
            "text" -> SqlTypeName.VARCHAR
            "int" -> SqlTypeName.BIGINT
            "float" -> SqlTypeName.DOUBLE
            "bool" -> SqlTypeName.BOOLEAN
            "datetime" -> SqlTypeName.TIMESTAMP
            "date" -> SqlTypeName.DATE
            "decimal" -> SqlTypeName.DECIMAL
            else -> SqlTypeName.ANY
        }

    /**
     * Decode a [SubqueryExpression] back into a Calcite [RexSubQuery]. The nested plan is built
     * into a standalone RelNode that shares [builder]'s [org.apache.calcite.plan.RelOptCluster]
     * — Calcite requires a `RexSubQuery`'s `rel` to live in the same cluster as the outer
     * expression — via [PlanNodeDecoder.decodeSubrel] (push subtree, pop with `build()`, leaving
     * the outer stack balanced). Inverse of [encodeSubquery].
     */
    private fun decodeSubquery(
        builder: RelBuilder,
        sub: SubqueryExpression,
        catalog: FunctionCatalog,
    ): RexNode {
        val subRel = PlanNodeDecoder.decodeSubrel(builder, sub.subquery, catalog)
        return when (sub.kind.lowercase()) {
            "scalar" -> RexSubQuery.scalar(subRel)
            "exists" -> RexSubQuery.exists(subRel)
            "in" ->
                RexSubQuery.`in`(
                    subRel,
                    com.google.common.collect.ImmutableList
                        .copyOf(sub.operandsList.map { decode(builder, it, catalog) }),
                )
            else -> throw UnsupportedOperationException(
                "Subquery kind '${sub.kind}' is not in the v1 wire format",
            )
        }
    }

    private fun decodeOver(
        builder: RelBuilder,
        over: OverExpression,
        resultType: String,
        catalog: FunctionCatalog,
    ): RexNode {
        val type =
            builder.typeFactory.createTypeWithNullability(
                builder.typeFactory.createSqlType(sqlTypeNameFor(resultType)),
                true,
            )
        val exprs = over.operandsList.map { decode(builder, it, catalog) }
        val partitionKeys = over.partitionKeysList.map { decode(builder, it, catalog) }
        val orderKeys =
            over.orderKeysList.map { ok ->
                val dirs = mutableSetOf<SqlKind>()
                if (ok.descending) dirs.add(SqlKind.DESCENDING)
                dirs.add(if (ok.nullsFirst) SqlKind.NULLS_FIRST else SqlKind.NULLS_LAST)
                RexFieldCollation(decode(builder, ok.expr, catalog), dirs)
            }
        return builder.rexBuilder.makeOver(
            type,
            aggOperatorFor(over.aggregate),
            exprs,
            partitionKeys,
            ImmutableList.copyOf(orderKeys),
            frameBoundFor(builder, over.frame.lower, over.frame.lowerOffset),
            frameBoundFor(builder, over.frame.upper, over.frame.upperOffset),
            over.frame.isRows,
            true, // allowPartial
            false, // nullWhenCountZero — the CASE null-on-empty wrapper is explicit in the plan
            over.distinct,
            false, // ignoreNulls
        )
    }

    private fun aggOperatorFor(code: String): SqlAggFunction =
        when (code.lowercase()) {
            "sum" -> SqlStdOperatorTable.SUM
            "count" -> SqlStdOperatorTable.COUNT
            "avg" -> SqlStdOperatorTable.AVG
            "min" -> SqlStdOperatorTable.MIN
            "max" -> SqlStdOperatorTable.MAX
            "row_number" -> SqlStdOperatorTable.ROW_NUMBER
            "rank" -> SqlStdOperatorTable.RANK
            "dense_rank" -> SqlStdOperatorTable.DENSE_RANK
            "ntile" -> SqlStdOperatorTable.NTILE
            "lag" -> SqlStdOperatorTable.LAG
            "lead" -> SqlStdOperatorTable.LEAD
            "first_value" -> SqlStdOperatorTable.FIRST_VALUE
            "last_value" -> SqlStdOperatorTable.LAST_VALUE
            else -> throw UnsupportedOperationException(
                "Window aggregate '$code' is not in the v1 wire format",
            )
        }

    private fun frameBoundFor(
        builder: RelBuilder,
        fb: FrameBound,
        offset: Long,
    ): RexWindowBound =
        when (fb) {
            FrameBound.UNBOUNDED_PRECEDING -> RexWindowBounds.UNBOUNDED_PRECEDING
            FrameBound.CURRENT_ROW -> RexWindowBounds.CURRENT_ROW
            FrameBound.UNBOUNDED_FOLLOWING -> RexWindowBounds.UNBOUNDED_FOLLOWING
            FrameBound.PRECEDING ->
                RexWindowBounds.preceding(builder.rexBuilder.makeExactLiteral(java.math.BigDecimal.valueOf(offset)))
            FrameBound.FOLLOWING ->
                RexWindowBounds.following(builder.rexBuilder.makeExactLiteral(java.math.BigDecimal.valueOf(offset)))
            else -> throw UnsupportedOperationException(
                "Window frame bound '$fb' is not in the v1 wire format",
            )
        }

    private fun decodeParameter(
        builder: RelBuilder,
        param: ParameterRef,
        resultType: String,
    ): RexNode {
        // Calcite's RexDynamicParam needs a RelDataType; surface-type tags map
        // to the same SqlTypeName the encoder used.
        val type = builder.typeFactory.createSqlType(sqlTypeNameFor(resultType))
        return builder.rexBuilder.makeDynamicParam(type, param.positionalIndex)
    }

    private fun operatorFor(
        opName: String,
        catalog: FunctionCatalog,
    ): SqlOperator =
        when (opName.lowercase()) {
            "and" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.AND
            "or" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.OR
            "not" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.NOT
            "eq" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.EQUALS
            "ne" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.NOT_EQUALS
            "lt" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LESS_THAN
            "le" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LESS_THAN_OR_EQUAL
            "gt" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.GREATER_THAN
            "ge" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.GREATER_THAN_OR_EQUAL
            "add" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.PLUS
            "sub" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.MINUS
            "mul" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.MULTIPLY
            "div" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.DIVIDE
            "is_null" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.IS_NULL
            "is_not_null" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.IS_NOT_NULL
            // Phase 08 B4 / DF-S05 + DF-DSL04 — first-class set/pattern membership.
            "in" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.IN
            "like" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LIKE
            // COALESCE — the 3VL-correct FALSE-port complement `not(coalesce(pred, false))` the
            // TTR-P Branch→Filter lowering emits (Rules.notCoalesceFalse); also a stock scalar fn.
            "coalesce" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.COALESCE
            // String / scalar functions emitted by the encoder's `operator.name`
            // fallback (operationCode's else branch). These mirror exactly what
            // pattern SQL produces — `||` concat for LIKE-pattern building,
            // SUBSTRING for prefix tests, and unary minus for negated aggregates.
            "||" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.CONCAT
            "substring" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.SUBSTRING
            "-" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.UNARY_MINUS
            // Searched CASE — operands are Calcite's flat [when1,then1,…,else] list, so it
            // rides the generic FunctionCall like any other operator (the encoder already
            // emits "case" via operationCode's `operator.name` fallback). Calcite lowers
            // windowed aggregates (SUM(...) OVER …) to CASE, so the unparse path needs it.
            "case" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.CASE
            // CalciteExtParser (CEP) — custom postfix COLLATE (Calcite ships no COLLATE operator).
            // COLLATE is SPECIAL syntax, encoded via operationCode's `operator.name` fallback →
            // "collate"; map it back here (the encode side already round-trips it).
            "collate" -> org.tatrman.translator.functions.SqlCollateOperator
            // CEP-P1 — the T-SQL datetime family (Calcite built-ins), encoded via the same
            // `operator.name` fallback. Their datepart operand rides as a SYMBOL literal (see the
            // SYMBOL_TYPE_TAG decode path above).
            "dateadd" -> org.apache.calcite.sql.`fun`.SqlLibraryOperators.DATEADD
            "datediff" -> org.tatrman.translator.functions.DateOperators.DATEDIFF
            "datepart" -> org.apache.calcite.sql.`fun`.SqlLibraryOperators.DATEPART
            "date_part" -> org.apache.calcite.sql.`fun`.SqlLibraryOperators.DATE_PART
            // Standard `EXTRACT(<unit> FROM <datetime>)` — the dialect-agnostic date-part extraction
            // (unparses per dialect: PG/ANSI `EXTRACT`, etc.). Its unit rides as a SYMBOL literal like
            // the DATE* family. Emitted by the MD dot-path inline-viaCalc lowering.
            "extract" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.EXTRACT
            // CEP-P2 — faithful CONVERT / TRY_CONVERT custom operators.
            "convert" -> org.tatrman.translator.functions.ConvertOperators.CONVERT
            "try_convert" -> org.tatrman.translator.functions.ConvertOperators.TRY_CONVERT
            // TF-P1.S3 — decoded by [decodeCast] (the target type rides on result_type); mapped here so
            // a name lookup agrees with the encoder's `operationCode`.
            "safe_cast" -> org.apache.calcite.sql.`fun`.SqlLibraryOperators.SAFE_CAST
            // Catalog-driven decode: function-syntax operators (CONCAT, LEFT, IIF, ISNULL, LEN, …)
            // aren't hand-mapped above; resolve them from the FunctionCatalog, built by enumerating
            // the loaded custom + library operator tables (CalciteOperatorTables). The explicit
            // structural/contract entries above stay the authority — the catalog is only the
            // fallback, so e.g. the binary `||` is never conflated with the function-syntax "concat".
            else ->
                catalog
                    .lookup(opName)
                    ?: throw UnsupportedOperationException(
                        "Operator '$opName' is not in the v1 wire format",
                    )
        }

    internal fun surfaceTypeOf(t: RelDataType): String =
        when (t.sqlTypeName) {
            SqlTypeName.VARCHAR, SqlTypeName.CHAR -> "text"
            SqlTypeName.BOOLEAN -> "bool"
            SqlTypeName.INTEGER, SqlTypeName.BIGINT, SqlTypeName.SMALLINT, SqlTypeName.TINYINT -> "int"
            SqlTypeName.DECIMAL, SqlTypeName.DOUBLE, SqlTypeName.FLOAT, SqlTypeName.REAL -> "float"
            SqlTypeName.DATE, SqlTypeName.TIME, SqlTypeName.TIMESTAMP -> "datetime"
            else -> "unknown:${t.sqlTypeName}"
        }
}
