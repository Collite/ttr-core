// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Manifest v3's `recipe` object (AU contracts §5.1): what the runtime (ttr-server's `/refresh`, the runner) and the
 * Studio read of a recipe bundle besides its statements — the recipe's key, parameters and the values the run binds,
 * its roles with their classes and kinds, the routes and the wiring, the mode verdict, the verdict schema, what it
 * reads and its three fingerprints. Present only in a recipe bundle (`schemaVersion: 3`); every other manifest omits
 * it and stays byte-identical.
 */
@Serializable
data class RecipeManifest(
    val name: String,
    /** The `key:` attributes as written (`order.order_number`). */
    val key: List<String>,
    /** `now`, or the literal instant of a replay fixture. */
    val asOf: String,
    val document: String? = null,
    /** The `parameters:`, each as its `def parameter` declares it — the `values` fingerprint's input. */
    val parameters: List<RecipeParameter>,
    /**
     * The names the run binds beside the parameters (AU-C-4): `as_of`, and each value derived from it — the door
     * computes no date arithmetic, so the runner computes `as_of_minus_365_days` and binds it.
     */
    val bindings: List<RecipeBinding>,
    val roles: RecipeRoles,
    val wiring: List<RecipeWiring>,
    /** The island kinds the recipe uses (`sql`, `canonical`, `builtin`, `foreign:python`, `call`, `turn`). */
    val kinds: List<String>,
    /** `per-candidate` · `set-level, windowed` · `set-level, batch-only`. */
    val mode: String,
    val windows: List<RecipeWindow> = emptyList(),
    val verdictSchema: VerdictSchema,
    /** The model objects the statements read (`er.entity.order`, …) — D-94's out-of-scope-read check. */
    val objectsRead: List<String>,
    val fingerprints: RecipeFingerprints,
    /** `always` · `on_route` (D-80; a program may override). */
    val protocol: String,
    val parts: List<RecipePartEntry>? = null,
    /**
     * Golden fixtures in path order; presence gates execution (D-59).
     * Null (and omitted) without fixtures, keeping manifests byte-compatible with grammar/v0.17.3.
     */
    val tests: List<String>? = null,
    /** Native preview dependencies v1; absent for recipes with no directly wired writer defaults. */
    val approvalDependencies: RecipeApprovalDependencies? = null,
)

/** Explicit coverage evidence, bound into text/2. Consumers must verify it against pinned action/ER metadata. */
@Serializable
data class RecipeApprovalDependencies(
    val version: Int,
    /** Checked recipe import qnames, sorted and unique; required for scoped offline verification. */
    val imports: List<String>,
    /** The unchanged text/1 digest, not an independently approved fingerprint. */
    val textBase: String,
    /** Distinct directly wired action name → action-dependency/1 digest, in sorted name order. */
    val actions: Map<String, String>,
)

@Serializable
data class RecipeParameter(
    val name: String,
    /** `decimal · int · text · date · days · working_days · bool` (the `def parameter`'s). */
    val type: String,
    /** The value as written. */
    val value: String? = null,
)

/** A name the run binds (AU-C-4): `as_of` (`source: run`), or one derived from it (`source: derived` + [derive]). */
@Serializable
data class RecipeBinding(
    val name: String,
    /** The plan.v1 surface tag the statements bind it as (`datetime` for an instant). */
    val type: String,
    val source: String,
    val derive: RecipeDerive? = null,
)

/** `name` = [base] [op] [amount] [unit] — `as_of` minus 365 days; [amount] a number or a `days`/`int` parameter. */
@Serializable
data class RecipeDerive(
    val base: String,
    val op: String,
    val amount: String,
    val unit: String,
)

@Serializable
data class RecipeRoles(
    val candidates: String? = null,
    val contexts: List<RecipeContext>,
    val decision: String? = null,
    val routes: List<RecipeRoute>,
    val switchMode: String? = null,
    /** The named `else` route; null without one, or for `else: none`. */
    val elseRoute: String? = null,
)

@Serializable
data class RecipeContext(
    val name: String,
    /** `lookup` · `per-candidate` · `set-level` — the key lineage's verdict (06 A-3). */
    @SerialName("class") val contextClass: String,
    val kind: String,
)

@Serializable
data class RecipeRoute(
    val name: String,
    val because: String? = null,
    val detail: List<String> = emptyList(),
    /** The route's condition reads a `turn`-fed column (REC-040). */
    val turnFed: Boolean = false,
)

/** `verdict.release -> release { order: order_number }` — [columns]: the action's columns → the values wired. */
@Serializable
data class RecipeWiring(
    val routes: List<String>,
    val action: String,
    val columns: Map<String, String>,
    /** `when: <expression>` in TTR-P's spelling, evaluated on the route row (17 §5); null without one. */
    @SerialName("when") val whenCondition: String? = null,
)

@Serializable
data class RecipeWindow(
    val context: String,
    val by: List<String>,
    /** `day` · `week` · `month` · `rows N` · `gap <duration>`. */
    val over: String,
)

/** The verdict entities the writer keeps (§5.3): `<recipe>_verdict` and its `_detail`, `_current`. */
@Serializable
data class VerdictSchema(
    val entity: String,
    val columns: List<OutputColumn>,
    val detail: String,
    val current: String,
)

/**
 * D-13/D-60 (contracts §5.1): [text] the logic — the recipe's lowered graph, canonical, and its statements, with
 * every `because:` taken out; [values] the parameter values; [labels] the `because:` texts. A value change moves
 * [values] only (approval kind `values`); a `because:` change moves [labels] only (no approval gates it).
 * A directly wired writer default additionally binds native action/ER dependencies into text/2;
 * [RecipeManifest.approvalDependencies] records the evidence a governed consumer verifies.
 */
@Serializable
data class RecipeFingerprints(
    val text: String,
    val values: String,
    val labels: String,
)
