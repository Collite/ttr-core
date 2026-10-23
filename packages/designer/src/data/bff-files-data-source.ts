// SPDX-License-Identifier: Apache-2.0
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
interface Snapshot {
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

/** Atomic file-map snapshots over the same rich worker reads as local projects. */
export class BffFilesDataSource implements ModelDataSource {
  readonly capabilities: DataSourceCapabilities = {
    edit: true,
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
  private retired: LspClient[] = [];
  private disposed = false;
  private pending: Promise<boolean> | null = null;
  private abort = new AbortController();
  private fileListeners = new Set<() => void>();
  private listeners = new Set<(sha: string) => void>();
  private readonly fetcher: typeof fetch;
  private readonly createClient: () => Promise<LspClient>;
  private token: string;
  constructor(
    private readonly options: BffOptions,
    deps: { fetch?: typeof fetch; createClient?: () => Promise<LspClient> } = {}
  ) {
    if (!isHostPath(options.api)) throw new Error('The BFF API must be a same-origin path.');
    this.token = options.token;
    this.fetcher = deps.fetch ?? globalThis.fetch.bind(globalThis);
    this.createClient =
      deps.createClient ?? (async () => (await import('../lsp-client.js')).createLspClient());
  }
  setToken(token: string): void {
    this.token = token;
  }
  refresh(options: { deferDisposal?: boolean } = {}): Promise<boolean> {
    if (this.disposed) return Promise.reject(new Error('Hosted source is disposed.'));
    if (!this.pending)
      this.pending = this.load(options.deferDisposal ?? false).finally(() => {
        this.pending = null;
      });
    return this.pending;
  }
  private async load(deferDisposal: boolean): Promise<boolean> {
    const scope = this.options.scope;
    const path =
      scope.kind === 'served' ? '/served/files' : `/changes/${encodeURIComponent(scope.id)}/files`;
    const response = await this.fetcher(`${this.options.api.replace(/\/$/, '')}${path}`, {
      headers: { Authorization: `Bearer ${this.token}`, Accept: 'application/json' },
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
      signal: this.abort.signal,
    });
    if (!response.ok) throw new Error(`Model snapshot request failed (${response.status}).`);
    const snapshot = snapshotOf(await response.json());
    if (snapshot.sha === this.sha) return false;
    const project: ProjectFiles = {
      rootName: 'repo',
      files: new Map(Object.entries(snapshot.files)),
    };
    const client = await this.createClient();
    try {
      // Contract §4.1 — the BFF serves model/** only.
      await openFileMap(client, 'file:///repo', project.files, { under: 'model/' });
      const worker = new SnapshotWorker(client, {
        projectRoot: 'file:///repo',
        files: project.files,
      });
      // Validate the entire catalog before replacing any currently served state.
      const catalog = await worker.listCatalog();
      worker.onFilesChanged(() => { worker.invalidate(); this.catalog = null; for (const cb of this.fileListeners) cb(); });
      worker.onModelChanged(() => { worker.invalidate(); this.catalog = null; });
      if (this.disposed) throw new Error('Hosted source is disposed.');
      const previous = this.worker;
      this.worker = worker;
      this.catalog = catalog;
      this.project = project;
      this.sha = snapshot.sha;
      if (previous) this.retired.push(previous.lspClient);
      if (!deferDisposal) this.disposeRetired();
      for (const cb of this.fileListeners) cb();
      for (const cb of this.listeners) cb(snapshot.sha);
      return true;
    } catch (error) {
      client.dispose();
      throw error;
    }
  }
  private get source(): WorkerLspDataSource {
    if (!this.worker) throw new Error('Hosted snapshot has not loaded.');
    return this.worker;
  }
  edit(params: import('@tatrman/lsp').ModelerEditParams) { return this.source.edit(params); }
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
    return this.catalog ??= await this.source.listCatalog();
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
    this.catalog = null;
    this.disposeRetired();
    this.listeners.clear();
    this.fileListeners.clear();
  }
}
