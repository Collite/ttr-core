// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { twinDumps, yamlTwins } from './yaml-fixtures.js';
import { parseString, parseYamlDocument } from '@tatrman/parser';
import { checkYamlLocations } from './yaml-locations.js';

const twins = yamlTwins();
describe(`TS canonical YAML conformance (${twins.length} twins)`, () => {
  it.each(twins)('$name dumps identically from native and YAML', (twin) => {
    const result = twinDumps(twin);
    expect(result.yaml).toBe(result.native);
  });
  it('keeps code-point name locations in both spellings after an emoji label', () => {
    const native =
      'model er\ndef entity E {description: "😀😀", attributes: [def attribute name {type: text}]}';
    const yaml =
      'ttr: "0.22"\nmodel: er\ndefs: [{kind: entity, id: E, properties: {description: "😀😀", attributes: [{id: name, properties: {type: text}}]}}]';
    const parsed = parseYamlDocument(yaml, 'emoji.yaml');
    checkYamlLocations(yaml, 'emoji.yaml', parsed.ast);
    for (const [text, ast] of [
      [native, parseString(native).ast],
      [yaml, parsed.ast],
    ] as const) {
      const entity = ast!.definitions[0];
      if (entity.kind !== 'entity') throw new Error('entity expected');
      const source = entity.attributes![0].source;
      const slice = [...text].slice(source.offsetStart, source.offsetEnd).join('');
      expect(slice).toContain('name');
      const lines = [...text].slice(0, source.offsetStart).join('').split('\n');
      expect([source.line, source.column]).toEqual([lines.length, [...lines.at(-1)!].length]);
    }
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
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
});
