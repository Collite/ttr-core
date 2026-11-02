// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.model.AutoEntry
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.SourceLocation

/**
 * Grammar 0.22 — the orchestration constructs D-142 deleted from the automation model (2026-11-01, "delete, don't
 * deprecate"). The process engine in the estate owns flow, waits, human tasks, schedules and delivery (D-141, D-143,
 * D-144, D-147, D-148); the calculus keeps the decision layer.
 *
 * For ONE release every removed construct still parses and is refused with its own number — the automation
 * validator reports `ttr/automation-removed` naming `CUT-nnn`, the recipe checker the TTR-P id built from it (ttrp-frontend
 * mints the TTR-P ids, MD5) — so a stale model fails loudly and names what models it now, instead of reading as an
 * unknown property. One table for both (the TS twin `removed-constructs.ts` mirrors it number for number).
 */
object RemovedConstructs {
    /** The validator's code for every removed construct (its `CUT-nnn` is in the message). */
    const val CODE = "ttr/automation-removed"

    enum class Cut(
        val number: Int,
        val construct: String,
        val replacement: String,
    ) {
        PROCESS(
            1,
            "`def process` (with its `def step`s and `after:` waits)",
            "a BPMN process — a service task per step calling the run API, a wait as a message catch with a timer " +
                "boundary event",
        ),
        DEPLOYMENT_PROCESS(2, "a deployment's `process:`", "deploy the program; the BPMN process calls it"),
        TRIGGER(3, "a deployment's `trigger:`", "a BPMN timer start event"),
        NATIVE_TARGET(
            4,
            "`target: native`",
            "name an executor of the world (`def executor <name> { extends: <type> }`)",
        ),
        CONNECTORS(
            5,
            "a deployment's `connectors:` (`deliver`, `ask`, `start`)",
            "the engine's tasks call the connectors",
        ),
        DELIVER(
            6,
            "a program's `deliver:` (and its digest)",
            "a BPMN send task; the Server renders a digest on request",
        ),
        SCHEDULE(7, "a program's `schedule:`", "a BPMN timer start event"),
        ASK_DUE(8, "a program's `ask_due:`", "the BPMN user task's due date, with a timer boundary event"),
        EVENT_PORT(9, "`port: event`", "`port: key` — the engine's message carries the key"),
        EVENT_SCHEMA(10, "a program's `event:` schema", "`port: key` — the engine's message carries the key"),
        SINK_DELIVER(11, "the native sink `deliver`", "a BPMN send task acting on the action row"),
        SINK_TASK(
            12,
            "the native sink `task`",
            "`approval: required` on the action — the emitted BPMN puts a user task behind the route",
        ),
        SINK_START(13, "the native sink `start`", "a BPMN call activity acting on the action row"),
        SINK_EMIT(14, "the native sink `emit`", "a BPMN message the engine sends for the action row"),
        ON_DUE(15, "`on_due:`", "a timer boundary event on the BPMN user task"),
        ROLE_DELIVERY(16, "a role's `delivery:`", "the BPMN send task addresses the role's members"),
        ;

        /** `CUT-001` — the number the validator names and the recipe checker's id carries (locale-free digits). */
        val ref: String get() = "CUT-" + number.toString().padStart(3, '0')

        /** The finding's text, after `<what>: ` — what was removed, when, and what models it now. */
        val message: String
            get() = "$construct was removed in grammar 0.22 (D-142) — model it in the process engine: $replacement"
    }

    /**
     * One removed construct found among an object's entries: [key] as written (`sinks.native` for a sink), [at] the
     * entry it was written in (the sink's value for a sink; the value itself when only values are kept, [inProperties]).
     */
    data class Hit(
        val cut: Cut,
        val key: String,
        val value: AutoValue,
        val at: SourceLocation,
    )

    /** The native sinks D-142 removed (`transition`, `writer` and `obligation` stay). */
    private val SINKS =
        mapOf(
            "deliver" to Cut.SINK_DELIVER,
            "task" to Cut.SINK_TASK,
            "start" to Cut.SINK_START,
            "emit" to Cut.SINK_EMIT,
        )

    /**
     * The removed keys of each kind, whatever their value. Two more are gated by their value: a program's `port:
     * event` ([isEventPort]) and an action's `sinks: { native: <removed sink> }` ([isRemovedSink]).
     */
    private val KEYS: Map<String, Map<String, Cut>> =
        mapOf(
            "program" to
                mapOf(
                    "deliver" to Cut.DELIVER,
                    "schedule" to Cut.SCHEDULE,
                    "ask_due" to Cut.ASK_DUE,
                    "event" to Cut.EVENT_SCHEMA,
                ),
            "action" to mapOf("on_due" to Cut.ON_DUE),
            "role" to mapOf("delivery" to Cut.ROLE_DELIVERY),
            // the connectors (`connectors: { deliver, ask, start }`) as a block or one key each
            "deployment" to
                mapOf("process" to Cut.DEPLOYMENT_PROCESS, "trigger" to Cut.TRIGGER) +
                listOf("connectors", "deliver", "ask", "start").associateWith { Cut.CONNECTORS },
        )

    /**
     * The removed constructs among the free-form entries of one object of [kind] (`program`, `action`, `role`,
     * `deployment`), in authored order, each at its entry. A deployment's `target: native` is typed, not an entry —
     * see [isNativeTarget].
     */
    fun inEntries(
        kind: String,
        entries: List<AutoEntry>,
    ): List<Hit> =
        entries.flatMap { e ->
            val cut = KEYS[kind]?.get(e.key)
            when {
                cut != null -> listOf(Hit(cut, e.key, e.value, e.source))
                kind == "program" && e.key == "port" && isEventPort(e.value) ->
                    listOf(Hit(Cut.EVENT_PORT, e.key, e.value, e.source))
                kind == "action" && e.key == "sinks" ->
                    (e.value as? AutoValue.Obj)
                        ?.entries
                        .orEmpty()
                        .filter { it.key == "native" }
                        .mapNotNull { s ->
                            SINKS[idOf(s.value)]?.let { Hit(it, "sinks.native", s.value, s.value.source) }
                        }
                else -> emptyList()
            }
        }

    /** [inEntries] over entries kept as `key to value` (the metadata model's `properties`): each hit at its value. */
    fun inProperties(
        kind: String,
        properties: List<Pair<String, AutoValue>>,
    ): List<Hit> = inEntries(kind, properties.map { (key, value) -> AutoEntry(key, value, value.source) })

    /** A removed key of [kind] whatever its value — the validator does not report it as unknown. */
    fun isRemovedKey(
        kind: String,
        key: String,
    ): Boolean = KEYS[kind]?.containsKey(key) == true

    /** `port: event` — the event port D-142 removed. */
    fun isEventPort(value: AutoValue): Boolean = idOf(value) == "event"

    /** A native sink value D-142 removed (`deliver`, `task`, `start`, `emit`). */
    fun isRemovedSink(value: AutoValue): Boolean = idOf(value) in SINKS

    /**
     * `target: native` — the Server as a pseudo-executor, which no longer exists: a deployment names an executor of its
     * world. A world that declares an executor named `native` ([executors]) targets that executor, not the pseudo one.
     */
    fun isNativeTarget(
        target: String?,
        executors: Collection<String>,
    ): Boolean = target == "native" && "native" !in executors

    private fun idOf(v: AutoValue): String? = (v as? AutoValue.Id)?.ref?.path
}
