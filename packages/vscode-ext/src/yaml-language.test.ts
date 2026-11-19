// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { YamlLanguageSelector, type YamlLanguageDocument } from './yaml-language';

describe('YAML language selection', () => {
  function document(languageId = 'yaml'): YamlLanguageDocument {
    return {
      uri: { toString: () => 'file:///work/e.yaml', scheme: 'file' },
      languageId,
      isClosed: false,
      getText: () => 'ttr: "0.22"',
    };
  }
  it.each(['yaml', 'ttr-yaml'] as const)(
    'preserves a manual switch from the automatic %s selection across reopen and marker changes',
    async (language) => {
      const doc = document();
      const selector = new YamlLanguageSelector(
        async () => language,
        async (doc, selected) => {
          doc.languageId = selected;
          await selector.select(doc);
        }
      );
      await selector.select(doc);
      doc.languageId = language === 'yaml' ? 'ttr-yaml' : 'yaml';
      await selector.select(doc);
      await selector.select(doc, true);
      expect(doc.languageId).not.toBe(language);
    }
  );
  it('asks the server once, admits canonical headers, and reconsiders only automatic choices', async () => {
    let calls = 0,
      selected: 'yaml' | 'ttr-yaml' = 'ttr-yaml';
    const doc = document();
    const selector = new YamlLanguageSelector(
      async (_uri, text) => {
        calls++;
        expect(text).toBe('ttr: "0.22"');
        return selected;
      },
      async (doc, language) => {
        doc.languageId = language;
      }
    );
    await selector.select(doc);
    expect(doc.languageId).toBe('ttr-yaml');
    await selector.select(doc);
    expect(calls).toBe(1);
    selected = 'yaml';
    await selector.select(doc, true);
    expect(doc.languageId).toBe('yaml');
    expect(calls).toBe(2);
    const extension = readFileSync(require.resolve('./extension.ts'), 'utf8');
    expect(extension).not.toContain('onDidChangeTextDocument');
    expect(extension).toContain('modeler/yamlLanguage');
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
