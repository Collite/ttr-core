// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Window
import org.tatrman.ttrp.expr.TtrpType

/**
 * What the recipe checker learned about a recipe (AU-core-P1) — printed by `ttrp explain`, handed to the P2
 * manifest v3 (`recipe.roles`, `mode`, `windows`, AU contracts §5.1). Names follow the contract's spellings.
 */
data class RecipeModel(
    val name: String,
    /** The `key:` attributes as written (`order.order_number`). */
    val keyAttributes: List<String>,
    /** The key's column names (`order_number`) — what lineage tracks. */
    val keyColumns: List<String>,
    val candidates: String?,
    val contexts: List<ContextInfo>,
    val decision: String?,
    val routes: List<RouteInfo>,
    val switchMode: String?,
    val wirings: List<WiringInfo>,
    val parameters: List<String>,
    val mode: RecipeMode,
    /** The `def program`s of the imports that run this recipe. */
    val programs: List<String>,
    /**
     * AU-core-P2: the row entering the decision's `switch` (name → type, null where unknown) — what every route's
     * rows carry before the verdict columns, and what a `because:` fill names. Null without a decision, or when the
     * row is not known.
     */
    val routeRow: Map<String, TtrpType?>? = null,
    /** AU-core-P2: the recipe's `parameters:` as their `def parameter`s declare them (manifest v3 `parameters`). */
    val parameterDecls: List<ParameterInfo> = emptyList(),
    /** AU-core-P2: the `within` nodes whose column is a calendar sk (D-82) — the lowering joins the calendar. */
    val calendarSkWithins: Set<String> = emptySet(),
    /**
     * AU-core-P2: the run-bound values the lowering derived from `:as_of` (or another bound value) and a duration —
     * the door has no date arithmetic, so the runner computes each and binds it (manifest v3 `params[].derive`).
     */
    val derivedParams: List<DerivedParam> = emptyList(),
)

/**
 * A run-bound value derived from another (AU-core-P2): [name] = [base] [op] [amount] [unit], where [amount] is a
 * number or the name of a `days`/`int` parameter — `as_of_minus_365_days` = `as_of` − 365 days.
 */
data class DerivedParam(
    val name: String,
    val base: String,
    /** `minus` or `plus`. */
    val op: String,
    val amount: String,
    /** `days · weeks · months · hours · minutes`. */
    val unit: String,
)

/** A recipe parameter as declared: `def parameter tolerance { type: decimal, value: 0.01 }`. */
data class ParameterInfo(
    val name: String,
    /** `decimal · int · text · date · days · working_days · bool`, or null when the declaration names none. */
    val type: String?,
    /** The value as written (`0.01`, `30`), or null when the declaration has none. */
    val value: String?,
)

/** The recipe's mode verdict (AU contracts §4): what kind of program may run it. */
enum class RecipeMode(
    val spelling: String,
) {
    PER_CANDIDATE("per-candidate"),
    SET_LEVEL_WINDOWED("set-level, windowed"),
    SET_LEVEL_BATCH_ONLY("set-level, batch-only"),
}

/** A context's class (06 A-3): decided by key lineage, never declared. */
enum class ContextClass(
    val spelling: String,
) {
    LOOKUP("lookup"),
    PER_CANDIDATE("per-candidate"),
    SET_LEVEL("set-level"),
}

data class ContextInfo(
    val name: String,
    /** `sql`, `canonical`, `builtin`, `foreign`, `call`, `turn`. */
    val kind: String,
    val contextClass: ContextClass,
    val window: Window?,
    /** For a set-level context: the op that lost the key, and where. */
    val lostKeyAt: String? = null,
    val lostKeyLocation: SourceLocation? = null,
    /** AU-core-P2: the context's out row (name → type) — a `detail:` island's columns. Null when not known. */
    val row: Map<String, TtrpType?>? = null,
)

data class RouteInfo(
    val name: String,
    val because: String?,
    val detail: List<String>,
    /** The route's condition reads a column a `turn` island produced (the turn rule, REC-040). */
    val turnFed: Boolean,
    val isElse: Boolean = false,
)

data class WiringInfo(
    val routes: List<String>,
    val action: String,
    val columns: Map<String, String>,
    val whenCondition: Boolean,
)
