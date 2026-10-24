// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.ast

import org.tatrman.ttrp.expr.Expression

/**
 * The recipe block (AU-core-P0 · S0.1; AU ttr-core contracts §1, D-25): role containers, one
 * decision, the action wiring. The walker builds it structurally — every cross-reference (a route
 * no wiring consumes, a `:name` no `parameters:` declares, an action the imports lack) and the
 * key-lineage mode verdict are the S1.1/S1.2 RecipeChecker's. Enumerated header / switch values
 * the walker could not read are reported as `TTRP-PRS-006` and left null here.
 */
data class RecipeDecl(
    /** `key:` — one qname, or several for a composite key (`[a.x, a.y]`). Empty if absent. */
    val key: List<Qname>,
    val asOf: AsOf,
    /** `parameters: [ … ]` — the names, in order. */
    val parameters: List<Name>,
    /** `document: "<the literate .md>"` (contracts §6). */
    val document: String?,
    /** `protocol:` — null when absent (the program may still set it, D-80). */
    val protocol: RecipeProtocol?,
    /** `recipe: <name>` — set by a multi-skeleton document (contracts §6); null in a plain `.ttrp`. */
    val name: String?,
    val members: List<RecipeMember>,
    override val location: SourceLocation,
    override val leadingTrivia: List<Trivia> = emptyList(),
    override val trailingTrivia: List<Trivia> = emptyList(),
) : Statement {
    val roleContainers: List<RoleContainer> get() = members.filterIsInstance<RoleContainer>()
    val candidates: List<RoleContainer> get() = roleContainers.filter { it.role == RecipeRole.CANDIDATES }
    val contexts: List<RoleContainer> get() = roleContainers.filter { it.role == RecipeRole.CONTEXT }
    val decisions: List<DecisionDecl> get() = members.filterIsInstance<DecisionDecl>()
    val wirings: List<Wiring> get() = members.filterIsInstance<ActionsBlock>().flatMap { it.wirings }
}

/** A name with its own span (a parameter in `parameters:`, a context in `detail:`). */
data class Name(
    val text: String,
    override val location: SourceLocation,
) : TtrpNode

/** `as of:` — the instant a run reads the world at (D-108: `:as_of` is bound to it per run). */
sealed interface AsOf {
    /** `as of: now` — the default (also when the header is absent). */
    data object Now : AsOf

    /** `as of: "2026-10-01T06:00:00+02:00"` — a literal instant, for replay fixtures. */
    data class Instant(
        val text: String,
    ) : AsOf
}

/** `protocol:` (D-80): write the run protocol always, or only for runs that took a route. */
enum class RecipeProtocol(
    val spelling: String,
) {
    ALWAYS("always"),
    ON_ROUTE("on_route"),
    ;

    companion object {
        fun of(spelling: String): RecipeProtocol? = entries.firstOrNull { it.spelling == spelling }
    }
}

sealed interface RecipeMember : TtrpNode

enum class RecipeRole(
    val keyword: String,
) {
    CANDIDATES("candidates"),
    CONTEXT("context"),
}

/**
 * A role container: `candidates`/`context` + the container header with an island [form] (D-19).
 * [body] is the container body when written — required for a [IslandForm.Target] (the walker
 * reports a missing one), optional otherwise; a fragment body is decomposed like any container's.
 */
data class RoleContainer(
    val role: RecipeRole,
    val name: String,
    val ports: List<PortDecl>,
    val form: IslandForm,
    val window: Window?,
    val body: ContainerBody?,
    override val location: SourceLocation,
    val nameLocation: SourceLocation = location,
    val retries: Int? = null,
    val onFailureOf: String? = null,
    /** `on failure of x absorbs` — reserved, TTRP-FAIL-003 (the container precedent). */
    val onFailureAbsorbs: Boolean = false,
) : RecipeMember {
    /**
     * The plain container this role container is, for a `target` form with a body — so the
     * existing container paths (fragment decomposition, the checks, later the graph) reuse it.
     */
    fun asContainer(): ContainerDecl? {
        val target = (form as? IslandForm.Target)?.target ?: return null
        val b = body ?: return null
        return ContainerDecl(
            name = name,
            ports = ports,
            target = target,
            body = b,
            location = location,
            nameLocation = nameLocation,
            onFailureOf = onFailureOf,
            retries = retries,
        )
    }
}

/** How a role container's island runs (D-19; AU contracts §1 `islandForm`). */
sealed interface IslandForm : TtrpNode {
    /** `target <engine>` — today's island (SQL / canonical / a fragment). */
    data class Target(
        val target: Qname,
        override val location: SourceLocation,
    ) : IslandForm

    /** `builtin <capability> { config }` — a Server capability. */
    data class Builtin(
        val capability: Qname,
        val config: ConfigBlock?,
        override val location: SourceLocation,
    ) : IslandForm

