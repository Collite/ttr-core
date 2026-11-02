// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import org.tatrman.ttr.parser.model.AutoValue

/**
 * Grammar 0.22 — the orchestration constructs D-142 deleted from the automation model (2026-11-01, "delete, don't
 * deprecate"). The process engine in the estate owns flow, waits, human tasks, schedules and delivery (D-141, D-143,
 * D-144, D-147, D-148); the calculus keeps the decision layer.
 *
 * For ONE release every removed construct still parses and is refused with its own id — the automation validator
 * reports `ttr/automation-removed`, the recipe checker `TTRP-CUT-nnn` — so a stale model fails loudly and names
 * what models it now, instead of reading as an unknown property. One table for both (the TS twin
 * `removed-constructs.ts` mirrors it id for id).
 */
object RemovedConstructs {
    /** The validator's code for every removed construct (the TTRP id is in the message). */
    const val CODE = "ttr/automation-removed"

    enum class Cut(
        val id: String,
        val construct: String,
        val replacement: String,
    ) {
        PROCESS(
            "TTRP-CUT-001",
            "`def process` (with its `def step`s and `after:` waits)",
            "a BPMN process — a service task per step calling the run API, a wait as a message catch with a timer " +
                "boundary event",
        ),
        DEPLOYMENT_PROCESS("TTRP-CUT-002", "a deployment's `process:`", "deploy the program; the BPMN process calls it"),
        TRIGGER("TTRP-CUT-003", "a deployment's `trigger:`", "a BPMN timer start event"),
        NATIVE_TARGET(
            "TTRP-CUT-004",
            "`target: native`",
            "name an executor of the world (`def executor <name> { extends: <type> }`)",
        ),
        CONNECTORS(
            "TTRP-CUT-005",
            "a deployment's `connectors:` (`deliver`, `ask`, `start`)",
            "the engine's tasks call the connectors",
        ),
        DELIVER(
            "TTRP-CUT-006",
            "a program's `deliver:` (and its digest)",
            "a BPMN send task; the Server renders a digest on request",
        ),
        SCHEDULE("TTRP-CUT-007", "a program's `schedule:`", "a BPMN timer start event"),
        ASK_DUE("TTRP-CUT-008", "a program's `ask_due:`", "the BPMN user task's due date, with a timer boundary event"),
        EVENT_PORT("TTRP-CUT-009", "`port: event`", "`port: key` — the engine's message carries the key"),
        EVENT_SCHEMA("TTRP-CUT-010", "a program's `event:` schema", "`port: key` — the engine's message carries the key"),
        SINK_DELIVER("TTRP-CUT-011", "the native sink `deliver`", "a BPMN send task acting on the action row"),
        SINK_TASK(
            "TTRP-CUT-012",
            "the native sink `task`",
            "`approval: required` on the action — the emitted BPMN puts a user task behind the route",
        ),
        SINK_START("TTRP-CUT-013", "the native sink `start`", "a BPMN call activity acting on the action row"),
        SINK_EMIT("TTRP-CUT-014", "the native sink `emit`", "a BPMN message the engine sends for the action row"),
        ON_DUE("TTRP-CUT-015", "`on_due:`", "a timer boundary event on the BPMN user task"),
        ROLE_DELIVERY("TTRP-CUT-016", "a role's `delivery:`", "the BPMN send task addresses the role's members"),
        ;

        /** The finding's text, after `<what>: ` — what was removed, when, and what models it now. */
        val message: String
            get() = "$construct was removed in grammar 0.22 (D-142) — model it in the process engine: $replacement"
    }

    /** One removed construct found among an object's entries: [key] as written (`sinks.native` for a sink). */
    data class Hit(
        val cut: Cut,
        val key: String,
        val value: AutoValue,
    )

    /** The native sinks D-142 removed (`transition`, `writer` and `obligation` stay). */
    private val SINKS =
        mapOf(
            "deliver" to Cut.SINK_DELIVER,
            "task" to Cut.SINK_TASK,
            "start" to Cut.SINK_START,
            "emit" to Cut.SINK_EMIT,
        )

    /** The deployment keys of the removed connectors (D-142: `connectors: { deliver, ask, start }`). */
    private val CONNECTOR_KEYS = setOf("connectors", "deliver", "ask", "start")

    /**
     * The removed constructs among the free-form entries of one object of [kind] (`program`, `action`, `role`,
     * `deployment`), in authored order. A deployment's `target: native` is typed, not an entry — see [isNativeTarget].
     */
    fun inEntries(
        kind: String,
        entries: List<Pair<String, AutoValue>>,
    ): List<Hit> =
        entries.flatMap { (key, value) ->
            when (kind) {
                "program" ->
                    when (key) {
                        "deliver" -> listOf(Hit(Cut.DELIVER, key, value))
                        "schedule" -> listOf(Hit(Cut.SCHEDULE, key, value))
                        "ask_due" -> listOf(Hit(Cut.ASK_DUE, key, value))
                        "event" -> listOf(Hit(Cut.EVENT_SCHEMA, key, value))
                        "port" -> if (idOf(value) == "event") listOf(Hit(Cut.EVENT_PORT, key, value)) else emptyList()
                        else -> emptyList()
                    }
                "action" ->
                    when (key) {
                        "on_due" -> listOf(Hit(Cut.ON_DUE, key, value))
                        "sinks" ->
                            (value as? AutoValue.Obj)
                                ?.entries
                                .orEmpty()
                                .filter { it.key == "native" }
                                .mapNotNull { e -> SINKS[idOf(e.value)]?.let { Hit(it, "sinks.native", e.value) } }
                        else -> emptyList()
                    }
                "role" -> if (key == "delivery") listOf(Hit(Cut.ROLE_DELIVERY, key, value)) else emptyList()
                "deployment" ->
                    when (key) {
                        "process" -> listOf(Hit(Cut.DEPLOYMENT_PROCESS, key, value))
                        "trigger" -> listOf(Hit(Cut.TRIGGER, key, value))
                        in CONNECTOR_KEYS -> listOf(Hit(Cut.CONNECTORS, key, value))
                        else -> emptyList()
                    }
                else -> emptyList()
            }
        }

    /** The removed keys of [kind] — read by nothing but [inEntries]; the validator skips them as unknown keys. */
    fun isRemovedKey(
        kind: String,
        key: String,
    ): Boolean =
        when (kind) {
            "program" -> key in setOf("deliver", "schedule", "ask_due", "event")
            "action" -> key == "on_due"
            "role" -> key == "delivery"
            "deployment" -> key == "process" || key == "trigger" || key in CONNECTOR_KEYS
            else -> false
        }

    /** `target: native` — the Server as a pseudo-executor; a deployment now names an executor of its world. */
    fun isNativeTarget(target: String?): Boolean = target == "native"

    private fun idOf(v: AutoValue): String? = (v as? AutoValue.Id)?.ref?.path
}
