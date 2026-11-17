// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { parseString, parseYamlDocument } from '@tatrman/parser';
import { dump } from './dump.js';

const root = fileURLToPath(new URL('./fixtures/', import.meta.url));
const files = readdirSync(root).filter((name) => name.endsWith('.yaml'));
const representatives = new Map<string, string>();
for (const file of files) {
  const text = readFileSync(path.join(root, file), 'utf8');
  const model = text.match(/^model: (.+)$/m)?.[1] ?? 'none';
  if (!representatives.has(model)) representatives.set(model, file);
}

describe('canonical YAML model families', () => {
  it('covers every model code and the empty envelope', () => {
    expect([...representatives.keys()].sort()).toEqual(['automation', 'binding', 'cn', 'cnc', 'db', 'er', 'lexicon', 'md', 'none', 'world']);
  });
  it.each([...representatives])('%s has the same TS dump as its native twin', (_model, file) => {
    const yaml = parseYamlDocument(readFileSync(path.join(root, file), 'utf8'), file);
    expect(yaml.diagnostics.filter((d) => d.severity === 'error')).toEqual([]);
    const nativeFile = file.replace(/\.yaml$/, '.ttrm');
    const native = parseString(readFileSync(path.join(root, nativeFile), 'utf8'), nativeFile);
    expect(dump({ast: yaml.ast, errors: yaml.diagnostics, sourceFile: file})).toBe(dump(native));
  });
});
