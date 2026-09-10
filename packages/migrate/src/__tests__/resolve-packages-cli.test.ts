// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
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

const entityFile = (pkg: string, e: string) =>
  `package ${pkg}\nmodel er schema entity\ndef entity ${e} { attributes: [def attribute id { type: int }] }\n`;

describe('modeler resolve-packages CLI', () => {
  let root: string;

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'modeler-rp-cli-'));
    writeFileSync(join(root, 'modeler.toml'), '[project]\nname = "t"\n');
    mkdirSync(join(root, 'a'), { recursive: true });
    writeFileSync(join(root, 'a', 'er.ttrm'), entityFile('a', 'artikl'));
  });
  afterEach(() => rmSync(root, { recursive: true, force: true }));

  it('--out writes a valid artifact and exits 0', () => {
    const out = join(root, 'resolved-packages.json');
    const r = runCli(['resolve-packages', root, '--out', out]);
    expect(r.status).toBe(0);
    const artifact = JSON.parse(readFileSync(out, 'utf-8'));
    expect(artifact.formatVersion).toBe(1);
    expect(artifact.packages.map((p: { canonicalName: string }) => p.canonicalName)).toContain('a');
  });

  it('default output path is <root>/.modeler/resolved-packages.json', () => {
    const r = runCli(['resolve-packages', root]);
    expect(r.status).toBe(0);
    expect(existsSync(join(root, '.modeler', 'resolved-packages.json'))).toBe(true);
  });

  it('exits 2 on an IO error (unwritable --out path)', () => {
    // A regular file masquerading as a parent directory → mkdir/writeFile throws.
    const blocker = join(root, 'blocker');
    writeFileSync(blocker, 'x');
    const r = runCli(['resolve-packages', root, '--out', join(blocker, 'sub', 'rp.json')]);
    expect(r.status).toBe(2);
  });

  it('--check exits 0 when the on-disk artifact is in sync', () => {
    const out = join(root, 'resolved-packages.json');
    expect(runCli(['resolve-packages', root, '--out', out]).status).toBe(0);
    expect(runCli(['resolve-packages', root, '--out', out, '--check']).status).toBe(0);
  });

  it('--check exits non-zero when the model has drifted from the snapshot', () => {
    const out = join(root, 'resolved-packages.json');
    runCli(['resolve-packages', root, '--out', out]);
    // Add a new package/entity → the snapshot is now stale.
    mkdirSync(join(root, 'a', 'b'), { recursive: true });
    writeFileSync(join(root, 'a', 'b', 'er.ttrm'), entityFile('a.b', 'sub'));
    const r = runCli(['resolve-packages', root, '--out', out, '--check']);
    expect(r.status).not.toBe(0);
    // Regenerating brings it back in sync.
    runCli(['resolve-packages', root, '--out', out]);
    expect(runCli(['resolve-packages', root, '--out', out, '--check']).status).toBe(0);
  });

  it('the SKIPPED line names the file that does not parse — and not one that only carries a warning', () => {
    // review-089 ⒄: a well-formed query keeping the soft-deprecated `language: SQL` beside a tagged
    // """sql block used to be reported as "SKIPPED … it does not parse", which was false.
    mkdirSync(join(root, 'q'), { recursive: true });
    writeFileSync(
      join(root, 'q', 'q.ttrm'),
      'package q\nmodel query\ndef query x {\n  language: SQL\n  sourceText: """sql\nSELECT 1\n"""\n}\n'
    );
    mkdirSync(join(root, 'bad'), { recursive: true });
    writeFileSync(join(root, 'bad', 'broken.ttrm'), 'package bad\nmodel book schema entity\ndef entity ghost { }\n');
    const out = join(root, 'resolved-packages.json');
    // execFileSync drops stderr on success; spawnSync keeps both streams.
    const r = spawnSync('node', [CLI, 'resolve-packages', root, '--out', out], { encoding: 'utf-8' });
    expect(r.status).toBe(0);
    const skippedLines = r.stderr.split('\n').filter((l) => l.includes('SKIPPED'));
    expect(skippedLines).toHaveLength(1);
    expect(skippedLines[0]).toContain(join(root, 'bad', 'broken.ttrm'));
    expect(r.stderr).not.toContain(join(root, 'q', 'q.ttrm'));
    const artifact = JSON.parse(readFileSync(out, 'utf-8'));
    expect(artifact.packages.map((p: { canonicalName: string }) => p.canonicalName)).toEqual(['a', 'q']);
  });

  it('--check exits non-zero when no artifact exists yet', () => {
    const out = join(root, 'missing.json');
    const r = runCli(['resolve-packages', root, '--out', out, '--check']);
    expect(r.status).not.toBe(0);
  });
});
