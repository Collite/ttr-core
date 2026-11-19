// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { isTtrYaml } from './yaml-language';

describe('YAML language selection', () => {
  const marker = 'profile: ./ttr-profile.yaml\ngrammar: ">=0.22 <1.0"\n';
  it('opts in only below the nearest profile marker, including nested markers', async () => {
    const read = async (file: string) =>
      file === '/work/model/ttr.yaml'
        ? marker
        : file === '/work/model/plain/ttr.yaml'
          ? 'other: yaml'
          : undefined;
    expect(await isTtrYaml('/work/model/e.yaml', 'entity: E', '/work', read)).toBe(true);
    expect(await isTtrYaml('/work/notes.yaml', 'entity: E', '/work', read)).toBe(false);
    expect(await isTtrYaml('/work/model/plain/e.yaml', 'entity: E', '/work', read)).toBe(false);
    expect(await isTtrYaml('/work/model/ttr-profile.yaml', marker, '/work', read)).toBe(false);
  });
  it('recognizes a per-file header and never a plain canonical header or a non-YAML file', async () => {
    const read = async () => undefined;
    for (const header of [
      'ttr: {profile: ./p.yaml, grammar: ">=0.22 <1.0"}',
      'ttr:\n  profile: ./p.yaml\n  grammar: ">=0.22 <1.0"',
    ])
      expect(await isTtrYaml('/work/e.yml', header + '\nentity: E', '/work', read)).toBe(true);
    expect(await isTtrYaml('/work/e.yml', 'ttr: 0.22', '/work', read)).toBe(false);
    expect(await isTtrYaml('/work/e.txt', marker, '/work', read)).toBe(false);
  });
  it('contributes a marker-activated language without global YAML associations and reuses the YAML grammar', () => {
    const pkg = JSON.parse(readFileSync(require.resolve('../package.json'), 'utf8'));
    expect(pkg.activationEvents).toContain('workspaceContains:**/ttr.yaml');
    expect(
      pkg.contributes.languages.find((l: { id: string }) => l.id === 'ttr-yaml').extensions
    ).toBeUndefined();
    const grammar = JSON.parse(
      readFileSync(require.resolve('../syntaxes/ttr-yaml.tmLanguage.json'), 'utf8')
    );
    expect(grammar.patterns).toEqual([{ include: 'source.yaml' }]);
  });
});
