// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { runYamlMigration } from '../yaml-migrate.js';

// File policy tests use a protocol peer; the real Kotlin bridge is tested in canonical-yaml CI.
describe('canonical YAML migration file policy', () => {
  let root: string;
  let bridge: string;
  let file: string;
  const before = "ttr: '0.21'\nmodel: er\n";
  const after = 'ttr: "0.21"\nmodel: "er"\n';
  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'yaml migrate '));
    bridge = join(root, 'bridge with spaces');
    file = join(root, 'model.yaml');
    writeFileSync(file, before);
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
const fs = require('node:fs');
const r = JSON.parse(fs.readFileSync(0, 'utf8'));
if (r.operation !== 'migrate' || r.from !== '0.21' || r.to !== '0.21' || !r.source.startsWith('ttr:')) {
  process.stderr.write('<yaml>:1:1: refused marker or version'); process.exit(2);
}
const source = ${JSON.stringify(after)};
process.stdout.write(JSON.stringify({ source, changed: source !== r.source }));
`
    );
    chmodSync(bridge, 0o755);
  });
  afterEach(() => rmSync(root, { recursive: true, force: true }));
  const options = () => ({ from: '0.21', to: '0.21', bridge });

  it('dry-run validates via bridge and returns a diff without writes or metadata changes', async () => {
    const stat = statSync(file);
    const result = await runYamlMigration([file], { ...options(), dryRun: true });
    expect(result.writes).toHaveLength(1);
    expect(result.writes[0].diff).toContain('+ttr: "0.21"');
    expect(readFileSync(file, 'utf8')).toBe(before);
    expect(statSync(file).mtimeMs).toBe(stat.mtimeMs);
    expect(readdirSync(root).sort()).toEqual(['bridge with spaces', 'model.yaml']);
  });
  it('writes only changed files, preserves permissions and is idempotent', async () => {
    chmodSync(file, 0o640);
    expect((await runYamlMigration([file], options())).writes).toHaveLength(1);
    expect(readFileSync(file, 'utf8')).toBe(after);
    expect(statSync(file).mode & 0o777).toBe(0o640);
    const stat = statSync(file);
    expect((await runYamlMigration([file], options())).writes).toEqual([]);
    expect(statSync(file).mtimeMs).toBe(stat.mtimeMs);
  });
  it('refuses the entire batch before writes when any marker or version fails', async () => {
    const other = join(root, 'foreign.yaml');
    writeFileSync(other, 'entities: []\n');
    await expect(runYamlMigration([file, other], options())).rejects.toThrow('<yaml>:1:1: refused');
    expect(readFileSync(file, 'utf8')).toBe(before);
    expect(readFileSync(other, 'utf8')).toBe('entities: []\n');
    await expect(runYamlMigration([file], { ...options(), to: '0.22' })).rejects.toThrow('version');
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  for (const stdout of [
    'broken',
    '{}',
    '[]',
    'null',
    '{"source":1,"changed":true}',
    '{"source":"x","changed":false}',
    '{"source":"x","changed":true,"extra":1}',
  ]) {
    it(`refuses invalid bridge output ${stdout} before writes`, async () => {
      writeFileSync(
        bridge,
        `#!/usr/bin/env node\nprocess.stdout.write(${JSON.stringify(stdout)});\n`
      );
      await expect(runYamlMigration([file], options())).rejects.toThrow();
      expect(readFileSync(file, 'utf8')).toBe(before);
    });
  }
  it('preserves a concurrent edit and removes staged temporary files', async () => {
    writeFileSync(
      bridge,
      `#!/usr/bin/env node
const fs = require('node:fs');
fs.writeFileSync(${JSON.stringify(file)}, 'external edit');
process.stdout.write(JSON.stringify({ source: ${JSON.stringify(after)}, changed: true }));
`
    );
    await expect(runYamlMigration([file], options())).rejects.toThrow('input changed');
    expect(readFileSync(file, 'utf8')).toBe('external edit');
    expect(readdirSync(root).sort()).toEqual(['bridge with spaces', 'model.yaml']);
  });
  it('requires explicit input files and a bridge', async () => {
    await expect(runYamlMigration([], options())).rejects.toThrow('At least one');
    await expect(runYamlMigration([file], { ...options(), bridge: '' })).rejects.toThrow(
      'bridge executable'
    );
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
  it('refuses missing executable, duplicate paths, symlinks and invalid UTF-8 without writes', async () => {
    await expect(
      runYamlMigration([file], { ...options(), bridge: join(root, 'missing') })
    ).rejects.toThrow();
    await expect(runYamlMigration([file, file], options())).rejects.toThrow('Duplicate');
    const link = join(root, 'link.yaml');
    symlinkSync(file, link);
    await expect(runYamlMigration([link], options())).rejects.toThrow('regular file');
    const bad = join(root, 'bad.yaml');
    writeFileSync(bad, Buffer.from([0xc3, 0x28]));
    await expect(runYamlMigration([file, bad], options())).rejects.toThrow();
    expect(readFileSync(file, 'utf8')).toBe(before);
  });
});
