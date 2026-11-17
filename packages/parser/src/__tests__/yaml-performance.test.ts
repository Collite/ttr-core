// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { parseYamlDocument } from '../index.js';

it('R2 parses a 1600 × 10 model within 3 seconds', () => {
  const text =
    'ttr: "0.22"\nmodel: er\ndefs:\n' +
    Array.from(
      { length: 1600 },
      (_, i) =>
        `- kind: entity\n  id: Entity${i}\n  properties:\n    attributes:\n` +
        Array.from(
          { length: 10 },
          (_, j) => `    - id: attribute${j}\n      properties: {type: text}\n`
        ).join('')
    ).join('');
  parseYamlDocument('ttr: "0.22"'); // Exclude the one-time schema compilation.
  const start = performance.now();
  const result = parseYamlDocument(text);
  const elapsed = performance.now() - start;
  expect(result.diagnostics).toEqual([]);
  expect(result.ast!.definitions).toHaveLength(1600);
  expect(elapsed).toBeLessThan(3000);
  console.info(`YAML size guard: ${text.length} bytes, 1600 × 10, ${Math.round(elapsed)} ms`);
}, 10000);
