// SPDX-License-Identifier: Apache-2.0
// ModelGraph → CanvasGraph mapper for the modeling face (db/er). canvas-core stays
// dependency-free: the input is a STRUCTURAL subset of @tatrman/lsp's ModelGraph (the lsp
// type is assignable to it), so the one-way dependency graph holds. Ports are synthesized
// per node and NEVER dropped (D-2 posture); rows ride slotData.rows; FK/relation
// cardinality rides the edge.

import type { CanvasGraph, CanvasNode, CanvasEdge, CanvasPort, Cardinality } from './types.js';

export type LsCardinality = 'one' | 'zero-or-one' | 'many' | 'one-or-many';

export interface ModelGraphRowInput {
  name: string;
  qname: string;
  kind: 'column' | 'attribute' | 'measure' | 'level';
  type: string | null;
  isKey: boolean;
  optional: boolean;
  isNameAttribute: boolean;
  isCodeAttribute: boolean;
}

export interface ModelGraphNodeInput {
  qname: string;
  kind: 'table' | 'view' | 'entity' | 'cubelet' | 'dimension';
  name: string;
  label: string;
  schemaCode: 'db' | 'er' | 'md' | 'cnc';
  rows: ModelGraphRowInput[];
  /** designer-side fixture fill for grammar gaps (spread into slotData; never LSP-fabricated) */
  slotExtra?: Record<string, unknown>;
}

export interface ModelGraphEdgeInput {
  id: string;
  qname: string;
  kind: 'fk' | 'relation' | 'grain' | 'hop';
  /** hop edges: the hidden cards in between (VL V-24) */
  via?: string[];
  fromNode: string;
  toNode: string;
  fromCardinality: LsCardinality | null;
  toCardinality: LsCardinality | null;
}

export interface ModelGraphInput {
  schemaCode: 'db' | 'er' | 'md' | 'cnc';
  nodes: ModelGraphNodeInput[];
  edges: ModelGraphEdgeInput[];
}

const CARD: Record<LsCardinality, Cardinality> = {
  one: '1',
  'zero-or-one': '0..1',
  many: '0..*',
  'one-or-many': '1..*',
};
const mapCard = (c: LsCardinality | null): Cardinality | undefined => (c ? CARD[c] : undefined);

const shortName = (qname: string): string => qname.split('.').pop() ?? qname;

export function modelGraphToCanvas(mg: ModelGraphInput): CanvasGraph {
  const hasOut = new Set(mg.edges.map((e) => e.fromNode));
  const hasIn = new Set(mg.edges.map((e) => e.toNode));

  const nodes: CanvasNode[] = mg.nodes.map((n) => {
    // synthesized connection ports — always present (never dropped), connected iff an edge uses them
    const ports: CanvasPort[] = [
      { id: `${n.qname}::out`, direction: 'out', role: 'data', connected: hasOut.has(n.qname) },
      { id: `${n.qname}::in`, direction: 'in', role: 'data', connected: hasIn.has(n.qname) },
    ];
    return {
      id: n.qname,
      qname: n.qname,
      kind: n.kind,
      label: n.label,
      ports,
      slotData: { rows: n.rows, nodeKind: n.kind, ...(n.slotExtra ?? {}) },
    };
  });

  const edges: CanvasEdge[] = mg.edges.map((e) => {
    const ends = { from: { node: e.fromNode, port: `${e.fromNode}::out` }, to: { node: e.toNode, port: `${e.toNode}::in` } };
    if (e.kind === 'hop') {
      // a path through hidden cards (VL V-24/V-26): dashed, no cardinalities, `…` or `… ×n` at the midpoint
      const via = e.via ?? [];
      return { id: e.id, ...ends, role: 'data', kind: 'hop', via, label: via.length > 1 ? `… ×${via.length}` : '…' };
    }
    return {
      id: e.id,
      ...ends,
      role: 'data',
      label: e.kind === 'relation' ? shortName(e.qname) : undefined,
      cardinality: { from: mapCard(e.fromCardinality), to: mapCard(e.toCardinality) },
    };
  });

  return {
    id: `${mg.schemaCode}-graph`,
    face: 'modeling',
    kind: mg.schemaCode,
    nodes,
    edges,
    containers: [],
  };
}
