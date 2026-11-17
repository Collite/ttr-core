// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseString, parseYamlDocument } from '@tatrman/parser';
import { dump } from './dump.js';
import { yamlTwins, type YamlTwin } from './yaml-fixtures.js';

const representatives = new Map<string, YamlTwin>();
for (const twin of yamlTwins()) {
  const text = readFileSync(twin.yaml, 'utf8');
  const model = text.match(/^model: ["']?(\w+)/m)?.[1] ?? 'none';
  if (!representatives.has(model)) representatives.set(model, twin);
}

describe('canonical YAML model families', () => {
  it('covers every model code and the empty envelope', () => {
    expect([...representatives.keys()].sort()).toEqual(['automation', 'binding', 'cn', 'cnc', 'db', 'er', 'lexicon', 'md', 'none', 'query', 'world']);
  });
  it.each([...representatives])('%s has the same TS dump as its native twin', (_model, twin) => {
    const yaml = parseYamlDocument(readFileSync(twin.yaml, 'utf8'), twin.yaml);
    expect(yaml.diagnostics.filter((d) => d.severity === 'error')).toEqual([]);
    const native = parseString(readFileSync(twin.native, 'utf8'), twin.native);
    expect(dump({ast: yaml.ast, errors: yaml.diagnostics, sourceFile: twin.yaml})).toBe(dump(native));
  });
});
