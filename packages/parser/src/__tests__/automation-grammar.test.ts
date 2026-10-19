// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseString } from '../index.js';
import type { AutomationDef, AutoValue, AttributeDef, EntityDef, RoleDef, WorldDef } from '../ast.js';
import { TTR_NON_ID_WORDS } from '../generated/ttr-id-words.js';

// Grammar 0.16 — the automation model (AU-core-P0 S0.2). The design fixtures (`17` §1–§2, as
// written for 0.16) live with the Kotlin parser specs and are shared by all three runtimes.
const AUTOMATION = join(__dirname, '../../../kotlin/ttr-parser/src/test/resources/automation');

function parseFixture(name: string) {
  return parseString(readFileSync(join(AUTOMATION, name), 'utf-8'), name);
}

function entry(def: { entries: { key: string; value: AutoValue }[] }, key: string): AutoValue | undefined {
  return def.entries.find((e) => e.key === key)?.value;
}

describe('grammar 0.16 — model automation', () => {
  it('parses 17 §1 (automation.ttrm): parameters, actions, roles, approvals', () => {
    const { ast, errors } = parseFixture('automation.ttrm');
    expect(errors).toEqual([]);
    expect(ast?.modelDirective?.modelCode).toBe('automation');
    const kinds = ast!.definitions.map((d) => d.kind);
    expect(kinds.filter((k) => k === 'parameter')).toHaveLength(9);
    expect(kinds.filter((k) => k === 'action')).toHaveLength(10);
    expect(kinds.filter((k) => k === 'role')).toHaveLength(8);
    expect(kinds.filter((k) => k === 'approval')).toHaveLength(4);

    const defs = ast!.definitions as AutomationDef[];
    const tolerance = defs.find((d) => d.name === 'tolerance')!;
    expect(tolerance.type).toMatchObject({ kind: 'simple', name: 'decimal' });
    expect(entry(tolerance, 'value')).toMatchObject({ kind: 'number', value: 0.01 });
    expect(tolerance.label?.entries).toEqual({ en: 'Price tolerance', cs: 'Cenová tolerance' });

    const hold = defs.find((d) => d.name === 'hold')!;
    expect(hold.columns!.map((c) => c.name)).toEqual(['order', 'department']);
    expect(hold.set).toMatchObject({ attribute: 'order.held_for', column: 'department' });
    expect(hold.transition).toMatchObject({ attribute: 'order.state', to: 'held', from: ['placed'] });

    const release = defs.find((d) => d.name === 'release')!;
    expect(release.transition).toMatchObject({ to: 'released', from: ['placed', 'held'] });

    const setState = defs.find((d) => d.name === 'set_state')!;
    expect(setState.transition).toMatchObject({ attribute: 'return_request_line.state', toColumn: 'to', from: [] });
    expect(setState.transition!.to).toBeUndefined();

    const ask = defs.find((d) => d.name === 'ask')!;
    expect(entry(ask, 'due')).toMatchObject({ kind: 'duration', amount: 2, unit: 'working_days' });
    expect(ask.columns!.find((c) => c.name === 'action')!.type).toMatchObject({ name: 'action' });

    const sales = ast!.definitions.find((d): d is RoleDef => d.kind === 'role' && d.name === 'sales')!;
    expect(sales.entries!.map((e) => e.key)).toEqual(['resolve', 'delivery']);
    expect(sales.entries![0].value).toMatchObject({ kind: 'tagged', tag: 'keycloak_group', value: 'hartland-sales' });
  });

  it('parses 17 §1 (programs.ttrm): programs and event schemas', () => {
    const { ast, errors } = parseFixture('programs.ttrm');
    expect(errors).toEqual([]);
    const programs = ast!.definitions.filter((d): d is AutomationDef => d.kind === 'program');
    expect(programs).toHaveLength(8);
    const morning = programs.find((p) => p.name === 'morning_release')!;
    expect(entry(morning, 'deliver')).toMatchObject({ kind: 'autoObject' });
    expect(entry(morning, 'schedule')).toMatchObject({ kind: 'string', value: '06:00 Europe/Prague' });
    const decide = programs.find((p) => p.name === 'decide_on_request')!;
    expect(entry(decide, 'event')).toMatchObject({ kind: 'id', path: 'schema.line_received' });
    expect(entry(decide, 'mode')).toMatchObject({ kind: 'id', path: 'on_event' });
    expect(ast!.definitions.filter((d) => d.kind === 'schema')).toHaveLength(3);
  });

  it('parses 17 §2 (demo-world.ttrm): the automation members nest in the world', () => {
    const { ast, errors } = parseFixture('demo-world.ttrm');
    expect(errors).toEqual([]);
    const world = ast!.definitions[0] as WorldDef;
    expect(world.name).toBe('demo');
    expect(world.identities!.map((m) => m.name)).toEqual(['automation_decider']);
    expect(world.grants).toHaveLength(3);
    expect(entry(world.grants![0], 'program')).toMatchObject({ kind: 'star' });
    expect(entry(world.grants![0], 'as')).toMatchObject({ kind: 'id', path: 'identity.automation_decider' });
    expect(world.deployments).toHaveLength(12);
    const morning = world.deployments!.find((d) => d.name === 'release_native_morning')!;
    expect(morning.target).toMatchObject({ path: 'native' });
    expect(entry(morning, 'trigger')).toMatchObject({ kind: 'tagged', tag: 'schedule', value: '06:00 Europe/Prague' });
    expect(world.executors.map((e) => e.extends)).toEqual(['n8n_l1', 'camunda_8', 'python_client']);
    const flow = world.processes![0];
    expect(flow.steps.map((s) => s.name)).toEqual(['decide', 'inbound', 'settle']);
    expect(entry(flow.steps[1], 'after')).toMatchObject({ kind: 'autoObject' });
  });

  it('reads a comma-less literal followed by the next key as a literal, not a duration', () => {
    const { ast, errors } = parseString(
      'model automation\ndef parameter p { value: 0.01 label: { en: "P" } quorum: 2 days }',
      'p.ttrm',
    );
    expect(errors).toEqual([]);
    const p = ast!.definitions[0] as AutomationDef;
    expect(entry(p, 'value')).toMatchObject({ kind: 'number', value: 0.01 });
    expect(p.label?.entries).toEqual({ en: 'P' });
    expect(entry(p, 'quorum')).toMatchObject({ kind: 'duration', amount: 2, unit: 'days' });
  });

  it('parses a call-shaped value and a nested list of values', () => {
    const { ast, errors } = parseString(
      'model automation\ndef action ask { on_due: hold { order: order_number, department: "Sales" }, routes: [a, 1 days, "x", *] }',
      'a.ttrm',
    );
    expect(errors).toEqual([]);
    const a = ast!.definitions[0] as AutomationDef;
    expect(entry(a, 'on_due')).toMatchObject({ kind: 'call', name: 'hold' });
    const routes = entry(a, 'routes');
    expect(routes?.kind).toBe('autoList');
    expect(routes?.kind === 'autoList' && routes.items.map((i) => i.kind)).toEqual(['id', 'duration', 'string', 'star']);
  });

  it('carries states and transitions on an attribute', () => {
    const { ast, errors } = parseString(
      'model er\ndef entity order { attributes: [ def attribute state { type: text, states: [placed, held], transitions: { placed: [held] } } ] }',
      'o.ttrm',
    );
    expect(errors).toEqual([]);
    const attr = (ast!.definitions[0] as EntityDef).attributes![0] as AttributeDef;
    expect(attr.states).toEqual(['placed', 'held']);
    expect(attr.transitions).toMatchObject([{ from: 'placed', to: ['held'] }]);
  });

  it('keeps every automation noun usable as a name (none is a non-id keyword)', () => {
    const nouns = [
      'automation', 'parameter', 'parameters', 'action', 'service', 'approval', 'program', 'recipe',
      'identity', 'deployment', 'process', 'step', 'steps', 'transition', 'transitions', 'states', 'set',
    ];
    for (const noun of nouns) expect(TTR_NON_ID_WORDS.has(noun)).toBe(false);
    const { errors } = parseString(
      `model db\ndef table t { columns: [ ${nouns.map((n) => `def column ${n} { type: text }`).join(', ')} ] }`,
      't.ttrm',
    );
    expect(errors).toEqual([]);
  });

  it('rejects a top-level world member and a named grant', () => {
    expect(parseString('model world\ndef identity x { principal: "p" }', 'w.ttrm').errors.length).toBeGreaterThan(0);
    expect(
      parseString('model world\ndef world w { def grant g { who: role.ops } }', 'w.ttrm').errors.length,
    ).toBeGreaterThan(0);
  });
});
