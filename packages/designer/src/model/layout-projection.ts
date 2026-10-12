// SPDX-License-Identifier: Apache-2.0
// The layout projection (VL contracts §5.2, V-23…V-25): the picture a layout shows, cut from the
// whole-schema graph the viewer already holds. Pure — no React, no backend reads.
//
//   nodes      = the layout's resolved cards that are in the whole graph (V-23)
//   edges      = every whole-graph edge whose two ends are visible, unchanged (V-23)
//   hop edges  = for every hidden card H and every pair of visible cards {A, B} with real edges A–H and
//                H–B, one dashed A ⋯ B edge; every H of the same pair in one edge (`via`, `viaCount`);
//                none where a real A–B edge exists (V-24). Derived, never written anywhere (V-25).
//
// A layout names cards by full qname (`hartland.er.entity.item`); a backend's graph may key its nodes
// otherwise (the catalog serves `er.entity.item` with the package beside it). `layoutIndexOf` bridges
// the two and is the index the resolution rule (§4) runs against.

import type { GetGraphResponse, ModelGraphEdge, ModelGraphNode } from '@tatrman/lsp';
import { compareAlphabetical, sortAlphabetical, type LayoutDocument } from '@tatrman/parser/layout';
import { resolveRefs, type Ambiguity, type LayoutIndexEntry, type LayoutModelIndex } from '../layout/resolve.js';

/** A path through one hidden card, drawn as one dashed `…` edge (V-24). */
export interface HopEdge {
  id: string;
  fromNode: string;
  toNode: string;
  /** the hidden cards in between, as full qnames, sorted */
  via: string[];
  viaCount: number;
  kind: 'hop';
}

/** The resolution index of a whole-schema graph, and the map between full qnames and node ids. */
export interface LayoutGraphIndex {
  index: LayoutModelIndex;
  /** full qname → the graph's node id */
  nodeOf: ReadonlyMap<string, string>;
  /** node id → full qname */
  fullQnameOf: ReadonlyMap<string, string>;
}

export interface LayoutProjection {
  graph: GetGraphResponse;
  hopEdges: HopEdge[];
  /** ref → full qname, every ref that resolved */
  resolved: ReadonlyMap<string, string>;
  missing: string[];
  ambiguous: Ambiguity[];
  ignored: string[];
}

const leaf = (qname: string): string => qname.slice(qname.lastIndexOf('.') + 1);

/** `pkg` + `.` + the catalog qname — unless the backend's qname already carries the package (the worker's). */
function fullQname(qname: string, pkg: string): string {
  return pkg && !qname.startsWith(`${pkg}.`) ? `${pkg}.${qname}` : qname;
}

function entryOf(fq: string, pkg: string, kind: string, name: string, schema: string): LayoutIndexEntry {
  return { fullQname: fq, qname: pkg && fq.startsWith(`${pkg}.`) ? fq.slice(pkg.length + 1) : fq, kind, name, pkg, schema };
}

/** The resolution index of `whole` (§4): its cards, and its relations / foreign keys as ignorable edges. */
export function layoutIndexOf(whole: GetGraphResponse): LayoutGraphIndex {
  const schema = whole.schema;
  const nodeOf = new Map<string, string>();
  const fullQnameOf = new Map<string, string>();
  const pkgOf = new Map<string, string>();
  const cards: LayoutIndexEntry[] = [];
  for (const n of whole.nodes) {
    const pkg = n.pkg ?? '';
    const fq = fullQname(n.qname, pkg);
    nodeOf.set(fq, n.qname);
    fullQnameOf.set(n.qname, fq);
    pkgOf.set(n.qname, pkg);
    cards.push(entryOf(fq, pkg, n.kind, n.name, schema));
  }
  const edges: LayoutIndexEntry[] = [];
  for (const e of whole.edges) {
    if (e.kind !== 'relation' && e.kind !== 'fk') continue;
    const pkg = pkgOf.get(e.fromNode) ?? '';
    edges.push(entryOf(fullQname(e.qname, pkg), pkg, e.kind === 'fk' ? 'foreign_key' : 'relation', leaf(e.qname), schema));
  }
  return { index: { cards, edges }, nodeOf, fullQnameOf };
}

// one adjacency per graph's edge list — Expand, Neighbours, Hide others, placement and the hop edges all
// ask it, many times per gesture
const ADJACENCY = new WeakMap<readonly ModelGraphEdge[], ReadonlyMap<string, ReadonlySet<string>>>();

/** Undirected adjacency over the whole graph's real edges (self-loops dropped), built once per edge list. */
function adjacencyOf(edges: readonly ModelGraphEdge[]): ReadonlyMap<string, ReadonlySet<string>> {
  const held = ADJACENCY.get(edges);
  if (held) return held;
  const adj = new Map<string, Set<string>>();
  const link = (a: string, b: string) => {
    if (!adj.has(a)) adj.set(a, new Set());
    adj.get(a)!.add(b);
  };
  for (const e of edges) {
    if (e.fromNode === e.toNode) continue;
    link(e.fromNode, e.toNode);
    link(e.toNode, e.fromNode);
  }
  ADJACENCY.set(edges, adj);
  return adj;
}

