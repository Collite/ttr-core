// SPDX-License-Identifier: Apache-2.0
// The pluggable model data source (contracts §6, MD6).
//
// Two implementations back it:
//   - WorkerLspDataSource         — the existing browser-worker LSP path (edit: true)
//   - WsDesignerServerDataSource  — the ttrm/* WS JSON-RPC path to ttr-designer-server (edit: false)
//
// Reads stay portable across backends. SO S1.3 adds optional structured authoring
// and changed-file methods; Worker/local and BFF snapshots implement that save seam.
// WS/Veles remain read-only. Commercial canvas forms retain their extension gate.
//
// `getLayout` is the one exception (TP-5 T1, C1-f): it's a genuine read (viewing
// positions, not editing them) now that `ttr-designer-server` speaks it — so it
// lives on this interface, optional, rather than behind `capabilities.edit`.
// `WorkerLspDataSource` doesn't implement it (the worker path already has its own
// separate `LspClient.getLayout` against the in-file block, untouched until T5).

import type { TtrmIndex, TtrmGraph, TtrmObjectDetail, TtrmSearchHit, TtrmLayoutPayload, LayoutSummary, ResolvedLayout } from './ttrm-types.js';
import type { RenderableSchemaCode, BindingMapData, SymbolDetail, GetGraphResponse } from '@tatrman/lsp';
import type { ObjectDescription } from './structural-graph.js';

export type ModelIndex = TtrmIndex;
export type ModelGraphPayload = TtrmGraph;
export type ObjectDetail = TtrmObjectDetail;
export type SearchHit = TtrmSearchHit;
export type LayoutPayload = TtrmLayoutPayload;
export type { LayoutSummary, ResolvedLayout } from './ttrm-types.js';
export interface LayoutIssue { path: string; reason: string }

/** Model kinds a backend may serve (mirrors `@tatrman/lsp` RenderableSchemaCode: db|er|md|cnc). */
export type ModelKind = RenderableSchemaCode;

/**
 * Per-backend capability descriptor (Designer Merge, DM-P1 / contracts §1). The shell renders
 * capabilities, never assumes them: a read a backend can't serve degrades visibly (`DM-CAP-*`,
 * see `capability-hints.ts`), never a dead control. Two axes matter — WHICH kinds
 * (`modelKinds`) and, for kinds it does serve, whether the backend returns the rich slot-data
 * graph or only a structural one (contracts §1.1a; that second axis lands with the DS shell's
 * graph consumer in DM-P2).
 */
export interface DataSourceCapabilities {
  /** Source authoring available. Commercial canvas forms retain their own license gate. */
  readonly edit: boolean;
  /** model kinds this backend can serve at all (`getModelGraph(scope.schema)`). */
  readonly modelKinds: readonly ModelKind[];
  /** `getBindings` available → binding/lineage perspectives can be generated. */
  readonly bindings: boolean;
  /** perspectives (binding/lineage) generable — implied by bindings + cross-face reach. */
  readonly perspectives: boolean;
  /**
   * view-persistence (FO-31) mechanism: the `.ttrg` block, the `.ttrl` sidecar, or `file` — the backend
   * stores no layouts, the tab holds them and **Save as…** downloads them (VL V-29; `'none'` retired).
   */
  readonly layoutPersist: 'in-file' | 'sidecar' | 'file';
  /**
   * The SHAPE of graph a served kind returns (contracts §1.1a — the second capability axis).
   * `'rich'` = the DS `CanvasGraph` slot data the skins render fully (rows/PK/FK, measures/`calc:`,
   * cnc properties); `'structural'` = a row-less dependency graph (the WS `ttrm-adapter`, Veles
   * browse) — the skin's structural marks render but slot bodies are absent (`DM-CAP-002`). Distinct
   * from `modelKinds`: a kind can be *served* (in `modelKinds`) yet only structurally (this axis).
   */
  readonly graphShape: 'rich' | 'structural';
}

/** One graph as the catalog spine needs it (contracts §6 — feeds `buildCatalog`). */
export interface CatalogGraphMeta {
  uri: string;
  name: string;
  schema: string;
  /** a layout the catalog serves (VL V-34) — listed after the schemas, opened by `getLayoutDoc` */
  layout?: { missingCount: number };
}

/** One symbol as the catalog spine needs it (cube/concept/program subjects). */
export interface CatalogSymbolMeta {
  qname: string;
  kind: string;
  name: string;
  packageName: string | null;
}

