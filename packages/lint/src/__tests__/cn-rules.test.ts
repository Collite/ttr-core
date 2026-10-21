// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { lintDocument, lintProject } from '../runner.js';
import { resolveLintConfig } from '../config.js';
import { RULES, rulesByCategory } from '../registry.js';
import type { LintDiagnostic } from '../rule.js';
import { buildProject, lintProj, recommendedConfig, type ProjectFile } from './helpers.js';

/**
 * CN-P2 S2.3 — the model-level `cn/*` slugs as lint rules (cn contracts §7). Each conformance fixture
 * 85–101 holds one slug's firing cases and its near-misses; the expected sites below are the ones its
 * header comment names. The portable slugs are also compared across runtimes by the sem dump; the
 * lexicon pair (100, 101) is checked here only.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(here, '../../../../tests/conformance/fixtures');

function load(dir: string): ProjectFile[] {
  return readdirSync(path.join(FIXTURES, dir))
    .filter((f) => f.endsWith('.ttrm'))
    .sort()
    .map((f) => ({ uri: `${dir}/${f}`, src: readFileSync(path.join(FIXTURES, dir, f), 'utf8') }));
}

/** Every rule switched on at its default — the cn hygiene slugs included. */
const CN_ON = recommendedConfig({ 'cn-ungrounded': 'info', 'cn-phase-no-definition': 'info', 'cn-event-no-period': 'info' });

const CN_RULE_IDS = new Set(rulesByCategory('cn').map((r) => r.id));

/** The cn diagnostics of a scenario (document and project rules), as `file:line code`, sorted. */
function cnSites(dir: string, config = CN_ON): string[] {
  const project = buildProject(load(dir));
  const all: LintDiagnostic[] = [];
  for (const [uri, ast] of project.documents) all.push(...lintDocument(uri, ast, project.deps, config));
  for (const diags of lintProject(project.documents, project.graph, project.deps, config).values()) all.push(...diags);
  return all
    .filter((d) => CN_RULE_IDS.has(d.ruleId))
    .map((d) => `${d.source.file.slice(dir.length + 1)}:${d.source.line} ${d.code}`)
    .sort();
}

const EXPECTED: Record<string, string[]> = {
  '85-cn-duplicate-grounding': [
    'a-er.ttrm:19 cn/duplicate-grounding',
    'a-er.ttrm:28 cn/duplicate-grounding',
    'c-bindings.ttrm:5 cn/duplicate-grounding',
  ],
  '86-cn-grounding-target-kind': [
    'c-concepts.ttrm:12 cn/grounding-target-kind',
    'c-concepts.ttrm:20 cn/grounding-target-kind',
    'c-concepts.ttrm:21 cn/grounding-target-kind',
    'c-concepts.ttrm:21 cn/phase-no-definition',
    'c-concepts.ttrm:23 cn/grounding-target-kind',
    'c-concepts.ttrm:8 cn/grounding-target-kind',
    'd-bindings.ttrm:5 cn/grounding-target-kind',
    'd-bindings.ttrm:6 cn/grounding-target-kind',
  ],
  '87-cn-role-no-event': ['a-er.ttrm:16 cn/role-no-event', 'b-concepts.ttrm:6 cn/role-no-event'],
  '88-cn-bearer-missing': [
    'a-er.ttrm:13 ttr/required-property-missing',
    'b-concepts.ttrm:5 ttr/required-property-missing',
    'b-concepts.ttrm:7 ttr/required-property-missing',
  ],
  '89-cn-phase-double-definition': ['b-concepts.ttrm:5 cn/phase-double-definition', 'c-bindings.ttrm:4 cn/phase-double-definition'],
  '90-cn-phase-no-definition': ['b-concepts.ttrm:5 cn/phase-no-definition'],
  '91-cn-event-no-period': ['a-er.ttrm:17 cn/event-no-period', 'b-concepts.ttrm:4 cn/event-no-period'],
  '92-cn-ungrounded': [
    'b-concepts.ttrm:4 cn/ungrounded',
    'b-concepts.ttrm:5 cn/ungrounded',
    'b-concepts.ttrm:6 cn/ungrounded',
    'b-concepts.ttrm:7 cn/ungrounded',
  ],
  '93-cn-db-grounding': ['c-concepts.ttrm:4 cn/db-grounding', 'd-bindings.ttrm:4 cn/db-grounding'],
  '94-cn-involves-unlabelled-duplicate': [
    'b-concepts.ttrm:17 cn/involves-unlabelled-duplicate',
    'b-concepts.ttrm:7 cn/involves-unlabelled-duplicate',
  ],
  '95-cn-antirigid-supertype': [
    'b-concepts.ttrm:10 cn/antirigid-supertype',
    'b-concepts.ttrm:8 cn/antirigid-supertype',
    'b-concepts.ttrm:9 cn/antirigid-supertype',
  ],
  '96-cn-unknown-relation-type': ['b-concepts.ttrm:7 cn/unknown-relation-type', 'b-concepts.ttrm:8 cn/unknown-relation-type'],
  '97-cn-cnc-deprecated': ['a-cnc.ttrm:7 cn/cnc-deprecated', 'b-er.ttrm:4 cn/cnc-deprecated'],
  '98-cn-hierarchy-shape': [
    'b-concepts.ttrm:10 cn/hierarchy-shape',
    'b-concepts.ttrm:14 cn/hierarchy-shape',
    'c-bindings.ttrm:7 cn/hierarchy-shape',
  ],
  '99-cn-participant-path-broken': [
    'd-bindings.ttrm:10 cn/participant-path-broken',
    'd-bindings.ttrm:19 cn/participant-path-broken',
    'd-bindings.ttrm:20 cn/participant-path-broken',
  ],
  '100-cn-duplicate-default-sense': ['c-lexicon-en.ttrm:5 cn/duplicate-default-sense'],
  '101-cn-phase-as-default': ['c-lexicon.ttrm:6 cn/phase-as-default', 'c-lexicon.ttrm:7 cn/phase-as-default'],
};

