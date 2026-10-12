// SPDX-License-Identifier: Apache-2.0
// Structural GetGraphResponse mapper (Designer Merge, DM-P2.S3 / contracts §1.1a — the graph-SHAPE
// axis). The Worker backend returns the rich DS `GetGraphResponse` natively (rows/PK-FK, measures,
// cnc props — the slot data the skins render). WS + Veles serve only the dependency graph
// (`TtrmNode`/`TtrmEdge`); this maps that structural shape up to the `GetGraphResponse` the shell
// consumes. Honest partial render (`DM-CAP-002` via `capabilities.graphShape === 'structural'`),
// never a failure.
//
// The dependency graph has every model object as a vertex: an entity, each of its attributes, and
// each relation are separate nodes, joined only by `attribute → entity` DEFINES edges. Drawn as is,
// that is a pile of boxes, not an ER diagram. So (ttr-core#170):
//   • an attribute / column with a DEFINES edge to a node in the graph becomes a ROW of that card;
//   • a relation that names its entities (`from` / `to`, ttr-server#185) becomes an EDGE between them,
//     with its cardinality; a foreign key that names its columns becomes an edge between their tables;
//   • labels come from `displayLabel[lang]`, falling back to the identifier.
// A node the backend doesn't describe that far (an older Veles: no endpoints) stays a node.

import type { GetGraphResponse, ModelGraphNode, ModelGraphEdge, ModelGraphRow, RenderableSchemaCode, Cardinality } from '@tatrman/lsp';
import type { TtrmNode, TtrmEdge } from './ttrm-types.js';
import { DEFAULT_LANGUAGE, languagesOf, localizedDescription, localizedLabel } from '../i18n/labels.js';

const NODE_KINDS = new Set<ModelGraphNode['kind']>(['table', 'view', 'entity', 'cubelet', 'dimension']);
const RENDERABLE = new Set<RenderableSchemaCode>(['db', 'er', 'md', 'cnc']);
/** kinds that are members of a card (DEFINES → their owner), not cards of their own. */
const MEMBER_KINDS = new Set(['attribute', 'column']);

/** best-effort structural kind: keep a known DS node kind, else default by schema (structural-only). */
function coerceKind(raw: string, schema: RenderableSchemaCode): ModelGraphNode['kind'] {
  if (NODE_KINDS.has(raw as ModelGraphNode['kind'])) return raw as ModelGraphNode['kind'];
  switch (schema) {
    case 'db': return 'table';
    case 'md': return 'cubelet';
    default: return 'entity';
  }
}

/** an authored cardinality bound (`1`, `0..1`, `0..*`, `1..*`) → the canvas glyph kind. */
export function cardinalityOf(range: string | undefined): Cardinality | null {
  switch (range?.trim()) {
    case '1': return 'one';
    case '0..1': return 'zero-or-one';
    case '0..*':
    case '*':
    case 'n':
    case 'N': return 'many';
    case '1..*':
    case '1..n': return 'one-or-many';
    default: return null;
  }
}

/** row-less dependency edge → structural ModelGraphEdge (no cardinalities/source spans on this axis). */
function mapEdge(e: TtrmEdge, i: number): ModelGraphEdge {
  return {
    id: `${e.from}->${e.to}#${i}`,
    qname: `${e.from}->${e.to}`,
    kind: e.type === 'REFERENCES' ? 'fk' : 'relation',
    fromNode: e.from,
    toNode: e.to,
    fromCardinality: null,
    toCardinality: null,
    sourceUri: '',
    sourceLocation: { line: 0, column: 0 },
  };
}

export interface StructuralGraphOptions {
  /** the language labels are shown in (`?lang=` / setting / browser); default `en`. */
  lang?: string;
}

/** For every member node (attribute / column), the card it belongs to — from its DEFINES edge. */
export function membersByOwner(nodes: TtrmNode[], edges: TtrmEdge[]): Map<string, TtrmNode[]> {
  const byQname = new Map(nodes.map((n) => [n.qname, n]));
  const out = new Map<string, TtrmNode[]>();
  for (const e of edges) {
    if (e.type !== 'DEFINES') continue;
    const member = byQname.get(e.from);
    const owner = byQname.get(e.to);
    if (!member || !owner || !MEMBER_KINDS.has(member.kind) || MEMBER_KINDS.has(owner.kind)) continue;
    const list = out.get(owner.qname) ?? [];
    list.push(member);
    out.set(owner.qname, list);
  }
  return out;
}

/**
 * Map a structural graph — WS `ttrm/getGraph`, Veles browse — to the `GetGraphResponse` the shell
 * consumes: cards with member rows, relation / foreign-key edges, labels in `opts.lang`.
 * `layout`/`imports` are empty (layout is read separately via the ViewStateStore; imports are a
 * Worker-only concept).
 */
