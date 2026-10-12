// SPDX-License-Identifier: Apache-2.0
// VL-P0 T1 — the layout document + canonical form (contracts §1, V-1…V-5).

import { describe, it, expect } from 'vitest';
import {
  canonicalize,
  compareCodePoints,
  roundCoordinate,
  validateDocument,
  IDENTIFIER,
  type LayoutDocument,
} from '../layout-document.js';

const ITEM = 'hartland.er.entity.item';
const STORE = 'hartland.er.entity.store';
const SALES = 'hartland.er.entity.store_sales';

function doc(over: Partial<LayoutDocument> = {}): LayoutDocument {
  return { name: 'sales_overview', model: 'er', objects: [SALES, ITEM, STORE], positions: {}, ...over };
}

describe('compareCodePoints (V-5 — code point, not locale)', () => {
  it('orders ASCII before the Latin Extended letters, whatever a locale would say', () => {
    // Czech collation puts č between c and d; code point order puts it after z (U+010D > U+007A).
    expect(['č', 'z', 'a'].sort(compareCodePoints)).toEqual(['a', 'z', 'č']);
    expect(['zásoby', 'zákazník', 'čas', 'daň', 'adresa'].sort(compareCodePoints)).toEqual(['adresa', 'daň', 'zákazník', 'zásoby', 'čas']);
  });

  it('compares astral characters by code point, not by UTF-16 code unit', () => {
    // U+1F600 is a surrogate pair (D83D DE00) — code-unit order would put it before U+FF5E.
    expect(['\u{1F600}', '～'].sort(compareCodePoints)).toEqual(['～', '\u{1F600}']);
  });

  it('is a total order with a shorter prefix first', () => {
    expect(compareCodePoints('item', 'item_class')).toBeLessThan(0);
    expect(compareCodePoints('item', 'item')).toBe(0);
  });
});

describe('roundCoordinate (V-3 — integers on write)', () => {
  it('rounds halves up (toward +∞), the same as floor(x + 0.5)', () => {
    expect(roundCoordinate(319.5)).toBe(320);
    expect(roundCoordinate(319.49)).toBe(319);
    expect(roundCoordinate(-319.5)).toBe(-319);
    expect(roundCoordinate(-319.51)).toBe(-320);
  });

  it('never yields negative zero', () => {
    expect(Object.is(roundCoordinate(-0.4), 0)).toBe(true);
    expect(Object.is(roundCoordinate(-0), 0)).toBe(true);
  });
});

describe('canonicalize (V-5)', () => {
  it('sorts objects and positions keys by code point and rounds coordinates', () => {
    const { doc: c } = canonicalize(doc({ positions: { [STORE]: { x: 319.5, y: 180.2 }, [ITEM]: { x: 10, y: -40.5 } } }));
    expect(c.objects).toEqual([ITEM, STORE, SALES]);
    expect(Object.keys(c.positions)).toEqual([ITEM, STORE]);
    expect(c.positions[STORE]).toEqual({ x: 320, y: 180 });
    expect(c.positions[ITEM]).toEqual({ x: 10, y: -40 });
  });

  it('is idempotent', () => {
    const once = canonicalize(doc({ positions: { [STORE]: { x: 1.5, y: 2.5 } }, collapsed: [STORE, ITEM] })).doc;
    const twice = canonicalize(once).doc;
    expect(twice).toEqual(once);
  });

  it('collapses duplicate objects with a warning naming the ref', () => {
    const { doc: c, warnings } = canonicalize(doc({ objects: [ITEM, STORE, ITEM] }));
    expect(c.objects).toEqual([ITEM, STORE]);
    expect(warnings).toEqual([{ path: 'objects', message: `duplicate object ${ITEM} (kept once)` }]);
  });

  it('drops empty optional fields so the canonical form has one spelling', () => {
    const { doc: c } = canonicalize(doc({ skin: '', collapsed: [] }));
    expect(c).not.toHaveProperty('skin');
    expect(c).not.toHaveProperty('collapsed');
    expect(c.positions).toEqual({});
  });

  it('sorts and de-duplicates collapsed', () => {
    expect(canonicalize(doc({ collapsed: [STORE, ITEM, STORE] })).doc.collapsed).toEqual([ITEM, STORE]);
  });

  it('does not mutate its input', () => {
    const input = doc({ positions: { [STORE]: { x: 1.5, y: 2 } } });
    const snapshot = JSON.parse(JSON.stringify(input));
    canonicalize(input);
    expect(input).toEqual(snapshot);
  });
});

describe('validateDocument (V-1…V-4)', () => {
  it('accepts a well-formed document', () => {
    expect(validateDocument(doc({ positions: { [ITEM]: { x: 1, y: 2 } } }))).toEqual({ ok: true, errors: [], warnings: [] });
  });

  it('V-3: a positions key not in objects is an error naming the key', () => {
    const r = validateDocument(doc({ positions: { 'hartland.er.entity.warehouse': { x: 1, y: 2 } } }));
    expect(r.ok).toBe(false);
    expect(r.errors).toEqual([
      { path: 'positions.hartland.er.entity.warehouse', message: 'positions key hartland.er.entity.warehouse is not in objects' },
    ]);
  });

  it('V-4: a name outside the Identifier rule is an error', () => {
    for (const bad of ['a-b', '1abc', '', 'zákazník', 'two words']) {
      const r = validateDocument(doc({ name: bad }));
      expect(r.ok, bad).toBe(false);
      expect(r.errors[0].path).toBe('name');
    }
    expect(IDENTIFIER.test('_sales_2026')).toBe(true);
  });

  it('a model outside er|db|md|cnc is an error', () => {
    const r = validateDocument(doc({ model: 'xx' as LayoutDocument['model'] }));
    expect(r.errors).toEqual([{ path: 'model', message: 'model must be one of er, db, md, cnc (got xx)' }]);
  });

  it('duplicate objects are a warning, not an error', () => {
    const r = validateDocument(doc({ objects: [ITEM, ITEM] }));
    expect(r.ok).toBe(true);
    expect(r.warnings).toEqual([{ path: 'objects', message: `duplicate object ${ITEM} (kept once)` }]);
  });

  it('an empty ref or a ref with a control character is an error', () => {
    const r = validateDocument(doc({ objects: [ITEM, '', 'bad\nref'] }));
    expect(r.errors.map((e) => e.path)).toEqual(['objects[1]', 'objects[2]']);
  });

  it('non-finite coordinates are an error', () => {
    const r = validateDocument(doc({ positions: { [ITEM]: { x: Number.NaN, y: Infinity } } }));
    expect(r.errors.map((e) => e.path)).toEqual([`positions.${ITEM}.x`, `positions.${ITEM}.y`]);
  });

  it('collapsed entries follow the ref rule', () => {
    expect(validateDocument(doc({ collapsed: [''] })).errors[0].path).toBe('collapsed[0]');
  });
});
