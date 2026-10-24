// SPDX-License-Identifier: Apache-2.0
import { changedFiles, type ChangedFile } from '../fs/changed-files.js';
import type { ModelerEditParams } from '@tatrman/lsp';
import type { LspClient } from '../lsp-client.js';
import type { ProjectFiles } from '../fs/file-system.js';
import { isHostPath, type HostScope } from '../host/host-channel.js';
import { WorkerLspDataSource } from './worker-lsp-data-source.js';
import type {
  ModelDataSource,
  DataSourceCapabilities,
  GraphScope,
  ModelKind,
  SearchParams,
  CatalogListing,
} from './model-data-source.js';
import { openFileMap } from './open-file-map.js';
import type { GetGraphResponse } from '@tatrman/lsp';

/** Share reads across catalog/canvas; authoring invalidates them without changing the fetched SHA. */
class SnapshotWorker extends WorkerLspDataSource {
  invalidate(): void { this.schemas.clear(); }
  private schemas = new Map<ModelKind, Promise<GetGraphResponse | null>>();
  override getSchemaGraph(schema: ModelKind) {
    let read = this.schemas.get(schema);
    if (!read) {
      read = super.getSchemaGraph(schema).catch((error) => {
        this.schemas.delete(schema);
        throw error;
      });
      this.schemas.set(schema, read);
    }
    return read;
  }
}

