// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.model

import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.SourceLocation

/**
 * Grammar 0.16 — the automation tier (`model automation`; AU-core-P0 S0.2 T5): typed, reconciled
 * counterparts of the six automation def kinds. Keyed `QualifiedName(AUTOMATION, <kind>, <name>,
 * <package>)` — `hartland.automation` · `automation` · `program` · `morning_release`.
 *
 * Every object carries the typed reading of its contract properties (AU ttr-core contracts §2) AND
 * [AutomationObject.properties], the free-form entries as authored (the parser's [AutoValue]s, in
 * order) — the world-manifest precedent: a property this library does not type yet still reaches
 * the consumer. The typed fields read leniently: a malformed value reads as absent (null / default);
 * the automation validator (ttr-semantics) is where it is reported.
 */
data class AutomationSchema(
    val objects: Map<QualifiedName, AutomationObject> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "automation"

    val parameters: List<AutomationParameter> get() = objects.values.filterIsInstance<AutomationParameter>()
    val actions: List<AutomationAction> get() = objects.values.filterIsInstance<AutomationAction>()
    val services: List<AutomationService> get() = objects.values.filterIsInstance<AutomationService>()
    val roles: List<AutomationRole> get() = objects.values.filterIsInstance<AutomationRole>()
    val approvals: List<AutomationApproval> get() = objects.values.filterIsInstance<AutomationApproval>()
    val programs: List<AutomationProgram> get() = objects.values.filterIsInstance<AutomationProgram>()
    val recipes: List<AutomationRecipe> get() = objects.values.filterIsInstance<AutomationRecipe>()

    /** The object of [kind] named [name] (any package), or null. */
    fun byName(
        kind: String,
        name: String,
    ): AutomationObject? = objects.values.firstOrNull { it.kind == kind && it.qname.name == name }

    override fun objects(): Sequence<ModelObject> = objects.values.asSequence()
}

/** One automation object (`def parameter | action | service | role | approval | program | recipe`). */
sealed interface AutomationObject : ModelObject {
    /** The localised `label:`; empty when absent. */
    val label: LocalizedText

    /** The free-form entries as authored, in order (a later duplicate wins on lookup). */
    val properties: List<Pair<String, AutoValue>>

    val sourceLocation: SourceLocation?

    /** The last value written under [key], or null. */
    fun property(key: String): AutoValue? = properties.lastOrNull { it.first == key }?.second
}

/** `2 working_days` — a duration as written; the unit is one of the validator's units. */
data class AutomationDuration(
    val amount: Double,
    val unit: String,
)

/** `def parameter <n> { type, value, label }` (D-13: a value change is a re-approval of kind `values`). */
data class AutomationParameter(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    /** `decimal · int · text · date · days · working_days · bool`. */
    val type: String? = null,
    /** The value as written (a number, a string or a boolean). */
    val value: AutoValue? = null,
) : AutomationObject {
    override val kind: String = "parameter"
}

/** One `def column` of an action: its name, its type (`text`, `rows`, `role`, `action`, or `<entity>.<attr>`). */
data class AutomationColumn(
    val name: String,
    val type: String,
    val optional: Boolean = false,
)

/** An action's `transition:` — [to] a state, or the state named by the column [toColumn]; [from] the guard. */
data class AutomationTransition(
    val attribute: String,
    val to: String? = null,
    val toColumn: String? = null,
    val from: List<String> = emptyList(),
)

/** An action's `set: <attribute> = <column>`. */
data class AutomationSet(
    val attribute: String,
    val column: String,
)

/** A call-shaped value: `on_due: hold { order: order_number, department: "Sales" }`. */
data class AutomationCall(
    val action: String,
    val arguments: List<Pair<String, AutoValue>> = emptyList(),
)

