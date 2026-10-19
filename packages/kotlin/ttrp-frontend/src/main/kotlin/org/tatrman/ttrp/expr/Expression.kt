// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.expr

import org.tatrman.ttrp.ast.SourceLocation

/**
 * A stable catalogue id — the identity a function/operator carries across surfaces
 * and what capability manifests reference (T5-c / B-T6 β). Value class over a
 * `String` such as `"op.eq"`, `"fn.avg"`, `"agg.sum"`.
 */
@JvmInline
value class CatalogId(
    val value: String,
) {
    /** The surface spelling behind a prefixed id (`fn.avg` -> `avg`); for a bare id, itself. */
    val name: String get() = value.substringAfterLast('.')

    override fun toString(): String = value

    companion object {
        // Operators are ordinary catalogue ids — one uniform [FunctionCall] arm (plan.v1 shape).
        val OR = CatalogId("op.or")
        val AND = CatalogId("op.and")
        val NOT = CatalogId("op.not")
        val EQ = CatalogId("op.eq")
        val NEQ = CatalogId("op.neq")
        val LT = CatalogId("op.lt")
        val LTE = CatalogId("op.lte")
        val GT = CatalogId("op.gt")
        val GTE = CatalogId("op.gte")
        val ADD = CatalogId("op.add")
        val SUB = CatalogId("op.sub")
        val MUL = CatalogId("op.mul")
        val DIV = CatalogId("op.div")
        val NEG = CatalogId("op.neg")
    }
}

/** A literal payload — the four scalar literal shapes TTR-P expressions carry. */
sealed interface LiteralValue {
    data class Str(
        val value: String,
    ) : LiteralValue

    data class Num(
        val raw: String,
    ) : LiteralValue

    data class Bool(
        val value: Boolean,
    ) : LiteralValue

    data object Null : LiteralValue
}

/**
 * The ONE PL expression IR (T5-e) — a deliberate structural twin of kantheon's
 * `plan.v1.Expression` (B-T3 "own IR, adapt") so the P3 lowering in ttr-translator
 * is mechanical. No proto is imported here (S25 vendors it downstream, not in the
 * front-half). Binary/unary operators are [FunctionCall]s over catalogue ids
 * (`op.and`, `op.eq`, `op.add`, …) — one uniform arm, exactly like plan.v1; the
 * walker does the folding. There is no lambda arm and no subquery arm (B-T5 sweep:
 * not in the v1 IR).
 *
 * [type] is `null` until [ExpressionTypechecker] annotates it; every node carries a
 * [SourceLocation] for surgical diagnostics/edits.
 */
sealed interface Expression {
    val location: SourceLocation
    val type: TtrpType?
}

/** A column reference, port-qualified per C3-a-iv-4 (`left.x`); [port] null = unqualified. */
data class ColumnRef(
    val port: String?,
    val column: String,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

data class Literal(
    val value: LiteralValue,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

/**
 * An MD dot-path in expression position (contracts §1.2/§3, D14). The walker captures the raw,
 * order-free component list; classification and resolution to a canonical path are S2/S3 — this
 * node only preserves the parsed structure. [type] stays null until MD typing (S3, R15).
 */
data class MdPath(
    val components: List<MdPathComponent>,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

/** One component of an [MdPath] (contracts §1.2 `pathComponent`). */
sealed interface MdPathComponent {
    val location: SourceLocation

    /** A bare identifier: member / level / measure / agg / cubelet (classified in S2). */
    data class Name(
        val text: String,
        override val location: SourceLocation,
    ) : MdPathComponent

    /** A numeric member (`2025`, `06`). */
    data class IntLit(
        val text: String,
        override val location: SourceLocation,
    ) : MdPathComponent

    /** A quoted member (`"Kaufland K123"`); [text] excludes the surrounding quotes. */
    data class StrLit(
        val text: String,
        override val location: SourceLocation,
    ) : MdPathComponent

    /** A member set `{a, b}` (D15 — braces compulsory). */
    data class MemberSet(
        val atoms: List<MdPathAtom>,
        override val location: SourceLocation,
    ) : MdPathComponent

    /** A range `lo..hi` (ordered domains, R7). */
    data class Range(
        val lo: MdPathAtom,
        val hi: MdPathAtom,
        override val location: SourceLocation,
    ) : MdPathComponent

    /** The free-dimension star `*` (bound to an attribute in S2, R7). */
    data class Star(
        override val location: SourceLocation,
    ) : MdPathComponent
}

/** An atom inside a set/range (contracts §1.2 `pathAtom`). */
sealed interface MdPathAtom {
    val text: String
    val location: SourceLocation

    data class Name(
        override val text: String,
        override val location: SourceLocation,
    ) : MdPathAtom

    data class IntLit(
        override val text: String,
        override val location: SourceLocation,
    ) : MdPathAtom

    data class StrLit(
        override val text: String,
        override val location: SourceLocation,
    ) : MdPathAtom
}

/**
 * A scalar function or operator call (operators are catalogue ids — B-T5). [argNames] is empty for an
 * all-positional call (every call before AU-core-P0, and every operator); otherwise it is parallel to
 * [args], `null` for a positional argument — `age(since: x)`, `working_days(between: a, and: :as_of)`.
 */
data class FunctionCall(
    val function: CatalogId,
    val args: List<Expression>,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
    val argNames: List<String?> = emptyList(),
) : Expression

/**
 * An aggregate call — a DISTINCT IR arm, never a [FunctionCall] (B-T5 sweep). Only
 * legal inside `aggregate(…)` / `aggregate { … }`; anywhere else is `TTRP-AGG-001`.
 */
data class AggregateCall(
    val function: CatalogId,
    val args: List<Expression>,
    val distinct: Boolean,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

/** An explicit cast — the ONLY coercion the IR carries; implicit widening is applied in-place, never materialized. */
data class Cast(
    val expr: Expression,
    val target: TtrpType,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

data class CaseWhen(
    val branches: List<Pair<Expression, Expression>>,
    val elseExpr: Expression?,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

data class InList(
    val expr: Expression,
    val items: List<Expression>,
    val negated: Boolean,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

data class IsNull(
    val expr: Expression,
    val negated: Boolean,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

/**
 * `:name` (AU-core-P0, D-108) — a recipe parameter (`:tolerance`, declared in the recipe's
 * `parameters:` and a `def parameter`) or a runtime binding (`:as_of`, reserved; a port / event
 * value). Which one is the S1.3 checker's question (TTRP-REC-020); the walker only records the name.
 */
data class ParamRef(
    val name: String,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : Expression

/** The units a duration is written in (AU contracts §1 `durationLiteral`). */
enum class DurationUnit(
    val spelling: String,
) {
    DAYS("days"),
    WORKING_DAYS("working_days"),
    WEEKS("weeks"),
    MONTHS("months"),
    MINUTES("minutes"),
    HOURS("hours"),
    ;

    companion object {
        fun of(spelling: String): DurationUnit? = entries.firstOrNull { it.spelling == spelling }
    }
}

/** A duration (AU-core-P0, D-108): a literal amount or a parameter's value, in a [DurationUnit]. */
sealed interface DurationExpr : Expression {
    val unit: DurationUnit
}

/** `30 days` · `2 working_days`. */
data class DurationLiteral(
    val amount: Long,
    override val unit: DurationUnit,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : DurationExpr

/** `days(:inbound_reminder)` · `working_days(:settlement)` — the amount is a parameter's value (17 §5). */
data class DurationParam(
    override val unit: DurationUnit,
    val param: String,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : DurationExpr
