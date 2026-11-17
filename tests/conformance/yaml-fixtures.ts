// SPDX-License-Identifier: Apache-2.0
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseString, parseYamlDocument } from '@tatrman/parser';
import { dump } from './dump.js';
import { checkYamlLocations } from './yaml-locations.js';

export const fixtureRoot = fileURLToPath(new URL('./fixtures/', import.meta.url));
export interface YamlTwin {
  name: string;
  native: string;
  yaml: string;
}
export function yamlTwins(root = fixtureRoot): YamlTwin[] {
  const native: string[] = [];
  const yaml: string[] = [];
  function walk(dir: string): void {
    for (const item of readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
      a.name.localeCompare(b.name)
    )) {
      const file = path.join(dir, item.name);
      if (item.isDirectory()) {
        if (!item.name.endsWith('-negative')) walk(file);
      } else if (/\.ttr[mg]$/.test(file)) native.push(file);
      else if (file.endsWith('.yaml')) yaml.push(file);
    }
  }
  walk(root);
  if (!native.length) throw new Error(`Empty conformance roster: ${root}`);
  const expected = native.map((file) => file.replace(/\.ttr[mg]$/, '.yaml'));
  if (new Set(expected).size !== expected.length) throw new Error('Colliding YAML twin stems');
  for (const file of expected) if (!existsSync(file)) throw new Error(`Missing YAML twin: ${file}`);
  for (const file of yaml)
    if (!expected.includes(file)) throw new Error(`Orphan YAML twin: ${file}`);
  return native.map((file, i) => ({
    name: path.relative(root, file),
    native: file,
    yaml: expected[i],
  }));
}

export function twinDumps(twin: YamlTwin): { native: string; yaml: string } {
  const native = parseString(readFileSync(twin.native, 'utf8'), twin.native);
  const yamlText = readFileSync(twin.yaml, 'utf8');
  const yaml = parseYamlDocument(yamlText, twin.yaml);
  for (const [file, diagnostics] of [
    [twin.native, native.errors],
    [twin.yaml, yaml.diagnostics],
  ] as const) {
    const fatal = diagnostics.filter((d) => d.severity === 'error');
    if (fatal.length || (file === twin.native ? !native.ast : !yaml.ast))
      throw new Error(`${file}: ${JSON.stringify(fatal)}`);
  }
  checkYamlLocations(yamlText, twin.yaml, yaml.ast);
  return {
    native: dump(native),
    yaml: dump({ ast: yaml.ast, errors: yaml.diagnostics, sourceFile: twin.yaml }),
  };
}
