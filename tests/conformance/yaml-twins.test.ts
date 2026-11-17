// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { twinDumps, yamlTwins } from './yaml-fixtures.js';

const twins = yamlTwins();
describe(`TS canonical YAML conformance (${twins.length} twins)`, () => {
  it.each(twins)('$name dumps identically from native and YAML', (twin) => {
    const result = twinDumps(twin);
    expect(result.yaml).toBe(result.native);
  });
  it('refuses empty, missing, orphan and colliding fixture rosters; discovers directory fixtures', () => {
    const root = mkdtempSync(path.join(tmpdir(), 'yaml-roster-'));
    try {
      expect(() => yamlTwins(root)).toThrow('Empty');
      mkdirSync(path.join(root, 'nested'));
      const stem = path.join(root, 'nested', 'a');
      writeFileSync(stem + '.ttrm', '');
      expect(() => yamlTwins(root)).toThrow('Missing');
      writeFileSync(stem + '.yaml', '');
      expect(yamlTwins(root).map((t) => t.name)).toEqual(['nested/a.ttrm']);
      writeFileSync(stem + 'orphan.yaml', '');
      expect(() => yamlTwins(root)).toThrow('Orphan');
      rmSync(stem + 'orphan.yaml');
      writeFileSync(stem + '.ttrg', '');
      expect(() => yamlTwins(root)).toThrow('Colliding');
    } finally { rmSync(root, {recursive: true, force: true}); }
  });
});