/** `def action <n> { columns, sinks, transition?, set?, approval, deliverable, due, on_due, dry_run, label }` (D-65, D-98). */
data class AutomationAction(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    val columns: List<AutomationColumn> = emptyList(),
    /** Target → sink: `native → transition`, `n8n → door`, `n8n → "n8n-nodes-base.emailSend"`. */
    val sinks: Map<String, String> = emptyMap(),
    val transition: AutomationTransition? = null,
    val set: AutomationSet? = null,
    /** `none` or `required`; null when absent. */
    val approval: String? = null,
    val deliverable: Boolean = false,
    val due: AutomationDuration? = null,
    val onDue: AutomationCall? = null,
    /** The `dry_run:` text, `{ en: …, cs: … }`. */
    val dryRun: LocalizedText = LocalizedText.EMPTY,
) : AutomationObject {
    override val kind: String = "action"
}

/** `def service <n> { request, response, timeout, budget: { per_run }, write, idempotency }` (D-69). */
data class AutomationService(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    /** The request / response `def schema` names. */
    val request: String? = null,
    val response: String? = null,
    val timeout: AutomationDuration? = null,
    val budgetPerRun: Int? = null,
    val write: Boolean = false,
    val idempotency: String? = null,
) : AutomationObject {
    override val kind: String = "service"
}

/** How an automation role resolves to people. */
sealed interface RoleMembership {
    /** `resolve: keycloak_group "<group>"`. */
    data class KeycloakGroup(
        val group: String,
    ) : RoleMembership

    /** `members: ["<principal>", …]`. */
    data class Members(
        val principals: List<String>,
    ) : RoleMembership
}

/** A `def role` in a `model automation` file: `resolve: keycloak_group "…"` | `members: […]`, `delivery`. */
data class AutomationRole(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    val membership: RoleMembership? = null,
    /** `delivery: { email: "…" }` — channel → address. */
    val delivery: Map<String, String> = emptyMap(),
) : AutomationObject {
    override val kind: String = "role"
}

/** `def approval <kind> { by: role.<n>, quorum }` — named by its kind (`recipe · values · program · deployment · personal`). */
data class AutomationApproval(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    /** The approving role's name (`process_owner` for `by: role.process_owner`). */
    val byRole: String? = null,
    val quorum: Int = 1,
) : AutomationObject {
    override val kind: String = "approval"
}

/** A program's delivery: right away, or a digest grouped by a column per day/week. */
sealed interface ProgramDelivery {
    data object Immediate : ProgramDelivery

    data class Digest(
        val by: String,
        val per: String,
    ) : ProgramDelivery
}

/**
 * `def program <n> { recipe, port, event, mode, as_of, deliver, schedule, overrides, budget, turn_budget, ask_due,
 * protocol }` (D-26, D-104). `schedule` is an exporter hint; the Server's trigger is the deployment's (D-106).
 */
data class AutomationProgram(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    val recipe: String? = null,
    /** `none · key · keys · event`. */
    val port: String? = null,
    /** The event `def schema` name (`line_received` for `event: schema.line_received`). */
    val event: String? = null,
    /** `batch · on_event · windowed`. */
    val mode: String? = null,
    /** `run`, `event`, or a literal instant. */
    val asOf: String? = null,
    val deliver: ProgramDelivery? = null,
    val schedule: String? = null,
    val overrides: List<Pair<String, AutoValue>> = emptyList(),
    val budgetSync: AutomationDuration? = null,
    val budgetBatch: AutomationDuration? = null,
    val turnBudgetPerRun: Int? = null,
    val turnBudgetPerCandidate: Int? = null,
    val askDue: AutomationDuration? = null,
    /** `always · on_route`. */
    val protocol: String? = null,
) : AutomationObject {
    override val kind: String = "program"
}

/**
 * `def recipe <n> { … }` — the GENERATED catalog object (the TTR-P compiler writes it into a recipe bundle,
 * contracts §5.3); its shape is the compiler's contract, carried in [properties]. [key] and [mode] are read.
 */
data class AutomationRecipe(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val label: LocalizedText = LocalizedText.EMPTY,
    override val properties: List<Pair<String, AutoValue>> = emptyList(),
    override val sourceLocation: SourceLocation? = null,
    /** The key attribute(s) (`order.order_number`). */
    val key: List<String> = emptyList(),
    val mode: String? = null,
) : AutomationObject {
    override val kind: String = "recipe"
}

// ----- the automation world members (nested in `def world`; QualifiedName(WORLD, <world>, <name>)) -----

