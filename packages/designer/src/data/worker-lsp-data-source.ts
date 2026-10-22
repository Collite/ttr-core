// SPDX-License-Identifier: Apache-2.0
// WorkerLspDataSource — wraps the existing browser-worker LSP path as a
// ModelDataSource (contracts §6). capabilities.edit === true. The App keeps using
// the raw `LspClient` for graph-list / layout / edit operations via the exposed
// `lspClient` escape hatch (§6 is a READ contract; edit ops stay on LspClient,
// gated by capabilities.edit — recorded contracts delta, contracts.md §v1.3).
//
// This whole path is the offline fallback as of TP-5 T4 (`WsDesignerServerDataSource`
// now has real `ttrm/*` edit methods too) — decided 2026-07-15 (T5.2) to keep both,
// not retire this one. Works with no `ttr-designer-server` process running.
//
// The mappings here are thin — no new semantics. Whole-schema subjects come
// from the LSP; model/layouts/** uses the shared VL reader and resolver against
// those graphs. The shell uses these reads for both local and hosted projects.

import type { LspClient } from '../lsp-client.js';
import type { ModelGraph, RenderableSchemaCode, BindingMapData, SymbolDetail, GetGraphResponse } from '@tatrman/lsp';
import type {
  ModelDataSource,
  DataSourceCapabilities,
  ModelIndex,
  ModelGraphPayload,
  ObjectDetail,
  SearchHit,
  SearchParams,
  GraphScope,
  Disposable,
  CatalogListing,
  LayoutSummary,
  ResolvedLayout,
} from './model-data-source.js';
import type { TtrmNode, TtrmEdge } from './ttrm-types.js';
import { readLayoutFiles, normalizeForTab } from '../layout/open-files.js';
import { resolveRefs } from '../layout/resolve.js';
import { layoutIndexOf } from '../model/layout-projection.js';
import { modeOf } from '@tatrman/parser/layout';

export interface WorkerContext {
  projectRoot: string;
  /** The currently open graph document uri (set as graphs are opened). */
  graphUri?: string;
  files?: ReadonlyMap<string, string>;
  directoryHandle?: FileSystemDirectoryHandle;
}

/** Map the renderable ModelGraph (rows/fk/relation) down to the ttrm dependency-graph DTO. */
export function modelGraphToTtrm(mg: ModelGraph): ModelGraphPayload {
  const nodes: TtrmNode[] = mg.nodes.map((n) => ({
    qname: n.qname,
    kind: n.kind,
    label: n.label,
    schema: n.schemaCode,
    pkg: '',
  }));
  const edges: TtrmEdge[] = mg.edges.map((e) => ({
    from: e.fromNode,
    to: e.toNode,
    type: e.kind === 'fk' ? 'REFERENCES' : 'MAPS_TO',
  }));
  return { nodes, edges };
}

export class WorkerLspDataSource implements ModelDataSource {
  // Full-featured: the Worker path carries the S1 lsp graft (md/cnc + getBindings). edit:true is
  // the local-file offline capability — further gated by license in the commercial build.
  readonly capabilities: DataSourceCapabilities = {
    edit: true,
    modelKinds: ['db', 'er', 'md', 'cnc'],
    bindings: true,
    perspectives: true,
    layoutPersist: 'in-file',
    graphShape: 'rich', // the Worker path carries the DS lsp graft → full CanvasGraph slot data (§1.1a)
  } as const;

  constructor(
    readonly lspClient: LspClient,
    private readonly ctx: WorkerContext,
  ) {}

  setGraphUri(uri: string): void {
    this.ctx.graphUri = uri;
  }

  async getModelIndex(): Promise<ModelIndex> {
    const [graphList, pkgGraph] = await Promise.all([
      this.lspClient.listGraphs(this.ctx.projectRoot),
      this.lspClient.getPackageGraph(),
    ]);
    const packages = pkgGraph.packages.map((p) => p.name);
    const schemas = [...new Set(graphList.graphs.map((g) => g.schema))];
    return {
      packages,
      schemas,
      areas: [],
      counts: { objects: 0, schemas: schemas.length, areas: 0 },
      modelVersion: '',
    };
  }

  async getModelGraph(scope?: GraphScope): Promise<ModelGraphPayload> {
    const uri = this.ctx.graphUri;
    if (!uri) throw new Error('WorkerLspDataSource.getModelGraph: no current graph uri');
    const mg = await this.lspClient.getModelGraph(uri, (scope?.schema ?? 'db') as RenderableSchemaCode);
    return modelGraphToTtrm(mg);
  }

  getBindings(): Promise<BindingMapData> {
    return this.lspClient.getBindings();
  }

  getSymbolDetail(qname: string): Promise<SymbolDetail | null> {
    return this.lspClient.getSymbolDetail(qname);
  }

  /**
   * The whole-schema graph (VL V-21): the project's model graph for `schema`, each card's package from
   * the symbol listing (the worker's qnames already carry it; the panel groups by it).
   */
  async getSchemaGraph(schema: RenderableSchemaCode): Promise<GetGraphResponse | null> {
    const [mg, symbols] = await Promise.all([
      this.lspClient.getModelGraph(this.ctx.graphUri ?? this.ctx.projectRoot, schema),
      this.lspClient.listSymbols(),
    ]);
    const pkg = new Map(symbols.map((s) => [s.qname, s.packageName ?? '']));
    return {
      schema,
      nodes: mg.nodes.map((n) => ({ ...n, pkg: pkg.get(n.qname) ?? '' })),
      edges: mg.edges,
      layout: { nodes: {}, edges: {} },
      missingObjects: [],
      imports: [],
    };
  }

