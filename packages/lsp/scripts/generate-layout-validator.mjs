// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, writeFileSync } from 'node:fs';
import { standaloneValidators } from '../../../scripts/standalone-validators.mjs';
import layoutSchema from './layout-schema.mjs';

const generated = new URL('../src/generated/', import.meta.url);
mkdirSync(generated, { recursive: true });
writeFileSync(
  new URL('validate-layout.ts', generated),
  standaloneValidators(import.meta.url, { validateLayoutSchema: layoutSchema }, { strict: false })
);
