// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { parseString } from '../index.js';

// AU-core-P0 · S0.3 T3: every `ttrm` example on the Automation manual page parses with grammar 0.16 — the
// page cannot drift from the grammar it documents.
const page = fs.readFileSync(path.resolve(__dirname, '../../../../docs/manual/en/19-automation.md'), 'utf8');
const blocks = [...page.matchAll(/```ttrm\n([\s\S]*?)```/g)].map((m) => m[1]);

describe('docs/manual/en/19-automation.md', () => {
  it('has its examples', () => {
    expect(blocks.length).toBeGreaterThanOrEqual(6);
  });

  it.each(blocks.map((b, i) => [i, b] as const))('example %i parses with no error', (_i, block) => {
    const { errors } = parseString(block, '19-automation.ttrm');
    expect(errors).toEqual([]);
  });
});
