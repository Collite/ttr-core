// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.sql

import org.tatrman.ttrp.emit.EmitDiagnosticId
import org.tatrman.ttrp.emit.TtrpEmitException
import org.tatrman.ttrp.expr.Cast
import org.tatrman.ttrp.expr.ColumnRef
import org.tatrman.ttrp.expr.Expression
import org.tatrman.ttrp.expr.FunctionCall
import org.tatrman.ttrp.expr.Literal
import org.tatrman.ttrp.expr.LiteralValue
import org.tatrman.ttrp.expr.TtrpType
import org.tatrman.ttrp.expr.catalog.ValidityCatalog
import org.tatrman.ttrp.expr.catalog.ValiditySpec
import org.tatrman.ttrp.graph.capability.RejectDomain
import org.tatrman.ttrp.graph.capability.RejectsSupport
import org.tatrman.ttrp.resolve.ColumnType

/**
 * Renders the `internal.*` validity calls a reject guard carries directly to Postgres SQL
 * (Option E, RJ-P3). The canonical validity domains (contracts §2 / RJ-P0 YAMLs) are **regex**-
 * based, which the `plan.v1` wire + Calcite path cannot carry (no POSIX-regex operator, no raw-SQL
 * escape) — so the guard column is composed here as raw dialect SQL from the [ValidityCatalog]
 * domain, while every other column still routes through the translator ([CtePlanner]).
 *
 * This IS the guard emit contract (task 3.1.2): the SQL below realizes each YAML `domain`
 * (`trim` → ASCII-whitespace `btrim`, `regex` → `~`, `bounds` → a post-regex numeric range), NULL
 * as success per `null_is_success`.
 */
object RejectGuardSql {
    /** PG escape-string for the ASCII-whitespace trim set (space, tab, LF, CR, FF, VT). */
    private const val ASCII_WS = "E' \\t\\n\\r\\f\\x0B'"

    /** True if [e] is an `internal.*` validity call this renderer handles. */
    fun isInternal(e: Expression): Boolean = e is FunctionCall && e.function.value.startsWith("internal.")

    /**
     * True for a synthesized `_ttrp_v<n>` validity flag — internal to the guard/branch, never part
     * of any OUT or `rejects` port schema (contracts §1). Excluded from every passthrough so it
     * neither leaks into outputs nor appears in the reject rows (which carry only `_ttrp_reject_*`).
     */
    fun isValidityFlag(name: String): Boolean = name.startsWith("_ttrp_v")

    /**
     * The raw PG boolean expression for an `internal.*` validity call, or null if unrecognized.
     * [arg] renders a sub-expression operand (a column, cast, or literal) to PG SQL.
     */
    fun render(
        call: FunctionCall,
        arg: (Expression) -> String,
        rejects: RejectsSupport? = null,
    ): String? =
        when (call.function.value) {
            "internal.is_castable" -> {
                val x = arg(call.args[0])
                val suffix = strLit(call.args.getOrNull(1))
                val pair = canonicalPair(suffix)
                // Native form is emit-usable ONLY where the engine proved `domain: canonical`
                // (contracts §3); every shipped PG entry is `guard`, so this is normally the
                // canonical guard below, but a canonical entry emits the engine's native oracle.
                val entry = rejects?.entry("cast", pair)
                if (entry?.domain == RejectDomain.CANONICAL && entry.nativeForm != null) {
                    nativeCastGuard(entry.nativeForm!!, x, suffix)
                } else {
                    ValidityCatalog.rejectCapability("cast", pair)?.let { castGuard(it, x) }
                }
            }
            "internal.is_nonzero" -> {
                val x = arg(call.args[0])
                val spec = ValidityCatalog.rejectCapability("op.div", "numeric,numeric->numeric")
                nullSafe(x, "($x) <> 0", spec?.nullIsSuccess ?: true)
            }
            "internal.is_parseable_dt" -> {
                val x = arg(call.args[0])
                // Portable floor: a format-anchored parse validity. Without a native TRY path we
                // fall back to the spec's regex when present, else accept-non-null (guard refined
                // per-format in a later pass; the fixture roster is cast-based).
                ValidityCatalog.all
                    .firstOrNull { it.function == "fn.to_date" || it.function == "fn.to_timestamp" }
                    ?.let { spec -> spec.domain.regex?.let { nullSafe(x, "$x ~ '${esc(it)}'", spec.nullIsSuccess) } }
                    ?: nullSafe(x, "$x IS NOT NULL", true)
            }
            else -> null
        }

    /** The engine's native validity oracle (e.g. `pg_input_is_valid("x", 'bigint')`), NULL-safe. */
    private fun nativeCastGuard(
        nativeForm: String,
        x: String,
        suffix: String?,
    ): String = "$x IS NULL OR $nativeForm($x, '${pgType(suffix)}')"

