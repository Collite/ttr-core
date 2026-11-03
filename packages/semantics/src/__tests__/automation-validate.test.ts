// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { parseString, type Document, type EntityDef } from '@tatrman/parser';
import { validateAutomationProject, automationStdlibNeeded } from '../automation-validate.js';
import { loadAutomationStdlib } from '../stock-loader.js';
import { buildCanonicalKey, modelForKind } from '../qname.js';
import { ProjectSymbolTable } from '../project-symbols.js';
import { Resolver } from '../resolver.js';

// Grammar 0.16 — the TS twin of the Kotlin `AutomationValidatorSpec` (AU-core-P0 S0.2 T4), on the
// same fixtures: design `17` §1–§2 as written (ttr-parser test resources) refuse exactly their removed
// constructs since grammar 0.22 (D-142); the Core fixture model as cut validates clean against the
// implicit stdlib; each planted negative fires exactly its code.
const KOTLIN = join(__dirname, '../../../kotlin');
const AUTOMATION = join(KOTLIN, 'ttr-parser/src/test/resources/automation');
const SEMANTIC = join(KOTLIN, 'ttr-semantics/src/test/resources/automation');

function parse(path: string): Document {
  const r = parseString(readFileSync(path, 'utf-8'), path);
  expect(r.errors).toEqual([]);
  return r.ast!;
}

function hartland(): Document[] {
  return [
    ...['automation.ttrm', 'programs.ttrm', 'demo-world.ttrm'].map((f) => parse(join(AUTOMATION, f))),
    parse(join(SEMANTIC, 'hartland-er-stub.ttrm')),
  ];
}

const REMOVED = 'ttr/automation-removed';

function cutIds(diags: { code: string; message: string }[]): string[] {
  return diags
    .filter((d) => d.code === REMOVED)
    .map((d) => {
      const id = /CUT-\d{3}/.exec(d.message)?.[0];
      if (!id) throw new Error(`no TTRP-CUT id in ${d.message}`);
      return id;
    });
}

function counts(ids: string[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const id of ids) out[id] = (out[id] ?? 0) + 1;
  return out;
}

function ttrmFiles(dir: string): string[] {
  return readdirSync(dir)
    .flatMap((f) => {
      const p = join(dir, f);
      return statSync(p).isDirectory() ? ttrmFiles(p) : f.endsWith('.ttrm') ? [p] : [];
    })
    .sort();
}

