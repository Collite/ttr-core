// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.loader.AutomationStdlib
import org.tatrman.ttr.parser.loader.ParseResult
import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.AutoBody
import org.tatrman.ttr.parser.model.AutoEntry
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.AutomationDef
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.ProcessDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.SchemaDef
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TargetReferenceValue
import org.tatrman.ttr.parser.model.WorldAutoMember
import org.tatrman.ttr.parser.model.WorldDef

/**
 * One finding of the automation validator. [severity] is `error`, `warning` or `info`; [code] is
 * one of the `ttr/automation-*` / `ttr/states-*` ids (a cross-target contract — the TS twin
 * `validateAutomationProject` emits the same codes on the same documents).
 */
data class AutomationDiagnostic(
    val code: String,
    val message: String,
    val severity: String,
    val source: SourceLocation,
)

/**
 * Grammar 0.16 — the automation model's semantic contract (AU-core-P0 S0.2 T3; AU ttr-core
 * contracts §2–§3): the per-kind property contract of the one permissive grammar body (required
 * and known properties, enum values, durations), the references between the automation objects
 * (programs → recipes, event schemas; approvals → roles; grants, deployments and process steps →
 * programs, identities, executors), the action columns' attribute types, and the attribute
 * lifecycle (`states:` / `transitions:` and the actions' transitions against them, D-67/D-85).
 *
 * A standalone, project-scoped entry point (the [WorldValidator] precedent — NOT part of
 * [Validator], excluded from the conformance dump): references cross files, so it takes every
 * parsed document of a project. The automation stdlib ([AutomationStdlib]) joins the project
 * implicitly when it [needs it][AutomationStdlib.isNeededBy]. Name matching is by object name
 * within the project (`role.ops` → the role `ops`), the way the recipe compiler reads them.
 */
object AutomationValidator {
    private val PARAMETER_TYPES = setOf("decimal", "int", "text", "date", "days", "working_days", "bool")
    private val DURATION_UNITS = setOf("seconds", "minutes", "hours", "days", "working_days", "weeks", "months")
    private val COLUMN_TYPES = setOf("text", "int", "decimal", "date", "timestamp", "bool", "rows", "role", "action")
    private val NATIVE_SINKS = setOf("transition", "writer", "deliver", "task", "obligation", "start", "emit")
    private val APPROVAL_KINDS = setOf("recipe", "values", "program", "deployment", "personal")
    private val PORTS = setOf("none", "key", "keys", "event")
    private val STEP_PORTS = setOf("key", "keys")
    private val MODES = setOf("batch", "on_event", "windowed")
    private val PROTOCOLS = setOf("always", "on_route")
    private val GRANULARITIES = setOf("recipe", "parts", "islands")
    private val UNTIL = setOf("fulfilled", "due", "absent")
    private val DIGEST_PER = setOf("day", "week")
    private val DEPLOYMENT_FLAGS =
        setOf("dry_run", "enabled", "debug", "ungoverned", "allow_restricted_view", "approval_override", "obo_only")

    private const val WHO_SHAPE = "`role.<r>`, `identity.<i>`, `user.<u>`, `user \"<u>\"`, `*` or \"*\""

    /** The typed body properties each kind takes (description and tags are universal). */
    private val TYPED =
        mapOf(
            "parameter" to setOf("type", "label"),
            "action" to setOf("columns", "transition", "set", "label"),
            "service" to setOf("label"),
            "approval" to setOf("label"),
            "program" to setOf("label"),
            "identity" to emptySet(),
            "grant" to emptySet(),
            "deployment" to setOf("target"),
            "step" to emptySet(),
        )

    /** Known free-form keys per kind (the typed alternatives are always allowed). */
    private val KNOWN =
        mapOf(
            "parameter" to setOf("value"),
            "action" to setOf("sinks", "approval", "deliverable", "due", "on_due", "dry_run"),
            "service" to setOf("request", "response", "timeout", "budget", "write", "idempotency"),
            "approval" to setOf("by", "quorum"),
            "program" to
                setOf(
                    "recipe",
                    "port",
                    "event",
                    "mode",
                    "as_of",
                    "deliver",
                    "schedule",
                    "overrides",
                    "budget",
                    "turn_budget",
                    "ask_due",
                    "protocol",
                ),
            "role" to setOf("resolve", "members", "delivery"),
            "identity" to setOf("principal", "credential"),
            "grant" to setOf("who", "program", "as"),
            "deployment" to
                setOf("program", "process", "run_as", "trigger", "granularity", "read_ceiling") + DEPLOYMENT_FLAGS,
            "process" to setOf("instance"),
            "step" to setOf("program", "port", "after"),
        )

