// SPDX-License-Identifier: Apache-2.0
// TS mirror of the `ttrp/getGraph` / `ttrp/getWorld` / `ttrp/getLayout` wire shapes
// (contracts §4). The committed `hero-getGraph.json` fixture is the 5.1↔5.3 contract;
// any drift here without regenerating that fixture is a contract break.

export interface Range {
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
}

export interface Provenance {
  originQname: string;
  originName: string;
}

export interface PortView {
  name: string;
  kind: 'data' | 'control';
  direction: 'in' | 'out';
}

export interface NodeView {
  zeta: string;
  kind: string;
  label: string;
  range: Range | null;
  ports: PortView[];
  synthesized?: boolean;
  provenance?: Provenance | null;
}

export interface EdgeView {
  from: string;
  to: string;
  fromPort: string;
  toPort: string;
  type: 'data' | 'control-fs' | 'control-ss';
  via?: string | null;
}

export interface ContainerView {
  path: string;
  target: string;
  derived: boolean;
  fragment?: string | null;
  ports: Record<string, string[]>;
  nodes: NodeView[];
  edges: EdgeView[];
}

export interface GraphView {
  program: string;
  containers: ContainerView[];
  leaves: NodeView[];
  edges: EdgeView[];
}

export interface AbstractCoord {
  layer: number;
  index: number;
}

export interface IslandView {
  id: string;
  name: string;
  engine: string;
  invocation: string | null;
}

export interface TransferView {
  id: string;
  fromIsland: string | null;
  toIsland: string | null;
  via: string | null;
  format: string;
}

export interface DerivedView {
  islands: IslandView[];
  transfers: TransferView[];
  waves: string[][];
}

export interface RecipeIsland {
  name: string;
  kind: string;
  contextClass?: string | null;
  routes: string[];
}
export interface RecipeSummary {
  name: string;
  mode: string;
  lanes: { role: string; islands: RecipeIsland[] }[];
}

export interface GetGraphResult {
  recipe?: RecipeSummary | null;
  graph: GraphView;
  provenance: Record<string, Provenance>;
  derived: string[];
  orchestration: DerivedView;
  autoLayout: Record<string, Record<string, AbstractCoord>>;
}

export interface EngineView {
  name: string;
  type: string | null;
  version: string | null;
}

export interface StorageView {
  name: string;
  type: string | null;
  staging: boolean;
}

export interface GetWorldResult {
  world: string;
  fingerprint: string;
  engines: EngineView[];
  executors: EngineView[];
  storages: StorageView[];
  staging: string | null;
}

// ---- layout (getLayout / setLayout) ----

export interface NodePos {
  zeta: string;
  x: number;
  y: number;
}

export interface CanvasLayout {
  key: string;
  skin?: string | null;
  mode: 'auto' | 'manual';
  nodes: NodePos[];
  collapsed: string[];
}

export interface LayoutDiagnostic {
  code: string;
  severity: string;
  message: string;
}

export interface GetLayoutResult {
  exists: boolean;
  version: number;
  canvases: CanvasLayout[];
  orphaned: string[];
  diagnostics: LayoutDiagnostic[];
}