    /** cast-suffix → the PG type name the native oracle names (`text->int64` ⇒ `bigint`). */
    private fun pgType(suffix: String?): String =
        when (suffix) {
            "int64" -> "bigint"
            "decimal18_4" -> "numeric(18,4)"
            "float64" -> "double precision"
            "date" -> "date"
            "timestamp" -> "timestamp"
            "bool" -> "boolean"
            else -> suffix ?: "text"
        }

    /** cast text->T guard: ASCII-ws trim, canonical regex, then the post-regex numeric bounds. */
    private fun castGuard(
        spec: ValiditySpec,
        x: String,
    ): String {
        val d = spec.domain
        val trimmed = if (d.trim == "ascii-ws") "btrim($x, $ASCII_WS)" else x
        val checks = mutableListOf<String>()
        d.regex?.let { checks += "$trimmed ~ '${esc(it)}'" }
        d.bounds?.let { checks += boundsCheck(trimmed, it) }
        // Defense-in-depth (RJ-P5 review, B1): a spec with no renderable check would collapse to an
        // accept-all guard (`x IS NULL OR true`), silently routing invalid rows to the clean stream to
        // crash at the clean cast. That domain must be fail-closed at TTRP-RJ-107 and never reach emit;
        // fail loud if one slips through rather than emit a degenerate guard.
        require(checks.isNotEmpty()) {
            "reject guard for ${spec.function} ${spec.typePair} has no renderable checks — an unsupported " +
                "validity domain reached SQL emit (should be fail-closed at TTRP-RJ-107)"
        }
        val body = if (checks.size > 1) "(${checks.joinToString(" AND ")})" else checks.first()
        return nullSafe(x, body, spec.nullIsSuccess)
    }

    /**
     * Post-regex range check: the string is already digits(+sign), so a numeric cast is safe here.
     * SAFETY (F4, RJ-P5 review): this `::numeric` cast on a non-numeric row is prevented only by the
     * left-to-right short-circuit of the surrounding `regex AND bounds` / `x IS NULL OR …` — valid in
     * a SELECT target-list `BoolExpr` (where PG evaluates left-to-right). Do NOT hoist this expression
     * into a `WHERE` qual or reorder the conjuncts, or the planner may evaluate the cast on rejected
     * rows and abort the statement. The guard is emitted only into the target list (contracts §6).
     */
    private fun boundsCheck(
        trimmed: String,
        bounds: String,
    ): String =
        when (bounds) {
            "int64" -> "$trimmed::numeric BETWEEN -9223372036854775808 AND 9223372036854775807"
            "int32" -> "$trimmed::numeric BETWEEN -2147483648 AND 2147483647"
            else -> "true" // decimal/float bounds ride the regex; refined per-type in a later pass
        }

    private fun nullSafe(
        x: String,
        body: String,
        nullSuccess: Boolean,
    ): String = if (nullSuccess) "$x IS NULL OR $body" else body

    private fun strLit(e: Expression?): String? = ((e as? Literal)?.value as? LiteralValue.Str)?.value

    private fun esc(s: String): String = s.replace("'", "''")

    /** cast-suffix (`internal.is_castable`'s 2nd arg) → canonical §2 type-pair spelling. */
    private fun canonicalPair(suffix: String?): String =
        when (suffix) {
            null -> "text->?"
            "decimal18_4" -> "text->decimal(18,4)"
            else -> "text->$suffix"
        }

    /**
     * The clean-side rendering of a reject-capable [Cast] inside a **guarded** island: the canonical
     * §2 target type (`text->int64` ⇒ `bigint`, not the authored `integer`). This aligns the clean
     * *value* with the int64 guard domain — a value in `(int32max, int64max]` passes the guard, so a
     * narrower `AS integer` cast would overflow at runtime — and with the Polars `pl.Int64` clean
     * cast, so the two engines' clean streams conform on both schema and value (RJ-P5). Null for a
     * non-reject-capable cast; the caller then keeps the authored spelling via [renderArg] (and the
     * fail-fast twin, whose plan has no guard, never routes a cast here — R-P3 byte-identity).
     */
    fun renderCleanCast(cast: Cast): String? {
        val suffix = typeSuffix(cast.target) ?: return null
        ValidityCatalog.rejectCapability("cast", canonicalPair(suffix)) ?: return null
        return "CAST(${renderArg(cast.expr)} AS ${pgType(suffix)})"
    }

    /** A cast-target [org.tatrman.ttrp.expr.TtrpType] → its canonical §2 suffix, or null if not reject-capable. */
    private fun typeSuffix(t: org.tatrman.ttrp.expr.TtrpType): String? =
        when (t) {
            org.tatrman.ttrp.expr.TtrpType.Integer -> "int64"
            is org.tatrman.ttrp.expr.TtrpType.Decimal -> "decimal18_4"
            org.tatrman.ttrp.expr.TtrpType.Float,
            org.tatrman.ttrp.expr.TtrpType.Double,
            org.tatrman.ttrp.expr.TtrpType.Number,
            -> "float64"
            org.tatrman.ttrp.expr.TtrpType.Date -> "date"
            org.tatrman.ttrp.expr.TtrpType.Timestamp, org.tatrman.ttrp.expr.TtrpType.Datetime -> "timestamp"
            org.tatrman.ttrp.expr.TtrpType.Bool -> "bool"
            else -> null
        }

