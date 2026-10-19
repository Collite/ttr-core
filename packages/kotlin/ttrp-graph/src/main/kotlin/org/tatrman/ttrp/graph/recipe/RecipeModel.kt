// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.graph.recipe

import org.tatrman.ttrp.ast.SourceLocation
import org.tatrman.ttrp.ast.Window

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
