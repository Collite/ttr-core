// SPDX-License-Identifier: Apache-2.0
// VL-P1 T2 — projectLayout + hop edges (VL contracts §5.2, V-23…V-25), on the structural catalog
// fixtures (Hartland, as the catalog serves it) and on a hand-built six-card graph.

import { describe, it, expect } from 'vitest';
import type { GetGraphResponse, ModelGraphEdge, ModelGraphNode } from '@tatrman/lsp';
import { ttrmToGetGraphResponse } from '../../data/structural-graph.js';
import type { TtrmGraph } from '../../data/ttrm-types.js';
import type { LayoutDocument } from '../../layout/layout-document.js';
import erFixture from '../../data/__tests__/fixtures/veles/hartland-er-graph.json';
import dbFixture from '../../data/__tests__/fixtures/veles/hartland-db-graph.json';
import {
  projectLayout, layoutIndexOf, hiddenNeighbours, hiddenClosure, visibleNeighbours,
} from '../layout-projection.js';

const er = ttrmToGetGraphResponse('er', (erFixture as TtrmGraph).nodes, (erFixture as TtrmGraph).edges);
const db = ttrmToGetGraphResponse('db', (dbFixture as TtrmGraph).nodes, (dbFixture as TtrmGraph).edges);

const doc = (objects: string[], model: LayoutDocument['model'] = 'er'): LayoutDocument => ({ name: 'l', model, objects, positions: {} });

// a – h – b and a – k – b (two hidden paths between a and b); c – d real, c – x – d hidden
function node(name: string): ModelGraphNode {
  return { qname: `p.er.entity.${name}`, kind: 'entity', name, schemaCode: 'er', label: name, sourceUri: '', sourceLocation: { line: 0, column: 0 }, rows: [] };
}
function edge(from: string, to: string): ModelGraphEdge {
  return {
    id: `p.er.relation.${from}_${to}`, qname: `p.er.relation.${from}_${to}`, kind: 'relation',
    fromNode: `p.er.entity.${from}`, toNode: `p.er.entity.${to}`, fromCardinality: 'many', toCardinality: 'one',
    sourceUri: '', sourceLocation: { line: 0, column: 0 },
  };
}
const six: GetGraphResponse = {
  schema: 'er',
  nodes: ['a', 'b', 'c', 'd', 'h', 'k', 'x'].map(node),
  edges: [edge('a', 'h'), edge('h', 'b'), edge('a', 'k'), edge('k', 'b'), edge('c', 'd'), edge('c', 'x'), edge('x', 'd')],
  layout: { nodes: {}, edges: {} },
  missingObjects: [],
  imports: [],
};
const fq = (n: string) => `p.er.entity.${n}`;

describe('layoutIndexOf — the catalog keys nodes without the package', () => {
  it('maps full qnames to node ids and back; relations become ignorable edges', () => {
    const idx = layoutIndexOf(er);
    expect(idx.nodeOf.get('hartland.er.entity.item')).toBe('er.entity.item');
    expect(idx.fullQnameOf.get('er.entity.store')).toBe('hartland.er.entity.store');
    expect(idx.index.cards.find((c) => c.name === 'item')).toEqual({
      fullQname: 'hartland.er.entity.item', qname: 'er.entity.item', kind: 'entity', name: 'item', pkg: 'hartland', schema: 'er',
    });
    expect(idx.index.edges!.map((e) => e.fullQname)).toContain('hartland.er.entity.rel_store_sales_item');
  });

  it('a backend whose qnames carry the package (the worker) maps to itself', () => {
    const idx = layoutIndexOf(six);
    expect(idx.nodeOf.get(fq('a'))).toBe(fq('a'));
  });
});