    // ---- a minimal PG renderer for the validity call's operand (column / cast / literal) ----

    fun renderArg(e: Expression): String =
        when (e) {
            // Quote-double any embedded `"` so an identifier can't break out of the raw-SQL guard
            // (F3, RJ-P5 review). Unreachable from authored TTR-P today (the `IDENT` grammar rule
            // excludes quotes and `_ttrp_` is reserved), but defensive for externally-introspected
            // column names that may flow through a guard/clean CTE — parity with the Calcite path.
            is ColumnRef -> "\"${e.column.replace("\"", "\"\"")}\""
            is Literal ->
                when (val v = e.value) {
                    is LiteralValue.Str -> "'${esc(v.value)}'"
                    is LiteralValue.Num -> v.raw
                    is LiteralValue.Bool -> v.value.toString()
                    LiteralValue.Null -> "NULL"
                }
            is Cast -> "CAST(${renderArg(e.expr)} AS ${pgCastType(e.target)})"
            is FunctionCall -> renderCall(e)
            else -> throw IllegalArgumentException("reject guard operand not renderable to PG SQL: $e")
        }

    /**
     * The Postgres type a raw-rendered `cast(x as <t>)` names. The TTR-P canonical spellings are not all Postgres
     * types (`string`, `double`, `number`, `datetime` are not), so a cast calc on a Postgres island rendered
     * `CAST("order_id" AS string)` — invalid SQL. `integer` and an unsized `decimal` keep their spelling (the
     * fail-fast twin's casts stay byte-identical, R-P3); a decimal keeps its precision/scale; a custom type id that
     * is a SQL spelling ([ColumnType]) maps to its Postgres type; object / list have no cast.
     */
    fun pgCastType(t: TtrpType): String =
        when (t) {
            TtrpType.Integer -> "integer"
            TtrpType.Float -> "float"
            TtrpType.Double -> "double precision"
            TtrpType.Number -> "numeric"
            is TtrpType.Decimal ->
                when {
                    t.precision == null -> "decimal"
                    t.scale == null -> "decimal(${t.precision})"
                    else -> "decimal(${t.precision},${t.scale})"
                }
            TtrpType.Bool -> "boolean"
            TtrpType.Str -> "text"
            TtrpType.Date -> "date"
            TtrpType.Timestamp, TtrpType.Datetime -> "timestamp"
            is TtrpType.Named -> ColumnType.parse(t.name)?.let { pgType(it) } ?: noPgCast(t.canonical)
            TtrpType.Obj, TtrpType.Lst -> noPgCast(t.canonical)
        }

    private fun pgType(t: ColumnType): String =
        when (t.kind) {
            ColumnType.Kind.TEXT -> t.length?.let { "varchar($it)" } ?: "text"
            ColumnType.Kind.INT -> "integer"
            ColumnType.Kind.BIGINT -> "bigint"
            ColumnType.Kind.SMALLINT, ColumnType.Kind.TINYINT -> "smallint"
            ColumnType.Kind.DECIMAL -> "numeric(${t.precision},${t.scale})"
            ColumnType.Kind.FLOAT -> "double precision"
            ColumnType.Kind.REAL -> "real"
            ColumnType.Kind.BOOL -> "boolean"
            ColumnType.Kind.DATE -> "date"
            ColumnType.Kind.TIME -> "time"
            ColumnType.Kind.DATETIME, ColumnType.Kind.TIMESTAMP -> "timestamp"
        }

    private fun noPgCast(type: String): Nothing =
        throw TtrpEmitException(
            EmitDiagnosticId.UNSUPPORTED_NODE,
            detail =
                "no Postgres cast to `$type` — cast to a scalar type " +
                    "(text, int, decimal, float, bool, date, datetime)",
        )

    /** `op.*` operators render infix (`div` is `/`, true division — never a function call); `fn.*` prefix. */
    private fun renderCall(e: FunctionCall): String {
        val a = e.args.map { renderArg(it) }
        val infix = INFIX_OPS[e.function.name]
        return when {
            infix != null && a.size == 2 -> "(${a[0]} $infix ${a[1]})"
            e.function.name == "not" && a.size == 1 -> "(NOT ${a[0]})"
            e.function.name == "neg" && a.size == 1 -> "(-${a[0]})"
            else -> "${e.function.name}(${a.joinToString(", ")})" // fn.* scalar functions
        }
    }
}

private val INFIX_OPS =
    mapOf(
        "add" to "+",
        "sub" to "-",
        "mul" to "*",
        "div" to "/",
        "eq" to "=",
        "neq" to "<>",
        "lt" to "<",
        "lte" to "<=",
        "gt" to ">",
        "gte" to ">=",
        "and" to "AND",
        "or" to "OR",
    )