    /** `foreign <lang> per candidate | set schema <s>` — e.g. python; in/out schemas declared. */
    data class Foreign(
        val language: String,
        val grain: Grain,
        val schema: String,
        override val location: SourceLocation,
    ) : IslandForm

    /** `call <service> [per candidate | set]` — a `def service`. */
    data class Call(
        val service: Qname,
        val grain: Grain?,
        override val location: SourceLocation,
    ) : IslandForm

    /** `turn <agent> ask "<question>" answer <schema>` — nondeterministic (the turn rule, REC-040). */
    data class Turn(
        val agent: Qname,
        val ask: String,
        val answerSchema: String,
        override val location: SourceLocation,
    ) : IslandForm
}

enum class Grain { PER_CANDIDATE, SET }

/** `window { by: k, … over: day | week | month | rows: N | gap: <duration> }` (D-28). */
data class Window(
    val by: List<Name>,
    val over: WindowOver?,
    override val location: SourceLocation,
) : TtrpNode

sealed interface WindowOver {
    /** `over: day | week | month`. */
    data class Calendar(
        val unit: String,
    ) : WindowOver

    /** `over: rows: N`. */
    data class Rows(
        val count: Int,
    ) : WindowOver

    /** `over: gap: <duration>` (a session window). */
    data class Gap(
        val duration: org.tatrman.ttrp.expr.DurationLiteral,
    ) : WindowOver
}

/** `decision <name>(in …) { statements…  <input> -> switch { … } }` — the routes are its outputs. */
data class DecisionDecl(
    val name: String,
    val ports: List<PortDecl>,
    val statements: List<Statement>,
    val switch: SwitchStmt,
    override val location: SourceLocation,
    val nameLocation: SourceLocation = location,
    val sourceFragment: FragmentBody? = null,
) : RecipeMember

enum class SwitchMode(
    val spelling: String,
) {
    EXCLUSIVE("exclusive"),
    OVERLAPPING("overlapping"),
    ;

    companion object {
        fun of(spelling: String): SwitchMode? = entries.firstOrNull { it.spelling == spelling }
    }
}

/** The decision's `switch` statement (D-31/D-102): one-expression route conditions. */
data class SwitchStmt(
    val input: DottedRef,
    /** `mode:` — null when not written (the checker's default); also null if the value was not a mode. */
    val mode: SwitchMode?,
    val routes: List<Route>,
    val elseRoute: ElseRoute?,
    override val location: SourceLocation,
) : TtrpNode {
    /** Every route name the switch can emit, `else` included (a `none` else emits nothing). */
    val routeNames: List<String> get() = routes.map { it.name } + listOfNotNull(elseRoute?.name)
}

data class Route(
    val name: String,
    val condition: Expression,
    /** `because: "…"` — explanation, `{column}` filled from the decision row (D-108). */
    val because: String?,
    /** `detail: a, b` — the context names attached per key. */
    val detail: List<Name>,
    override val location: SourceLocation,
    val nameLocation: SourceLocation = location,
) : TtrpNode

/** `else: <route>` — or `else: none` ([name] null): no verdict row for an unmatched candidate (D-80). */
data class ElseRoute(
    val name: String?,
    val because: String?,
    val detail: List<Name>,
    override val location: SourceLocation,
) : TtrpNode

data class ActionsBlock(
    val wirings: List<Wiring>,
    override val location: SourceLocation,
) : RecipeMember

/** `verdict.release, … -> release { order: order_number } when: <expr>` — typed against `def action` at S1.1. */
data class Wiring(
    val routes: List<RouteRef>,
    val action: String,
    val args: List<WiringArg>,
    /** `when: <expr>` — lowered to a route split (17 §5). */
    val whenCondition: Expression?,
    override val location: SourceLocation,
    val actionLocation: SourceLocation = location,
) : TtrpNode {
    fun arg(name: String): WiringValue? = args.firstOrNull { it.name == name }?.value
}

/** `<decision>.<route>`; `<decision>.all` is the identity verdict (every routed row). */
data class RouteRef(
    val decision: String,
    val route: String,
    override val location: SourceLocation,
) : TtrpNode {
    val isAll: Boolean get() = route == "all"
}

data class WiringArg(
    val name: String,
    val value: WiringValue,
    override val location: SourceLocation,
) : TtrpNode

sealed interface WiringValue : TtrpNode {
    /** One expression: a column, `role.x`, a literal, a duration, `:param`. */
    data class Expr(
        val expression: Expression,
        override val location: SourceLocation,
    ) : WiringValue

    /** A bare comma list: `detail: shortfall, price_differences`. */
    data class Values(
        val items: List<Expression>,
        override val location: SourceLocation,
    ) : WiringValue

    /** An action call: `on_due: hold { order: order_number, department: "Sales" }` (D-98). */
    data class Call(
        val action: String,
        val args: List<WiringArg>,
        override val location: SourceLocation,
    ) : WiringValue
}
