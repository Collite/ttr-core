// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
// Importing the release list makes this a closure check rather than a second package list.
// @ts-expect-error Plain JavaScript release tooling.
import { family } from './designer-family.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const pkg = (name: string) =>
  JSON.parse(readFileSync(`${root}/packages/${name}/package.json`, 'utf8'));
describe('Designer release family', () => {
  it('closes all published runtime dependencies and preserves the React peer boundary', () => {
    const packages = family.map(pkg),
      names = new Set(packages.map((p: { name: string }) => p.name));
    for (const p of packages) {
      expect(p.private, p.name).toBe(false);
      expect(p.license).toBe('Apache-2.0');
      expect(p.version).toBe(pkg('designer').version);
      expect(p.publishConfig.registry).toBe('https://registry.npmjs.org');
      expect(p.files).toContain('LICENSE');
      expect(p.files).toContain('NOTICE');
      const dependencies = p.publishConfig.dependencies ?? p.dependencies ?? {};
      for (const name of Object.keys(dependencies).filter((n) => n.startsWith('@tatrman/')))
        expect(names.has(name), `${p.name} -> ${name}`).toBe(true);
      expect(p.repository.url).toBe('git+https://github.com/Collite/ttr-core.git');
    }
    const designer = pkg('designer');
    for (const name of ['react', 'react-dom']) {
      expect(designer.peerDependencies[name]).toBe('^19.0.0');
      expect(designer.dependencies[name]).toBeUndefined();
    }
    // canvas-core is pure TypeScript and deliberately has no React dependency or peer.
    expect(pkg('canvas-core').dependencies.react).toBeUndefined();
    expect(designer.dependencies['@tatrman/perspectives']).toBe('workspace:*');
    expect(designer.dependencies['@tatrman/deep-links']).toBe('workspace:*');
  });
});
