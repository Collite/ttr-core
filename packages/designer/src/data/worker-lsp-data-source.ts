// SPDX-License-Identifier: Apache-2.0
// WorkerLspDataSource — wraps the existing browser-worker LSP path as a
// ModelDataSource: rich reads plus the open structured edit/save seam. The source owns
// the one editable file cache, applies returned patches and acknowledges host text once.
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
  LayoutIssue,
  ResolvedLayout,
} from './model-data-source.js';
import type { TtrmNode, TtrmEdge } from './ttrm-types.js';
import { readLayoutFiles, normalizeForTab, type OpenedLayout } from '../layout/open-files.js';
import { resolveRefs } from '../layout/resolve.js';
import { layoutIndexOf } from '../model/layout-projection.js';
import type { ModelerEditParams, ModelerEditResult } from '@tatrman/lsp';
import type { WorkspaceEdit } from 'vscode-languageserver-types';
import { applyWorkspaceEdit } from '../lsp/apply-workspace-edit.js';
import { changedFiles } from '../fs/changed-files.js';
import { modeOf } from '@tatrman/parser/layout';

export interface WorkerContext {
  projectRoot: string;
  /** The currently open graph document uri (set as graphs are opened). */
  graphUri?: string;
  files?: ReadonlyMap<string, string>;
  /** Initial authored bytes when rebasing onto a newly fetched immutable base. */
  currentFiles?: ReadonlyMap<string, string>;
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
  ) {
    this.baseFiles = new Map(ctx.files);
    this.docTextCache = new Map(ctx.currentFiles ?? ctx.files);
    // Layout reads must see authored bytes too; the fetched base remains immutable.
    this.ctx.files = this.docTextCache;
  }

  private readonly baseFiles: Map<string, string>;
  private readonly docTextCache: Map<string, string>;
  private readonly fileListeners = new Set<() => void>();
  private readonly modelListeners = new Set<(version: string) => void>();
  private revision = 0;
  private edits = Promise.resolve();

  changedFiles() { return changedFiles(this.baseFiles, this.docTextCache); }
  onFilesChanged(cb: () => void): Disposable {
    this.fileListeners.add(cb);
    return { dispose: () => { this.fileListeners.delete(cb); } };
  }
  /** Serial requests keep server edit ranges paired with the same pre-request host text. */
  edit(params: ModelerEditParams): Promise<ModelerEditResult> {
    const run = this.edits.then(async () => {
      const result = await this.lspClient.edit(params);
      if (result.ok && !params.dryRun) {
        await this.applyEdit(result.edit);
      }
      return result;
    });
    this.edits = run.then(() => {}, () => {});
    return run;
  }
  async applyEdit(edit: WorkspaceEdit): Promise<void> {
    const root = this.ctx.projectRoot.replace(/\/$/, '') + '/';
    const staged = new Map(this.docTextCache);
    const affected = await applyWorkspaceEdit(edit,
      uri => staged.get(decodeURIComponent(uri.slice(root.length))),
      async (uri, text) => {
        if (!uri.startsWith(root)) throw new Error('Edit outside project root.');
        staged.set(decodeURIComponent(uri.slice(root.length)), text);
      });
    if (!affected.length) return;
    // Commit the complete map synchronously: collect must never observe half an edit
    // while a transport acknowledgement is pending.
    for (const [path, text] of staged) this.docTextCache.set(path, text);
    for (const uri of affected) await this.lspClient.openDocument(uri, this.docTextCache.get(decodeURIComponent(uri.slice(root.length)))!);
    for (const cb of this.fileListeners) cb();
    this.revision++;
    for (const cb of this.modelListeners) cb(String(this.revision));
  }

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
   * The whole-schema graph (VL V-21), including each card's effective package from the LSP.
   */
  async getSchemaGraph(schema: RenderableSchemaCode): Promise<GetGraphResponse | null> {
    const mg = await this.lspClient.getModelGraph(this.ctx.graphUri ?? this.ctx.projectRoot, schema);
    return {
      schema,
      nodes: mg.nodes.map((n) => ({ ...n, pkg: n.pkg ?? '' })),
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

  private warnedLayoutIssues = new Set<string>();

  private readLayouts() {
    type Parsed = Extract<OpenedLayout, { ok: true }>;
    const grouped = new Map<string, Array<{ source: string; parsed: Parsed }>>();
    const issues: LayoutIssue[] = [];
    const paths = [...(this.ctx.files?.keys() ?? [])].filter((p) => p.startsWith('model/layouts/') && p.endsWith('.ttrg')).sort();
    for (const source of paths) {
      try {
        const inputs = [{ name: source, text: this.ctx.files!.get(source)! }];
        const sidecar = source.replace(/\.ttrg$/, '.ttrl');
        const text = this.ctx.files!.get(sidecar);
        if (text !== undefined) inputs.push({ name: sidecar, text });
        const parsed = readLayoutFiles(inputs);
        if (!parsed.ok) throw new Error(parsed.error);
        const group = grouped.get(parsed.doc.name) ?? [];
        group.push({ source, parsed });
        grouped.set(parsed.doc.name, group);
      } catch (e) { issues.push({ path: source, reason: String(e instanceof Error ? e.message : e).replace(/[\r\n]+/g, ' ') }); }
    }
    const layouts = new Map<string, { source: string; parsed: Parsed }>();
    for (const [name, entries] of grouped) {
      if (entries.length === 1) layouts.set(name, entries[0]);
      else for (const entry of entries) issues.push({ path: entry.source,
        reason: `the name ${name} is also declared by ${entries.filter((e) => e !== entry).map((e) => e.source).join(', ')}` });
    }
    return { layouts, issues };
  }

  private async resolvedLayouts() {
    const { layouts: parsed, issues } = this.readLayouts();
    const schemas = new Map<RenderableSchemaCode, Promise<GetGraphResponse | null>>();
    const entries = [...parsed.entries()];
    const results = await Promise.allSettled(entries.map(async ([name, entry]) => {
      const model = entry.parsed.doc.model;
      if (!schemas.has(model)) schemas.set(model, this.capabilities.modelKinds.includes(model)
        ? Promise.resolve().then(() => this.getSchemaGraph(model)) : Promise.resolve(null));
      let whole: GetGraphResponse | null;
      try { whole = await schemas.get(model)!; } catch { whole = null; }
      if (!whole) throw new Error(`the ${model} schema could not be read`);
      const doc = entry.parsed.doc;
      const r = resolveRefs([...doc.objects, ...Object.keys(doc.positions)], layoutIndexOf(whole).index, model);
      const normalized = normalizeForTab(doc, r);
      const fromObjects = new Set(doc.objects);
      const objects = [...new Set(doc.objects.flatMap((ref) => r.resolved.has(ref) ? [r.resolved.get(ref)!] : []))];
      const layout: ResolvedLayout = { name, source: entry.source, schema: model, objects, positions: normalized.positions,
        mode: modeOf(normalized), skin: normalized.skin ?? null, collapsed: normalized.collapsed ?? [],
        missing: r.missing.filter((ref) => fromObjects.has(ref)), ambiguous: r.ambiguous.filter((a) => fromObjects.has(a.ref)),
        ignored: r.ignored.filter((ref) => fromObjects.has(ref)), positionsFrom: entry.parsed.positionsFrom, modelVersion: '' };
      return layout;
    }));
    const layouts = new Map<string, ResolvedLayout>();
    results.forEach((result, i) => {
      if (result.status === 'fulfilled') layouts.set(result.value.name, result.value);
      else issues.push({ path: entries[i][1].source, reason: String(result.reason instanceof Error ? result.reason.message : result.reason).replace(/[\r\n]+/g, ' ') });
    });
    for (const issue of issues) {
      const key = `${issue.path}: ${issue.reason}`;
      if (!this.warnedLayoutIssues.has(key)) { this.warnedLayoutIssues.add(key); console.warn(`layout ${key} — not shown`); }
    }
    return { layouts, issues };
  }

  async listLayouts(): Promise<LayoutSummary[]> {
    return [...(await this.resolvedLayouts()).layouts.values()].map((l) => ({ name: l.name, source: l.source, schema: l.schema,
      objectCount: l.objects.length, missingCount: l.missing.length, ambiguousCount: l.ambiguous.length, hasPositions: Object.keys(l.positions).length > 0 }));
  }

  async layoutIssues(): Promise<LayoutIssue[]> { return (await this.resolvedLayouts()).issues; }

  async getLayoutDoc(name: string): Promise<ResolvedLayout> {
    const layout = (await this.resolvedLayouts()).layouts.get(name);
    if (!layout) throw new Error(`Unknown layout '${name}'.`);
    return layout;
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

  onModelChanged(cb: (version: string) => void): Disposable {
    this.modelListeners.add(cb);
    return { dispose: () => { this.modelListeners.delete(cb); } };
  }
}