describe('CN-P2 S2.3 — the cn slugs fire on their fixture, and only at the sites its header names', () => {
  it.each(Object.keys(EXPECTED))('%s', (dir) => {
    expect(cnSites(dir)).toEqual(EXPECTED[dir]);
  });

  it('the S2.2 equivalence fixtures stay clean but for the advisory db grounding', () => {
    expect(cnSites('81-cn-desugar-top-down')).toEqual(['d-concepts.ttrm:28 cn/db-grounding']);
    expect(cnSites('82-cn-desugar-top-down-authored')).toEqual(['e-bindings.ttrm:25 cn/db-grounding']);
    expect(cnSites('83-cn-desugar-bottom-up')).toEqual([]);
    expect(cnSites('84-cn-desugar-bottom-up-authored')).toEqual([]);
  });

  it('every message names the concept and says what to do', () => {
    const project = buildProject(load('86-cn-grounding-target-kind'));
    const msgs = [...lintProject(project.documents, project.graph, project.deps, CN_ON).values()]
      .flat()
      .filter((d) => d.code === 'cn/grounding-target-kind')
      .map((d) => d.message);
    expect(msgs).toContain("'cn2md_kind' grounds 'Brand' to a dimension or a domain — 'net_sales' is a measure");
    expect(msgs).toContain("'cn2er_phase' grounds 'HeldOrder' to an er entity stacked on a query (AM-2) — 'order' is an entity no query backs");
    expect(msgs).toContain("'cn2md_role' grounds a role, but 'Category' is a kind");
  });
});

describe('CN-P2 S2.3 — the cn rules in the registry and the presets', () => {
  it('one rule per cn slug, category cn', () => {
    const codes = rulesByCategory('cn').map((r) => r.code).filter((c) => c.startsWith('cn/')).sort();
    expect(codes).toEqual([
      'cn/antirigid-supertype',
      'cn/cnc-deprecated',
      'cn/db-grounding',
      'cn/duplicate-default-sense',
      'cn/duplicate-grounding',
      'cn/event-no-period',
      'cn/grounding-target-kind',
      'cn/hierarchy-shape',
      'cn/involves-unlabelled-duplicate',
      'cn/participant-path-broken',
      'cn/phase-as-default',
      'cn/phase-double-definition',
      'cn/phase-no-definition',
      'cn/role-no-event',
      'cn/ungrounded',
      'cn/unknown-relation-type',
    ]);
    expect(RULES.get('concept-bearer-missing')?.code).toBe('ttr/required-property-missing');
  });

  it('grades → default severities: errors, two warnings, advisory and hygiene at info', () => {
    const sev = (id: string) => RULES.get(id)?.defaultSeverity;
    expect(sev('cn-duplicate-grounding')).toBe('error');
    expect(sev('cn-participant-path-broken')).toBe('error');
    expect(sev('cn-antirigid-supertype')).toBe('warning');
    expect(sev('cn-cnc-deprecated')).toBe('warning');
    expect(sev('cn-phase-as-default')).toBe('warning');
    expect(sev('cn-db-grounding')).toBe('info');
    for (const id of ['cn-ungrounded', 'cn-phase-no-definition', 'cn-event-no-period']) expect(sev(id)).toBe('info');
  });

  it('hygiene is off in recommended, info in strict, error in all', () => {
    for (const id of ['cn-ungrounded', 'cn-phase-no-definition', 'cn-event-no-period']) {
      expect(resolveLintConfig({ extends: 'recommended' }).severityOf(id)).toBe('off');
      expect(resolveLintConfig({ extends: 'strict' }).severityOf(id)).toBe('info');
      expect(resolveLintConfig({ extends: 'all' }).severityOf(id)).toBe('error');
    }
    expect(resolveLintConfig({ extends: 'recommended' }).severityOf('cn-db-grounding')).toBe('info');
  });

  it('`[categories] cn` configures the whole family, with no unknown-category warning', () => {
    const c = resolveLintConfig({ categories: { cn: 'off' } });
    expect(c.diagnostics).toEqual([]);
    expect(c.severityOf('cn-duplicate-grounding')).toBe('off');
  });

  it('recommended shows no hygiene', () => {
    expect(cnSites('92-cn-ungrounded', recommendedConfig())).toEqual([]);
  });

  it('a project diagnostic lands on the file it points at, and a directive suppresses it', () => {
    const files: ProjectFile[] = [
      { uri: 'a.ttrm', src: 'package p\nmodel cn\n\ndef role Visitor { }\n// ttr-disable-next-line cn-role-no-event\ndef role Guest { }\n' },
      { uri: 'b.ttrm', src: 'package p\nmodel cn\n\ndef kind Party { }\n' },
    ];
    const byUri = lintProj(files, { config: CN_ON });
    const cn = (uri: string) => (byUri.get(uri) ?? []).filter((d) => d.code.startsWith('cn/')).map((d) => `${d.source.line} ${d.code}`);
    expect(cn('a.ttrm')).toEqual(['4 cn/role-no-event', '4 cn/ungrounded', '6 cn/ungrounded']);
    expect(cn('b.ttrm')).toEqual(['4 cn/ungrounded']);
  });
});
