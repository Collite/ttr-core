// SPDX-License-Identifier: Apache-2.0
// One resource vocabulary, copied from the grammar build and the generated CLI catalogue.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
const output = new URL('../src/generated/resources.ts', import.meta.url);
const resources = [
  ['profileSchema', '../../grammar/schemas/profile.schema.json'],
  ['canonicalSchema', '../../grammar/schemas/model-yaml.schema.json'],
  ['canonicalMetadata', '../../grammar/schemas/canonical-metadata.json'],
  [
    'diagnosticCodes',
    '../../kotlin/ttr-metadata-cli/src/main/resources/org/tatrman/ttr/metadata/cli/diagnostic-codes.json',
  ],
];
let text = '// Generated from grammar schemas and the diagnostic catalogue. Do not edit.\n';
for (const [name, file] of resources) {
  const bytes = readFileSync(new URL(file, import.meta.url), 'utf8');
  text += `export const ${name}: unknown = JSON.parse(${JSON.stringify(bytes)});\n`;
}
text += `export const canonicalProfileText = ${JSON.stringify(readFileSync(new URL('../../kotlin/ttr-profile/src/main/resources/profiles/canonical.yaml', import.meta.url), 'utf8'))};\n`;
const metadata = JSON.parse(readFileSync(new URL(resources[2][1], import.meta.url), 'utf8'));
text += `export const runningGrammar = ${JSON.stringify(metadata.version)};\n`;
if (process.argv.includes('--check')) {
  if (readFileSync(output, 'utf8') !== text)
    throw new Error('Profile resources are stale; run prebuild');
} else {
  mkdirSync(new URL('../src/generated/', import.meta.url), { recursive: true });
  writeFileSync(output, text);
}