export interface BffOptions {
  api: string;
  token: string;
  scope: HostScope;
}
export interface Snapshot {
  sha: string;
  files: Record<string, string>;
}
function snapshotOf(value: unknown): Snapshot {
  const s = value as Snapshot | null;
  if (
    !s ||
    typeof s.sha !== 'string' ||
    !s.sha ||
    !s.files ||
    typeof s.files !== 'object' ||
    Array.isArray(s.files)
  ) {
    throw new Error('Invalid model snapshot. Expected { sha, files }.');
  }
  for (const [path, text] of Object.entries(s.files)) {
    if (
      typeof text !== 'string' ||
      !path ||
      path.startsWith('/') ||
      /[\\\u0000-\u001f?#]/.test(path) ||
      path.split('/').some((p) => !p || p === '.' || p === '..')
    ) {
      throw new Error('Snapshot files must have repo-rooted paths and text contents.');
    }
  }
  return s;
}

export class SnapshotEditError extends Error {
  constructor(readonly code: 'frame/unsaved-edits' | 'frame/edit-conflict', message: string) { super(message); }
}
export interface SnapshotRefreshOptions {
  deferDisposal?: boolean;
  reason?: 'save' | 'refresh';
  discard?: boolean;
}
function sameFiles(a: ReadonlyMap<string,string>, b: ReadonlyMap<string,string>): boolean {
  return a.size === b.size && [...a].every(([path,text]) => b.get(path) === text);
}

/** Atomic file-map snapshots over the same rich worker reads as local projects. */
export class BffFilesDataSource implements ModelDataSource {
  readonly capabilities: DataSourceCapabilities = {
    edit: true,
    save: 'host',
    modelKinds: ['db', 'er', 'md', 'cnc'],
    bindings: true,
    perspectives: true,
    layoutPersist: 'file',
    graphShape: 'rich',
  };
  project: ProjectFiles | null = null;
  sha: string | null = null;
  private worker: SnapshotWorker | null = null;
  private catalog: CatalogListing | null = null;
  private catalogGen = 0;
  private retired: LspClient[] = [];
  private disposed = false;
  private pending: Promise<boolean> | null = null;
  private activeEdits = 0;
  private lastCollected: { sha: string | null; files: Map<string,string> } | null = null;
  private abort = new AbortController();
  private fileListeners = new Set<() => void>();
  private listeners = new Set<(sha: string) => void>();
  private readonly fetcher: typeof fetch;
  private readonly createClient: () => Promise<LspClient>;
  private token: string;
  constructor(
    private readonly options: BffOptions,
    private readonly deps: { fetch?: typeof fetch; createClient?: () => Promise<LspClient>; loadSnapshot?: () => Promise<Snapshot> } = {}
  ) {
    if (!isHostPath(options.api)) throw new Error('The BFF API must be a same-origin path.');
    this.token = options.token;
    this.fetcher = deps.fetch ?? ((...args) => globalThis.fetch(...args));
    this.createClient =
      deps.createClient ?? (async () => (await import('../lsp-client.js')).createLspClient());
  }
  setToken(token: string): void {
    this.token = token;
  }
  refresh(options: SnapshotRefreshOptions = {}): Promise<boolean> {
    if (this.disposed) return Promise.reject(new Error('Hosted source is disposed.'));
    if (!this.pending)
      this.pending = this.load(options).finally(() => {
        this.pending = null;
      });
    return this.pending;
  }
  private currentFiles(): Map<string,string> {
    const files = new Map(this.project?.files);
    for (const change of this.changedFiles()) {
      if (change.text === null) files.delete(change.path); else files.set(change.path, change.text);
    }
    return files;
  }
  /** Capture the exact collected map, including reverted and newly-created paths. */
  collectChangedFiles(): ChangedFile[] {
    const files = this.changedFiles();
    this.lastCollected = { sha: this.sha, files: this.currentFiles() };
    return files;
  }
  private async load(options: SnapshotRefreshOptions): Promise<boolean> {
    // Pin this acknowledgement to the collection that existed when it started.
    const collected = this.lastCollected;
    const scope = this.options.scope;
    const path =
      scope.kind === 'served' ? '/served/files' : `/changes/${encodeURIComponent(scope.id)}/files`;
    const read = async () => {
      const response = await this.fetcher(`${this.options.api.replace(/\/$/, '')}${path}`, {
        headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin',
        redirect: 'error',
        signal: this.abort.signal,
      });
      if (!response.ok) throw new Error(`Model snapshot request failed (${response.status}).`);
      return response.json();
    };
    const snapshot = snapshotOf(await (this.deps.loadSnapshot ? this.deps.loadSnapshot() : read()));
    if (snapshot.sha === this.sha) return false;
    for (let attempt = 0; attempt < 8; attempt++) {
      const current = this.currentFiles();
      const saved = collected?.sha === this.sha ? collected : null;
      const pending = changedFiles(saved?.files ?? this.project?.files ?? new Map(), current);
      if (!options.discard && this.worker) {
        if (this.activeEdits || ((this.changedFiles().length || pending.length) && (options.reason !== 'save' || !saved)))
          throw new SnapshotEditError('frame/unsaved-edits', 'Save or explicitly discard unsaved edits before changing snapshots.');
        if (options.reason === 'save') for (const file of pending) {
          // The collected text is the expected saved base, so our own save is not a conflict.
          if (snapshot.files[file.path] !== saved?.files.get(file.path))
            throw new SnapshotEditError('frame/edit-conflict', `Unsaved edits overlap changed snapshot file ${file.path}.`);
        }
      }
      const project: ProjectFiles = {rootName:'repo', files:new Map(Object.entries(snapshot.files))};
      const authored = new Map(project.files);
      if (!options.discard && options.reason === 'save') for (const file of pending) {
        if (file.text === null) authored.delete(file.path); else authored.set(file.path, file.text);
      }
      const client = await this.createClient();
      try {
        await openFileMap(client, 'file:///repo', authored, {under:'model/'});
        const worker = new SnapshotWorker(client, {projectRoot:'file:///repo',files:project.files,currentFiles:authored});
        const catalog = await worker.listCatalog();
        if (this.disposed) throw new Error('Hosted source is disposed.');
        if (!options.discard) {
          if (this.activeEdits) throw new SnapshotEditError('frame/unsaved-edits', 'Wait for the pending edit before changing snapshots.');
          if (!sameFiles(current, this.currentFiles())) { client.dispose(); continue; }
        }
        worker.onFilesChanged(() => { if (this.worker !== worker) return; worker.invalidate(); this.catalogGen++; this.catalog = null; for (const cb of this.fileListeners) cb(); });
        worker.onModelChanged(() => { if (this.worker !== worker) return; worker.invalidate(); this.catalogGen++; this.catalog = null; });
        const previous = this.worker;
        this.worker = worker;
        this.catalogGen++;
        this.catalog = catalog;
        this.project = project;
        this.sha = snapshot.sha;
        this.lastCollected = null;
        if (previous) this.retired.push(previous.lspClient);
        if (!options.deferDisposal) this.disposeRetired();
        for (const cb of this.fileListeners) cb();
        for (const cb of this.listeners) cb(snapshot.sha);
        return true;
      } catch (error) { client.dispose(); throw error; }
    }
    throw new SnapshotEditError('frame/unsaved-edits', 'Edits changed while the snapshot loaded; retry after authoring settles.');
  }
  private get source(): WorkerLspDataSource {
    if (!this.worker) throw new Error('Hosted snapshot has not loaded.');
    return this.worker;
  }
  async edit(params: ModelerEditParams) {
    if (!params.dryRun) this.activeEdits++;
    try { return await this.source.edit(params); }
    finally { if (!params.dryRun) this.activeEdits--; }
  }
  changedFiles() { return this.worker?.changedFiles() ?? []; }
  onFilesChanged(cb: () => void) {
    this.fileListeners.add(cb);
    return { dispose: () => { this.fileListeners.delete(cb); } };
  }
  getModelIndex() {
    return this.source.getModelIndex();
  }
  getModelGraph(scope?: GraphScope) {
    return this.source.getModelGraph(scope);
  }
  getGraph(ref: string) {
    return this.source.getGraph(ref);
  }
  getSchemaGraph(schema: ModelKind) {
    return this.source.getSchemaGraph(schema);
  }
  async listCatalog(): Promise<CatalogListing> {
    if (this.catalog) return this.catalog;
    const gen = this.catalogGen, worker = this.source;
    const catalog = await worker.listCatalog();
    if (gen === this.catalogGen && worker === this.worker) this.catalog = catalog;
    return catalog;
  }
  layoutIssues() {
    return this.source.layoutIssues();
  }
  listLayouts() {
    return this.source.listLayouts();
  }
  getLayoutDoc(name: string) {
    return this.source.getLayoutDoc(name);
  }
  getBindings() {
    return this.source.getBindings();
  }
  getSymbolDetail(qname: string) {
    return this.source.getSymbolDetail(qname);
  }
  getObject(qname: string) {
    return this.source.getObject(qname);
  }
  search(q: SearchParams) {
    return this.source.search(q);
  }
  onModelChanged(cb: (sha: string) => void) {
    this.listeners.add(cb);
    return {
      dispose: () => {
        this.listeners.delete(cb);
      },
    };
  }
  /** Called after React commits the replacement shell; its old reads can finish until then. */
  disposeRetired(): void {
    for (const client of this.retired.splice(0)) client.dispose();
  }
  dispose(): void {
    this.disposed = true;
    this.abort.abort();
    this.worker?.lspClient.dispose();
    this.worker = null;
    this.lastCollected = null;
    this.catalogGen++;
    this.catalog = null;
    this.disposeRetired();
    this.listeners.clear();
    this.fileListeners.clear();
  }
}