    /**
     * Validate every automation object of a project.
     *
     * @param results       every parsed document of the project
     * @param includeStdlib add the automation stdlib's entities (default: when the project needs it)
     */
    @JvmOverloads
    fun validate(
        results: List<ParseResult>,
        includeStdlib: Boolean = AutomationStdlib.isNeededBy(results),
    ): List<AutomationDiagnostic> {
        // The stdlib goes in FIRST so a project's own `obligation` / `state_history` / `approvals` wins
        // (review-114 M7 — the metadata loader's precedence).
        val docs = if (includeStdlib) listOf(AutomationStdlib.result) + results else results
        val ctx = Ctx(Index(docs))
        for (r in results) {
            val automationFile = r.modelDirective?.modelCode == "automation"
            for (def in r.definitions) {
                when (def) {
                    is AutomationDef -> ctx.automation(def)
                    is RoleDef -> if (automationFile || def.entries.isNotEmpty()) ctx.role(def)
                    is WorldDef -> ctx.world(def)
                    is EntityDef -> def.attributes.forEach { ctx.lifecycle(def, it) }
                    else -> {}
                }
            }
        }
        return ctx.out
    }

    /** Every object the references resolve against, by name. */
    private class Index(
        docs: List<ParseResult>,
    ) {
        val entities = LinkedHashMap<String, EntityDef>()
        val schemas = mutableSetOf<String>()
        val autos = LinkedHashMap<String, MutableMap<String, AutomationDef>>()
        val roles = mutableSetOf<String>()
        val executors = mutableSetOf<String>()

        init {
            for (r in docs) {
                val automationFile = r.modelDirective?.modelCode == "automation"
                for (def in r.definitions) {
                    when (def) {
                        is EntityDef -> entities[def.name] = def
                        is SchemaDef -> schemas += def.name
                        is AutomationDef -> autos.getOrPut(def.kind) { LinkedHashMap() }[def.name] = def
                        is RoleDef -> if (automationFile || def.entries.isNotEmpty()) roles += def.name
                        is WorldDef -> def.executors.mapTo(executors) { it.name }
                        else -> {}
                    }
                }
            }
        }

        fun has(
            kind: String,
            name: String,
        ): Boolean = autos[kind]?.containsKey(name) == true

        /** `entity.attribute` (the last two segments of a dotted ref) → the attribute, or null. */
        fun attribute(path: String): Pair<EntityDef?, AttributeDef?> {
            val parts = path.split('.')
            if (parts.size < 2) return null to null
            val entity = entities[parts[parts.size - 2]] ?: return null to null
            return entity to entity.attributes.firstOrNull { it.name == parts.last() }
        }
    }

