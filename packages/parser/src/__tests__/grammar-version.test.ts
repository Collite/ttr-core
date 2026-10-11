// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { TTR_GRAMMAR_VERSION, PROPERTY_MAP } from '@tatrman/grammar';

// Grammar-version guard. Bumping `// @grammar-version:` in TTR.g4 (and running
// the grammar prebuild) is what moves this constant; the assertion is the
// reminder that the CHANGELOG entry + downstream proto/version sync move with it.
describe('grammar version', () => {
  it('is 0.14 (named row schemas — top-level `def schema <name> { columns: [...] }`)', () => {
    expect(TTR_GRAMMAR_VERSION).toBe('0.14');
  });

  it('advertises the `def schema` properties: description, tags, columns', () => {
    expect(PROPERTY_MAP.schema.map((p) => p.name)).toEqual(['description', 'tags', 'columns']);
    expect(PROPERTY_MAP.schema.find((p) => p.name === 'columns')!.type).toBe('list of column definitions');
  });

  it('advertises description as accepting both forms on every kind that has it', () => {
    // NLS-P10: the property map is what the LSP/Designer show an author, so the
    // widened value form has to be visible there and not only in the .g4.
    const described = Object.keys(PROPERTY_MAP).filter((kind) =>
      PROPERTY_MAP[kind as keyof typeof PROPERTY_MAP].some((p) => p.name === 'description'),
    );
    expect(described.length).toBeGreaterThan(0);
    for (const kind of described) {
      const info = PROPERTY_MAP[kind as keyof typeof PROPERTY_MAP].find(
        (p) => p.name === 'description',
      )!;
      expect(info.type).toBe('localized string or string');
    }
    // `displayLabel` — the precedent this copied — is unchanged.
    expect(PROPERTY_MAP.entity.find((p) => p.name === 'displayLabel')!.type).toBe(
      'localized string',
    );
  });

  it('exposes the semantics property on exactly the four attachment kinds', () => {
    const hasSemantics = (kind: keyof typeof PROPERTY_MAP) =>
      PROPERTY_MAP[kind].some((p) => p.name === 'semantics');
    expect(hasSemantics('table')).toBe(true);
    expect(hasSemantics('column')).toBe(true);
    expect(hasSemantics('entity')).toBe(true);
    expect(hasSemantics('attribute')).toBe(true);
    // NOT on view/relation/query/role.
    expect(hasSemantics('view')).toBe(false);
    expect(hasSemantics('relation')).toBe(false);
    expect(hasSemantics('query')).toBe(false);
    expect(hasSemantics('role')).toBe(false);
  });

  it('exposes the inline lexicon property on the er/db carrier kinds (v4.4)', () => {
    const hasLexicon = (kind: keyof typeof PROPERTY_MAP) =>
      PROPERTY_MAP[kind].some((p) => p.name === 'lexicon');
    expect(hasLexicon('table')).toBe(true);
    expect(hasLexicon('column')).toBe(true);
    expect(hasLexicon('entity')).toBe(true);
    expect(hasLexicon('attribute')).toBe(true);
    // NOT on view/relation/query/role.
    expect(hasLexicon('view')).toBe(false);
    expect(hasLexicon('relation')).toBe(false);
  });
});