/** The raw listing the shell's `buildCatalog` consumes. Worker serves both; WS/Veles serve graphs
 *  only (no symbol listing yet → cube/concept/program groups are empty there — honest, not broken). */
export interface CatalogListing {
  graphs: CatalogGraphMeta[];
  symbols: CatalogSymbolMeta[];
}

/** Scope for `getModelGraph`. `schema` selects a schema view; `package` narrows to a package. */
export interface GraphScope {
  schema?: string;
  package?: string;
  edgeTypes?: string[];
}

export interface SearchParams {
  query: string;
  algorithm?: string;
  limit?: number;
}

export interface Disposable {
  dispose(): void;
}

export interface ModelDataSource {
  /** Open structured authoring; absent on read-only backends. Applies/acknowledges successful edits once. */
  edit?(params: import('@tatrman/lsp').ModelerEditParams): Promise<import('@tatrman/lsp').ModelerEditResult>;
  changedFiles?(): import('../fs/changed-files.js').ChangedFile[];
  onFilesChanged?(cb: () => void): Disposable;
  getModelIndex(): Promise<ModelIndex>;
  getModelGraph(scope?: GraphScope): Promise<ModelGraphPayload>;
  /**
   * The rich canvas read (DM-P2.S3 / contracts §1) — a graph by ref, as the DS `GetGraphResponse`
   * the shell maps to a `ModelGraph` for the skins. Worker returns the full slot-data shape; WS/Veles
   * return the structural (row-less) shape via `ttrmToGetGraphResponse` (§1.1a → `DM-CAP-002`). `null`
   * when the ref isn't a graph on this backend.
   */
  getGraph(ref: string): Promise<GetGraphResponse | null>;
  /**
   * The catalog listing (DM-P2.S4 / contracts §6) — the graphs + symbols the shell's `buildCatalog`
   * turns into the catalog spine. Worker serves both (rich); WS/Veles serve graphs only (schemas),
   * with an empty symbol list (honest degradation — no cube/concept/program subjects deployed yet).
   */
  listCatalog(): Promise<CatalogListing>;
  getObject(qname: string): Promise<ObjectDetail>;
  search(q: SearchParams): Promise<SearchHit[]>;
  onModelChanged(cb: (version: string) => void): Disposable;
  readonly capabilities: DataSourceCapabilities;
  /** `.ttrl` sidecar read for a `.ttrg` document (TP-5 T1). Optional — see file header. */
  getLayout?(uri: string): Promise<LayoutPayload>;
  /**
   * Project-wide er↔db bindings (DM-P1 / contracts §1) — feeds the binding + lineage
   * perspectives. Present only when `capabilities.bindings` is true (Worker/full backend);
   * absent on WS/Veles until their servers grow a bindings endpoint.
   */
  getBindings?(): Promise<BindingMapData>;
  /**
   * The inspector/TextDrawer read (DM-P2.S1 / contracts §1) — resolves a qname to its detail
   * (kind, label, source uri + line). Present on the Worker backend (full LSP); absent on WS/Veles
   * (deployed, no source access) → the shell reads `getObject` for structural detail there.
   */
  getSymbolDetail?(qname: string): Promise<SymbolDetail | null>;
  /**
   * The inspector read on a structural catalog (ttr-core#170) — label, identifier, plural and
   * description in the viewer's language, with the object's attributes / columns. Present on the
   * Veles backends, which have no `getSymbolDetail`; the shell prefers `getSymbolDetail` when both exist.
   */
  describe?(qname: string): Promise<ObjectDescription | null>;
  /** The languages the model's labels carry, as far as this backend has served them (the language picker). */
  knownLanguages?(): string[];
  /**
   * Every card of one schema, with the relations / foreign keys between them (VL V-18, V-21) — the
   * whole-schema graph the Objects panel lists, layouts resolve against (§4) and are projected from
   * (§5.2). Nodes carry `pkg` when the qname does not. Absent ⇒ no Objects panel on this backend.
   */
  getSchemaGraph?(schema: ModelKind): Promise<GetGraphResponse | null>;
  /** The layouts the catalog serves (VL V-36, `GET /model/layouts`). Present on the two Veles sources. */
  listLayouts?(): Promise<LayoutSummary[]>;
  /** One served layout, resolved by the catalog (VL V-36, `GET /model/layout?name=`); the viewer re-resolves it. */
  getLayoutDoc?(name: string): Promise<ResolvedLayout>;
  layoutIssues?(): Promise<LayoutIssue[]>;
}
