// SPDX-License-Identifier: Apache-2.0
// VL-P0 T5 — the resolution rule (contracts §4, V-15, V-16): a ref resolves against the cards of the
// layout's schema in four steps; the first step that matches decides; more than one match is ambiguous.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import { mergeTtr } from '../layout-ttr.js';
import { resolutionToJson, resolveRefs, type LayoutIndexEntry, type LayoutModelIndex } from '../resolve.js';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..', '..');

/** A card / relation the way the catalog serves it: `pkg` + `qname`, kind, the leaf identifier. */
function entry(pkg: string, qname: string, kind: string): LayoutIndexEntry {
  const schema = qname.split('.')[0];
  return { fullQname: `${pkg}.${qname}`, qname, kind, name: qname.split('.').pop()!, pkg, schema };
}

const HARTLAND: LayoutModelIndex = {
  cards: [
    entry('hartland', 'er.entity.item', 'entity'),
    entry('hartland', 'er.entity.store', 'entity'),
    entry('hartland', 'er.entity.store_sales', 'entity'),
    entry('hartland', 'db.dbo.item', 'table'),
    entry('hartland', 'db.dbo.store_sales', 'table'),
    entry('hartland', 'db.dbo.channel_sales', 'view'),
  ],
  edges: [
    entry('hartland', 'er.entity.rel_store_sales_item', 'relation'),
    entry('hartland', 'db.dbo.fk_ss_item', 'foreign_key'),
  ],
};

/** Two packages that both define `item` (and only one defines `store`). */
const TWO_PACKAGES: LayoutModelIndex = {
  cards: [
    entry('hartland.store', 'er.entity.item', 'entity'),
    entry('hartland.web', 'er.entity.item', 'entity'),
    entry('hartland.store', 'er.entity.store', 'entity'),
  ],
};

