// SPDX-License-Identifier: Apache-2.0
// Pure kernel-model + state-vocabulary types (contracts §1.3, §3). React-free by
// discipline (PF wall). The runtime skin registry + enforcement is DS-P1.S1 — this file
// carries only the shapes downstream packages (perspectives, designer, the hero fixture)
// reference now.

export type SchemaCode = 'db' | 'er' | 'md' | 'cnc'; // modeling kinds (extends today's db|er)

// ---- fixed state vocabularies (contracts §1.3) ----
export type RunStatus = 'idle' | 'running' | 'done' | 'failed';

export interface DiagnosticsState {
  errorCount: number;
  warnCount: number;
}

export interface NodeBaseState {
  runStatus?: RunStatus; // absent = not runnable / no run info
  diagnostics?: DiagnosticsState;
  selected: boolean;
  focused: boolean;
  readOnly: boolean;
  derived: boolean; // derived ⇒ canvas-level banner too (DS-CANV-002)
  orphanedLayout: boolean;
}

// ---- canvas kernel model (contracts §3) ----
export type PortRole = 'data' | 'control' | 'err' | 'rejects';
export type EdgeRole = 'data' | 'control' | 'transfer';
export type Cardinality = string; // '0..*', '1', 'N' …

export interface CanvasPort {
  id: string;
  direction: 'in' | 'out';
  role: PortRole;
  connected: boolean; // unconnected err/rejects still render (D-2)
}

export interface PortRef {
  node: string;
  port: string;
}

export interface CanvasNode {
  id: string;
  qname: string;
  /** face-specific: 'table'|'entity'|'cube'|'dimension'|'concept'|'op'|'container-ref'|'store'|'display'|… */
  kind: string;
  label: string;
  bodyText?: string;
  ports: CanvasPort[];
  /** notation payloads: rows (db/er), measures+calc (md), properties (cnc), engine/dialect, provenance */
  slotData: Record<string, unknown>;
  containerId?: string;
}

export interface CanvasEdge {
  id: string;
  from: PortRef;
  to: PortRef;
  role: EdgeRole;
  label?: string;
  cardinality?: { from?: Cardinality; to?: Cardinality };
  /** `hop` — a derived path through hidden cards (VL V-24): skins draw it dashed, no cardinality */
  kind?: 'hop';
  /** hop edges: the hidden cards in between, as full qnames */
  via?: string[];
}

export interface CanvasContainer {
  id: string;
  label: string;
  engine?: string;
  dialect?: string;
  fragmentDerived?: boolean; // `"""sql · derived view`
  collapsed: boolean; // collapsed ⇒ REGION render (D-3 β)
}

/** The "show bindings" toggle (S-5) is NOT a perspective — it is a base-layer decoration on
 *  the er canvas that ghosts the bound db table under each bound entity. Keyed by entity node id;
 *  absent ⇒ nothing drawn. Session-local (never persisted); the base layer draws it (contracts §4.1). */
export interface BindingHint {
  /** short label of the bound target — 'dbo.Customer' at rest, or the query name for query binds. */
  target: string;
  kind: 'table' | 'query' | 'unresolved';
}

export interface CanvasGraph {
  id: string; // canvas key (view-state correlation)
  face: 'modeling' | 'processing';
  kind?: SchemaCode;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  containers: CanvasContainer[];
  /** S-5 show-bindings decoration (entity node id → ghost chip). Base-layer drawn; not persisted. */
  bindingHints?: Record<string, BindingHint>;
  /** processing drill-in into a `"""sql · derived view` (fragmentDerived) ⇒ the whole canvas is
   *  read-only + auto-only + carries the DS-CANV-002 banner (contracts §3; D-3/DS-P5). */
  derived?: boolean;
}
