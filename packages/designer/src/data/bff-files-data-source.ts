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
} from './model-data-source.js';
import type { GetGraphResponse, ListedSymbol } from '@tatrman/lsp';

/** A fetched SHA is immutable. Share schema/symbol reads across its layout catalog and canvas. */
class SnapshotWorker extends WorkerLspDataSource {
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
    edit: false,
    modelKinds: ['db', 'er', 'md', 'cnc'],
    bindings: true,
    perspectives: true,
    layoutPersist: 'file',
    graphShape: 'rich',
  };
  project: ProjectFiles | null = null;
  sha: string | null = null;
  private worker: WorkerLspDataSource | null = null;
  private disposed = false;
  private pending: Promise<boolean> | null = null;
  private abort = new AbortController();
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
  refresh(): Promise<boolean> {
    if (this.disposed) return Promise.reject(new Error('Hosted source is disposed.'));
    if (!this.pending)
      this.pending = this.load().finally(() => {
        this.pending = null;
      });
    return this.pending;
  }
  private async load(): Promise<boolean> {
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
      await client.setProjectRoot('file:///repo', project.files.get('modeler.toml'));
      for (const [p, text] of project.files) {
        if (p.startsWith('model/') && !p.startsWith('model/layouts/') && /\.ttr[mg]$/.test(p)) {
          await client.openDocument(`file:///repo/${p}`, text);
        }
      }
      if (this.disposed) throw new Error('Hosted source is disposed.');
      const previous = this.worker;
      let symbols: Promise<ListedSymbol[]> | null = null;
      const snapshotClient: LspClient = {
        ...client,
        listSymbols: (options) => {
          if (options) return client.listSymbols(options);
          return (symbols ??= client.listSymbols().catch((error) => {
            symbols = null;
            throw error;
          }));
        },
      };
      this.worker = new SnapshotWorker(snapshotClient, {
        projectRoot: 'file:///repo',
        files: project.files,
      });
      this.project = project;
      this.sha = snapshot.sha;
      previous?.lspClient.dispose();
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
  listCatalog() {
    return this.source.listCatalog();
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
  dispose(): void {
    this.disposed = true;
    this.abort.abort();
    this.worker?.lspClient.dispose();
    this.worker = null;
    this.listeners.clear();
  }
}