const pairKey = (a: string, b: string): string => (compareAlphabetical(a, b) <= 0 ? `${a}|${b}` : `${b}|${a}`);

/** The hop edges between the `visible` node ids of `whole` (V-24), sorted by id. */
export function hopEdgesOf(whole: GetGraphResponse, visible: ReadonlySet<string>, fullQnameOf: ReadonlyMap<string, string>): HopEdge[] {
  const adj = adjacencyOf(whole.edges);
  const real = new Set<string>();
  for (const e of whole.edges) if (visible.has(e.fromNode) && visible.has(e.toNode)) real.add(pairKey(e.fromNode, e.toNode));
  const hops = new Map<string, { a: string; b: string; via: string[] }>();
  for (const h of whole.nodes) {
    if (visible.has(h.qname)) continue;
    const nb = sortAlphabetical([...(adj.get(h.qname) ?? [])].filter((n) => visible.has(n)));
    for (let i = 0; i < nb.length; i++) {
      for (let j = i + 1; j < nb.length; j++) {
        const key = pairKey(nb[i], nb[j]);
        if (real.has(key)) continue;
        const hop = hops.get(key) ?? { a: nb[i], b: nb[j], via: [] };
        hop.via.push(fullQnameOf.get(h.qname) ?? h.qname);
        hops.set(key, hop);
      }
    }
  }
  return [...hops.entries()]
    .sort(([k1], [k2]) => compareAlphabetical(k1, k2))
    .map(([key, h]) => {
      const via = sortAlphabetical(new Set(h.via));
      return { id: `hop:${key}`, fromNode: h.a, toNode: h.b, via, viaCount: via.length, kind: 'hop' as const };
    });
}

/**
 * Project `doc` onto `whole` (§5.2) — its cards and model only; positions, skin and collapsed do not
 * change the picture's contents. `opts.hops` is fixed at 1 in v1 (G-1, ruled: one hop stays). Pass `idx`
 * when the caller already holds `layoutIndexOf(whole)`.
 */
export function projectLayout(
  whole: GetGraphResponse,
  doc: Pick<LayoutDocument, 'objects' | 'model'>,
  _opts: { hops: 1 } = { hops: 1 },
  idx: LayoutGraphIndex = layoutIndexOf(whole),
): LayoutProjection {
  const r = resolveRefs(doc.objects, idx.index, doc.model);
  const visible = new Set<string>();
  for (const fq of r.resolved.values()) {
    const id = idx.nodeOf.get(fq);
    if (id !== undefined) visible.add(id);
  }
  const nodes: ModelGraphNode[] = whole.nodes.filter((n) => visible.has(n.qname));
  const edges: ModelGraphEdge[] = whole.edges.filter((e) => visible.has(e.fromNode) && visible.has(e.toNode));
  return {
    graph: { ...whole, nodes, edges, layout: { nodes: {}, edges: {} }, missingObjects: [] },
    hopEdges: hopEdgesOf(whole, visible, idx.fullQnameOf),
    resolved: r.resolved,
    missing: r.missing,
    ambiguous: r.ambiguous,
    ignored: r.ignored,
  };
}

/** The hidden cards one real edge away from any of `of` (Expand, Neighbours — V-20, V-27), sorted. */
export function hiddenNeighbours(whole: GetGraphResponse, visible: ReadonlySet<string>, of: Iterable<string>): string[] {
  const adj = adjacencyOf(whole.edges);
  const out = new Set<string>();
  for (const id of of) for (const n of adj.get(id) ?? []) if (!visible.has(n)) out.add(n);
  return sortAlphabetical(out);
}

/**
 * Every hidden card reachable from `of` through hidden cards (Expand all levels… — V-27): a walk that
 * starts at `of` and continues only through the cards it adds; visible cards are not walked through.
 */
export function hiddenClosure(whole: GetGraphResponse, visible: ReadonlySet<string>, of: Iterable<string>): string[] {
  const adj = adjacencyOf(whole.edges);
  const seen = new Set(visible);
  const added: string[] = [];
  let frontier = [...of];
  while (frontier.length > 0) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const n of adj.get(id) ?? []) {
        if (seen.has(n)) continue;
        seen.add(n);
        added.push(n);
        next.push(n);
      }
    }
    frontier = next;
  }
  return sortAlphabetical(added);
}

/** The visible cards one real edge away from `id` (Hide others keeps them — V-27; placement — V-19). */
export function visibleNeighbours(whole: GetGraphResponse, visible: ReadonlySet<string>, id: string): string[] {
  return sortAlphabetical([...(adjacencyOf(whole.edges).get(id) ?? [])].filter((n) => visible.has(n)));
}