  getGraph(ref: string): Promise<GetGraphResponse | null> {
    if (this.capabilities.modelKinds.includes(ref as RenderableSchemaCode)) return this.getSchemaGraph(ref as RenderableSchemaCode);
    // Native rich shape — the Worker path carries the DS LSP graft (slot data, layout, imports).
    return this.lspClient.getGraph(ref);
  }

  async listCatalog(): Promise<CatalogListing> {
    const [{ graphs }, symbols, { subjects }, layouts] = await Promise.all([
      this.lspClient.listGraphs(this.ctx.projectRoot),
      this.lspClient.listSymbols(),
      this.lspClient.listSchemaSubjects(),
      this.listLayouts(),
    ]);
    return {
      graphs: [
        ...subjects.filter((s) => this.capabilities.modelKinds.includes(s.schema as RenderableSchemaCode)).map((s) => ({ uri: s.schema, name: s.schema, schema: s.schema })),
        ...graphs.filter((g) => !g.uri.startsWith(`${this.ctx.projectRoot.replace(/\/$/, '')}/model/layouts/`)).map((g) => ({ uri: g.uri, name: g.name, schema: g.schema })),
        ...layouts.map((l) => ({ uri: l.source, name: l.name, schema: l.schema, layout: { missingCount: l.missingCount } })),
      ],
      symbols: symbols.map((s) => ({ qname: s.qname, kind: s.kind, name: s.name, packageName: s.packageName })),
    };
  }

  private layoutFiles(): Array<{ name: string; source: string }> {
    const layouts = [...(this.ctx.files?.keys() ?? [])].filter((p) => p.startsWith('model/layouts/') && p.endsWith('.ttrg'))
      .map((source) => ({ name: source.slice(source.lastIndexOf('/') + 1, -5), source }));
    if (new Set(layouts.map((l) => l.name)).size !== layouts.length) throw new Error('Layout file names must be unique under model/layouts/.');
    return layouts;
  }

  async listLayouts(): Promise<LayoutSummary[]> {
    return Promise.all(this.layoutFiles().map(async ({ name, source }) => {
      const l = await this.getLayoutDoc(name);
      return { name, source, schema: l.schema, objectCount: l.objects.length, missingCount: l.missing.length,
        ambiguousCount: l.ambiguous.length, hasPositions: Object.keys(l.positions).length > 0 };
    }));
  }

  async getLayoutDoc(name: string): Promise<ResolvedLayout> {
    const entry = this.layoutFiles().find((l) => l.name === name);
    if (!entry) throw new Error(`Unknown layout '${name}'.`);
    const inputs = [{ name: entry.source, text: this.ctx.files!.get(entry.source)! }];
    const sidecar = entry.source.replace(/\.ttrg$/, '.ttrl');
    const sidecarText = this.ctx.files!.get(sidecar);
    if (sidecarText !== undefined) inputs.push({ name: sidecar, text: sidecarText });
    const parsed = readLayoutFiles(inputs);
    if (!parsed.ok) throw new Error(parsed.error);
    const whole = await this.getSchemaGraph(parsed.doc.model);
    if (!whole) throw new Error(`Cannot load schema '${parsed.doc.model}' for layout '${name}'.`);
    const r = resolveRefs([...parsed.doc.objects, ...Object.keys(parsed.doc.positions)], layoutIndexOf(whole).index, parsed.doc.model);
    const doc = normalizeForTab(parsed.doc, r);
    const objects = [...new Set(parsed.doc.objects.flatMap((ref) => r.resolved.has(ref) ? [r.resolved.get(ref)!] : []))];
    return { name, source: entry.source, schema: doc.model, objects, positions: doc.positions,
      mode: modeOf(doc), skin: doc.skin ?? null, collapsed: doc.collapsed ?? [], missing: r.missing, ambiguous: r.ambiguous,
      ignored: r.ignored, positionsFrom: parsed.positionsFrom, modelVersion: '' };
  }

  async getObject(qname: string): Promise<ObjectDetail> {
    const detail = await this.lspClient.getSymbolDetail(qname);
    return {
      object: {
        qname,
        kind: detail?.kind ?? '',
        label: detail?.label ?? detail?.name ?? qname,
        schema: '',
        pkg: '',
      },
      sourceLocation: detail ? { file: detail.sourceUri, line: detail.sourceLine, column: 0 } : '',
      references: [],
    };
  }

  async search(q: SearchParams): Promise<SearchHit[]> {
    // Same client-side filtering AddObjectPicker does today (pinned, not improved).
    const symbols = await this.lspClient.listSymbols();
    const needle = q.query.toLowerCase();
    return symbols
      .filter((s) => s.qname.toLowerCase().includes(needle) || s.name.toLowerCase().includes(needle))
      .map((s) => ({ qname: s.qname, score: 1, matchedField: 'name' }))
      .slice(0, q.limit);
  }

  onModelChanged(_cb: (version: string) => void): Disposable {
    // The worker path has no file watching (documents are pushed via didOpen).
    return { dispose() {} };
  }
}
