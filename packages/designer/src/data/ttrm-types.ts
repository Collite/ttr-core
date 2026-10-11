// SPDX-License-Identifier: Apache-2.0
// TypeScript types for the `ttrm/*` WS JSON-RPC wire DTOs.
//
// These mirror the Kotlin `ttr-designer-server` handlers (M3.1) as pinned in
// `docs/ttr-metadata/architecture/contracts.md` §4 (+ the §v1.3 changelog).
// The canned fixtures under `__tests__/fixtures/ttrm/` are the contract record:
// if the server ever disagrees, reconcile via contracts.md — never ad hoc.

/** `ttrm/getStatus` — the handshake. Answers even before a model is loaded. */
export interface TtrmStatus {
  protocolVersion: number;
  modelVersion: string | null;
  loadedAt: string | null;
  repoRoot: string;
  issues: Array<{ message: string; file: string | null }>;
}

/** `ttrm/getModelIndex`. */
export interface TtrmIndex {
  packages: string[];
  schemas: string[];
  areas: string[];
  counts: { objects: number; schemas: number; areas: number };
  modelVersion: string;
}

export type TtrmEdgeType = 'DEFINES' | 'REFERENCES' | 'MAPS_TO' | 'USES';

export interface TtrmNode {
  qname: string;
  kind: string;
  /** the leaf identifier (`item`) — the localized name is `displayLabel`. */
  label: string;
  schema: string;
  pkg: string;
  // ---- optional, each omitted when empty (Veles JSON read API, ttr-server#185; ttr-core#170) ----
  /** entity / attribute: the localized name, every authored language — `{ en: "Product", cs: "Produkt" }`. */
  displayLabel?: Record<string, string>;
  /** entity: the plural name — `"Products"`. */
  labelPlural?: string;
  /** the plain-form description. */
  description?: string;
  /** the localized description — `{ en: …, cs: … }`. At most one of the two forms is authored. */
  descriptionLocalized?: Record<string, string>;
  /** relation: the entity qnames it joins, and its cardinality in the authored form (`0..*`, `1`). */
  from?: string;
  to?: string;
  cardinality?: { from?: string; to?: string };
  /** foreign key: the column qnames it joins. */
  fromColumns?: string[];
  toColumns?: string[];
}

export interface TtrmEdge {
  from: string;
  to: string;
  type: TtrmEdgeType;
}

/** `ttrm/getModelGraph` — the *dependency-graph* payload (not the canvas ModelGraph). */
export interface TtrmGraph {
  nodes: TtrmNode[];
  edges: TtrmEdge[];
}

/** `ttrm/getObject`. */
export interface TtrmObjectDetail {
  object: TtrmNode;
  sourceLocation: string | { file: string; line: number; column: number };
  references: string[];
}

/** `ttrm/search` hit element. */
export interface TtrmSearchHit {
  qname: string;
  score: number;
  matchedField: string;
}

/** Resolved world (`ttrm/getWorld` with a qname). */
export interface TtrmWorld {
  qname: string;
  fingerprint: string;
  engines: string[];
  executors: string[];
  storages: string[];
  staging: string | null;
}

/** `ttrm/getLayout` node entry — keyed by qname (T1, TP-5). */
export interface TtrmLayoutNode {
  qname: string;
  x: number;
  y: number;
}

/** `ttrm/getLayout` canvas entry. */
export interface TtrmLayoutCanvas {
  key: string;
  skin: string | null;
  mode: 'auto' | 'manual';
  nodes: TtrmLayoutNode[];
  collapsed: string[];
}

/** `ttrm/getLayout` — `.ttrl` sidecar read, `exists: false` when there's no sidecar yet (not an error). */
export interface TtrmLayoutPayload {
  exists: boolean;
  version: number;
  canvases: TtrmLayoutCanvas[];
  /** Qnames present in the sidecar but not found in the current model registry. */
  orphaned: string[];
  errors: string[];
}

/** `ttrm/setLayout` ack (T3/T4 — no WorkspaceEdit; the server writes the sidecar itself). */
export interface TtrmSetLayoutResult {
  ok: boolean;
}

/** `ttrm/listGraphs` entry (T3/T4). */
export interface TtrmGraphMetadata {
  uri: string;
  name: string;
  schema: string;
  objectCount: number;
  missingObjectCount: number;
}

/** `ttrm/getGraph` response (T3/T4) — the dependency-graph DTO, not the rich canvas ModelGraph (see plan.md T3/3.2.3). */
export interface TtrmGetGraphResponse {
  schema: string;
  nodes: TtrmNode[];
  edges: TtrmEdge[];
  missingObjects: string[];
}

/** Ack shape shared by `ttrm/addObjectToGraph` / `removeObjectFromGraph` (T4). */
export interface TtrmGraphMutationResult {
  ok: boolean;
  objectCount?: number;
  reason?: string;
}

/** `ttrm/createGraph` params (T4) — mirrors the Kotlin `TtrgGraphFile.CreateGraphParams` shape. */
export interface TtrmCreateGraphParams {
  uri: string;
  name: string;
  schema: string;
  packages?: string[];
  objects?: string[];
  description?: string;
  tags?: string[];
}

/** `ttrm/createGraph` ack (T4). */
export interface TtrmCreateGraphResult {
  ok: boolean;
  uri?: string;
  reason?: string;
}
