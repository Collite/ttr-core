// SPDX-License-Identifier: Apache-2.0
/**
 * Grammar 0.22 — the orchestration constructs D-142 deleted from the automation model (2026-11-01, "delete,
 * don't deprecate"); the process engine in the estate owns flow, waits, human tasks, schedules and delivery.
 * For ONE release each still parses and is refused with its own number (`CUT-nnn`; the recipe checker's TTR-P id
 * carries it). The TS twin of Kotlin `RemovedConstructs.kt`, number for number and word for word (the validator's
 * messages are a cross-target contract).
 */
import type { AutoEntry, AutoValue, SourceLocation } from '@tatrman/parser';

/** The validator's code for every removed construct (its `CUT-nnn` is in the message). */
export const REMOVED_CODE = 'ttr/automation-removed';

export interface Cut {
  /** `CUT-001` — the number the validator names. */
  ref: string;
  construct: string;
  replacement: string;
}

function cut(ref: string, construct: string, replacement: string): Cut {
  return { ref, construct, replacement };
}

export const CUTS = {
  PROCESS: cut(
    'CUT-001',
    '`def process` (with its `def step`s and `after:` waits)',
    'a BPMN process — a service task per step calling the run API, a wait as a message catch with a timer boundary event',
  ),
  DEPLOYMENT_PROCESS: cut('CUT-002', "a deployment's `process:`", 'deploy the program; the BPMN process calls it'),
  TRIGGER: cut('CUT-003', "a deployment's `trigger:`", 'a BPMN timer start event'),
  NATIVE_TARGET: cut(
    'CUT-004',
    '`target: native`',
    'name an executor of the world (`def executor <name> { extends: <type> }`)',
  ),
  CONNECTORS: cut(
    'CUT-005',
    "a deployment's `connectors:` (`deliver`, `ask`, `start`)",
    "the engine's tasks call the connectors",
  ),
  DELIVER: cut(
    'CUT-006',
    "a program's `deliver:` (and its digest)",
    'a BPMN send task; the Server renders a digest on request',
  ),
  SCHEDULE: cut('CUT-007', "a program's `schedule:`", 'a BPMN timer start event'),
  ASK_DUE: cut('CUT-008', "a program's `ask_due:`", "the BPMN user task's due date, with a timer boundary event"),
  EVENT_PORT: cut('CUT-009', '`port: event`', "`port: key` — the engine's message carries the key"),
  EVENT_SCHEMA: cut('CUT-010', "a program's `event:` schema", "`port: key` — the engine's message carries the key"),
  SINK_DELIVER: cut('CUT-011', 'the native sink `deliver`', 'a BPMN send task acting on the action row'),
  SINK_TASK: cut(
    'CUT-012',
    'the native sink `task`',
    '`approval: required` on the action — the emitted BPMN puts a user task behind the route',
  ),
  SINK_START: cut('CUT-013', 'the native sink `start`', 'a BPMN call activity acting on the action row'),
  SINK_EMIT: cut('CUT-014', 'the native sink `emit`', 'a BPMN message the engine sends for the action row'),
  ON_DUE: cut('CUT-015', '`on_due:`', 'a timer boundary event on the BPMN user task'),
  ROLE_DELIVERY: cut('CUT-016', "a role's `delivery:`", "the BPMN send task addresses the role's members"),
} as const;

/** The finding's text, after `<what>: ` — mirrors Kotlin `Cut.message`. */
export function cutMessage(c: Cut): string {
  return `${c.construct} was removed in grammar 0.22 (D-142) — model it in the process engine: ${c.replacement}`;
}

/**
 * One removed construct among an object's entries: [key] as written (`sinks.native` for a sink), [at] the entry it was
 * written in (the sink's value for a sink).
 */
export interface Hit {
  cut: Cut;
  key: string;
  value: AutoValue;
  at: SourceLocation;
}

const SINKS: Record<string, Cut> = {
  deliver: CUTS.SINK_DELIVER,
  task: CUTS.SINK_TASK,
  start: CUTS.SINK_START,
  emit: CUTS.SINK_EMIT,
};

/**
 * The removed keys of each kind, whatever their value. Two more are gated by their value: a program's `port: event`
 * (`isEventPort`) and an action's `sinks: { native: <removed sink> }` (`isRemovedSink`).
 */
const KEYS: Record<string, Record<string, Cut>> = {
  program: { deliver: CUTS.DELIVER, schedule: CUTS.SCHEDULE, ask_due: CUTS.ASK_DUE, event: CUTS.EVENT_SCHEMA },
  action: { on_due: CUTS.ON_DUE },
  role: { delivery: CUTS.ROLE_DELIVERY },
  // the connectors (`connectors: { deliver, ask, start }`) as a block or one key each
  deployment: {
    process: CUTS.DEPLOYMENT_PROCESS,
    trigger: CUTS.TRIGGER,
    connectors: CUTS.CONNECTORS,
    deliver: CUTS.CONNECTORS,
    ask: CUTS.CONNECTORS,
    start: CUTS.CONNECTORS,
  },
};

function idOf(v: AutoValue): string | undefined {
  return v.kind === 'id' ? v.path : undefined;
}

function cutOfKey(kind: string, key: string): Cut | undefined {
  const keys = KEYS[kind];
  return keys !== undefined && Object.prototype.hasOwnProperty.call(keys, key) ? keys[key] : undefined;
}

/** `port: event` — the event port D-142 removed. */
export function isEventPort(v: AutoValue): boolean {
  return idOf(v) === 'event';
}

/** A native sink value D-142 removed (`deliver`, `task`, `start`, `emit`). */
export function isRemovedSink(v: AutoValue): boolean {
  const id = idOf(v);
  return id !== undefined && Object.prototype.hasOwnProperty.call(SINKS, id);
}

/** The removed constructs among one object's free-form entries, in authored order, each at its entry (mirrors Kotlin `inEntries`). */
export function removedInEntries(kind: string, entries: readonly AutoEntry[]): Hit[] {
  const out: Hit[] = [];
  for (const { key, value, source } of entries) {
    const cut = cutOfKey(kind, key);
    if (cut !== undefined) out.push({ cut, key, value, at: source });
    else if (kind === 'program' && key === 'port' && isEventPort(value)) {
      out.push({ cut: CUTS.EVENT_PORT, key, value, at: source });
    } else if (kind === 'action' && key === 'sinks' && value.kind === 'autoObject') {
      for (const e of value.entries) {
        if (e.key === 'native' && isRemovedSink(e.value)) {
          out.push({ cut: SINKS[idOf(e.value)!], key: 'sinks.native', value: e.value, at: e.value.source });
        }
      }
    }
  }
  return out;
}

/** A removed key of [kind] whatever its value — the validator does not report it as unknown. */
export function isRemovedKey(kind: string, key: string): boolean {
  return cutOfKey(kind, key) !== undefined;
}

/** `target: native` — the Server as a pseudo-executor; a deployment now names an executor of its world. */
export function isNativeTarget(target: string | undefined): boolean {
  return target === 'native';
}
