#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deriveMetadata } from './canonical-metadata.mjs';
import { createSchemas } from './canonical-schemas.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
export function generatedFiles(source) {
  const metadata = deriveMetadata(source);
  const { model, profile, handled } = createSchemas(metadata);
  const bytes = (value) => JSON.stringify(value, null, 2) + '\n';
  return {
    'model-yaml.schema.json': bytes(model),
    'profile.schema.json': bytes(profile),
    'canonical-metadata.json': bytes({ ...metadata, handledValueRules: handled }),
  };
}
export function generateCanonicalSchemas({
  outputDirectory = resolve(directory, '../schemas'),
  check = false,
  grammarFile = resolve(directory, '../src/TTR.g4'),
} = {}) {
  const files = generatedFiles(readFileSync(grammarFile, 'utf8'));
  if (!check) mkdirSync(outputDirectory, { recursive: true });
  for (const [name, bytes] of Object.entries(files)) {
    const path = resolve(outputDirectory, name);
    if (check) {
      let existing;
      try {
        existing = readFileSync(path, 'utf8');
      } catch {
        /* A missing output is drift too. */
      }
      if (existing !== bytes)
        throw new Error(
          `Stale generated schema: ${path}; run pnpm --filter @tatrman/grammar prebuild`
        );
    } else writeFileSync(path, bytes);
  }
  return files;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  let outputDirectory;
  let check = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--check') check = true;
    else if (args[i] === '--output-dir' && args[i + 1] && !args[i + 1].startsWith('--'))
      outputDirectory = resolve(args[++i]);
    else throw new Error(`Unknown/incomplete schema generator argument: ${args[i]}`);
  }
  const files = generateCanonicalSchemas({
    check,
    ...(outputDirectory ? { outputDirectory } : {}),
  });
  console.log(
    `${args.includes('--check') ? 'Verified' : 'Generated'} ${Object.keys(files).join(', ')}`
  );
}
