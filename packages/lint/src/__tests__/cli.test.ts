// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const CLI = resolve(__dirname, '../../dist/cli.js');

function runCli(args: string[]): { status: number; stdout: string; stderr: string } {
  try {
    const stdout = execFileSync('node', [CLI, ...args], { encoding: 'utf-8' });
    return { status: 0, stdout, stderr: '' };
  } catch (err) {
    const e = err as { status?: number; stdout?: Buffer | string; stderr?: Buffer | string };
    return { status: e.status ?? 1, stdout: e.stdout?.toString() ?? '', stderr: e.stderr?.toString() ?? '' };
  }
}

describe('modeler-lint CLI', () => {
  it('preserves the manifest unknown-key warning and exits zero under default fail-on', () => {
    const temp = mkdtempSync(join(tmpdir(), 'modeler-lint-manifest-'));
    try {
      writeFileSync(join(temp, 'modeler.toml'), '[bindings]\nrequire = true');
      writeFileSync(join(temp, 'empty.ttrm'), '');
      const result = runCli([temp, '--format', 'json']);
      expect(result.status).toBe(0);
      expect(JSON.parse(result.stdout)).toEqual([expect.objectContaining({ code: 'ttrlint/manifest-unknown-key', severity: 'warning' })]);
    } finally { rmSync(temp, { recursive: true, force: true }); }
  });
  let root: string;

  beforeAll(() => {
    root = mkdtempSync(join(tmpdir(), 'modeler-lint-cli-'));
    writeFileSync(join(root, 'modeler.toml'), `[project]\nname = "t"\n`);
    mkdirSync(join(root, 'app'), { recursive: true });
    writeFileSync(
      join(root, 'app', 'main.ttrm'),
      `package app\nimport other.db.dbo.thing\nmodel db schema dbo\ndef table t { columns: [def column id { type: int }] }\n`
    );
  });
  afterAll(() => rmSync(root, { recursive: true, force: true }));

  it('reports the unused import (exit 1 with fail-on warning)', () => {
    const r = runCli([root, '--fail-on', 'warning']);
    expect(r.status).toBe(1);
    expect(r.stdout).toContain('unused-import');
  });

  it('--fix removes the unused import and then exits 0', () => {
    const r = runCli([root, '--fix']);
    const after = readFileSync(join(root, 'app', 'main.ttrm'), 'utf-8');
    expect(after).not.toContain('import other.db.dbo.thing');
    // default fail-on is error; no errors remain → exit 0
    expect(r.status).toBe(0);
  });

  it('--explain prints the rule docs and exits 0', () => {
    const r = runCli(['--explain', 'unused-import', root]);
    expect(r.status).toBe(0);
    expect(r.stdout).toContain('unused-import');
    expect(r.stdout.toLowerCase()).toContain('import');
  });

  it('--format json emits a stable shape', () => {
    // Re-add an unused import so there is something to report.
    writeFileSync(
      join(root, 'app', 'main.ttrm'),
      `package app\nimport other.db.dbo.thing\nmodel db schema dbo\ndef table t { columns: [def column id { type: int }] }\n`
    );
    const r = runCli([root, '--format', 'json', '--fail-on', 'none']);
    expect(r.status).toBe(0);
    const parsed = JSON.parse(r.stdout) as Array<{ ruleId: string; code: string; range: unknown }>;
    expect(parsed.some((d) => d.ruleId === 'unused-import' && d.code === 'ttr/unused-import')).toBe(true);
    expect(parsed[0]).toHaveProperty('range');
  });
});

// CN-P2 S2.7 T4 — the cn rules through the CLI: `--rule` runs one cn rule, `--explain` names its
// category, and `.ttrlint.toml` `[categories] cn` switches the family (cn contracts §7).
describe('modeler-lint CLI — the cn category', () => {
  let root: string;
  const write = (rel: string, text: string) => {
    mkdirSync(join(root, rel, '..'), { recursive: true });
    writeFileSync(join(root, rel), text);
  };

  beforeAll(() => {
    root = mkdtempSync(join(tmpdir(), 'modeler-lint-cn-'));
    write('modeler.toml', `[project]\nname = "t"\n`);
    write(
      'hartland/stores/a-er.ttrm',
      `package hartland.stores\nmodel er\ndef entity shopper { attributes: [ def attribute shopper_id { type: int, isKey: true } ] }\n`,
    );
    write(
      'hartland/stores/b-cn.ttrm',
      `package hartland.stores\nmodel cn\ndef kind Shopper { grounds: er.entity.shopper }\ndef role Visitor { of: Shopper, grounds: er.entity.shopper }\n`,
    );
    write(
      'hartland/stores/c-binding.ttrm',
      `package hartland.stores\nmodel binding\ndef cn2er_kind shopper_again { kind: cn.Shopper, target: er.entity.shopper }\n`,
    );
  });
  afterAll(() => rmSync(root, { recursive: true, force: true }));

  const ruleIds = (stdout: string) => (JSON.parse(stdout) as Array<{ ruleId: string }>).map((d) => d.ruleId).sort();

  it('reports the cn errors (a second grounding, a role with no event)', () => {
    const r = runCli([root, '--format', 'json', '--fail-on', 'none']);
    expect(ruleIds(r.stdout)).toEqual(expect.arrayContaining(['cn-duplicate-grounding', 'cn-role-no-event']));
  });

  it('--rule runs one cn rule', () => {
    const r = runCli([root, '--format', 'json', '--fail-on', 'none', '--rule', 'cn-duplicate-grounding']);
    expect(ruleIds(r.stdout)).toEqual(['cn-duplicate-grounding']);
  });

  it('--explain prints a cn rule with its category and default', () => {
    const r = runCli(['--explain', 'cn-phase-as-default', root]);
    expect(r.status).toBe(0);
    expect(r.stdout).toContain('cn-phase-as-default [cn, default warning]');
  });

  it('[categories] cn = "off" in .ttrlint.toml silences the family', () => {
    write('.ttrlint.toml', `[categories]\ncn = "off"\n`);
    const r = runCli([root, '--format', 'json', '--fail-on', 'none']);
    expect(ruleIds(r.stdout).filter((id) => id.startsWith('cn-'))).toEqual([]);
    rmSync(join(root, '.ttrlint.toml'));
  });
});
