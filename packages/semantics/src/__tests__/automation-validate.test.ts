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
// same fixtures: design `17` §1–§2 (ttr-parser test resources) validate clean against the hartland
// ER stub plus the implicit stdlib; each planted negative fires exactly its code.
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

describe('validateAutomationProject (grammar 0.16)', () => {
  it('17 §1–§2 validate clean: only the pre-catalog recipe INFOs', async () => {
    const stdlib = await loadAutomationStdlib();
    const diags = validateAutomationProject(hartland(), { stdlib });
    expect(diags.filter((d) => d.severity !== 'info')).toEqual([]);
    expect(new Set(diags.map((d) => d.code))).toEqual(new Set(['ttr/automation-recipe-precatalog']));
    expect(diags).toHaveLength(8); // one per program
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
    ['neg-01-deployment-program-and-process.ttrm', ['ttr/automation-deployment-program-and-process']],
    ['neg-02-duplicate-grant.ttrm', ['ttr/automation-duplicate-grant']],
    ['neg-03-event-not-schema.ttrm', ['ttr/automation-event-not-schema']],
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