/**
 * Who a grant is for: a role, a user (`user.<u>` — the rest of the path — or `user "<u>"` for a name an id cannot
 * spell), a named identity, or anyone (`*` / `"*"`).
 */
sealed interface GrantWho {
    data class Role(
        val name: String,
    ) : GrantWho

    data class User(
        val name: String,
    ) : GrantWho

    data class Identity(
        val name: String,
    ) : GrantWho

    data object Anyone : GrantWho
}

/** Whom a run runs as: the caller, the deployer (`author`), or a named identity (`identity.<n>`). */
sealed interface RunAsRef {
    data object Caller : RunAsRef

    data object Author : RunAsRef

    data class Identity(
        val name: String,
    ) : RunAsRef
}

/** `def identity <n> { principal, credential }` (D-90) — a service principal and its `TTR_CONN_<NAME>` credential. */
data class WorldIdentity(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val principal: String? = null,
    /** The credential's env var name, as written (`TTR_CONN_AUTOMATION_DECIDER`). */
    val credential: String? = null,
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "identity"
}

/**
 * `def grant { who, program, as }` (D-91) — unnamed, keyed by its triple; [qname] is synthetic (`grant#<n>`,
 * n = 1-based position in the world). Loaded FAIL-CLOSED: a grant whose `who`, `program` or `as` is missing
 * or malformed is not loaded at all (a load error says so) — so [who] and [runAs] are always set, and
 * [program] is null only when the grant was written `program: *` (or `"*"`). [runAs] is never
 * [RunAsRef.Caller] (a caller needs no grant).
 */
data class WorldGrant(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val who: GrantWho,
    val program: String?,
    val runAs: RunAsRef,
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "grant"

    /** `program: *`. */
    val anyProgram: Boolean get() = program == null
}

/** A deployment's Server trigger (D-106): a schedule, or none (event/manual only). */
sealed interface DeploymentTrigger {
    data class Schedule(
        val spec: String,
    ) : DeploymentTrigger

    data object None : DeploymentTrigger
}

/**
 * `def deployment <n> { program | process, target, run_as, trigger, … }` (D-107, D-92, D-94, D-106). Loaded
 * FAIL-CLOSED: a deployment with a non-boolean flag, a missing/malformed `run_as` or `target`, or not exactly
 * one of `program` / `process` is not loaded (a load error says so) — a flag keeps its default only when absent.
 */
data class WorldDeployment(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    /** The program's name; null when the deployment runs a [process]. */
    val program: String? = null,
    val process: String? = null,
    /** `native` or an executor of the world. */
    val target: String,
    val runAs: RunAsRef,
    val trigger: DeploymentTrigger? = null,
    val dryRun: Boolean = false,
    val enabled: Boolean = true,
    val debug: Boolean = false,
    val ungoverned: Boolean = false,
    /** `recipe · parts · islands`. */
    val granularity: String? = null,
    val allowRestrictedView: Boolean = false,
    val approvalOverride: Boolean = false,
    val readCeiling: Int? = null,
    val oboOnly: Boolean = false,
    val properties: List<Pair<String, AutoValue>> = emptyList(),
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "deployment"
}

/** A step's wait: the obligation kind(s) it waits on, or a time; ending at the first of [until]. */
data class StepAfter(
    val obligations: List<String> = emptyList(),
    val time: AutomationDuration? = null,
    val until: List<String> = emptyList(),
)

/** `def step <n> { program, port, after }` inside a process. */
data class ProcessStep(
    val name: String,
    val program: String? = null,
    /** `key · keys`. */
    val port: String? = null,
    val after: StepAfter? = null,
    val properties: List<Pair<String, AutoValue>> = emptyList(),
)

/** `def process <n> { instance, steps }` (D-39, D-86). */
data class WorldProcess(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val descriptionLocalized: LocalizedText = LocalizedText.EMPTY,
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    /** The instance key (`return_request_line.line_id`). */
    val instance: String? = null,
    val steps: List<ProcessStep> = emptyList(),
    val sourceLocation: SourceLocation? = null,
) : ModelObject {
    override val kind: String = "process"
}
