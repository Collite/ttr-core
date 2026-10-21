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

// ---- the conceptual-model definition forms (TTRP 0.5, CN-D29 — cn contracts §1.1 "The 0.5 spellings") ----------

/**
 * A form only a cn `definition:` / `members:` / `filter:` block reads (CN-P2 S2.6). The grammar parses it anywhere —
 * it stays target-neutral — and a TTR-P document refuses it wherever it sits (`TTRP-CN-001`). One sealed family, so a
 * consumer that never reads them (the emitters, the graph rules) answers them in one arm.
 */
sealed interface ConceptForm : Expression

/** `Visit` · `Visit v1` — a concept an `exists` or a concept aggregate ranges over, with its variable. */
data class ConceptBinding(
    val concept: String,
    val variable: String?,
    val location: SourceLocation,
)

/** `this as customer` · `this.request.order` — a participant reached by its path ([ThisRef] or [ColumnRef]). */
data class Participant(
    val path: Expression,
    val label: String?,
    val location: SourceLocation,
)

/**
 * `[not] exists Invoicing involving this as customer where OverdueInvoice` (DC-2 / DC-7 / DC-11). [where] is null
 * for a bare existence (`not exists OrderLine involving this.request.order, this.item`).
 */
data class ExistsExpr(
    val concepts: List<ConceptBinding>,
    val involving: List<Participant>,
    val where: Expression?,
    val negated: Boolean,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : ConceptForm

/**
 * A concept aggregate (DC-4 / DC-9): `count(StoreVisit involving this) >= 4`, `sum(Sale involving this where …).revenue`,
 * `latest(Inventory involving this.item, this.warehouse, by: as_of_date).quantity_on_hand`. [function] is the surface
 * name (the cn checker knows which it reads); [member] is the quality path after the call.
 */
data class ConceptAggregate(
    val function: String,
    val concepts: List<ConceptBinding>,
    val involving: List<Participant>,
    val where: Expression?,
    val namedArgs: List<Pair<String, Expression>>,
    val member: List<String>,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : ConceptForm

/** `this` — the concept a definition is of — and a path from it: `this.from.period.end` → `[from, period, end]`. */
data class ThisRef(
    val path: List<String>,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : ConceptForm

/** `category [not] in descendants(ProductCategory, 'H10-H19')` (DC-1 / DC-8) — membership in what a call names. */
data class InCall(
    val expr: Expression,
    val call: Expression,
    val negated: Boolean,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : ConceptForm

/** The DC-12 words, three-valued per the concept's `imprecision:` (AM-42); both renderers expand them (A-CN-14). */
enum class TemporalOp(
    val spelling: String,
) {
    AT_OR_BEFORE("at_or_before"),
    BEFORE("before"),
    CONTAINS("contains"),
    ENDED_BEFORE("ended_before"),
    OVERLAPS("overlaps"),
    ;

    companion object {
        fun of(spelling: String): TemporalOp? = entries.firstOrNull { it.spelling == spelling }
    }
}

/** `d.period.start at_or_before :as_of` · `e.period contains :as_of` · `s.period overlaps e.period` (DC-12). */
data class TemporalPredicate(
    val op: TemporalOp,
    val lhs: Expression,
    val rhs: Expression,
    override val location: SourceLocation,
    override val type: TtrpType? = null,
) : ConceptForm