    private class Ctx(
        val ix: Index,
    ) {
        val out = mutableListOf<AutomationDiagnostic>()

        /** The world being checked: identity and process references resolve within it (review-114). */
        private var world: WorldDef? = null

        fun report(
            code: String,
            message: String,
            source: SourceLocation,
            severity: String = "error",
        ) {
            out += AutomationDiagnostic(code, message, severity, source)
        }

        // ----- the six kinds -----

        fun automation(def: AutomationDef) {
            if (def.kind == "recipe") return // generated by the compiler; its shape is the compiler's contract
            val what = "${def.kind} '${def.name}'"
            commonEntries(def.kind, what, def.body)
            misplaced(def.kind, what, def.body, def.source)
            when (def.kind) {
                "parameter" -> parameter(def, what)
                "action" -> action(def, what)
                "service" -> service(def, what)
                "approval" -> approval(def, what)
                "program" -> program(def, what)
            }
        }

        private fun parameter(
            def: AutomationDef,
            what: String,
        ) {
            val type = def.body.type?.name
            if (type == null) {
                missing(what, "type", def.source)
            } else if (type !in PARAMETER_TYPES) {
                invalid(what, "type", type, PARAMETER_TYPES, def.source)
            }
            val value = def.body["value"]
            if (value == null) {
                missing(what, "value", def.source)
            } else if (type != null &&
                type in PARAMETER_TYPES &&
                (
                    !valueFits(type, value) ||
                        (type != "decimal" && value is AutoValue.Num && value.raw != kotlin.math.floor(value.raw))
                )
            ) {
                report(
                    "ttr/automation-invalid-value",
                    "$what: value ${render(value)} does not fit its type '$type'",
                    value.source,
                )
            }
        }

        private fun valueFits(
            type: String,
            v: AutoValue,
        ): Boolean =
            when (type) {
                "decimal", "int", "days", "working_days" -> v is AutoValue.Num
                "bool" -> v is AutoValue.Bool
                else -> v is AutoValue.Str
            }

        private fun action(
            def: AutomationDef,
            what: String,
        ) {
            val body = def.body
            if (body.columns.isEmpty()) missing(what, "columns", def.source)
            for (col in body.columns) {
                val type = col.type?.name
                if (type == null) {
                    missing("$what column '${col.name}'", "type", col.source)
                } else if (type !in COLUMN_TYPES) {
                    if (type.contains('.')) {
                        attributeRef("$what column '${col.name}'", type, col.source)
                    } else {
                        invalid(
                            "$what column '${col.name}'",
                            "type",
                            type,
                            COLUMN_TYPES + "<entity>.<attribute>",
                            col.source,
                        )
                    }
                }
            }
            val columns = body.columns.map { it.name }.toSet()
            val sinks = body["sinks"]
            when (sinks) {
                null -> missing(what, "sinks", def.source)
                is AutoValue.Obj ->
                    for (e in sinks.entries) {
                        if (e.key == "native") {
                            enumId(what, "sinks.native", e.value, NATIVE_SINKS)
                        } else if (!(e.value is AutoValue.Str || e.value is AutoValue.Id)) {
                            // a target's sink is its own vocabulary (a node name, `door`, `message`, …)
                            report(
                                "ttr/automation-invalid-value",
                                "$what: sink '${e.key}' must be a name or a string",
                                e.value.source,
                            )
                        }
                    }
                else -> shape(what, "sinks", "an object `{ native: …, <target>: … }`", sinks)
            }
            body["approval"]?.let { enumId(what, "approval", it, setOf("none", "required")) }
            body["deliverable"]?.let { bool(what, "deliverable", it) }
            body["due"]?.let { duration(what, "due", it) }
            body["on_due"]?.let { v ->
                if (v is AutoValue.Call) {
                    if (!ix.has("action", v.name)) unknownRef(what, "on_due", "action", v.name, v.source)
                } else {
                    shape(what, "on_due", "an action call `<action> { … }`", v)
                }
            }
            body["dry_run"]?.let {
                if (it !is AutoValue.Obj) {
                    shape(
                        what,
                        "dry_run",
                        "a localized text `{ en: …, cs: … }`",
                        it,
                    )
                }
            }
            body.transition?.let { t ->
                val (entity, attr) = attributeRef("$what transition", t.attribute, t.source)
                t.toColumn?.let { col ->
                    if (col !in columns) {
                        report(
                            "ttr/automation-unknown-column",
                            "$what: transition target `{$col}` is not a column",
                            t.source,
                        )
                    }
                }
                if (entity != null && attr != null && attr.states.isNotEmpty()) {
                    val states = attr.states.toSet()
                    val name = "${entity.name}.${attr.name}"
                    t.to?.let { to ->
                        if (to !in states) {
                            report(
                                "ttr/states-unknown-state",
                                "$what: '$to' is not a state of $name (${states.joinToString()})",
                                t.source,
                            )
                        }
                    }
                    for (from in t.from) {
                        if (from !in states) {
                            report(
                                "ttr/states-unknown-state",
                                "$what: from-state '$from' is not a state of $name",
                                t.source,
                            )
                        }
                    }
                    val allowed = attr.transitions.associate { it.from to it.to.toSet() }
                    val to = t.to
                    if (to != null && allowed.isNotEmpty()) {
                        for (from in t.from) {
                            // D-85: a self-transition is implicit and silent.
                            if (from != to && from in states && to !in (allowed[from] ?: emptySet())) {
                                report(
                                    "ttr/states-transition-not-allowed",
                                    "$what: $name does not allow $from -> $to",
                                    t.source,
                                )
                            }
                        }
                    }
                }
            }
            body.set?.let { s ->
                attributeRef("$what set", s.attribute, s.source)
                if (s.column !in columns) {
                    report("ttr/automation-unknown-column", "$what: set column '${s.column}' is not a column", s.source)
                }
            }
        }

        private fun service(
            def: AutomationDef,
            what: String,
        ) {
            val body = def.body
            for (key in listOf("request", "response")) {
                when (val v = body[key]) {
                    null -> missing(what, key, def.source)
                    is AutoValue.Id -> schemaRef(what, key, v)
                    else -> shape(what, key, "a `def schema` name", v)
                }
            }
            body["timeout"]?.let { duration(what, "timeout", it) }
            body["budget"]?.let { v ->
                val perRun = (v as? AutoValue.Obj)?.get("per_run")
                if (perRun == null || !isPositiveInt(perRun)) shape(what, "budget", "`{ per_run: <int> }`", v)
            }
            body["write"]?.let { bool(what, "write", it) }
            body["idempotency"]?.let { enumId(what, "idempotency", it, setOf("row")) }
        }

        private fun approval(
            def: AutomationDef,
            what: String,
        ) {
            if (def.name !in APPROVAL_KINDS) {
                report(
                    "ttr/automation-invalid-value",
                    "$what: an approval is named by its kind (${APPROVAL_KINDS.joinToString()})",
                    def.source,
                )
            }
            when (val by = def.body["by"]) {
                null -> missing(what, "by", def.source)
                else -> roleRef(what, "by", by)
            }
            def.body["quorum"]?.let { if (!isPositiveInt(it)) shape(what, "quorum", "a positive integer", it) }
        }

        private fun program(
            def: AutomationDef,
            what: String,
        ) {
            val body = def.body
            when (val recipe = body["recipe"]) {
                null -> missing(what, "recipe", def.source)
                is AutoValue.Id ->
                    if (!ix.has("recipe", recipe.ref.path)) {
                        report(
                            "ttr/automation-recipe-precatalog",
                            "$what: recipe '${recipe.ref.path}' is not in the catalog yet (accepted by name)",
                            recipe.source,
                            "info",
                        )
                    }
                else -> shape(what, "recipe", "a recipe name", recipe)
            }
            val port = body["port"]
            if (port == null) missing(what, "port", def.source) else enumId(what, "port", port, PORTS)
            val event = body["event"]
            val isEventPort = (port as? AutoValue.Id)?.ref?.path == "event"
            if (isEventPort && event == null) {
                report(
                    "ttr/automation-event-port-mismatch",
                    "$what: `port: event` needs an `event:` schema",
                    def.source,
                )
            }
            if (event != null && !isEventPort) {
                report(
                    "ttr/automation-event-port-mismatch",
                    "$what: `event:` is only read with `port: event`",
                    event.source,
                )
            }
            if (event != null) {
                if (event is AutoValue.Id) {
                    schemaRef(
                        what,
                        "event",
                        event,
                    )
                } else {
                    shape(what, "event", "a `def schema` name", event)
                }
            }
            val mode = body["mode"]
            if (mode == null) missing(what, "mode", def.source) else enumId(what, "mode", mode, MODES)
            body["as_of"]?.let { v -> if (v !is AutoValue.Str) enumId(what, "as_of", v, setOf("run", "event")) }
            body["deliver"]?.let { v ->
                when {
                    (v as? AutoValue.Id)?.ref?.path == "immediate" -> {}
                    v is AutoValue.Obj && v["digest"] is AutoValue.Obj -> {
                        val digest = v["digest"] as AutoValue.Obj
                        digest["per"]?.let { enumId(what, "deliver.digest.per", it, DIGEST_PER) }
                            ?: missing(what, "deliver.digest.per", v.source)
                        if (digest["by"] == null) missing(what, "deliver.digest.by", v.source)
                    }
                    else -> shape(what, "deliver", "`immediate` or `{ digest: { by: …, per: day | week } }`", v)
                }
            }
            body["schedule"]?.let { if (it !is AutoValue.Str) shape(what, "schedule", "a string", it) }
            body["overrides"]?.let { v ->
                if (v is AutoValue.Obj) {
                    for (e in v.entries) {
                        if (!ix.has("parameter", e.key)) {
                            report(
                                "ttr/automation-unknown-reference",
                                "$what: overrides '${e.key}', which is no `def parameter`",
                                e.source,
                                "warning",
                            )
                        }
                    }
                } else {
                    shape(what, "overrides", "an object of parameter values", v)
                }
            }
            body["budget"]?.let { v ->
                if (v is AutoValue.Obj) {
                    for (e in v.entries) {
                        if (e.key in
                            setOf("sync", "batch")
                        ) {
                            duration(what, "budget.${e.key}", e.value)
                        } else {
                            unknownKey(what, "budget.${e.key}", e.source)
                        }
                    }
                } else {
                    shape(what, "budget", "`{ sync: <duration>, batch: <duration> }`", v)
                }
            }
            body["turn_budget"]?.let { v ->
                if (v is AutoValue.Obj) {
                    for (e in v.entries) {
                        if (e.key in setOf("per_run", "per_candidate")) {
                            if (!isPositiveInt(
                                    e.value,
                                )
                            ) {
                                shape(what, "turn_budget.${e.key}", "a positive integer", e.value)
                            }
                        } else {
                            unknownKey(what, "turn_budget.${e.key}", e.source)
                        }
                    }
                } else {
                    shape(what, "turn_budget", "`{ per_run: <int>, per_candidate: <int> }`", v)
                }
            }
            body["ask_due"]?.let { duration(what, "ask_due", it) }
            body["protocol"]?.let { enumId(what, "protocol", it, PROTOCOLS) }
        }

        // ----- the automation role -----

        fun role(def: RoleDef) {
            val what = "role '${def.name}'"
            val keys = def.entries.map { it.key }.toSet()
            for (e in def.entries) if (e.key !in KNOWN.getValue("role")) unknownKey(what, e.key, e.source)
            if (("resolve" in keys) == ("members" in keys)) {
                report(
                    "ttr/automation-role-resolve",
                    "$what: declare exactly one of `resolve:` and `members:`",
                    def.source,
                )
            }
            def.entries.lastOrNull { it.key == "resolve" }?.value?.let { v ->
                if (v !is AutoValue.Tagged || v.tag != "keycloak_group") {
                    shape(what, "resolve", "`keycloak_group \"<group>\"`", v)
                }
            }
            def.entries.lastOrNull { it.key == "members" }?.value?.let { v ->
                if (v !is AutoValue.ListV ||
                    v.items.any { it !is AutoValue.Str }
                ) {
                    shape(what, "members", "a list of strings", v)
                }
            }
            def.entries.lastOrNull { it.key == "delivery" }?.value?.let { v ->
                if (v !is AutoValue.Obj) shape(what, "delivery", "an object `{ email: … }`", v)
            }
        }

        // ----- the world members -----

        fun world(world: WorldDef) {
            this.world = world
            val executors = world.executors.map { it.name }.toSet()
            // World members share one name space; a duplicate involving an automation member is an error
            // (an engine/executor/storage clash among themselves predates 0.16 and is left as it was).
            val members =
                world.engines.map { it.name to it.source } + world.executors.map { it.name to it.source } +
                    world.storages.map { it.name to it.source }
            val automationMembers =
                world.identities.mapNotNull { m -> m.name?.let { it to m.source } } +
                    world.deployments.mapNotNull { m -> m.name?.let { it to m.source } } +
                    world.processes.map { it.name to it.source }
            val seenNames = members.map { it.first }.toMutableSet()
            for ((name, source) in automationMembers) {
                if (!seenNames.add(name)) {
                    report(
                        "ttr/automation-duplicate-member",
                        "world '${world.name}': '$name' names two members",
                        source,
                    )
                }
            }
            for (m in world.identities) identity(m)
            val seen = HashMap<String, SourceLocation>()
            for (g in world.grants) {
                grant(g)
                val triple = listOf("who", "program", "as").joinToString(" / ") { g.body[it]?.let(::render) ?: "?" }
                if (seen.putIfAbsent(triple, g.source) != null) {
                    report(
                        "ttr/automation-duplicate-grant",
                        "world '${world.name}': grant ($triple) is declared twice",
                        g.source,
                    )
                }
            }
            for (d in world.deployments) deployment(d, executors)
            for (p in world.processes) process(p)
            for (storage in world.storages) {
                val writable = storage.manifest["writable"] ?: continue
                val items = (writable as? PropertyValue.ListValue)?.items ?: listOf(writable)
                // `<entity>` or `<entity>.<attribute>` (a string is a glob — not checked).
                for (item in items) {
                    if (item is PropertyValue.StringValue) continue
                    if (item !is PropertyValue.IdValue) {
                        report(
                            "ttr/automation-invalid-value",
                            "storage '${storage.name}': a writable item is `<entity>`, `<entity>.<attribute>` or a \"glob\"",
                            item.source,
                        )
                        continue
                    }
                    val parts = item.parts
                    val owner = if (parts.size >= 2) ix.entities[parts[parts.size - 2]] else null
                    val problem =
                        when {
                            ix.entities[parts.last()] != null -> null
                            owner == null -> "names no entity of the project"
                            owner.attributes.none { it.name == parts.last() } -> "names no attribute of '${owner.name}'"
                            else -> null
                        }
                    problem?.let {
                        report(
                            "ttr/automation-writable-unknown-entity",
                            "storage '${storage.name}': writable '${item.ref.path}' $it",
                            item.source,
                        )
                    }
                }
            }
        }

        private fun identity(m: WorldAutoMember) {
            val what = "identity '${m.name}'"
            commonEntries("identity", what, m.body)
            misplaced("identity", what, m.body, m.source)
            if (m.body["principal"] !is AutoValue.Str) {
                missingOr(
                    what,
                    "principal",
                    "a string",
                    m.body["principal"],
                    m.source,
                )
            }
            if (m.body["credential"] !is AutoValue.Id) {
                missingOr(
                    what,
                    "credential",
                    "a connection name",
                    m.body["credential"],
                    m.source,
                )
            }
        }

        private fun grant(g: WorldAutoMember) {
            val what = "grant"
            commonEntries("grant", what, g.body)
            misplaced("grant", what, g.body, g.source)
            when (val who = g.body["who"]) {
                null -> missing(what, "who", g.source)
                is AutoValue.Star -> {}
                // `user "<name>"` — a username an id cannot spell (`-`, `@`).
                is AutoValue.Tagged -> if (who.tag != "user") shape(what, "who", WHO_SHAPE, who)
                is AutoValue.Str ->
                    if (who.raw !=
                        "*"
                    ) {
                        shape(what, "who", WHO_SHAPE, who)
                    }
                is AutoValue.Id -> {
                    val parts = who.parts
                    when (parts.firstOrNull()) {
                        "role" -> roleRef(what, "who", who)
                        "identity" -> identityRef(what, "who", who)
                        "user" -> if (parts.size < 2) shape(what, "who", WHO_SHAPE, who)
                        else -> shape(what, "who", WHO_SHAPE, who)
                    }
                }
                else -> shape(what, "who", WHO_SHAPE, who)
            }
            when (val program = g.body["program"]) {
                null -> missing(what, "program", g.source)
                is AutoValue.Star -> {}
                is AutoValue.Str ->
                    if (program.raw !=
                        "*"
                    ) {
                        shape(what, "program", "a program name, `*` or \"*\"", program)
                    }
                is AutoValue.Id ->
                    if (!ix.has(
                            "program",
                            program.ref.path,
                        )
                    ) {
                        unknownRef(what, "program", "program", program.ref.path, program.source)
                    }
                else -> shape(what, "program", "a program name, `*` or \"*\"", program)
            }
            when (val asV = g.body["as"]) {
                null -> missing(what, "as", g.source)
                is AutoValue.Id ->
                    if (asV.ref.path != "author") {
                        if (asV.parts.firstOrNull() ==
                            "identity"
                        ) {
                            identityRef(what, "as", asV)
                        } else {
                            shape(what, "as", "`identity.<n>` or `author`", asV)
                        }
                    }
                else -> shape(what, "as", "`identity.<n>` or `author`", asV)
            }
        }

        private fun deployment(
            d: WorldAutoMember,
            executors: Set<String>,
        ) {
            val what = "deployment '${d.name}'"
            commonEntries("deployment", what, d.body)
            misplaced("deployment", what, d.body, d.source)
            val program = d.body["program"]
            val process = d.body["process"]
            if ((program == null) == (process == null)) {
                report(
                    "ttr/automation-deployment-program-and-process",
                    "$what: declare exactly one of `program:` and `process:`",
                    d.source,
                )
            }
            program?.let { v ->
                if (v is AutoValue.Id) {
                    if (!ix.has("program", v.ref.path)) unknownRef(what, "program", "program", v.ref.path, v.source)
                } else {
                    shape(what, "program", "a program name", v)
                }
            }
            process?.let { v ->
                if (v is AutoValue.Id) {
                    if (world?.processes?.none { it.name == v.ref.path } != false) {
                        unknownRef(what, "process", "process", v.ref.path, v.source)
                    }
                } else {
                    shape(what, "process", "a process name", v)
                }
            }
            when (val target = d.body.target) {
                null -> missing(what, "target", d.source)
                is TargetReferenceValue ->
                    if (target.ref.path != "native" && target.ref.path !in executors) {
                        unknownRef(what, "target", "executor", target.ref.path, target.source)
                    }
                else ->
                    report(
                        "ttr/automation-invalid-value",
                        "$what: target is `native` or an executor name",
                        target.source,
                    )
            }
            when (val runAs = d.body["run_as"]) {
                null -> missing(what, "run_as", d.source)
                is AutoValue.Id ->
                    if (runAs.ref.path !in setOf("caller", "author")) {
                        if (runAs.parts.firstOrNull() ==
                            "identity"
                        ) {
                            identityRef(what, "run_as", runAs)
                        } else {
                            shape(what, "run_as", "`caller`, `author` or `identity.<n>`", runAs)
                        }
                    }
                else -> shape(what, "run_as", "`caller`, `author` or `identity.<n>`", runAs)
            }
            d.body["trigger"]?.let { v ->
                val ok = (v is AutoValue.Tagged && v.tag == "schedule") || (v as? AutoValue.Id)?.ref?.path == "none"
                if (!ok) shape(what, "trigger", "`schedule \"<cron or HH:MM TZ>\"` or `none`", v)
            }
            d.body["granularity"]?.let { enumId(what, "granularity", it, GRANULARITIES) }
            for (flag in DEPLOYMENT_FLAGS) d.body[flag]?.let { bool(what, flag, it) }
            d.body["read_ceiling"]?.let {
                if (!isPositiveInt(
                        it,
                    )
                ) {
                    shape(what, "read_ceiling", "a positive integer", it)
                }
            }
        }

        private fun process(p: ProcessDef) {
            val what = "process '${p.name}'"
            for (e in p.entries) if (e.key !in KNOWN.getValue("process")) unknownKey(what, e.key, e.source)
            when (val instance = p["instance"]) {
                null -> missing(what, "instance", p.source)
                is AutoValue.Id -> attributeRef("$what instance", instance.ref.path, instance.source)
                else -> shape(what, "instance", "`<entity>.<attribute>`", instance)
            }
            if (p.steps.isEmpty()) missing(what, "steps", p.source)
            for (step in p.steps) {
                val sw = "$what step '${step.name}'"
                commonEntries("step", sw, step.body)
                misplaced("step", sw, step.body, step.source)
                when (val program = step.body["program"]) {
                    null -> missing(sw, "program", step.source)
                    is AutoValue.Id ->
                        if (!ix.has(
                                "program",
                                program.ref.path,
                            )
                        ) {
                            unknownRef(sw, "program", "program", program.ref.path, program.source)
                        }
                    else -> shape(sw, "program", "a program name", program)
                }
                val port = step.body["port"]
                if (port == null) missing(sw, "port", step.source) else enumId(sw, "port", port, STEP_PORTS)
                step.body["after"]?.let { after ->
                    if (after !is AutoValue.Obj) {
                        shape(sw, "after", "`{ obligation: …, time: …, until: [ … ] }`", after)
                    } else {
                        for (e in after.entries) {
                            when (e.key) {
                                "obligation" -> {}
                                "time" -> duration(sw, "after.time", e.value)
                                "until" -> {
                                    val items = (e.value as? AutoValue.ListV)?.items ?: listOf(e.value)
                                    for (i in items) enumId(sw, "after.until", i, UNTIL)
                                }
                                else -> unknownKey(sw, "after.${e.key}", e.source)
                            }
                        }
                    }
                }
            }
        }

        // ----- attribute lifecycle (D-67) -----

        fun lifecycle(
            entity: EntityDef,
            attr: AttributeDef,
        ) {
            if (attr.transitions.isEmpty()) return
            val name = "${entity.name}.${attr.name}"
            if (attr.states.isEmpty()) {
                report(
                    "ttr/states-transitions-without-states",
                    "$name declares `transitions:` without `states:`",
                    attr.source,
                    "warning",
                )
                return
            }
            val states = attr.states.toSet()
            val froms = mutableSetOf<String>()
            for (t in attr.transitions) {
                if (!froms.add(t.from)) {
                    report("ttr/states-duplicate-transition", "$name: `transitions:` names '${t.from}' twice", t.source)
                }
                for (s in listOf(t.from) + t.to) {
                    if (s !in states) {
                        report(
                            "ttr/states-unknown-state",
                            "$name: transition names '$s', which is not in its states",
                            t.source,
                        )
                    }
                }
            }
        }

        // ----- shared checks -----

        /** Unknown free-form keys, and a typed property that fell through to a free-form entry. */
        private fun commonEntries(
            kind: String,
            what: String,
            body: AutoBody,
        ) {
            val known = KNOWN[kind] ?: emptySet()
            val seen = mutableSetOf<String>()
            for (e in body.entries) {
                if (!seen.add(e.key)) {
                    report("ttr/automation-duplicate-property", "$what: `${e.key}:` is written twice", e.source)
                }
                if (e.key == "transition" || e.key == "set") {
                    report(
                        "ttr/automation-malformed-property",
                        "$what: `${e.key}:` is `${if (e.key == "set") "<entity>.<attribute> = <column>" else "<entity>.<attribute> -> <state>"}`",
                        e.source,
                    )
                } else if (e.key !in known) {
                    unknownKey(what, e.key, e.source)
                }
            }
        }

        private fun attributeRef(
            what: String,
            path: String,
            source: SourceLocation,
        ): Pair<EntityDef?, AttributeDef?> {
            val (entity, attr) = ix.attribute(path)
            if (entity == null) {
                report(
                    "ttr/automation-unknown-attribute",
                    "$what: '$path' names no entity of the project",
                    source,
                    "warning",
                )
            } else if (attr == null) {
                report(
                    "ttr/automation-unknown-attribute",
                    "$what: '${entity.name}' has no attribute '${path.substringAfterLast('.')}'",
                    source,
                )
            }
            return entity to attr
        }

        private fun schemaRef(
            what: String,
            key: String,
            v: AutoValue.Id,
        ) {
            val parts = v.parts
            val name = if (parts.size == 2 && parts[0] == "schema") parts[1] else v.ref.path
            if (name !in ix.schemas) {
                report("ttr/automation-event-not-schema", "$what: $key '${v.ref.path}' is no `def schema`", v.source)
            }
        }

        private fun roleRef(
            what: String,
            key: String,
            v: AutoValue,
        ) {
            val id = v as? AutoValue.Id
            if (id == null || id.parts.size != 2 || id.parts[0] != "role") {
                shape(what, key, "`role.<name>`", v)
                return
            }
            if (id.parts[1] !in ix.roles) unknownRef(what, key, "role", id.parts[1], v.source)
        }

        private fun identityRef(
            what: String,
            key: String,
            v: AutoValue.Id,
        ) {
            if (v.parts.size != 2 || world?.identities?.none { it.name == v.parts[1] } != false) {
                unknownRef(what, key, "identity", v.parts.drop(1).joinToString("."), v.source)
            }
        }

        private fun duration(
            what: String,
            key: String,
            v: AutoValue,
        ) {
            if (v !is AutoValue.Duration) {
                shape(what, key, "a duration (`2 working_days`)", v)
            } else if (v.unit !in DURATION_UNITS) {
                invalid(what, "$key unit", v.unit, DURATION_UNITS, v.source)
            } else if (v.amount < 0 || v.amount != kotlin.math.floor(v.amount)) {
                report("ttr/automation-invalid-value", "$what: `$key:` must be a whole, non-negative amount", v.source)
            }
        }

        /** A typed property the kind does not take (`transition:` on a program, `target:` on an action, …). */
        private fun misplaced(
            kind: String,
            what: String,
            body: AutoBody,
            source: SourceLocation,
        ) {
            val allowed = TYPED.getValue(kind)
            val present =
                listOfNotNull(
                    body.label?.let { "label" },
                    body.type?.let { "type" },
                    body.columns.takeIf { it.isNotEmpty() }?.let { "columns" },
                    body.target?.let { "target" },
                    body.transition?.let { "transition" },
                    body.set?.let { "set" },
                )
            for (key in present.filter { it !in allowed }) {
                val at =
                    when (key) {
                        "transition" -> body.transition?.source
                        "set" -> body.set?.source
                        "target" -> body.target?.source
                        "columns" -> body.columns.firstOrNull()?.source
                        else -> null
                    } ?: source
                report("ttr/automation-misplaced-property", "$what: a $kind takes no `$key:`", at)
            }
        }

        private fun bool(
            what: String,
            key: String,
            v: AutoValue,
        ) {
            if (v !is AutoValue.Bool) shape(what, key, "`true` or `false`", v)
        }

        private fun enumId(
            what: String,
            key: String,
            v: AutoValue,
            allowed: Set<String>,
        ) {
            val id = (v as? AutoValue.Id)?.ref?.path
            if (id == null || id !in allowed) invalid(what, key, render(v), allowed, v.source)
        }

        private fun isPositiveInt(v: AutoValue): Boolean =
            v is AutoValue.Num && v.raw >= 1 && v.raw == kotlin.math.floor(v.raw)

        private fun missing(
            what: String,
            key: String,
            source: SourceLocation,
        ) = report("ttr/automation-missing-property", "$what: `$key:` is required", source)

        private fun missingOr(
            what: String,
            key: String,
            expected: String,
            v: AutoValue?,
            source: SourceLocation,
        ) = if (v == null) missing(what, key, source) else shape(what, key, expected, v)

        private fun shape(
            what: String,
            key: String,
            expected: String,
            v: AutoValue,
        ) = report("ttr/automation-invalid-value", "$what: `$key:` must be $expected, not ${render(v)}", v.source)

        private fun invalid(
            what: String,
            key: String,
            value: String,
            allowed: Set<String>,
            source: SourceLocation,
        ) = report(
            "ttr/automation-invalid-value",
            "$what: $key '$value' is not one of ${allowed.joinToString()}",
            source,
        )

        private fun unknownKey(
            what: String,
            key: String,
            source: SourceLocation,
        ) = report("ttr/automation-unknown-property", "$what: unknown property `$key:`", source, "warning")

        private fun unknownRef(
            what: String,
            key: String,
            kind: String,
            name: String,
            source: SourceLocation,
        ) = report("ttr/automation-unknown-reference", "$what: $key names no $kind '$name'", source)
    }

    /** A value as written (for messages and the grant triple key). */
    internal fun render(v: AutoValue): String =
        when (v) {
            is AutoValue.Str -> "\"${v.raw}\""
            is AutoValue.Num -> if (v.raw == kotlin.math.floor(v.raw)) v.raw.toLong().toString() else v.raw.toString()
            is AutoValue.Bool -> v.raw.toString()
            is AutoValue.Null -> "null"
            is AutoValue.Id -> v.ref.path
            is AutoValue.Duration -> "${if (v.amount ==
                kotlin.math.floor(
                    v.amount,
                )
            ) {
                v.amount.toLong()
            } else {
                v.amount
            }} ${v.unit}"
            is AutoValue.Tagged -> "${v.tag} \"${v.value}\""
            is AutoValue.Call -> "${v.name} { … }"
            is AutoValue.Star -> "*"
            is AutoValue.Obj ->
                v.entries.joinToString(
                    ", ",
                    "{ ",
                    " }",
                ) { e: AutoEntry -> "${e.key}: ${render(e.value)}" }
            is AutoValue.ListV -> v.items.joinToString(", ", "[", "]") { render(it) }
        }
}
