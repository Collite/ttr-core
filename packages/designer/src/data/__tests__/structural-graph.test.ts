// SPDX-License-Identifier: Apache-2.0
// ttr-core#170 — the catalog's structural graph, mapped for the canvas: labels in the viewer's
// language, attributes / columns as rows of their card, relations / foreign keys as edges. The
// fixtures are cut from a real Veles (ttr-server#185) serving the Hartland demo model.
import { describe, it, expect } from 'vitest';
import type { TtrmEdge, TtrmNode } from '../ttrm-types.js';
import { ttrmToGetGraphResponse, StructuralGraphCache } from '../structural-graph.js';
import erFixture from './fixtures/veles/hartland-er-graph.json';
import dbFixture from './fixtures/veles/hartland-db-graph.json';

const er = erFixture as { nodes: TtrmNode[]; edges: TtrmEdge[] };
const db = dbFixture as { nodes: TtrmNode[]; edges: TtrmEdge[] };

const labelOf = (g: ReturnType<typeof ttrmToGetGraphResponse>, qname: string) => g.nodes.find((n) => n.qname === qname)?.label;

/** The same graph from a Veles that predates ttr-server#185: identifiers only, no endpoints. */
function olderVeles(nodes: TtrmNode[]): TtrmNode[] {
  return nodes.map(({ qname, kind, label, schema, pkg }) => ({ qname, kind, label, schema, pkg }));
}

describe('catalog ER graph (ttr-core#170)', () => {
  it('labels entities in English: Product, Store, Store Sales, Customer Address', () => {
    const g = ttrmToGetGraphResponse('er', er.nodes, er.edges, [], { lang: 'en' });
    expect(labelOf(g, 'er.entity.item')).toBe('Product');
    expect(labelOf(g, 'er.entity.store')).toBe('Store');
    expect(labelOf(g, 'er.entity.store_sales')).toBe('Store Sales');
    expect(labelOf(g, 'er.entity.customer_address')).toBe('Customer Address');
    // the identifier stays on the node
    expect(g.nodes.find((n) => n.qname === 'er.entity.item')?.name).toBe('item');
  });

  it('switches to Czech with the language: item → Produkt', () => {
    const g = ttrmToGetGraphResponse('er', er.nodes, er.edges, [], { lang: 'cs' });
    expect(labelOf(g, 'er.entity.item')).toBe('Produkt');
    expect(labelOf(g, 'er.entity.store')).toBe('Prodejna');
  });

  it('draws one card per entity, its attributes as rows', () => {
    const g = ttrmToGetGraphResponse('er', er.nodes, er.edges, [], { lang: 'en' });
    expect(g.nodes.map((n) => n.qname).sort()).toEqual([
      'er.entity.customer_address', 'er.entity.item', 'er.entity.store', 'er.entity.store_sales',
    ]);
    const item = g.nodes.find((n) => n.qname === 'er.entity.item')!;
    expect(item.rows.map((r) => r.name)).toEqual(['sk', 'product_name', 'category']);
    expect(item.rows.every((r) => r.kind === 'attribute')).toBe(true);
  });

  it('labels a row from the attribute displayLabel when the model authors one', () => {
    // Hartland labels no attribute; the grammar allows it (and Veles serves it) — so add one here.
    const nodes = er.nodes.map((n) => (n.qname === 'er.entity.item.category' ? { ...n, displayLabel: { en: 'Category', cs: 'Kategorie' } } : n));
    const g = ttrmToGetGraphResponse('er', nodes, er.edges, [], { lang: 'cs' });
    expect(g.nodes.find((n) => n.qname === 'er.entity.item')!.rows.map((r) => r.name)).toContain('Kategorie');
  });

  it('draws each relation as an edge between its entities, with its cardinality', () => {
    const g = ttrmToGetGraphResponse('er', er.nodes, er.edges, [], { lang: 'en' });
    const rel = g.edges.find((e) => e.qname === 'er.entity.rel_store_sales_item');
    expect(rel).toMatchObject({ kind: 'relation', fromNode: 'er.entity.store_sales', toNode: 'er.entity.item', fromCardinality: 'many', toCardinality: 'one' });
    expect(g.edges.filter((e) => e.kind === 'relation')).toHaveLength(3);
    expect(g.nodes.some((n) => n.qname.includes('rel_'))).toBe(false);
  });

  it('against an older Veles: identifiers, attributes still folded, relations stay nodes', () => {
    const g = ttrmToGetGraphResponse('er', olderVeles(er.nodes), er.edges, [], { lang: 'cs' });
    expect(labelOf(g, 'er.entity.item')).toBe('item');
    expect(g.nodes.find((n) => n.qname === 'er.entity.item')!.rows).toHaveLength(3);
    expect(g.nodes.filter((n) => n.qname.includes('rel_'))).toHaveLength(3);
  });

  it('DB graph: columns are rows of their table, the foreign key an edge between the tables', () => {
    const g = ttrmToGetGraphResponse('db', db.nodes, db.edges, [], { lang: 'en' });
    expect(g.nodes.map((n) => n.qname).sort()).toEqual(['db.dbo.item', 'db.dbo.store_sales']);
    expect(g.nodes.find((n) => n.qname === 'db.dbo.item')!.rows.map((r) => r.name)).toEqual(['i_item_sk', 'i_category']);
    expect(g.edges).toEqual([
      expect.objectContaining({ qname: 'db.dbo.fk_ss_item', kind: 'fk', fromNode: 'db.dbo.store_sales', toNode: 'db.dbo.item' }),
    ]);
  });
});

describe('inspector descriptions (ttr-core#170)', () => {
  it('describes an entity with label, identifier, plural, description and its attributes', () => {
    const cache = new StructuralGraphCache('en');
    cache.remember('er', er.nodes, er.edges);
    const item = cache.describe('er.entity.item')!;
    expect(item).toMatchObject({ qname: 'er.entity.item', kind: 'entity', label: 'Product', identifier: 'item', labelPlural: 'Products' });
    expect(item.description).toMatch(/^Products — category\/brand drills/);
    expect(item.members.find((m) => m.identifier === 'category')).toEqual({
      qname: 'er.entity.item.category', kind: 'attribute', identifier: 'category', label: 'category',
      description: 'Product category of this item',
    });
  });

  it('describes in Czech, a plain-form description in every language', () => {
    const cache = new StructuralGraphCache('cs');
    cache.remember('er', er.nodes, er.edges);
    expect(cache.describe('er.entity.item')).toMatchObject({ label: 'Produkt', identifier: 'item' });
    expect(cache.describe('er.entity.item')!.members.find((m) => m.identifier === 'category')!.description).toBe('Kategorie tohoto produktu');
    expect(cache.describe('er.entity.store')!.description).toBe('Stores — the six TN stores (Stores-channel dim).');
    expect(cache.languages()).toEqual(['cs', 'en']);
  });

  it('knows nothing it has not been served', () => {
    expect(new StructuralGraphCache('en').describe('er.entity.item')).toBeNull();
  });
});
