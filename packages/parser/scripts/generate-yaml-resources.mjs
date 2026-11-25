// SPDX-License-Identifier: Apache-2.0
// Embed the grammar package's exact resources for Node and browser consumers.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { standaloneValidators } from '../../../scripts/standalone-validators.mjs';
const root = new URL('../src/generated/', import.meta.url);
mkdirSync(root, { recursive: true });
let text = '// Generated from @tatrman/grammar schemas. Do not edit.\n';
for (const [name, file] of [
  ['canonicalSchema', 'model-yaml.schema.json'],
  ['canonicalMetadata', 'canonical-metadata.json'],
]) {
  const bytes = readFileSync(new URL(`../../grammar/schemas/${file}`, import.meta.url), 'utf8');
  text += `export const ${name}: unknown = /* @__PURE__ */ JSON.parse(${JSON.stringify(bytes)});\n`;
}
writeFileSync(new URL('canonical-yaml.ts', root), text);

const canonicalSchema = JSON.parse(
  readFileSync(new URL('../../grammar/schemas/model-yaml.schema.json', import.meta.url), 'utf8')
);
writeFileSync(
  new URL('validate-canonical-yaml.ts', root),
  standaloneValidators(
    import.meta.url,
    { validateCanonicalYaml: canonicalSchema },
    {
      allErrors: true,
      strict: false,
      ownProperties: true,
    }
  )
);