describe('projectLayout (V-23) — nodes, real edges, the resolution report', () => {
  it('shows the resolved cards and every real edge between two of them, unchanged', () => {
    const p = projectLayout(er, doc(['hartland.er.entity.item', 'store_sales']));
    expect(p.graph.nodes.map((n) => n.qname).sort()).toEqual(['er.entity.item', 'er.entity.store_sales']);
    expect(p.graph.edges.map((e) => e.id)).toEqual(['er.entity.rel_store_sales_item']);
    expect(p.graph.edges[0]).toBe(er.edges.find((e) => e.id === 'er.entity.rel_store_sales_item'));
  });

  it('missing / ignored pass through from the resolution rule', () => {
    const p = projectLayout(er, doc(['item', 'nope', 'Customer Address', 'hartland.er.entity.rel_store_sales_item']));
    expect(p.missing).toEqual(['Customer Address', 'nope']);
    expect(p.ignored).toEqual(['hartland.er.entity.rel_store_sales_item']);
    expect(p.graph.nodes.map((n) => n.name)).toEqual(['item']);
  });

  it('a db layout: tables and the foreign key between them', () => {
    const p = projectLayout(db, doc(['db.dbo.item', 'store_sales', 'hartland.db.dbo.fk_ss_item'], 'db'));
    expect(p.graph.nodes.map((n) => n.qname).sort()).toEqual(['db.dbo.item', 'db.dbo.store_sales']);
    expect(p.graph.edges.map((e) => e.kind)).toEqual(['fk']);
    expect(p.ignored).toEqual(['hartland.db.dbo.fk_ss_item']);
  });
});

describe('hop edges (V-24)', () => {
  it('Hartland: three entities around a hidden store_sales — three hops through it', () => {
    const p = projectLayout(er, doc(['item', 'store', 'customer_address']));
    expect(p.graph.edges).toEqual([]);
    expect(p.hopEdges.map((h) => [h.fromNode, h.toNode, h.via, h.viaCount])).toEqual([
      ['er.entity.customer_address', 'er.entity.item', ['hartland.er.entity.store_sales'], 1],
      ['er.entity.customer_address', 'er.entity.store', ['hartland.er.entity.store_sales'], 1],
      ['er.entity.item', 'er.entity.store', ['hartland.er.entity.store_sales'], 1],
    ]);
    expect(p.hopEdges.every((h) => h.kind === 'hop')).toBe(true);
  });

  it('A–H–B with H hidden ⇒ one hop; two hidden paths between the same pair ⇒ one edge, viaCount 2', () => {
    const p = projectLayout(six, doc([fq('a'), fq('b')]));
    expect(p.hopEdges).toEqual([{ id: `hop:${fq('a')}|${fq('b')}`, fromNode: fq('a'), toNode: fq('b'), via: [fq('h'), fq('k')], viaCount: 2, kind: 'hop' }]);
  });

  it('H visible ⇒ no hop through it (the real edges show the path); the other hidden path still hops', () => {
    const p = projectLayout(six, doc([fq('a'), fq('b'), fq('h')]));
    expect(p.graph.edges.map((e) => e.id).sort()).toEqual(['p.er.relation.a_h', 'p.er.relation.h_b']);
    expect(p.hopEdges.map((h) => h.via)).toEqual([[fq('k')]]);
  });

  it('A–B real ⇒ no hop between them, whatever is hidden', () => {
    const p = projectLayout(six, doc([fq('c'), fq('d')]));
    expect(p.graph.edges.map((e) => e.id)).toEqual(['p.er.relation.c_d']);
    expect(p.hopEdges).toEqual([]);
  });

  it('a hidden card with one visible neighbour draws nothing', () => {
    expect(projectLayout(six, doc([fq('a')])).hopEdges).toEqual([]);
    expect(projectLayout(six, doc([fq('a'), fq('c')])).hopEdges).toEqual([]);
  });

  it('is derived: recomputed from objects alone, the document is never touched (V-25)', () => {
    const d = doc([fq('a'), fq('b')]);
    const before = JSON.stringify(d);
    projectLayout(six, d);
    expect(JSON.stringify(d)).toBe(before);
  });
});

describe('frontier helpers (Expand, Neighbours, Hide others)', () => {
  const visible = new Set([fq('a'), fq('c')]);
  it('hiddenNeighbours: the hidden cards one real edge away', () => {
    expect(hiddenNeighbours(six, visible, [fq('a')])).toEqual([fq('h'), fq('k')]);
    expect(hiddenNeighbours(six, visible, [fq('a'), fq('c')])).toEqual([fq('d'), fq('h'), fq('k'), fq('x')]);
  });
  it('hiddenClosure: everything reachable through hidden cards', () => {
    expect(hiddenClosure(six, visible, [fq('a')])).toEqual([fq('b'), fq('h'), fq('k')]);
  });
  it('visibleNeighbours: the visible cards one real edge away', () => {
    expect(visibleNeighbours(six, new Set([fq('a'), fq('h'), fq('c')]), fq('a'))).toEqual([fq('h')]);
  });
});
