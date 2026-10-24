// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest';
// @ts-expect-error JavaScript release tooling.
import { publishMissingArtifacts } from './designer-publish.mjs';
const artifacts = [
  { name: '@tatrman/edit', version: '0.2.0', file: 'edit.tgz' },
  { name: '@tatrman/parser', version: '0.2.0', file: 'parser.tgz' },
];
it('resumes a partial release and reuses any published coordinate, including later edit versions', () => {
  const run = vi.fn((_: string, args: string[]) => {
    if (args[0] === 'publish') return '';
    if (args[1] === '@tatrman/edit@0.2.0') return '"0.2.0"';
    throw { stdout: JSON.stringify({ error: { code: 'E404' } }) };
  });
  publishMissingArtifacts(artifacts, '/artifacts', run);
  expect(run.mock.calls.filter((c) => c[1][0] === 'publish')).toEqual([
    [
      'npm',
      [
        'publish',
        '/artifacts/parser.tgz',
        '--access',
        'public',
        '--registry=https://registry.npmjs.org',
      ],
      { stdio: 'inherit' },
    ],
  ]);
});
it('does not publish any package after a registry/auth failure during preflight', () => {
  const run = vi.fn((_: string, args: string[]) => {
    throw {
      stdout: JSON.stringify({ error: { code: args[1].includes('edit') ? 'E404' : 'E401' } }),
    };
  });
  expect(() => publishMissingArtifacts(artifacts, '/artifacts', run)).toThrow();
  expect(run.mock.calls.some((c) => c[1][0] === 'publish')).toBe(false);
});