export function ttrmToGetGraphResponse(
  schema: string,
  nodes: TtrmNode[],
  edges: TtrmEdge[],
  missingObjects: string[] = [],
  opts: StructuralGraphOptions = {},
): GetGraphResponse {
  const lang = opts.lang ?? DEFAULT_LANGUAGE;
  const s: RenderableSchemaCode = RENDERABLE.has(schema as RenderableSchemaCode) ? (schema as RenderableSchemaCode) : 'er';

  const members = membersByOwner(nodes, edges);
  const ownerOf = new Map<string, string>();
  for (const [owner, list] of members) for (const m of list) ownerOf.set(m.qname, owner);

  const isCard = (n: TtrmNode) => !ownerOf.has(n.qname);
  const cards = new Set(nodes.filter(isCard).map((n) => n.qname));

  // relations / foreign keys that name both ends on this graph's cards become edges.
  const relationEdges: ModelGraphEdge[] = [];
  const asEdge = new Set<string>();
  for (const n of nodes) {
    if (n.kind === 'relation' && n.from && n.to && cards.has(n.from) && cards.has(n.to)) {
      asEdge.add(n.qname);
      relationEdges.push({
        id: n.qname,
        qname: n.qname,
        kind: 'relation',
        fromNode: n.from,
        toNode: n.to,
        fromCardinality: cardinalityOf(n.cardinality?.from),
        toCardinality: cardinalityOf(n.cardinality?.to),
        sourceUri: '',
        sourceLocation: { line: 0, column: 0 },
      });
    } else if (n.kind === 'foreign_key' && n.fromColumns?.length && n.toColumns?.length) {
      const fromTable = ownerOf.get(n.fromColumns[0]);
      const toTable = ownerOf.get(n.toColumns[0]);
      if (fromTable && toTable) {
        asEdge.add(n.qname);
        relationEdges.push({
          id: n.qname,
          qname: n.qname,
          kind: 'fk',
          fromNode: fromTable,
          toNode: toTable,
          fromCardinality: null,
          toCardinality: null,
          sourceUri: '',
          sourceLocation: { line: 0, column: 0 },
        });
      }
    }
  }

  const canvasNodes = nodes.filter((n) => isCard(n) && !asEdge.has(n.qname));
  const onCanvas = new Set(canvasNodes.map((n) => n.qname));

  return {
    schema: s,
    nodes: canvasNodes.map((n): ModelGraphNode => ({
      qname: n.qname,
      kind: coerceKind(n.kind, s),
      name: n.label, // the identifier
      schemaCode: s,
      ...(n.pkg ? { pkg: n.pkg } : {}),
      label: localizedLabel(n.label, n.displayLabel, lang),
      sourceUri: '',
      sourceLocation: { line: 0, column: 0 },
      rows: (members.get(n.qname) ?? []).map((m): ModelGraphRow => ({
        name: localizedLabel(m.label, m.displayLabel, lang),
        qname: m.qname,
        kind: m.kind === 'column' ? 'column' : 'attribute',
        type: null, // structural backend: no member types on the wire (DM-CAP-002)
        isKey: false,
        optional: false,
        isNameAttribute: false,
        isCodeAttribute: false,
      })),
    })),
    edges: [
      ...relationEdges,
      // the remaining dependency edges, between cards still on the canvas (DEFINES became rows).
      ...edges
        .filter((e) => e.type !== 'DEFINES' && onCanvas.has(e.from) && onCanvas.has(e.to))
        .map(mapEdge),
    ],
    layout: { nodes: {}, edges: {} },
    missingObjects,
    imports: [],
  };
}

/** One object as the inspector shows it on a structural backend (ttr-core#170). */
export interface ObjectDescription {
  qname: string;
  kind: string;
  /** the identifier (`item`) — shown beside the label, so the model name is never hidden. */
  identifier: string;
  /** the label in the viewer's language, else the identifier. */
  label: string;
  labelPlural?: string;
  description?: string;
  /** the card's attributes / columns, labelled and described the same way. */
  members: Array<{ qname: string; kind: string; identifier: string; label: string; description?: string }>;
}

/** Describe `node` (and its `members`) in `lang`. */
export function describeTtrmNode(node: TtrmNode, members: TtrmNode[], lang: string): ObjectDescription {
  return {
    qname: node.qname,
    kind: node.kind,
    identifier: node.label,
    label: localizedLabel(node.label, node.displayLabel, lang),
    ...(node.labelPlural ? { labelPlural: node.labelPlural } : {}),
    ...(() => {
      const d = localizedDescription(node.description, node.descriptionLocalized, lang);
      return d ? { description: d } : {};
    })(),
    members: members.map((m) => {
      const d = localizedDescription(m.description, m.descriptionLocalized, lang);
      return {
        qname: m.qname,
        kind: m.kind,
        identifier: m.label,
        label: localizedLabel(m.label, m.displayLabel, lang),
        ...(d ? { description: d } : {}),
      };
    }),
  };
}

/**
 * The structural graphs a catalog backend has served, kept so the inspector can describe an object
 * — with its members — without another round trip, and so the language picker knows which languages
 * the model's labels carry.
 */
export class StructuralGraphCache {
  private readonly graphs = new Map<string, { nodes: TtrmNode[]; edges: TtrmEdge[] }>();

  constructor(readonly lang: string = DEFAULT_LANGUAGE) {}

  remember(ref: string, nodes: TtrmNode[], edges: TtrmEdge[]): void {
    this.graphs.set(ref, { nodes, edges });
  }

  /** `node`, described with its members, from any graph served so far; null if none has it. */
  describe(qname: string): ObjectDescription | null {
    for (const { nodes, edges } of this.graphs.values()) {
      const node = nodes.find((n) => n.qname === qname);
      if (node) return describeTtrmNode(node, membersByOwner(nodes, edges).get(qname) ?? [], this.lang);
    }
    return null;
  }

  /** Every language the served graphs' labels and descriptions carry. */
  languages(): string[] {
    const texts = [...this.graphs.values()].flatMap(({ nodes }) => nodes.flatMap((n) => [n.displayLabel, n.descriptionLocalized]));
    return languagesOf(texts);
  }
}
