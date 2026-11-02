// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { Worker } from 'node:worker_threads';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { unifiedDiff } from '../text-diff.js';
import { referenceUnifiedDiff } from './text-diff-reference.js';

it('diffs 50,000 all-different lines within a 256 MB Worker', async () => {
  const code = ts.transpileModule(
    readFileSync(new URL('../text-diff.ts', import.meta.url), 'utf8'),
    {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }
  ).outputText;
  const result = await new Promise<string>((resolve, reject) => {
    const worker = new Worker(
      `const {parentPort} = require('node:worker_threads');
      const exports = {}; ${code}
      const a = Array.from({length: 50000}, (_, i) => 'old ' + i).join('\\n');
      const b = Array.from({length: 50000}, (_, i) => 'new ' + i).join('\\n');
      parentPort.postMessage(exports.unifiedDiff('large.yaml', a, b));`,
      {
        eval: true,
        resourceLimits: { maxOldGenerationSizeMb: 256 },
      }
    );
    worker.once('message', resolve);
    worker.once('error', reject);
    worker.once('exit', (code) => {
      if (code !== 0) reject(new Error(`Worker exited ${code}`));
    });
  });
  expect(result.split('\n')[2]).toBe('@@ -1,50000 +1,50000 @@');
  expect(result.split('\n')).toHaveLength(100003);
}, 30000);

it('trims huge unchanged regions before building a small diff', () => {
  const prefix = 'same\n'.repeat(50000);
  const result = unifiedDiff('small.yaml', prefix + 'old\nend', prefix + 'new\nend');
  expect(result).toBe(
    '--- a/small.yaml\n+++ b/small.yaml\n@@ -49998,5 +49998,5 @@\n same\n same\n same\n-old\n+new\n end'
  );
});

it('keeps base LCS tie-breaking with a long common suffix', () => {
  const suffix = Array.from({ length: 2000 }, (_, i) => `c${i}`);
  const before = ['p', 's', ...suffix].join('\n');
  const after = ['s', 's', ...suffix].join('\n');
  expect(unifiedDiff('ties', before, after)).toBe(referenceUnifiedDiff('ties', before, after));
  expect(unifiedDiff('ties', before, after)).toContain('@@ -1,5 +1,5 @@\n-p\n s\n+s\n c0');
});

it('matches the base diff for 200 seeded edits on 2,500-line texts within the remaining budget', () => {
  let seed = 207;
  const next = () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0);
  const lines = Array.from({ length: 2500 }, (_, i) => `line ${i % 11}`);
  const before = lines.join('\n');
  for (let run = 0; run < 200; run++) {
    const after = [...lines];
    // At least 550 common leading lines put the remaining table below 4M cells.
    for (let edit = 0; edit < 5; edit++) {
      const position = 550 + (next() % (after.length - 550));
      after.splice(position, next() % 3, `edit ${next() % 11}`);
    }
    expect(unifiedDiff('random', before, after.join('\n'))).toBe(
      referenceUnifiedDiff('random', before, after.join('\n'))
    );
  }
}, 30000);