describe('resolveRefs (§4)', () => {
  it('step 1 — the exact full qname', () => {
    expect(resolutionToJson(resolveRefs(['hartland.er.entity.item'], HARTLAND, 'er')).resolved).toEqual({
      'hartland.er.entity.item': 'hartland.er.entity.item',
    });
  });

  it('step 2 — the catalog qname, any package', () => {
    expect(resolutionToJson(resolveRefs(['er.entity.store', 'db.dbo.store_sales'], HARTLAND, 'er')).resolved).toEqual({
      'er.entity.store': 'hartland.er.entity.store',
    });
  });

  it('step 3 — kind.name, any package, the layout schema', () => {
    expect(resolveRefs(['entity.store_sales'], HARTLAND, 'er').resolved.get('entity.store_sales')).toBe('hartland.er.entity.store_sales');
    expect(resolveRefs(['table.item', 'view.channel_sales'], HARTLAND, 'db').resolved).toEqual(
      new Map([['table.item', 'hartland.db.dbo.item'], ['view.channel_sales', 'hartland.db.dbo.channel_sales']]),
    );
  });

  it('step 4 — the bare name, against the cards of the layout schema only', () => {
    // `item` is an entity in er and a table in db — the layout's model decides
    expect(resolveRefs(['item'], HARTLAND, 'er').resolved.get('item')).toBe('hartland.er.entity.item');
    expect(resolveRefs(['item'], HARTLAND, 'db').resolved.get('item')).toBe('hartland.db.dbo.item');
  });

  it('a bare name present in two packages is ambiguous, with both candidates, never guessed', () => {
    const r = resolveRefs(['item', 'store'], TWO_PACKAGES, 'er');
    expect(r.ambiguous).toEqual([{ ref: 'item', candidates: ['hartland.store.er.entity.item', 'hartland.web.er.entity.item'] }]);
    expect(r.resolved.get('store')).toBe('hartland.store.er.entity.store');
    expect(r.missing).toEqual([]);
  });

  it('an ambiguity at a step decides — it does not fall through to a later step', () => {
    const r = resolveRefs(['er.entity.item'], TWO_PACKAGES, 'er');
    expect(r.ambiguous).toEqual([{ ref: 'er.entity.item', candidates: ['hartland.store.er.entity.item', 'hartland.web.er.entity.item'] }]);
  });

  it('an exact full qname never consults the later steps', () => {
    // a contrived index where step 2 would match a second card: step 1 still decides alone
    const index: LayoutModelIndex = {
      cards: [entry('hartland', 'er.entity.item', 'entity'), { ...entry('other', 'er.entity.x', 'entity'), qname: 'hartland.er.entity.item' }],
    };
    expect(resolveRefs(['hartland.er.entity.item'], index, 'er')).toMatchObject({ ambiguous: [], resolved: new Map([['hartland.er.entity.item', 'hartland.er.entity.item']]) });
  });

  it('a card of another schema does not resolve — missing', () => {
    const r = resolveRefs(['hartland.db.dbo.item', 'hartland.er.entity.warehouse'], HARTLAND, 'er');
    expect(r.missing).toEqual(['hartland.db.dbo.item', 'hartland.er.entity.warehouse']);
    expect(r.resolved.size).toBe(0);
  });

  it('V-1: a relation or a foreign key is ignored, not missing — from the model, or from the qname shape', () => {
    const er = resolveRefs(
      ['hartland.er.entity.rel_store_sales_item', 'rel_store_sales_item', 'hartland.er.relation.rel_gone', 'item'],
      HARTLAND,
      'er',
    );
    expect(er.ignored).toEqual(['hartland.er.entity.rel_store_sales_item', 'hartland.er.relation.rel_gone', 'rel_store_sales_item']);
    expect(er.missing).toEqual([]);
    const db = resolveRefs(['hartland.db.dbo.fk_ss_item', 'billing.invoicing.db.dbo.fk.fk_item_class'], HARTLAND, 'db');
    expect(db.ignored).toEqual(['billing.invoicing.db.dbo.fk.fk_item_class', 'hartland.db.dbo.fk_ss_item']);
  });

  it('reports each ref once, sorted by code point', () => {
    const r = resolveRefs(['zzz', 'aaa', 'zzz', 'item', 'item'], HARTLAND, 'er');
    expect(r.missing).toEqual(['aaa', 'zzz']);
    expect([...r.resolved.keys()]).toEqual(['item']);
  });

  it('samples/v1.1-mini item_overview: its 2 entities resolve, its 3 relations are ignored (V-1)', () => {
    const read = mergeTtr(
      readFileSync(join(REPO, 'samples/v1.1-mini/graphs/item_overview.ttrg'), 'utf8'),
      readFileSync(join(REPO, 'samples/v1.1-mini/graphs/item_overview.ttrl'), 'utf8'),
    );
    if (!read.ok) throw new Error(JSON.stringify(read.errors));
    const index: LayoutModelIndex = {
      cards: [entry('billing.invoicing', 'er.entity.item', 'entity'), entry('billing.invoicing', 'er.entity.store_return', 'entity')],
    };
    const r = resolveRefs(read.doc.objects, index, 'er');
    expect(r.resolved.size).toBe(2);
    expect(r.ignored).toEqual([
      'billing.invoicing.er.relation.item_in_category',
      'billing.invoicing.er.relation.item_in_class',
      'billing.products.er.relation.class_in_category',
    ]);
    expect(r.missing).toEqual([]);
  });
});

describe('resolutionToJson — the vectors\' expect.json shape', () => {
  it('sorted keys, sorted lists', () => {
    expect(resolutionToJson(resolveRefs(['store', 'item', 'nope'], HARTLAND, 'er'))).toEqual({
      resolved: { item: 'hartland.er.entity.item', store: 'hartland.er.entity.store' },
      missing: ['nope'],
      ambiguous: [],
      ignored: [],
    });
    expect(Object.keys(resolutionToJson(resolveRefs(['store', 'item'], HARTLAND, 'er')).resolved)).toEqual(['item', 'store']);
  });
});
