// SPDX-License-Identifier: Apache-2.0
// ttr-core#170 — choosing the language and the text in it. Examples are the Hartland demo model's.
import { describe, it, expect } from 'vitest';
import { localizedDescription, localizedLabel, pickLanguage, resolveLanguage, languagesOf } from '../labels.js';

const ITEM_LABEL = { en: 'Product', cs: 'Produkt' };
const CATEGORY_DESCRIPTION = { en: 'Product category of this item', cs: 'Kategorie tohoto produktu' };

describe('model labels in the viewer language (ttr-core#170)', () => {
  it('labels from displayLabel[lang], falling back to the identifier', () => {
    expect(localizedLabel('item', ITEM_LABEL, 'en')).toBe('Product');
    expect(localizedLabel('item', ITEM_LABEL, 'cs')).toBe('Produkt');
    expect(localizedLabel('item', ITEM_LABEL, 'de')).toBe('item'); // no German label → the identifier
    expect(localizedLabel('category', undefined, 'en')).toBe('category');
  });

  it('matches a regional tag to its language, either way round, and ignores blank entries', () => {
    expect(pickLanguage(ITEM_LABEL, 'cs-CZ')).toBe('Produkt');
    expect(pickLanguage({ 'pt-BR': 'Produto' }, 'pt')).toBe('Produto');
    expect(pickLanguage({ EN: 'Product' }, 'en')).toBe('Product');
    expect(pickLanguage({ cs: '  ' }, 'cs')).toBeUndefined();
  });

  it('describes by the D7 chain: language → plain form → en → first by code → none', () => {
    expect(localizedDescription(undefined, CATEGORY_DESCRIPTION, 'cs')).toBe('Kategorie tohoto produktu');
    // store's description is authored plain (English) — every language shows it
    expect(localizedDescription('Stores — the six TN stores (Stores-channel dim).', undefined, 'cs'))
      .toBe('Stores — the six TN stores (Stores-channel dim).');
    expect(localizedDescription(undefined, CATEGORY_DESCRIPTION, 'de')).toBe('Product category of this item');
    expect(localizedDescription(undefined, { sk: 'Kategória', cs: 'Kategorie' }, 'de')).toBe('Kategorie');
    expect(localizedDescription(undefined, undefined, 'en')).toBeUndefined();
  });

  it('picks the language: ?lang= → saved → configured → browser → en', () => {
    expect(resolveLanguage({ query: 'cs', saved: 'en', configured: 'en', browser: ['en-US'] })).toBe('cs');
    expect(resolveLanguage({ query: null, saved: 'cs', configured: 'en', browser: ['en-US'] })).toBe('cs');
    expect(resolveLanguage({ configured: 'cs', browser: ['en-US'] })).toBe('cs');
    expect(resolveLanguage({ browser: ['cs-CZ', 'en'] })).toBe('cs');
    expect(resolveLanguage({})).toBe('en');
    // an invalid value is skipped, not trusted
    expect(resolveLanguage({ query: '"><script>', browser: ['en-GB'] })).toBe('en');
  });

  it('lists the languages a model carries', () => {
    expect(languagesOf([ITEM_LABEL, CATEGORY_DESCRIPTION, undefined])).toEqual(['cs', 'en']);
  });
});
