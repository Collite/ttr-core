// SPDX-License-Identifier: Apache-2.0
// Embed the grammar package's exact resources for Node and browser consumers.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
const root = new URL('../src/generated/', import.meta.url);
mkdirSync(root, { recursive: true });
let text = '// Generated from @tatrman/grammar schemas. Do not edit.\n';
for (const [name, file] of [
  ['canonicalSchema', 'model-yaml.schema.json'],
  ['canonicalMetadata', 'canonical-metadata.json'],
]) {
  const bytes = readFileSync(new URL(`../../grammar/schemas/${file}`, import.meta.url), 'utf8');
  text += `export const ${name}: unknown = JSON.parse(${JSON.stringify(bytes)});\n`;
}
writeFileSync(new URL('canonical-yaml.ts', root), text);
