// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';

// AG B6: the extension recognises Czech TTR-B (`.ttrb-cs`) exactly like English TTR-B (`.ttrb`) —
// a language id, the file association, the same language configuration / highlighting, and the
// same LSP wiring (the server checks both sentence by sentence).
const root = path.resolve(__dirname, '../..');

interface Language {
  id: string;
  extensions?: string[];
  configuration?: string;
}

const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8')) as {
  contributes: { languages: Language[]; grammars: { language: string }[] };
  activationEvents: string[];
};

describe('TTR-B languages', () => {
  const byId = new Map(pkg.contributes.languages.map((l) => [l.id, l]));

  it('registers ttrb-cs for *.ttrb-cs beside ttrb for *.ttrb', () => {
    expect(byId.get('ttrb')?.extensions).toEqual(['.ttrb']);
    expect(byId.get('ttrb-cs')?.extensions).toEqual(['.ttrb-cs']);
  });

  it('gives ttrb-cs the same language configuration (comments, brackets) as ttrb', () => {
    expect(byId.get('ttrb-cs')?.configuration).toBe(byId.get('ttrb')?.configuration);
    // …and the same (absent) TextMate grammar: neither has its own, so highlighting is identical.
    const grammarLanguages = pkg.contributes.grammars.map((g) => g.language);
    expect(grammarLanguages.includes('ttrb')).toBe(grammarLanguages.includes('ttrb-cs'));
  });

  it('activates on, and routes to the language server, both TTR-B languages', () => {
    expect(pkg.activationEvents).toContain('onLanguage:ttrb');
    expect(pkg.activationEvents).toContain('onLanguage:ttrb-cs');
    const extension = fs.readFileSync(path.join(root, 'src/extension.ts'), 'utf8');
    expect(extension).toContain("language: 'ttrb-cs'");
    expect(extension).toMatch(/createFileSystemWatcher\('\*\*\/\*\.\{[^}]*\bttrb-cs\b/);
  });

  it('scopes an embedded """ttrb-cs fence like """ttrb in .ttrp files', () => {
    const tm = JSON.parse(fs.readFileSync(path.join(root, 'syntaxes/ttrp.tmLanguage.json'), 'utf8')) as {
      patterns: { begin?: string; name?: string }[];
    };
    const fence = tm.patterns.find((p) => p.name === 'string.quoted.fenced.ttrb.ttrp');
    expect(fence).toBeDefined();
    const begin = new RegExp(fence!.begin!);
    expect(begin.test('"""ttrb')).toBe(true);
    expect(begin.test('"""ttrb-cs')).toBe(true);
    expect(begin.test('"""ttrb-de')).toBe(false);
  });
});