describe('validateAutomationProject (grammar 0.16)', () => {
  it('D-142: 17 §1–§2 as written refuse exactly their removed constructs, one finding each', async () => {
    const stdlib = await loadAutomationStdlib();
    const diags = validateAutomationProject(hartland(), { stdlib });
    expect(new Set(diags.filter((d) => d.severity !== 'info').map((d) => d.code))).toEqual(new Set([REMOVED]));
    for (const d of diags.filter((d) => d.code === REMOVED)) expect(d.severity).toBe('error');
    expect(counts(cutIds(diags))).toEqual({
      'CUT-001': 1, // def process return_line_flow
      'CUT-002': 1, // returns_n8n { process: … }
      'CUT-003': 4, // trigger: schedule … on four deployments
      'CUT-004': 8, // target: native
      'CUT-006': 2, // deliver: (digest, immediate)
      'CUT-007': 4, // schedule:
      'CUT-009': 3, // port: event
      'CUT-010': 3, // event: schema.…
      'CUT-011': 2, // sinks.native: deliver
      'CUT-012': 2, // sinks.native: task
      'CUT-013': 1, // sinks.native: start
      'CUT-016': 2, // role delivery:
    });
    const process = diags.find((d) => d.message.includes('CUT-001'))!;
    expect(process.message).toContain("process 'return_line_flow'");
    expect(process.message).toContain('model it in the process engine');
  });

  it('D-142: the constructs 17 does not use are refused too (ask_due, emit, on_due, connectors)', async () => {
    const project = [
      parse(join(SEMANTIC, 'hartland-er-stub.ttrm')),
      parse(join(SEMANTIC, 'negative/common.ttrm')),
      parse(join(SEMANTIC, 'removed/rest.ttrm')),
      parse(join(SEMANTIC, 'removed/rest-world.ttrm')),
    ];
    const diags = validateAutomationProject(project, { stdlib: await loadAutomationStdlib() });
    expect(new Set(diags.filter((d) => d.severity !== 'info').map((d) => d.code))).toEqual(new Set([REMOVED]));
    expect(cutIds(diags).sort()).toEqual(['CUT-005', 'CUT-008', 'CUT-014', 'CUT-015']);
  });

  it('D-142: `target: native` is refused only where the world declares no executor named `native`', async () => {
    const project = [
      parse(join(SEMANTIC, 'hartland-er-stub.ttrm')),
      parse(join(SEMANTIC, 'negative/common.ttrm')),
      parse(join(SEMANTIC, 'removed/native-executor.ttrm')),
    ];
    const diags = validateAutomationProject(project, { stdlib: await loadAutomationStdlib() }).filter((d) => d.severity !== 'info');
    expect(diags.map((d) => d.code)).toEqual([REMOVED]);
    expect(cutIds(diags)).toEqual(['CUT-004']);
    expect(diags[0]!.source.line).toBe(13); // world `bare`'s deployment
  });

  it('D-142: a removed key written twice is refused at each entry', async () => {
    const project = [
      parse(join(SEMANTIC, 'hartland-er-stub.ttrm')),
      parse(join(SEMANTIC, 'negative/common.ttrm')),
      parse(join(SEMANTIC, 'removed/duplicate-key.ttrm')),
    ];
    const cuts = validateAutomationProject(project, { stdlib: await loadAutomationStdlib() }).filter((d) => d.code === REMOVED);
    expect(cutIds(cuts)).toEqual(['CUT-007', 'CUT-007']);
    expect(cuts.map((d) => d.source.line)).toEqual([6, 7]);
  });

  it('the Core fixture model as cut validates clean: only the pre-catalog recipe INFOs', async () => {
    const root = join(KOTLIN, 'ttrp-graph/src/test/resources/fixtures/recipes/model/hartland');
    const diags = validateAutomationProject(ttrmFiles(root).map(parse), { stdlib: await loadAutomationStdlib() });
    expect(diags.filter((d) => d.severity !== 'info')).toEqual([]);
    expect(new Set(diags.map((d) => d.code))).toEqual(new Set(['ttr/automation-recipe-precatalog']));
  });

  it('the stdlib joins a project that declares `model automation` (obligation.state resolves)', () => {
    expect(automationStdlibNeeded(hartland())).toBe(true);
    // an import path segment named `automation` is no reason (review-114 M6)
    expect(automationStdlibNeeded([parse(join(SEMANTIC, 'hartland-er-stub.ttrm'))])).toBe(false);
    const withoutStdlib = validateAutomationProject(hartland());
    expect(
      withoutStdlib.some((d) => d.code === 'ttr/automation-unknown-attribute' && d.message.includes('obligation')),
    ).toBe(true);
  });

  const negatives: Array<[string, string[]]> = [
    ['neg-01-deployment-without-program.ttrm', ['ttr/automation-missing-property']],
    ['neg-02-duplicate-grant.ttrm', ['ttr/automation-duplicate-grant']],
    ['neg-03-not-a-schema.ttrm', ['ttr/automation-event-not-schema']],
    ['neg-04-transitions-unknown-state.ttrm', ['ttr/states-unknown-state']],
    ['neg-05-action-transition-unknown-state.ttrm', ['ttr/states-unknown-state']],
    ['neg-06-writable-unknown-entity.ttrm', ['ttr/automation-writable-unknown-entity']],
    ['neg-07-set-malformed.ttrm', ['ttr/automation-malformed-property']],
    ['neg-08-transition-not-allowed.ttrm', ['ttr/states-transition-not-allowed']],
  ];
  for (const [name, expected] of negatives) {
    it(`negative ${name} fires exactly ${expected.join(', ')}`, async () => {
      const project = [
        parse(join(SEMANTIC, 'hartland-er-stub.ttrm')),
        parse(join(SEMANTIC, 'negative/common.ttrm')),
        parse(join(SEMANTIC, `negative/${name}`)),
      ];
      const findings = validateAutomationProject(project, { stdlib: await loadAutomationStdlib() }).filter(
        (d) => d.severity !== 'info',
      );
      expect(findings.map((d) => d.code).sort()).toEqual([...expected].sort());
    });
  }

  // review-114 — the contract spellings, per-world references, the validator gaps, stdlib precedence.
  const review114: Array<[string, string[]]> = [
    ['s01-grant-spellings.ttrm', []],
    ['s02-duplicate-member.ttrm', ['ttr/automation-duplicate-member']],
    ['s03-identity-other-world.ttrm', ['ttr/automation-unknown-reference']],
    ['s04-durations.ttrm', ['ttr/automation-invalid-value', 'ttr/automation-invalid-value']],
    ['s05-int-fraction.ttrm', ['ttr/automation-invalid-value']],
    ['s06-misplaced.ttrm', ['ttr/automation-misplaced-property']],
    ['s07-duplicate-transition.ttrm', ['ttr/states-duplicate-transition']],
    ['s08-writable-non-id.ttrm', ['ttr/automation-invalid-value']],
    ['s09-duplicate-property.ttrm', ['ttr/automation-duplicate-property']],
    ['s10-user-obligation', []],
  ];
  for (const [name, expected] of review114) {
    it(`review-114 ${name} fires exactly ${expected.join(', ') || 'nothing'}`, async () => {
      const scenario = join(SEMANTIC, 'review-114', name);
      const files = statSync(scenario).isDirectory()
        ? readdirSync(scenario).filter((f) => f.endsWith('.ttrm')).sort().map((f) => join(scenario, f))
        : [scenario];
      const project = [
        parse(join(SEMANTIC, 'hartland-er-stub.ttrm')),
        parse(join(SEMANTIC, 'negative/common.ttrm')),
        ...files.map(parse),
      ];
      const findings = validateAutomationProject(project, { stdlib: await loadAutomationStdlib() }).filter(
        (d) => d.severity !== 'info',
      );
      expect(findings.map((d) => d.code).sort()).toEqual([...expected].sort());
    });
  }

  it('the stdlib is byte-identical to the Kotlin resource and parses clean', async () => {
    const ts = readFileSync(join(__dirname, '../stock/automation-stdlib.ttrm'), 'utf-8');
    const kt = readFileSync(join(KOTLIN, 'ttr-parser/src/main/resources/automation/automation-stdlib.ttrm'), 'utf-8');
    expect(ts).toBe(kt);
    const stdlib = (await loadAutomationStdlib())!;
    expect(stdlib.definitions.map((d) => d.name)).toEqual(['obligation', 'state_history', 'approvals']);
    const state = (stdlib.definitions[0] as EntityDef).attributes!.find((a) => a.name === 'state')!;
    expect(state.states).toEqual(['open', 'fulfilled', 'overdue', 'cancelled', 'reopened']);
  });

  it('maps the automation kinds to the automation model and the world members to the world', () => {
    for (const k of ['parameter', 'action', 'service', 'approval', 'program', 'recipe']) expect(modelForKind(k)).toBe('automation');
    for (const k of ['identity', 'deployment', 'process']) expect(modelForKind(k)).toBe('world');
    expect(modelForKind('role')).toBe('cnc');
    expect(modelForKind('role', 'automation')).toBe('automation');
    expect(buildCanonicalKey({ packageName: 'hartland.automation', kind: 'program', parts: ['morning_release'] })).toBe(
      'hartland.automation.automation.program.morning_release',
    );
  });

  // review-114 B1 — `automation` keys symbols but is no reference prefix: the stdlib package `automation`
  // and a package `hartland.automation` stay packages for imports and references.
  it('resolves through a package named `automation` and through `import hartland.automation.*`', async () => {
    const stdlib = (await loadAutomationStdlib())!;
    const events = parseString('package hartland.automation\nmodel automation\ndef schema line_received { columns: [def column line_id { type: text }] }', 'e.ttrm').ast!;
    const user = parseString('package hartland.x\nimport automation.*\nimport hartland.automation.*\nmodel er\ndef entity e { attributes: [ def attribute id { type: text } ] }', 'u.ttrm').ast!;
    const symbols = new ProjectSymbolTable();
    symbols.upsertDocument('stdlib://automation-stdlib.ttrm', stdlib, 'er', '', 'automation');
    symbols.upsertDocument('file:///e.ttrm', events, 'automation', '', 'hartland.automation');
    symbols.upsertDocument('file:///u.ttrm', user, 'er', '', 'hartland.x');
    const resolver = new Resolver(symbols);
    const ctx = { schemaCode: 'er', namespace: '', imports: user.imports, packageName: 'hartland.x' };
    const qualified = resolver.resolveReference({ path: 'automation.obligation', parts: ['automation', 'obligation'] }, ctx);
    expect(qualified.resolved && qualified.symbol.qname).toBe('automation.er.entity.obligation');
    const imported = resolver.resolveReference({ path: 'obligation', parts: ['obligation'] }, ctx);
    expect(imported.resolved && imported.symbol.qname).toBe('automation.er.entity.obligation');
    const schema = resolver.resolveReference({ path: 'line_received', parts: ['line_received'] }, ctx);
    expect(schema.resolved && schema.symbol.qname).toBe('hartland.automation.db.dbo.schema.line_received');
  });
});


it('CG-2 checks by and every startable_by role, and requires approval by', () => {
  const action = parseString('model automation\ndef role manager { members: ["maya"] }\ndef action release { columns: [def column x { type: text }], sinks: { native: writer }, approval: required, by: role.nobody }\ndef program p { recipe: r, port: none, mode: batch }');
  const world = parseString('model world\ndef world demo { def executor engine { extends: native } def deployment d { program: p, target: engine, run_as: caller, startable_by: [role.manager, role.absent] } }');
  expect(action.errors).toEqual([]);
  expect(world.errors).toEqual([]);
  expect(validateAutomationProject([action.ast!, world.ast!]).filter((d) => d.severity !== 'info').map((d) => d.code)).toEqual(['ttr/automation-unknown-reference', 'ttr/automation-unknown-reference']);
  const missing = parseString('model automation\ndef action a { columns: [def column x { type: text }], sinks: { native: writer }, approval: required }');
  expect(validateAutomationProject([missing.ast!]).find((d) => d.message.includes('by'))?.code).toBe('ttr/automation-missing-property');
});
