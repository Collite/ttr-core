// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { lintProj, recommendedConfig } from './helpers.js';
const ROOT = join(__dirname, '../../../kotlin/ttr-metadata/src/test/resources/md-hop');
const files = () => readdirSync(ROOT).filter((f) => f.endsWith('.ttrm')).map((f) => ({ uri: `file:///${f}`, src: readFileSync(join(ROOT, f), 'utf8') }));
const hopCodes = new Set(['md/er-unmapped', 'md/er-ambiguous', 'md/er-hop-override', 'md/er-lineage-opaque', 'md/role-claim-mismatch']);
describe('MD hop lint findings', () => {
  it('uses the quality bearer and scopes defaults to grounded elements', () => {
    expect([...lintProj(files()).values()].flat().filter((d) => hopCodes.has(d.code))).toEqual([]);
  });
  it('reports a grounded dimension conflict and respects warning/info/off', () => {
    const project = files().concat({ uri: 'file:///conflict.ttrm', src: readFileSync(join(ROOT, 'conflicting.ttrm.variant'), 'utf8') });
    for (const severity of ['warning', 'info', 'off'] as const) {
      const ds = [...lintProj(project, { config: recommendedConfig({ 'md-er-ambiguous': severity }) }).values()].flat().filter((d) => d.code === 'md/er-ambiguous');
      expect(ds.length).toBe(severity === 'off' ? 0 : 1);
      if (ds.length) expect(ds[0].severity).toBe(severity);
    }
  });
  it('reports explicit override as information, with the dimension source', () => {
    const project = files().concat({ uri: 'file:///override.ttrm', src: readFileSync(join(ROOT, 'override.ttrm.variant'), 'utf8') });
    const ds = [...lintProj(project).values()].flat().filter((d) => d.code === 'md/er-hop-override');
    expect(ds).toHaveLength(1);
    expect(ds[0].severity).toBe('info');
    expect(ds[0].source.file).toBe('file:///md.ttrm');
  });
});
