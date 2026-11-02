// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { Worker } from 'node:worker_threads';
import { readFileSync } from 'node:fs';
import ts from 'typescript';
import { unifiedDiff } from '../text-diff.js';
import { referenceUnifiedDiff } from './text-diff-reference.js';

async function boundedWorker(before: string, after: string) {
  const code = ts.transpileModule(
    readFileSync(new URL('../text-diff.ts', import.meta.url), 'utf8'),
    {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }
  ).outputText;
  return new Promise<{ header: string; lines: number }>((resolve, reject) => {
    const worker = new Worker(
      `const {parentPort} = require('node:worker_threads');
      const exports = {}; ${code}
      const result = exports.unifiedDiff('large.yaml', ${before}, ${after});
      let lines = 1;
      for (let i = 0; i < result.length; i++) if (result[i] === '\\n') lines++;
      parentPort.postMessage({header: result.slice(0, 100).split('\\n')[2], lines});`,
      { eval: true, resourceLimits: { maxOldGenerationSizeMb: 256 } }
    );
    worker.once('message', resolve);
    worker.once('error', reject);
    worker.once('exit', (code) => {
      if (code !== 0) reject(new Error(`Worker exited ${code}`));
    });
  });
}

it('diffs 50,000 all-different lines within a 256 MB Worker', async () => {
  expect(
    await boundedWorker(
      "Array.from({length: 50000}, (_, i) => 'old ' + i).join('\\n')",
      "Array.from({length: 50000}, (_, i) => 'new ' + i).join('\\n')"
    )
  ).toEqual({ header: '@@ -1,50000 +1,50000 @@', lines: 100003 });
}, 30000);

it('diffs 2,000,000 lines against two within a 256 MB Worker and the LCS budget', async () => {
  expect(await boundedWorker("'old\\n'.repeat(1999999) + 'old'", "'new\\nnext'")).toEqual({
    header: '@@ -1,2000000 +1,2 @@',
    lines: 2000005,
  });
}, 30000);

it('trims huge unchanged regions before building a small diff', () => {
  const prefix = 'same\n'.repeat(50000);
  const result = unifiedDiff('small.yaml', prefix + 'old\nend', prefix + 'new\nend');
  expect(result).toBe(
    '--- a/small.yaml\n+++ b/small.yaml\n@@ -49998,5 +49998,5 @@\n same\n same\n same\n-old\n+new\n end'
  );
});

it('keeps a 2,100-line first-line edit identical to base instead of replacing the whole file', () => {
  const suffix = Array.from({ length: 2099 }, (_, i) => `line ${i}`).join('\n');
  const before = `old\n${suffix}`;
  const after = `new\n${suffix}`;
  const result = unifiedDiff('first', before, after);
  expect(result).toBe(referenceUnifiedDiff('first', before, after));
  expect(result.split('\n')).toHaveLength(8);
});

it('keeps correct suffix context line numbers when tier 2 changes the middle length', () => {
  const suffix = Array.from({ length: 2100 }, (_, i) => `c${i}`).join('\n');
  expect(unifiedDiff('suffix', `head\nold\n${suffix}`, `head\nnew\nextra\n${suffix}`)).toBe(
    '--- a/suffix\n+++ b/suffix\n@@ -1,5 +1,6 @@\n head\n-old\n+new\n+extra\n c0\n c1\n c2'
  );
});

it('bounds the tier-3 fallback to the changed middle, excluding a shared prefix and suffix', () => {
  const before = ['head', ...Array.from({ length: 2100 }, (_, i) => `old ${i}`), 'tail'];
  const after = ['head', ...Array.from({ length: 2100 }, (_, i) => `new ${i}`), 'tail'];
  const result = unifiedDiff('middle', before.join('\n'), after.join('\n'));
  expect(result.split('\n')[2]).toBe('@@ -2,2100 +2,2100 @@');
  expect(result.split('\n')).toHaveLength(4203);
  expect(result).not.toMatch(/[+-](?:head|tail)/);
});

it('keeps base LCS tie-breaking with a long common suffix', () => {
  const suffix = Array.from({ length: 2000 }, (_, i) => `c${i}`);
  const before = ['p', 's', ...suffix].join('\n');
  const after = ['s', 's', ...suffix].join('\n');
  expect(unifiedDiff('ties', before, after)).toBe(referenceUnifiedDiff('ties', before, after));
  expect(unifiedDiff('ties', before, after)).toContain('@@ -1,5 +1,5 @@\n-p\n s\n+s\n c0');
});

it('uses prefix-only tier 1 before suffix trimming even when the whole file exceeds the budget', () => {
  const prefix = Array.from({ length: 300 }, (_, i) => `h${i}`);
  const suffix = Array.from({ length: 2000 }, (_, i) => `c${i}`);
  const before = [...prefix, 'p', 's', ...suffix].join('\n');
  const after = [...prefix, 's', 's', ...suffix].join('\n');
  expect(unifiedDiff('prefixed-ties', before, after)).toBe(
    referenceUnifiedDiff('prefixed-ties', before, after)
  );
  expect(unifiedDiff('prefixed-ties', before, after)).toContain('@@ -298,8 +298,8 @@');
});

it('matches the base diff for 50 seeded small-alphabet edits within the tier-1 budget', () => {
  let seed = 207;
  const next = () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0);
  const lines = Array.from({ length: 2500 }, (_, i) => `line ${i % 4}`);
  const before = lines.join('\n');
  for (let run = 0; run < 50; run++) {
    const after = [...lines];
    // At least 550 common leading lines keep every case in prefix-only tier 1.
    for (let edit = 0; edit < 5; edit++) {
      const position = 550 + (next() % (after.length - 550));
      after.splice(position, next() % 3, `line ${next() % 4}`);
    }
    expect(unifiedDiff('random', before, after.join('\n'))).toBe(
      referenceUnifiedDiff('random', before, after.join('\n'))
    );
  }
}, 30000);
